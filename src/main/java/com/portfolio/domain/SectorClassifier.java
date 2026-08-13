package com.portfolio.domain;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Places a held position into one or more {@link SectorBucket}s, and reports how much of it counts
 * as AI/Tech exposure. Pure domain: no Spring, no JDBC, no network — everything it needs is handed
 * in per position, so the whole thing is unit-testable.
 *
 * <p>Two <b>orthogonal</b> answers come out of one pass, which is the point of the class. The
 * sector split is a partition (buckets sum to the position value), while the AI/Tech figure cuts
 * across buckets: Alphabet, Meta, Amazon and Netflix are AI/Tech by any sensible reading of "how
 * concentrated am I in this theme", yet GICS files them under Communication Services and Consumer
 * Cyclical. Reporting only the sector split would scatter that concentration across three buckets
 * and read Tech at roughly a third of its true size.
 *
 * <h2>Funds are looked through, not treated as a lump</h2>
 * An index ETF is dissolved into its own sector weights, so a Nasdaq-100 tracker contributes to
 * Tech/AI, Consumer, Comms and the rest in proportion to what it actually holds. Without this the
 * largest position in a typical portfolio here would be an opaque "Funds" row — precisely the
 * exposure the panel exists to measure. Weights come from Yahoo and are refreshed with the rest of
 * the fundamentals snapshot; a fund that stops returning them falls to {@link
 * SectorBucket#UNCLASSIFIED} and is surfaced, never served from stale numbers.
 *
 * <h2>The AI/Tech figure for a fund is a lower bound</h2>
 * A fund's technology weight is counted in full. Named constituents on the {@code aitech.include}
 * list are then added <i>only if</i> their own sector is not itself Technology — otherwise they
 * would be double-counted, having already been inside the technology weight. Yahoo publishes just
 * the top 10 holdings (roughly half a broad fund by weight), so qualifying names in the unnamed
 * tail go uncounted and the result understates rather than overstates. {@link
 * Classification#nameCoverage()} reports how much of the fund was name-identified so the UI can
 * say so.
 */
public class SectorClassifier {

    private static final String RESOURCE = "/sectors.properties";
    private static final String OVERRIDE_PREFIX = "override.";
    private static final String AITECH_KEY = "aitech.include";
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final int GBP_SCALE = 2;

    private final Map<String, SectorBucket> overrides;
    private final Set<String> aiTechInclude;

    public SectorClassifier() {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream(RESOURCE)) {
            if (in != null) props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, SectorBucket> ov = new LinkedHashMap<>();
        Set<String> ai = new LinkedHashSet<>();
        for (String name : props.stringPropertyNames()) {
            if (!name.startsWith(OVERRIDE_PREFIX)) continue;
            String symbol = normalise(name.substring(OVERRIDE_PREFIX.length()));
            String value = props.getProperty(name, "").trim();
            if (symbol.isEmpty() || value.isEmpty()) continue;
            // An unknown bucket name is a typo in a checked-in file, not a runtime condition —
            // fail loudly at construction rather than silently mis-bucketing a position forever.
            ov.put(symbol, SectorBucket.valueOf(value.toUpperCase()));
        }
        for (String s : props.getProperty(AITECH_KEY, "").split(",")) {
            String sym = normalise(s);
            if (!sym.isEmpty()) ai.add(sym);
        }
        this.overrides = Map.copyOf(ov);
        this.aiTechInclude = Set.copyOf(ai);
    }

    /** Visible for tests — bypasses the classpath resource. */
    SectorClassifier(Map<String, SectorBucket> overrides, Set<String> aiTechInclude) {
        this.overrides = Map.copyOf(overrides);
        this.aiTechInclude = Set.copyOf(aiTechInclude);
    }

    /**
     * Splits one position's GBP value across buckets and computes its AI/Tech share.
     *
     * <p>Resolution order: bond → override → fund look-through → Yahoo sector → unclassified.
     * Bonds come first so gilts need no configuration, and the override beats the feed so an
     * instrument Yahoo has no sector for (a bullion ETC) can still be placed.
     */
    public Classification classify(Position p) {
        String symbol = normalise(p.symbol());
        BigDecimal gbp = p.gbp() == null ? BigDecimal.ZERO : p.gbp();

        if (Instruments.isBond(p.symbol())) {
            return single(SectorBucket.FIXED_INCOME, gbp, symbol);
        }
        SectorBucket override = overrides.get(symbol);
        if (override != null) {
            return single(override, gbp, symbol);
        }
        if (p.fundWeights() != null && !p.fundWeights().isEmpty()) {
            return lookThrough(symbol, gbp, p.fundWeights(), p.fundHoldings());
        }
        SectorBucket bucket = SectorBucket.fromYahooSector(p.yahooSector());
        return single(bucket == null ? SectorBucket.UNCLASSIFIED : bucket, gbp, symbol);
    }

    private Classification single(SectorBucket bucket, BigDecimal gbp, String symbol) {
        boolean fromSector = bucket == SectorBucket.TECH_AI;
        boolean aiTech = fromSector || aiTechInclude.contains(symbol);
        BigDecimal zero = BigDecimal.ZERO.setScale(GBP_SCALE);
        return new Classification(
                Map.of(bucket, scaled(gbp)),
                aiTech ? scaled(gbp) : zero,
                fromSector ? scaled(gbp) : zero,
                aiTech && !fromSector ? scaled(gbp) : zero,
                false,
                null);
    }

    private Classification lookThrough(String symbol, BigDecimal gbp,
                                       Map<String, BigDecimal> weights,
                                       List<Constituent> holdings) {
        BigDecimal totalWeight = weights.values().stream()
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalWeight.signum() <= 0) {
            return single(SectorBucket.UNCLASSIFIED, gbp, symbol);
        }

        // Yahoo's weights sum to ~1.0 but not exactly, so normalise by their own total rather
        // than trusting them — otherwise a fund's slices would not add back to its market value.
        Map<SectorBucket, BigDecimal> byBucket = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : weights.entrySet()) {
            if (e.getValue() == null || e.getValue().signum() == 0) continue;
            SectorBucket bucket = SectorBucket.fromYahooSector(e.getKey());
            if (bucket == null) bucket = SectorBucket.UNCLASSIFIED;
            BigDecimal slice = gbp.multiply(e.getValue(), MC).divide(totalWeight, MC);
            byBucket.merge(bucket, scaled(slice), BigDecimal::add);
        }
        // Rounding each slice independently can leave the parts a penny or two off the whole.
        // Push the difference onto the largest bucket so the panel reconciles exactly against
        // the portfolio total — a look-through must never create or destroy value.
        absorbRounding(byBucket, scaled(gbp));

        BigDecimal aiTechWeight = weights.getOrDefault("technology", BigDecimal.ZERO);
        BigDecimal namedCoverage = BigDecimal.ZERO;
        List<Constituent> named = holdings == null ? List.of() : holdings;
        for (Constituent c : named) {
            if (c == null || c.weight() == null) continue;
            namedCoverage = namedCoverage.add(c.weight());
            if (!aiTechInclude.contains(normalise(c.symbol()))) continue;
            SectorBucket own = SectorBucket.fromYahooSector(c.yahooSector());
            // Skip when its sector is unknown: it might already sit inside the technology
            // weight, and double-counting would overstate the very figure being measured.
            // Skipping keeps the result a genuine lower bound.
            if (own == null || own == SectorBucket.TECH_AI) continue;
            aiTechWeight = aiTechWeight.add(c.weight());
        }
        BigDecimal aiTechGbp = gbp.multiply(aiTechWeight, MC).divide(totalWeight, MC);
        if (aiTechGbp.compareTo(gbp) > 0) aiTechGbp = gbp;   // cannot exceed the position itself

        BigDecimal coverage = namedCoverage.divide(totalWeight, MC);
        if (coverage.compareTo(BigDecimal.ONE) > 0) coverage = BigDecimal.ONE;

        // Split against the *bucket* value rather than recomputing from the technology weight, so
        // the bridge the UI prints ("Technology sector + AI names filed elsewhere = the total")
        // holds to the penny against the Technology row itself.
        //
        // A fund's AI/Tech share contains its whole technology slice by construction — the two are
        // only ever added to. So a scaled total below the bucket means absorbRounding pushed its
        // remainder here, not that the theme is genuinely smaller; lift it back. Clamping the other
        // way instead (min) left the parts a penny short of the Technology row across a portfolio,
        // which is visible once the contributor column is totalled.
        BigDecimal techBucket = byBucket.getOrDefault(SectorBucket.TECH_AI,
                BigDecimal.ZERO.setScale(GBP_SCALE));
        BigDecimal scaledAi = scaled(aiTechGbp).max(techBucket);
        return new Classification(byBucket, scaledAi, techBucket, scaledAi.subtract(techBucket),
                true, coverage);
    }

    private static void absorbRounding(Map<SectorBucket, BigDecimal> byBucket, BigDecimal target) {
        BigDecimal sum = byBucket.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal diff = target.subtract(sum);
        if (diff.signum() == 0 || byBucket.isEmpty()) return;
        SectorBucket largest = byBucket.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElseThrow();
        byBucket.merge(largest, diff, BigDecimal::add);
    }

    private static BigDecimal scaled(BigDecimal v) {
        return v.setScale(GBP_SCALE, RoundingMode.HALF_UP);
    }

    private static String normalise(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }

    /** Whether a directly-held symbol counts as AI/Tech by name, ignoring its sector. */
    public boolean isAiTechByName(String symbol) {
        return aiTechInclude.contains(normalise(symbol));
    }

    /**
     * One position to classify. {@code fundWeights} keys are Yahoo sector names and its values are
     * fractions of fund NAV; an empty map means "not a fund". {@code fundHoldings} are the named
     * top constituents, each already carrying its own resolved sector so this class needs no
     * lookup callback.
     */
    public record Position(String symbol,
                           BigDecimal gbp,
                           String yahooSector,
                           Map<String, BigDecimal> fundWeights,
                           List<Constituent> fundHoldings) {

        public static Position direct(String symbol, BigDecimal gbp, String yahooSector) {
            return new Position(symbol, gbp, yahooSector, Map.of(), List.of());
        }
    }

    /** A named fund constituent: its weight in the fund and its own Yahoo sector (may be null). */
    public record Constituent(String symbol, BigDecimal weight, String yahooSector) {
    }

    /**
     * @param byBucket           GBP per bucket; sums exactly to the position value
     * @param aiTechGbp          GBP counting as AI/Tech — a lower bound when {@code fund} is true
     * @param fromTechSectorGbp  the part of {@code aiTechGbp} that is already in the Technology
     *                           bucket, i.e. equal to {@code byBucket[TECH_AI]}
     * @param crossSectorGbp     the part filed under some other sector (Alphabet in
     *                           Communications, Amazon in Consumer …). Reported explicitly rather
     *                           than left for the caller to subtract, so the bridge the UI prints
     *                           — {@code aiTechGbp = fromTechSectorGbp + crossSectorGbp} — cannot
     *                           be silently broken by a later change to the AI/Tech rule.
     * @param fund               whether this was resolved by look-through
     * @param nameCoverage       fraction of the fund identified by name, {@code null} when direct
     */
    public record Classification(Map<SectorBucket, BigDecimal> byBucket,
                                 BigDecimal aiTechGbp,
                                 BigDecimal fromTechSectorGbp,
                                 BigDecimal crossSectorGbp,
                                 boolean fund,
                                 BigDecimal nameCoverage) {
    }
}
