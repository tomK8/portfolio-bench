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
import java.util.List;
import java.util.Map;

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

    public StyleBreakdownService(AllocationService allocationService,
                                 FundamentalsRepository fundamentalsRepo,
                                 StyleClassifier classifier) {
        this.allocationService = allocationService;
        this.fundamentalsRepo = fundamentalsRepo;
        this.classifier = classifier;
    }

    public StyleBreakdown breakdown() {
        List<String> warnings = new ArrayList<>();

        AllocationService.CurrentSnapshot snapshot = allocationService.currentSnapshot();
        if (snapshot == null) {
            warnings.add("No cash-ledger transactions yet — nothing to classify.");
            return empty(warnings);
        }
        AllocationPoint point = snapshot.point();

        Map<String, FundamentalsRepository.Cached> cache = fundamentalsRepo.loadAll();
        if (cache.isEmpty()) {
            warnings.add("Fundamentals cache is empty, so nothing can be scored. "
                    + "It fills on the next background refresh.");
        }

        Map<StyleBucket, BigDecimal> total = new EnumMap<>(StyleBucket.class);
        Map<StyleBucket, List<StyleBreakdown.Constituent>> members = new EnumMap<>(StyleBucket.class);
        List<StyleBreakdown.Contributor> unknown = new ArrayList<>();
        List<StyleBreakdown.IncomeContributor> incomeRows = new ArrayList<>();

        BigDecimal incomeGbp = BigDecimal.ZERO;
        BigDecimal annualIncome = BigDecimal.ZERO;
        BigDecimal yieldBase = BigDecimal.ZERO;
        boolean fundsExcludedFromYield = false;

        for (Map.Entry<String, BigDecimal> e : point.symbolGbp().entrySet()) {
            String symbol = e.getKey();
            BigDecimal gbp = e.getValue();
            if (gbp == null || gbp.signum() == 0 || CASH_SYMBOL.equalsIgnoreCase(symbol)) continue;

            QuoteSummary quote = quoteFor(cache, symbol);
            boolean fund = quote != null && !quote.sectorWeights().isEmpty();
            StyleClassifier.Classification c = classifier.classify(
                    new StyleClassifier.Position(symbol, gbp, metricsOf(quote), fund));

            total.merge(c.bucket(), c.gbp(), BigDecimal::add);
            members.computeIfAbsent(c.bucket(), k -> new ArrayList<>())
                    .add(new StyleBreakdown.Constituent(symbol, c.gbp(), null,
                            c.valueScore(), c.growthScore(), c.dividendYield(),
                            flagsOf(c), c.note()));
            if (c.bucket() == StyleBucket.UNKNOWN) {
                unknown.add(new StyleBreakdown.Contributor(symbol, c.gbp(), c.note()));
            }

            // Income overlay. A fund's distribution yield is not published by Yahoo for anything
            // held here, so funds contribute to neither the numerator nor the denominator — the
            // resulting portfolio yield is a lower bound, flagged as such rather than diluted by
            // pretending the funds yield zero.
            if (fund) {
                fundsExcludedFromYield = true;
            } else if (c.dividendYield() != null) {
                yieldBase = yieldBase.add(gbp);
                annualIncome = annualIncome.add(gbp.multiply(c.dividendYield(), MC));
                if (c.income()) {
                    incomeGbp = incomeGbp.add(gbp);
                    incomeRows.add(new StyleBreakdown.IncomeContributor(
                            symbol, c.gbp(), c.dividendYield(),
                            scaled(gbp.multiply(c.dividendYield(), MC)), c.bucket().label()));
                }
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
            warnings.add(unknown.size() + " position(s) had too few fundamentals to score and are "
                    + "excluded from the value/growth split — see the list below.");
        }
        if (fundsExcludedFromYield) {
            warnings.add("Funds are excluded from the income figures: Yahoo publishes no "
                    + "distribution yield for them. The portfolio yield shown is a lower bound.");
        }
        incomeRows.sort(Comparator.comparing(StyleBreakdown.IncomeContributor::annualIncomeGbp).reversed());

        StyleBreakdown.Income income = new StyleBreakdown.Income(
                scaled(incomeGbp), fraction(incomeGbp, totalGbp), fraction(incomeGbp, investedGbp),
                fraction(annualIncome, yieldBase), scaled(annualIncome),
                fundsExcludedFromYield, List.copyOf(incomeRows));

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
    private static StyleClassifier.Metrics metricsOf(QuoteSummary q) {
        if (q == null) return StyleClassifier.Metrics.empty();
        Map<String, BigDecimal> x = q.extra();
        return new StyleClassifier.Metrics(
                q.trailingPe(), q.forwardPe(),
                x.get("dividendYield"),
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

    private static QuoteSummary quoteFor(Map<String, FundamentalsRepository.Cached> cache, String symbol) {
        FundamentalsRepository.Cached c = cache.get(symbol);
        return c == null ? null : c.quote();
    }

    private static StyleBreakdown empty(List<String> warnings) {
        return new StyleBreakdown(null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(),
                new StyleBreakdown.Income(BigDecimal.ZERO, null, null, null, BigDecimal.ZERO,
                        false, List.of()),
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
