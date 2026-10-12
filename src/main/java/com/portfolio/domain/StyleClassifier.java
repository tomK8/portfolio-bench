package com.portfolio.domain;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Places a held position into a {@link StyleBucket} — value, growth, both or neither — and reports
 * whether it counts as an income holding. Pure domain: no Spring, no JDBC, no network. Everything
 * it needs arrives per position, so the whole thing is unit-testable.
 *
 * <h2>Two axes, scored against absolute thresholds</h2>
 * Cheapness and growth are measured separately and each yields a 0–1 score, which is then cut at
 * {@code score.threshold} into the {@link StyleBucket#of} 2×2. The thresholds are absolute (a
 * forward P/E under 16 is cheap, revenue growth over 15% is fast), <b>not</b> percentile ranks
 * within the portfolio. That choice is load-bearing and was made against the data: ranking within
 * a portfolio that holds both deep-value European banks and 85%-growth semiconductors puts the
 * mega-cap compounders mid-pack on <i>both</i> axes, so Alphabet, Microsoft, Amazon, Visa and
 * Mastercard all landed in "neither" — an artefact of the peer set, not a judgement about them.
 *
 * <h2>Each score is achieved ÷ available</h2>
 * A metric that is absent lowers neither score; it simply does not vote. An axis with fewer than
 * {@code min.metrics} inputs is not scored at all and the position becomes
 * {@link StyleBucket#UNKNOWN}, which the UI lists by name. The one deliberate exception is
 * dividend yield: Yahoo omits the field entirely for a non-payer, so a missing yield is read as a
 * genuine <b>zero</b> whenever any other metric is present. Treating it as absent instead would
 * quietly shrink the denominator for every growth name and make non-payers look cheaper.
 *
 * <h2>Price-to-book is not used for direct holdings</h2>
 * Yahoo's {@code priceToBook} is corrupt for non-US listings — a currency-scale artefact that
 * reports AstraZeneca at 479.7 (really ≈4.8), RELX at 3676.6 (≈36.8) and ASML at 1563 (≈15.6).
 * Feeding that in would mark every UK and European holding as ruinously expensive, which is
 * precisely backwards for what is mostly an income book. P/E, P/S and EV/EBITDA are clean and
 * carry the value axis instead. The fund-level P/B from {@code equityHoldings} is unaffected and
 * is used.
 *
 * <h2>Earnings growth is down-weighted on purpose</h2>
 * {@code earningsGrowth} is a single-quarter year-on-year figure and swings wildly — Micron at
 * +1368%, Aviva at +979%, Legal &amp; General at +507%. At full weight it decides the growth axis
 * on one noisy quarter and turns cyclical banks into growth stocks. It votes at
 * {@code growth.earnings.weight} (0.4) against revenue growth's 1.0, and P/E compression — which
 * fires for any cyclical whose earnings are recovering — at 0.5, so that flat revenue cannot be
 * outvoted by the two soft signals together.
 *
 * <h2>Forward estimates carry the loss-makers</h2>
 * Three of the four inputs above need positive trailing earnings to exist at all: Yahoo publishes
 * no {@code trailingPE} and no {@code earningsGrowth} for a loss-maker, which also removes the P/E
 * compression test. That left Intel and Moderna with revenue growth alone — one metric, below
 * {@code min.metrics} — so both dropped out of the split as UNKNOWN, exactly the population the
 * growth axis most needs to judge. The two {@code earningsTrend} estimates fill the gap.
 *
 * <p>They are deliberately asymmetric. <b>Forward revenue growth always votes</b>, because revenue
 * has a positive base by definition, and it is the heavier of the two ({@code 0.5} against
 * {@code 0.25}).
 * <b>Forward earnings growth votes only when both the base and the estimate are positive</b> — a
 * gate applied upstream in {@code YahooQuoteSummaryFetcher}, so the field simply does not arrive
 * for a loss-maker. Yahoo derives that growth rate off a signed base, so Moderna's shrinking loss
 * (EPS −8.58 → −4.91) is published as <b>+42.5% growth</b>; taking it at face value would score a
 * loss-making name as a grower off a ratio of two negatives. It is the same negative-base trap the
 * P/E compression test guards against, and it gets the same treatment.
 *
 * <p>Both weights are deliberately small enough that the estimates <b>cannot unseat reported
 * growth</b> — they decide a position only where the reported metrics are missing, which is the
 * case they were added for. At a full-weight vote they instead redefined the bucket for the whole
 * book, dropping Apple and Amazon out of Growth on FY+1 estimates that undershoot a threshold
 * calibrated for trailing figures.
 */
public class StyleClassifier {

    private static final String RESOURCE = "/style.properties";
    private static final String OVERRIDE_PREFIX = "override.";
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final int GBP_SCALE = 2;
    private static final int SCORE_SCALE = 4;

    private final Map<String, StyleBucket> overrides;
    private final Thresholds t;

    public StyleClassifier() {
        this(load());
    }

    /** Visible for tests — bypasses the classpath resource. */
    StyleClassifier(Properties props) {
        Map<String, StyleBucket> ov = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            if (!name.startsWith(OVERRIDE_PREFIX)) continue;
            String symbol = normalise(name.substring(OVERRIDE_PREFIX.length()));
            String value = props.getProperty(name, "").trim();
            if (symbol.isEmpty() || value.isEmpty()) continue;
            // A bad bucket name is a typo in a checked-in file, not a runtime condition — fail at
            // construction rather than mis-labelling a position forever.
            ov.put(symbol, StyleBucket.valueOf(value.toUpperCase()));
        }
        this.overrides = Map.copyOf(ov);
        this.t = new Thresholds(props);
    }

    private static Properties load() {
        Properties props = new Properties();
        try (InputStream in = StyleClassifier.class.getResourceAsStream(RESOURCE)) {
            if (in != null) props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return props;
    }

    /**
     * Scores one position. Resolution order: bond → override → wrapper asset class → fund →
     * equity 2×2, so gilts need no configuration and a hand-placed instrument beats the feed.
     *
     * <p>The asset-class step defers to {@link Instruments#assetClassOf} for the wrappers whose
     * <em>underlying</em> decides the bucket — bullion has no earnings, sales or yield to score,
     * and a gilt ETF is fixed income. It exists so that set lives in one place: SGLN was in
     * {@link Instruments}' bullion list but not in {@code style.properties}, so the gold actually
     * held was reported as unscoreable — a data gap to go and fix rather than the deliberate
     * holding it is. See {@code SectorClassifier.classify} for the same reasoning.
     */
    public Classification classify(Position p) {
        String symbol = normalise(p.symbol());
        BigDecimal gbp = p.gbp() == null ? BigDecimal.ZERO : p.gbp();
        Metrics m = p.metrics() == null ? Metrics.empty() : p.metrics();

        if (Instruments.isBond(p.symbol())) {
            return fixedIncome(p.symbol(), gbp, p.quantity(), m);
        }
        StyleBucket override = overrides.get(symbol);
        if (override != null) {
            // A bond fund overridden here is fixed income too, and gets the same income treatment
            // as a directly-held gilt — it just has no coupon in its symbol to derive one from.
            return override == StyleBucket.FIXED_INCOME
                    ? fixedIncome(p.symbol(), gbp, p.quantity(), m)
                    : structural(override, gbp, m);
        }
        switch (Instruments.assetClassOf(p.symbol())) {
            // Bullion: no earnings, no sales, no yield. Every axis abstains, and that is a
            // property of the asset rather than a gap in the data.
            case COMMODITY -> {
                return structural(StyleBucket.NON_EQUITY, gbp, m);
            }
            // A gilt in an ETF wrapper is still a gilt, and gets the same income treatment as a
            // directly-held one — it just has no coupon in its symbol to derive a yield from.
            case GILT -> {
                return fixedIncome(p.symbol(), gbp, p.quantity(), m);
            }
            // Cash in a wrapper: nothing to score on either axis, and not an income holding
            // either — a money-market ETF's carry is not a dividend policy. The branch also has
            // to sit above the fund one: CSH2 publishes equity multiples for the substitute
            // basket it swaps away, which would place parked cash on a growth tilt.
            case CASH_EQUIVALENT -> {
                return structural(StyleBucket.CASH_EQUIVALENTS, gbp, m);
            }
            default -> { }
        }
        if (p.fund()) {
            return fund(gbp, m);
        }
        return equity(gbp, m);
    }

    private Classification equity(BigDecimal gbp, Metrics m) {
        BigDecimal pe = firstPositive(m.forwardPe(), m.trailingPe());
        boolean anyValueInput = pe != null || m.evToEbitda() != null || m.priceToSales() != null;

        Axis value = new Axis();
        value.vote("Forward P/E", pe, pe != null && lt(pe, t.peMax), t.peWeight);
        value.vote("EV/EBITDA", m.evToEbitda(),
                m.evToEbitda() != null && positive(m.evToEbitda()) && lt(m.evToEbitda(), t.evMax),
                t.evWeight);
        value.vote("Price/Sales", m.priceToSales(),
                m.priceToSales() != null && lt(m.priceToSales(), t.psMax), t.psWeight);
        // A missing dividend yield means "pays none", not "unknown" — but only once we know the
        // name is real at all, hence the guard on some other value input existing.
        BigDecimal dy = m.dividendYield() == null && anyValueInput ? BigDecimal.ZERO : m.dividendYield();
        value.vote("Dividend yield", dy, dy != null && gte(dy, t.divYieldMin), t.divWeight);

        Axis growth = new Axis();
        growth.vote("Revenue growth", m.revenueGrowth(),
                m.revenueGrowth() != null && gte(m.revenueGrowth(), t.revMin), t.revWeight);
        growth.vote("FCF growth", m.fcfGrowth(),
                m.fcfGrowth() != null && gte(m.fcfGrowth(), t.fcfMin), t.fcfWeight);
        growth.vote("Earnings growth", m.earningsGrowth(),
                m.earningsGrowth() != null && gte(m.earningsGrowth(), t.epsMin), t.epsWeight);
        // Forward earnings well above trailing is the market pricing in growth. Only meaningful
        // when both multiples are positive: a loss-maker's negative forward P/E would otherwise
        // read as enormous "compression".
        BigDecimal compression = null;
        if (positive(m.trailingPe()) && positive(m.forwardPe())) {
            compression = m.trailingPe().divide(m.forwardPe(), MC).subtract(BigDecimal.ONE);
        }
        growth.vote("P/E compression", compression,
                compression != null && gte(compression, t.compressionMin), t.compressionWeight);
        // The two forward estimates. These are the only growth inputs that survive negative
        // trailing earnings, which is the whole reason they exist — see the class javadoc.
        growth.vote("Fwd revenue growth", m.forwardRevenueGrowth(),
                m.forwardRevenueGrowth() != null && gte(m.forwardRevenueGrowth(), t.fwdRevMin),
                t.fwdRevWeight);
        growth.vote("Fwd earnings growth", m.forwardEarningsGrowth(),
                m.forwardEarningsGrowth() != null && gte(m.forwardEarningsGrowth(), t.fwdEpsMin),
                t.fwdEpsWeight);

        if (value.count() < t.minMetrics || growth.count() < t.minMetrics) {
            return new Classification(StyleBucket.UNKNOWN, scaled(gbp), null, null,
                    List.of(), List.of(), m.dividendYield(), false,
                    "Too few fundamentals to score (" + value.count() + " value, "
                            + growth.count() + " growth; " + t.minMetrics + " needed on each)");
        }
        BigDecimal vs = value.score();
        BigDecimal gs = growth.score();
        boolean cheap = gte(vs, t.scoreThreshold);
        boolean growing = gte(gs, t.scoreThreshold);
        return new Classification(StyleBucket.of(cheap, growing), scaled(gbp), vs, gs,
                value.hits(), growth.hits(), m.dividendYield(), isIncome(m.dividendYield()), null);
    }

    /**
     * Funds are placed on their aggregate price multiples alone. Yahoo returns null for a fund's
     * earnings growth on every fund held here, so there is no growth axis to score — hence the
     * separate buckets rather than a spot in the 2×2.
     */
    private Classification fund(BigDecimal gbp, Metrics m) {
        BigDecimal pe = m.fundPe();
        if (pe == null || !positive(pe)) {
            return new Classification(StyleBucket.UNKNOWN, scaled(gbp), null, null,
                    List.of(), List.of(), null, false,
                    "Fund with no aggregate P/E published");
        }
        StyleBucket bucket = lt(pe, t.fundValueMax) ? StyleBucket.FUND_VALUE
                : gte(pe, t.fundGrowthMin) ? StyleBucket.FUND_GROWTH
                : StyleBucket.FUND_BLEND;
        List<String> notes = new ArrayList<>();
        notes.add("P/E " + trim(pe));
        if (m.fundPb() != null) notes.add("P/B " + trim(m.fundPb()));
        if (m.fundPs() != null) notes.add("P/S " + trim(m.fundPs()));
        return new Classification(bucket, scaled(gbp), null, null,
                List.copyOf(notes), List.of(), null, false, null);
    }

    /**
     * Fixed income, with its <b>running yield</b> — the one bucket that is an income holding by
     * construction rather than by clearing {@link #isIncome}. A gilt is what the income allocation
     * <i>is</i>; excluding it because a low-coupon line yields under the equity bar would have hidden
     * a third of the book from a tile whose whole job is to say how much of the portfolio pays.
     * (It does mean a deep-discount holding like {@code GILT 0.875% 2033}, bought for pull-to-par
     * rather than income, drags the basket yield down — which is a true thing to show, not a flaw.)
     *
     * <p>The yield is derived, never fetched: {@code nominal × coupon ÷ value}. Both inputs are
     * exact — the coupon is in the id and the nominal is in the ledger — so this needs no price and
     * no FX, and because {@code value} came from the caller's own replay the quotient is precisely
     * {@code coupon ÷ (clean price / 100)} against the price the position was valued at. A second
     * price lookup could disagree with the number printed beside it.
     *
     * <p>Yahoo's distribution yield is the fallback for a bond <i>fund</i>, which has no coupon in
     * its symbol. With neither, the position still counts as an income holding — its value is real
     * — but contributes nothing to the income figure, and the null yield is what tells the caller
     * to say so rather than book a silent zero.
     */
    private Classification fixedIncome(String symbol, BigDecimal gbp, BigDecimal quantity, Metrics m) {
        BigDecimal coupon = Instruments.couponOf(symbol);
        if (coupon != null && quantity != null && gbp.signum() > 0) {
            BigDecimal yield = quantity.multiply(coupon, MC).divide(gbp, MC);
            return new Classification(StyleBucket.FIXED_INCOME, scaled(gbp), null, null,
                    List.of("Coupon " + pct(coupon) + " on nominal"), List.of(), yield, true, null);
        }
        if (m.dividendYield() != null) {
            return new Classification(StyleBucket.FIXED_INCOME, scaled(gbp), null, null,
                    List.of("Published distribution yield"), List.of(),
                    m.dividendYield(), true, null);
        }
        return new Classification(StyleBucket.FIXED_INCOME, scaled(gbp), null, null,
                List.of(), List.of(), null, true,
                "No coupon in the id and no published distribution yield — counted as an income "
                        + "holding by value, but its income is not in the total");
    }

    private Classification structural(StyleBucket bucket, BigDecimal gbp, Metrics m) {
        return new Classification(bucket, scaled(gbp), null, null, List.of(), List.of(),
                m.dividendYield(), false, null);
    }

    /** Whether a yield clears the income bar. Public so the caller's tile and rows agree. */
    public boolean isIncome(BigDecimal dividendYield) {
        return dividendYield != null && gte(dividendYield, t.incomeYieldMin);
    }

    /** One axis under construction: which metrics voted, which passed, and the weighted score. */
    private static final class Axis {
        private BigDecimal achieved = BigDecimal.ZERO;
        private BigDecimal available = BigDecimal.ZERO;
        private int votes;
        private final List<String> hits = new ArrayList<>();

        void vote(String name, BigDecimal value, boolean pass, BigDecimal weight) {
            if (value == null) return;
            votes++;
            available = available.add(weight);
            if (pass) {
                achieved = achieved.add(weight);
                hits.add(name);
            }
        }

        /** How many metrics actually had a value — the coverage test, not the pass count. */
        int count() {
            return votes;
        }

        BigDecimal score() {
            if (available.signum() == 0) return null;
            return achieved.divide(available, MC).setScale(SCORE_SCALE, RoundingMode.HALF_UP);
        }

        List<String> hits() {
            return List.copyOf(hits);
        }
    }

    private static boolean lt(BigDecimal a, BigDecimal b) {
        return a != null && a.compareTo(b) < 0;
    }

    private static boolean gte(BigDecimal a, BigDecimal b) {
        return a != null && a.compareTo(b) >= 0;
    }

    private static boolean positive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    private static BigDecimal firstPositive(BigDecimal... vs) {
        for (BigDecimal v : vs) if (positive(v)) return v;
        return null;
    }

    /** {@code 0.0475} → {@code "4.75%"}, trailing zeros trimmed. */
    private static String pct(BigDecimal fraction) {
        return fraction.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString() + "%";
    }

    private static String trim(BigDecimal v) {
        return v.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal scaled(BigDecimal v) {
        return v == null ? null : v.setScale(GBP_SCALE, RoundingMode.HALF_UP);
    }

    private static String normalise(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }

    /** Every tunable, read once from {@code style.properties}. */
    private static final class Thresholds {
        final BigDecimal peMax, evMax, psMax, divYieldMin;
        final BigDecimal revMin, fcfMin, epsMin, compressionMin, fwdRevMin, fwdEpsMin;
        final BigDecimal peWeight, evWeight, psWeight, divWeight;
        final BigDecimal revWeight, fcfWeight, epsWeight, compressionWeight;
        final BigDecimal fwdRevWeight, fwdEpsWeight;
        final BigDecimal scoreThreshold, incomeYieldMin, fundValueMax, fundGrowthMin;
        final int minMetrics;

        Thresholds(Properties p) {
            peMax = dec(p, "value.pe.max", "16");
            evMax = dec(p, "value.evebitda.max", "11");
            psMax = dec(p, "value.ps.max", "2");
            divYieldMin = dec(p, "value.divyield.min", "0.025");
            revMin = dec(p, "growth.revenue.min", "0.15");
            fcfMin = dec(p, "growth.fcf.min", "0.15");
            epsMin = dec(p, "growth.earnings.min", "0.15");
            compressionMin = dec(p, "growth.pecompression.min", "0.15");
            fwdRevMin = dec(p, "growth.fwdrevenue.min", "0.15");
            fwdEpsMin = dec(p, "growth.fwdearnings.min", "0.15");
            peWeight = dec(p, "value.pe.weight", "1.0");
            evWeight = dec(p, "value.evebitda.weight", "0.8");
            psWeight = dec(p, "value.ps.weight", "0.8");
            divWeight = dec(p, "value.divyield.weight", "1.0");
            revWeight = dec(p, "growth.revenue.weight", "1.0");
            fcfWeight = dec(p, "growth.fcf.weight", "0.8");
            epsWeight = dec(p, "growth.earnings.weight", "0.4");
            compressionWeight = dec(p, "growth.pecompression.weight", "0.5");
            fwdRevWeight = dec(p, "growth.fwdrevenue.weight", "0.5");
            fwdEpsWeight = dec(p, "growth.fwdearnings.weight", "0.25");
            scoreThreshold = dec(p, "score.threshold", "0.5");
            incomeYieldMin = dec(p, "income.divyield.min", "0.025");
            fundValueMax = dec(p, "fund.pe.value.max", "18");
            fundGrowthMin = dec(p, "fund.pe.growth.min", "28");
            minMetrics = Integer.parseInt(p.getProperty("min.metrics", "2").trim());
        }

        private static BigDecimal dec(Properties p, String key, String def) {
            return new BigDecimal(p.getProperty(key, def).trim());
        }
    }

    /**
     * One position to score. {@code fund} comes from the caller (the sector block's own test —
     * a non-empty cached {@code sectorWeights}), because only the repository layer knows it.
     *
     * <p>{@code quantity} is the held units in the same convention the rest of the codebase uses —
     * <b>nominal</b> for a bond, shares otherwise. Only fixed income reads it, to turn a coupon
     * into an income figure, so everything else may leave it null via the four-argument form.
     */
    public record Position(String symbol, BigDecimal gbp, Metrics metrics, boolean fund,
                           BigDecimal quantity) {

        public Position(String symbol, BigDecimal gbp, Metrics metrics, boolean fund) {
            this(symbol, gbp, metrics, fund, null);
        }
    }

    /**
     * The fundamentals a style score needs, already unpacked from the cache. Every field is
     * nullable; absent metrics abstain rather than counting against the position. Growth figures
     * and the dividend yield are <b>fractions</b> (0.15 = 15%), matching Yahoo's own scale.
     *
     * <p>{@code forwardRevenueGrowth} / {@code forwardEarningsGrowth} are next-fiscal-year analyst
     * estimates. The latter is null for any company whose earnings base is negative — the
     * fetcher withholds it rather than publish growth measured off a loss.
     */
    public record Metrics(BigDecimal trailingPe, BigDecimal forwardPe, BigDecimal dividendYield,
                          BigDecimal evToEbitda, BigDecimal priceToSales,
                          BigDecimal revenueGrowth, BigDecimal earningsGrowth, BigDecimal fcfGrowth,
                          BigDecimal forwardRevenueGrowth, BigDecimal forwardEarningsGrowth,
                          BigDecimal fundPe, BigDecimal fundPb, BigDecimal fundPs) {

        public static Metrics empty() {
            return new Metrics(null, null, null, null, null, null, null, null, null, null,
                    null, null, null);
        }

        /**
         * Eleven nullable fields is past the point where positional construction stays readable,
         * and every test case sets a different handful of them.
         */
        public static final class Builder {
            private BigDecimal trailingPe, forwardPe, dividendYield, evToEbitda, priceToSales;
            private BigDecimal revenueGrowth, earningsGrowth, fcfGrowth;
            private BigDecimal forwardRevenueGrowth, forwardEarningsGrowth;
            private BigDecimal fundPe, fundPb, fundPs;

            public Builder pe(String trailing, String forward) {
                this.trailingPe = dec(trailing);
                this.forwardPe = dec(forward);
                return this;
            }

            public Builder divYield(String v) {
                this.dividendYield = dec(v);
                return this;
            }

            public Builder evEbitda(String v) {
                this.evToEbitda = dec(v);
                return this;
            }

            public Builder ps(String v) {
                this.priceToSales = dec(v);
                return this;
            }

            public Builder revGrowth(String v) {
                this.revenueGrowth = dec(v);
                return this;
            }

            public Builder epsGrowth(String v) {
                this.earningsGrowth = dec(v);
                return this;
            }

            public Builder fcfGrowth(String v) {
                this.fcfGrowth = dec(v);
                return this;
            }

            public Builder fwdRevGrowth(String v) {
                this.forwardRevenueGrowth = dec(v);
                return this;
            }

            public Builder fwdEpsGrowth(String v) {
                this.forwardEarningsGrowth = dec(v);
                return this;
            }

            public Builder fund(String pe, String pb, String ps) {
                this.fundPe = dec(pe);
                this.fundPb = dec(pb);
                this.fundPs = dec(ps);
                return this;
            }

            public Metrics build() {
                return new Metrics(trailingPe, forwardPe, dividendYield, evToEbitda, priceToSales,
                        revenueGrowth, earningsGrowth, fcfGrowth,
                        forwardRevenueGrowth, forwardEarningsGrowth, fundPe, fundPb, fundPs);
            }

            private static BigDecimal dec(String v) {
                return v == null ? null : new BigDecimal(v);
            }
        }
    }

    /**
     * @param bucket      where the position landed
     * @param gbp         its value, echoed back at GBP scale
     * @param valueScore  0–1, or null when the position was not scored on the 2×2
     * @param growthScore 0–1, or null likewise
     * @param valueFlags  which value tests it passed — for funds, its published multiples instead
     * @param growthFlags which growth tests it passed
     * @param dividendYield the yield used for the income overlay, as a fraction
     * @param income      whether this counts as an income holding — clearing the yield bar for an
     *                    equity, structurally true for fixed income. Cuts across every bucket
     * @param note        why it could not be scored, when {@code bucket} is UNKNOWN
     */
    public record Classification(StyleBucket bucket, BigDecimal gbp,
                                 BigDecimal valueScore, BigDecimal growthScore,
                                 List<String> valueFlags, List<String> growthFlags,
                                 BigDecimal dividendYield, boolean income, String note) {
    }
}
