package com.portfolio.application;

import com.portfolio.adapter.YahooQuoteSummaryFetcher.QuoteSummary;
import com.portfolio.application.AllocationService.AllocationPoint;
import com.portfolio.domain.StyleBucket;
import com.portfolio.domain.StyleClassifier;
import com.portfolio.persistence.FundamentalsRepository;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the investment-style breakdown of the current portfolio — how much is value, how much is
 * growth, how much is both, and how much of it throws off income.
 *
 * <p>Values come from {@link AllocationService#currentSnapshot()}, the same forward-replay that
 * feeds the sector block directly below and the charts below that, so the three can never disagree
 * about what is held. Fundamentals come from the cache written by {@code FundamentalsFetchJob};
 * nothing here fetches, and nothing here is hand-maintained beyond the thresholds in
 * {@code style.properties}.
 *
 * <p>Everything degrades. An empty ledger, a cold fundamentals cache or a position with too few
 * metrics each produce a warning and a partial answer rather than an exception. Value is never
 * dropped quietly: anything unscoreable lands in {@link StyleBucket#UNKNOWN} and is listed by
 * name, because a portfolio that silently omitted a fifth of itself would make the growth-vs-value
 * split look far more confident than it is.
 */
public class StyleBreakdownService {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final int GBP_SCALE = 2;
    private static final int PCT_SCALE = 6;
    private static final String CASH_SYMBOL = "CASH";

    private final AllocationService allocationService;
    private final FundamentalsRepository fundamentalsRepo;
    private final StyleClassifier classifier;
    private final FundamentalsWarmer warmer;
    /** Null where a caller has no price/dividend store — every use is guarded. */
    private final DividendYieldService dividendYields;
    private final FundamentalsWarmer dividendWarmer;

    public StyleBreakdownService(AllocationService allocationService,
                                 FundamentalsRepository fundamentalsRepo,
                                 StyleClassifier classifier,
                                 FundamentalsWarmer warmer) {
        this(allocationService, fundamentalsRepo, classifier, warmer, null,
                FundamentalsWarmer.disabled());
    }

    public StyleBreakdownService(AllocationService allocationService,
                                 FundamentalsRepository fundamentalsRepo,
                                 StyleClassifier classifier,
                                 FundamentalsWarmer warmer,
                                 DividendYieldService dividendYields,
                                 FundamentalsWarmer dividendWarmer) {
        this.allocationService = allocationService;
        this.fundamentalsRepo = fundamentalsRepo;
        this.classifier = classifier;
        this.warmer = warmer;
        this.dividendYields = dividendYields;
        this.dividendWarmer = dividendWarmer == null ? FundamentalsWarmer.disabled() : dividendWarmer;
    }

    public StyleBreakdown breakdown() {
        List<String> warnings = new ArrayList<>();

        AllocationService.CurrentSnapshot snapshot = allocationService.currentSnapshot();
        if (snapshot == null) {
            warnings.add("No cash-ledger transactions yet — nothing to classify.");
            return empty(warnings);
        }
        AllocationPoint point = snapshot.point();

        // Yields derived from each name's own payment history, for the instruments Yahoo's
        // summaryDetail says nothing about. HICL.L — a 6% infrastructure trust — publishes neither
        // dividendYield nor dividendRate there, so before this the income overlay read it as a
        // non-payer and filed £10,270 under Value with no warning at all.
        Map<String, DividendYieldService.Derived> derived = dividendYields == null
                ? Map.of() : dividendYields.yieldsFor(point.symbolGbp().keySet());

        Map<String, FundamentalsRepository.Cached> cache = fundamentalsRepo.loadAll();
        if (cache.isEmpty()) {
            warnings.add("Fundamentals cache is empty, so nothing can be scored. "
                    + "It fills on the next background refresh.");
        }

        Map<StyleBucket, BigDecimal> total = new EnumMap<>(StyleBucket.class);
        Map<StyleBucket, List<StyleBreakdown.Constituent>> members = new EnumMap<>(StyleBucket.class);
        List<StyleBreakdown.Contributor> unknown = new ArrayList<>();
        // Unscored only because no metrics have been fetched yet — warmed at the end of the pass.
        Set<String> awaitingData = new LinkedHashSet<>();
        List<StyleBreakdown.IncomeContributor> bondRows = new ArrayList<>();
        List<StyleBreakdown.IncomeContributor> equityRows = new ArrayList<>();
        // Fixed income with no derivable coupon or distribution yield: its value is in the tile,
        // its income is not, and that has to be said rather than booked as a silent zero.
        List<String> incomeUnknown = new ArrayList<>();

        BigDecimal incomeGbp = BigDecimal.ZERO;
        BigDecimal bondGbp = BigDecimal.ZERO;
        BigDecimal bondIncome = BigDecimal.ZERO;
        BigDecimal equityIncomeGbp = BigDecimal.ZERO;
        BigDecimal equityIncome = BigDecimal.ZERO;
        BigDecimal annualIncome = BigDecimal.ZERO;
        boolean fundsExcludedFromYield = false;

        for (Map.Entry<String, BigDecimal> e : point.symbolGbp().entrySet()) {
            String symbol = e.getKey();
            BigDecimal gbp = e.getValue();
            if (gbp == null || gbp.signum() == 0 || CASH_SYMBOL.equalsIgnoreCase(symbol)) continue;

            QuoteSummary quote = quoteFor(cache, symbol);
            boolean fund = quote != null && !quote.sectorWeights().isEmpty();
            DividendYieldService.Derived derivedYield = derived.get(symbol);
            StyleClassifier.Classification c = classifier.classify(
                    new StyleClassifier.Position(symbol, gbp,
                            metricsOf(quote, derivedYield), fund,
                            snapshot.quantityBySymbol().get(symbol)));

            total.merge(c.bucket(), c.gbp(), BigDecimal::add);
            members.computeIfAbsent(c.bucket(), k -> new ArrayList<>())
                    .add(new StyleBreakdown.Constituent(symbol, c.gbp(), null,
                            c.valueScore(), c.growthScore(), c.dividendYield(),
                            flagsOf(c), c.note()));
            if (c.bucket() == StyleBucket.UNKNOWN) {
                // A missing cache row means no metric ever reached the classifier, so its "too few
                // metrics" note describes the fetch, not the instrument. Same three-way split the
                // sector block makes, and the same warmer clears the first two states.
                String state = quote == null ? "AWAITING_DATA"
                        : quote.missing() ? "FETCH_FAILED" : "NOT_SCOREABLE";
                if (!"NOT_SCOREABLE".equals(state)) awaitingData.add(symbol);
                unknown.add(new StyleBreakdown.Contributor(symbol, c.gbp(), c.note(), state));
            }

            // Income overlay. A fund's distribution yield is not published by Yahoo for anything
            // held here, so an equity fund contributes to neither figure — the yields are a lower
            // bound, flagged as such rather than diluted by pretending the funds yield zero. A
            // *bond* fund is exempt: it reaches this loop as FIXED_INCOME via a style.properties
            // override, not through the fund branch, and is an income holding whatever Yahoo says.
            boolean bond = c.bucket() == StyleBucket.FIXED_INCOME;
            // A fund's distribution yield is now derivable from its own payment history, so a
            // distributing ETF contributes to the portfolio-wide figure instead of being assumed
            // to pay nothing. It still does not join the income *group*: that is the scored
            // equities plus fixed income, and a tracker paying 0.9% is not an income holding.
            // An accumulating fund derives a real zero and is correctly no longer a caveat.
            BigDecimal fundYield = fund && !bond && derivedYield != null ? derivedYield.yield() : null;
            if (fund && !bond && fundYield == null) {
                fundsExcludedFromYield = true;
                continue;
            }
            BigDecimal effectiveYield = fund && !bond ? fundYield : c.dividendYield();
            BigDecimal positionIncome = effectiveYield == null
                    ? null : gbp.multiply(effectiveYield, MC);
            if (positionIncome != null) annualIncome = annualIncome.add(positionIncome);
            if (fund && !bond) continue;      // in the total, not in the income group
            if (!c.income()) continue;

            // An income holding: the yielding equities, plus all fixed income structurally.
            incomeGbp = incomeGbp.add(gbp);
            StyleBreakdown.IncomeContributor row = new StyleBreakdown.IncomeContributor(
                    symbol, c.gbp(), c.dividendYield(), scaled(positionIncome),
                    c.bucket().label(), positionIncome == null ? c.note() : null,
                    yieldSource(quote, derivedYield));
            if (bond) {
                bondGbp = bondGbp.add(gbp);
                bondRows.add(row);
                if (positionIncome == null) incomeUnknown.add(symbol);
                else bondIncome = bondIncome.add(positionIncome);
            } else {
                equityIncomeGbp = equityIncomeGbp.add(gbp);
                equityRows.add(row);
                equityIncome = equityIncome.add(positionIncome);
            }
        }

        BigDecimal cashGbp = point.cashGbp() == null ? BigDecimal.ZERO : point.cashGbp();
        if (cashGbp.signum() != 0) {
            total.merge(StyleBucket.CASH, cashGbp, BigDecimal::add);
            for (Map.Entry<String, BigDecimal> e : snapshot.cashGbpByAccountCcy().entrySet()) {
                members.computeIfAbsent(StyleBucket.CASH, k -> new ArrayList<>())
                        .add(new StyleBreakdown.Constituent(
                                e.getKey().replace("|", " · "), e.getValue(), null,
                                null, null, null, List.of(), null));
            }
        }

        BigDecimal totalGbp = point.totalGbp();
        BigDecimal investedGbp = point.investedGbp();

        List<StyleBreakdown.Row> rows = new ArrayList<>();
        for (Map.Entry<StyleBucket, BigDecimal> b : total.entrySet()) {
            if (b.getValue().signum() == 0) continue;
            StyleBucket bucket = b.getKey();
            rows.add(new StyleBreakdown.Row(
                    bucket.name(), bucket.label(), scaled(b.getValue()),
                    fraction(b.getValue(), totalGbp),
                    bucket == StyleBucket.CASH ? null : fraction(b.getValue(), investedGbp),
                    bucket.scored(),
                    finaliseConstituents(members.get(bucket), b.getValue())));
        }
        rows.sort(Comparator.comparing(StyleBreakdown.Row::gbp).reversed());

        if (!unknown.isEmpty()) {
            unknown.sort(Comparator.comparing(StyleBreakdown.Contributor::gbp).reversed());
            long settled = unknown.stream()
                    .filter(c -> "NOT_SCOREABLE".equals(c.state())).count();
            if (settled > 0) {
                warnings.add(settled + " position(s) had too few fundamentals to score and are "
                        + "excluded from the value/growth split — see the list below.");
            }
        }
        // Fire-and-forget, shared with the sector block so a newly bought name is fetched once.
        warmer.warm(awaitingData);
        // A symbol whose payment history has never been read in full cannot be told apart from one
        // that pays nothing, so it is warmed rather than assumed. Separate warmer from the one
        // above: this backfills price history, not fundamentals.
        if (dividendYields != null) {
            dividendWarmer.warm(dividendYields.unscanned(point.symbolGbp().keySet()));
        }
        if (fundsExcludedFromYield) {
            warnings.add("Some funds are excluded from the income figures — no distribution yield "
                    + "is published for them and none could be derived from their payment history. "
                    + "Both yields shown are a lower bound.");
        }
        if (!incomeUnknown.isEmpty()) {
            warnings.add("No coupon or distribution yield could be derived for "
                    + String.join(", ", incomeUnknown) + " — counted in the income holdings by "
                    + "value, but contributing nothing to the income figure.");
        }
        sortByIncome(bondRows);
        sortByIncome(equityRows);

        BigDecimal incomeAnnual = bondIncome.add(equityIncome);
        StyleBreakdown.Income income = new StyleBreakdown.Income(
                scaled(incomeGbp), fraction(incomeGbp, totalGbp), fraction(incomeGbp, investedGbp),
                fraction(incomeAnnual, incomeGbp), fraction(annualIncome, totalGbp),
                scaled(incomeAnnual), scaled(annualIncome),
                scaled(annualIncome.subtract(incomeAnnual)), fundsExcludedFromYield,
                new StyleBreakdown.Section(StyleBucket.FIXED_INCOME.label(), scaled(bondGbp),
                        scaled(bondIncome), fraction(bondIncome, bondGbp), List.copyOf(bondRows)),
                new StyleBreakdown.Section("Equities", scaled(equityIncomeGbp),
                        scaled(equityIncome), fraction(equityIncome, equityIncomeGbp),
                        List.copyOf(equityRows)));

        BigDecimal v = total.getOrDefault(StyleBucket.VALUE, BigDecimal.ZERO);
        BigDecimal g = total.getOrDefault(StyleBucket.GROWTH, BigDecimal.ZERO);
        BigDecimal vg = total.getOrDefault(StyleBucket.VALUE_GROWTH, BigDecimal.ZERO);
        BigDecimal n = total.getOrDefault(StyleBucket.NEITHER, BigDecimal.ZERO);
        StyleBreakdown.Quadrant quadrant = new StyleBreakdown.Quadrant(
                scaled(v), scaled(g), scaled(vg), scaled(n),
                scaled(v.add(g).add(vg).add(n)));

        return new StyleBreakdown(point.date(), totalGbp, investedGbp, scaled(cashGbp),
                rows, income, quadrant, unknown, warnings);
    }

    /**
     * Unpacks the cached quote into the classifier's input. Keeps every Yahoo field name confined
     * to this one method, so the domain class stays ignorant of where its numbers came from.
     */
    private static StyleClassifier.Metrics metricsOf(QuoteSummary q,
                                                    DividendYieldService.Derived derived) {
        BigDecimal fallback = derived == null ? null : derived.yield();
        if (q == null) {
            // No cached quote, but a payment history can still say what it yields.
            return fallback == null ? StyleClassifier.Metrics.empty()
                    : new StyleClassifier.Metrics(null, null, fallback, null, null, null, null,
                            null, null, null, null, null, null);
        }
        Map<String, BigDecimal> x = q.extra();
        return new StyleClassifier.Metrics(
                q.trailingPe(), q.forwardPe(),
                x.get("dividendYield") != null ? x.get("dividendYield") : fallback,
                x.get("enterpriseToEbitda"),
                x.get("priceToSalesTrailing12Months"),
                x.get("revenueGrowth"),
                x.get("earningsGrowth"),
                x.get("fcfGrowthYoy"),
                x.get("forwardRevenueGrowth"),
                x.get("forwardEarningsGrowth"),
                x.get("fundPriceToEarnings"),
                x.get("fundPriceToBook"),
                x.get("fundPriceToSales"));
    }

    /**
     * Where the yield on a row came from, so a reader can argue with it. Yahoo's own figure wins
     * when it exists; a derived one names its evidence ("4 payments to 2026-05-28").
     */
    private static String yieldSource(QuoteSummary q, DividendYieldService.Derived derived) {
        if (q != null && q.extra().get("dividendYield") != null) return "Yahoo published yield";
        if (derived != null) return "Derived — " + derived.explain();
        return null;
    }

    /** Value flags first, then growth, each tagged so the popup can tell them apart. */
    private static List<String> flagsOf(StyleClassifier.Classification c) {
        List<String> out = new ArrayList<>();
        for (String s : c.valueFlags()) out.add("V:" + s);
        for (String s : c.growthFlags()) out.add("G:" + s);
        return List.copyOf(out);
    }

    private static List<StyleBreakdown.Constituent> finaliseConstituents(
            List<StyleBreakdown.Constituent> members, BigDecimal bucketTotal) {
        if (members == null || members.isEmpty()) return List.of();
        List<StyleBreakdown.Constituent> out = new ArrayList<>();
        for (StyleBreakdown.Constituent c : members) {
            out.add(new StyleBreakdown.Constituent(c.label(), scaled(c.gbp()),
                    fraction(c.gbp(), bucketTotal), c.valueScore(), c.growthScore(),
                    c.dividendYield(), c.flags(), c.note()));
        }
        out.sort(Comparator.comparing(StyleBreakdown.Constituent::gbp).reversed());
        return List.copyOf(out);
    }

    /** Largest annual income first; an unknown-income row sorts last rather than as a zero. */
    private static void sortByIncome(List<StyleBreakdown.IncomeContributor> rows) {
        rows.sort(Comparator.comparing(StyleBreakdown.IncomeContributor::annualIncomeGbp,
                Comparator.nullsLast(Comparator.reverseOrder())));
    }

    private static QuoteSummary quoteFor(Map<String, FundamentalsRepository.Cached> cache, String symbol) {
        FundamentalsRepository.Cached c = cache.get(symbol);
        return c == null ? null : c.quote();
    }

    private static StyleBreakdown empty(List<String> warnings) {
        return new StyleBreakdown(null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(),
                new StyleBreakdown.Income(BigDecimal.ZERO, null, null, null, null,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false,
                        new StyleBreakdown.Section(StyleBucket.FIXED_INCOME.label(),
                                BigDecimal.ZERO, BigDecimal.ZERO, null, List.of()),
                        new StyleBreakdown.Section("Equities",
                                BigDecimal.ZERO, BigDecimal.ZERO, null, List.of())),
                new StyleBreakdown.Quadrant(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO),
                List.of(), warnings);
    }

    private static BigDecimal fraction(BigDecimal part, BigDecimal whole) {
        if (part == null || whole == null || whole.signum() == 0) return null;
        return part.divide(whole, MC).setScale(PCT_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaled(BigDecimal v) {
        return v == null ? null : v.setScale(GBP_SCALE, RoundingMode.HALF_UP);
    }
}
