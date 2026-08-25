package com.portfolio.application;

import com.portfolio.adapter.YahooQuoteSummaryFetcher.QuoteSummary;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.StyleClassifier;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.domain.model.TransactionType;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.DividendHistoryRepository;
import com.portfolio.persistence.FundamentalsRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import com.portfolio.port.HistoricalFxRateProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The income half of the style block. The value/growth scoring is covered at the domain level in
 * {@code StyleClassifierTest}; what needs a service is the arithmetic that spans positions —
 * whether the two yields agree with the rows beneath them, and whether the bridge between their
 * different numerators actually closes.
 */
class StyleBreakdownServiceTest {

    /** No FX needed: everything below is GBP, so a provider that returns nothing is honest. */
    private static final HistoricalFxRateProvider NO_FX = (ccy, from, to) -> new TreeMap<>();

    @TempDir
    Path dbDir;

    private StyleBreakdown breakdown() {
        JdbcConnectionFactory cf = new JdbcConnectionFactory(dbDir);
        KeyValueStore kv = new KeyValueStore(dbDir);
        AllocationService alloc = new AllocationService(
                new CashTransactionRepository(cf, kv), new PriceHistoryRepository(cf),
                NO_FX, new YahooTickerMap(), kv);
        return new StyleBreakdownService(alloc, new FundamentalsRepository(cf),
                new StyleClassifier(),
                new FundamentalsWarmer(symbols -> { }))
                .breakdown();
    }

    /** The same block, with the derived-yield fallback wired in. */
    private StyleBreakdown breakdownWithDividendHistory() {
        JdbcConnectionFactory cf = new JdbcConnectionFactory(dbDir);
        KeyValueStore kv = new KeyValueStore(dbDir);
        YahooTickerMap tickers = new YahooTickerMap();
        AllocationService alloc = new AllocationService(
                new CashTransactionRepository(cf, kv), new PriceHistoryRepository(cf),
                NO_FX, tickers, kv);
        DividendYieldService yields = new DividendYieldService(
                new DividendHistoryRepository(cf), new PriceHistoryRepository(cf), tickers);
        return new StyleBreakdownService(alloc, new FundamentalsRepository(cf),
                new StyleClassifier(), new FundamentalsWarmer(symbols -> { }),
                yields, FundamentalsWarmer.disabled())
                .breakdown();
    }

    /**
     * £100,000 nominal of a 4.75% gilt at 95.24 (£95,240, £4,750/yr), a 5%-yielding bank at
     * £50,000 (£2,500/yr) and a 0.5%-yielding grower at £40,000 (£200/yr) — one holding on each
     * side of the income bar, plus fixed income.
     */
    private void seedPortfolio() {
        JdbcConnectionFactory cf = new JdbcConnectionFactory(dbDir);
        CashTransactionRepository cash = new CashTransactionRepository(cf, new KeyValueStore(dbDir));
        cash.saveII(List.of(
                contribution("2024-01-01", 200_000),
                trade("2024-01-02", "GILT 4.75% 2038", 100_000, -100_000),
                trade("2024-01-02", "BANK", 1_000, -50_000),
                trade("2024-01-02", "GROWER", 400, -40_000)));

        PriceHistoryRepository prices = new PriceHistoryRepository(cf);
        prices.savePriceBars(List.of(
                bar("GILT 4.75% 2038", "95.24"), bar("BANK", "50"), bar("GROWER", "100")));

        FundamentalsRepository fundamentals = new FundamentalsRepository(cf);
        fundamentals.save("BANK", quote("BANK", "0.05", "0.03"), Instant.now());
        fundamentals.save("GROWER", quote("GROWER", "0.005", "0.30"), Instant.now());
    }

    @Test
    void fixedIncomeIsPartOfTheHeadlineIncomeFigure() {
        seedPortfolio();
        StyleBreakdown.Income inc = breakdown().income();

        // Gilt + bank; the 0.5% grower is below the 2.5% bar and is not an income holding.
        assertEquals(0, gbp("145240").compareTo(inc.gbp()));
        assertEquals(0, gbp("95240").compareTo(inc.fixedIncome().gbp()));
        assertEquals(0, gbp("50000").compareTo(inc.equity().gbp()));
        assertEquals(0, gbp("4750").compareTo(inc.fixedIncome().annualIncomeGbp()));
        assertEquals(0, gbp("2500").compareTo(inc.equity().annualIncomeGbp()));
        assertEquals("GILT 4.75% 2038", inc.fixedIncome().contributors().get(0).symbol());
    }

    @Test
    void theTwoYieldsDivideByTheDenominatorsTheyName() {
        seedPortfolio();
        StyleBreakdown b = breakdown();
        StyleBreakdown.Income inc = b.income();

        // On income holdings: 7,250 / 145,240 = 4.9917%. Not over the whole portfolio, and not
        // over a base that quietly excludes the fixed income now inside the headline.
        assertEquals(0, frac("0.049917").compareTo(round6(inc.yieldOnIncomeHoldings())));

        // On total portfolio: every yielding holding (the grower's £200 included) over everything
        // held, £10,000 of idle cash included. That the cash dilutes it is the point — it is the
        // figure that answers "what does the portfolio pay me", and cash pays nothing here.
        assertEquals(0, gbp("7450").compareTo(inc.annualIncomeGbp()));
        assertEquals(0, gbp("195240").compareTo(b.totalGbp()));
        assertEquals(0, frac("0.038158").compareTo(round6(inc.yieldOnTotal())));
    }

    @Test
    void theBridgeBetweenTheTwoNumeratorsCloses() {
        // The tile prints one £/yr figure beside two percentages with different numerators, so
        // the drill-down has to be able to explain the gap rather than leave it looking wrong.
        seedPortfolio();
        StyleBreakdown.Income inc = breakdown().income();
        assertEquals(0, gbp("200").compareTo(inc.otherAnnualIncomeGbp()));
        assertEquals(0, inc.annualIncomeGbp().compareTo(
                inc.incomeAnnualGbp().add(inc.otherAnnualIncomeGbp())));
        assertEquals(0, inc.incomeAnnualGbp().compareTo(
                inc.fixedIncome().annualIncomeGbp().add(inc.equity().annualIncomeGbp())));
    }

    @Test
    void aGiltWithNoDerivableIncomeCountsByValueAndIsNamed() {
        // "GILT" with no coupon in the id: isBond says yes, couponOf says nothing. Its value is
        // real so it belongs in the tile; booking its income as zero would silently understate
        // the portfolio yield, so it is reported instead.
        JdbcConnectionFactory cf = new JdbcConnectionFactory(dbDir);
        new CashTransactionRepository(cf, new KeyValueStore(dbDir)).saveII(List.of(
                contribution("2024-01-01", 10_000),
                trade("2024-01-02", "GILT UNDATED", 10_000, -10_000)));
        new PriceHistoryRepository(cf).savePriceBars(List.of(bar("GILT UNDATED", "100")));

        StyleBreakdown b = breakdown();
        assertEquals(0, gbp("10000").compareTo(b.income().gbp()));
        assertEquals(0, BigDecimal.ZERO.compareTo(b.income().annualIncomeGbp()));
        assertNull(b.income().fixedIncome().contributors().get(0).annualIncomeGbp());
        assertNotNull(b.income().fixedIncome().contributors().get(0).note());
        assertTrue(b.warnings().stream().anyMatch(w -> w.contains("GILT UNDATED")),
                () -> "expected a warning naming the gilt, got " + b.warnings());
    }

    // ---- fixtures ---------------------------------------------------------------------------

    /**
     * The HICL case, end to end. Yahoo's {@code summaryDetail} for HICL.L carries no
     * {@code dividendYield} and no {@code dividendRate} — a successful fetch that returns almost
     * nothing — so the trust scored into VALUE and its £10,270 sat outside the income tile with no
     * warning. Its payment history says otherwise, and the chart feed already ships it.
     */
    @Test
    void aTrustWithNoPublishedYieldBecomesAnIncomeHoldingFromItsPaymentHistory() {
        JdbcConnectionFactory cf = new JdbcConnectionFactory(dbDir);
        CashTransactionRepository cash = new CashTransactionRepository(cf, new KeyValueStore(dbDir));
        cash.saveII(List.of(
                contribution("2024-01-01", 20_000),
                trade("2024-01-02", "HICL", 7_300, -10_270)));
        new PriceHistoryRepository(cf).savePriceBars(List.of(bar("HICL", "140.0")));
        // A real fetch, no dividend fields at all — exactly what Yahoo returns for this listing.
        new FundamentalsRepository(cf).save("HICL", quoteWithoutDividend("HICL"), Instant.now());

        // HICL's real cadence, anchored on today so the test cannot rot: four payments last year,
        // but only three inside the trailing twelve months — the July payment dropped out before
        // its replacement went ex, which is exactly what made a plain TTM sum read 4.48%.
        DividendHistoryRepository div = new DividendHistoryRepository(cf);
        div.saveDividends("HICL", List.of(
                payment(642, "2.06"), payment(537, "2.06"), payment(460, "2.07"),
                payment(390, "2.08"),
                payment(271, "2.09"), payment(173, "2.09"), payment(89, "2.09")), "GBp");
        div.markFullScan("HICL", LocalDate.now().minusYears(30), 7);

        StyleBreakdown.Income inc = breakdownWithDividendHistory().income();

        StyleBreakdown.IncomeContributor row = inc.equity().contributors().stream()
                .filter(c -> "HICL".equals(c.symbol())).findFirst().orElse(null);
        assertNotNull(row, "a 6% trust belongs in the income tile");
        // 8.35p over a 140p close = 5.96%. Units cancel; no FX and no pence conversion involved.
        assertEquals(0, new BigDecimal("0.059643").compareTo(round6(row.dividendYield())));
        assertTrue(row.yieldSource().startsWith("Derived"), row.yieldSource());
        assertTrue(row.yieldSource().contains("4 payments"), row.yieldSource());
        assertTrue(row.yieldSource().contains("clipped"), row.yieldSource());
    }

    /** Without a scan marker the same history is not trusted: absent must not read as zero. */
    @Test
    void anUnscannedSymbolIsLeftAloneRatherThanBookedAtZero() {
        JdbcConnectionFactory cf = new JdbcConnectionFactory(dbDir);
        CashTransactionRepository cash = new CashTransactionRepository(cf, new KeyValueStore(dbDir));
        cash.saveII(List.of(
                contribution("2024-01-01", 20_000),
                trade("2024-01-02", "HICL", 7_300, -10_270)));
        new PriceHistoryRepository(cf).savePriceBars(List.of(bar("HICL", "140.0")));
        new FundamentalsRepository(cf).save("HICL", quoteWithoutDividend("HICL"), Instant.now());
        // Payments stored, but no full-window scan recorded — the nightly incremental case.
        new DividendHistoryRepository(cf).saveDividends("HICL",
                List.of(payment(89, "2.09")), "GBp");

        StyleBreakdown.Income inc = breakdownWithDividendHistory().income();
        assertTrue(inc.equity().contributors().stream()
                .noneMatch(c -> "HICL".equals(c.symbol())));
    }

    /** A payment {@code daysAgo} before today, so the trailing windows stay put as time passes. */
    private static com.portfolio.domain.DividendYield.Payment payment(int daysAgo, String amount) {
        return new com.portfolio.domain.DividendYield.Payment(
                LocalDate.now().minusDays(daysAgo), new BigDecimal(amount));
    }

    /** A real quote with enough to score both axes, but no dividend field of any kind. */
    private static QuoteSummary quoteWithoutDividend(String symbol) {
        return new QuoteSummary(symbol, "GBp", null, null,
                new BigDecimal("10"), new BigDecimal("13"), null, null, null, null, null,
                Map.of("priceToSalesTrailing12Months", new BigDecimal("1.5"),
                        "revenueGrowth", new BigDecimal("0.03"),
                        "earningsGrowth", new BigDecimal("0.03")),
                Map.of(), false);
    }

    private static CashTransaction trade(String date, String symbol, double qty, double amountGbp) {
        return new CashTransaction(date, Account.II, TransactionType.TRANSACTION, symbol,
                qty, amountGbp, "GBP", 1.0, amountGbp, null, null, date);
    }

    private static CashTransaction contribution(String date, double amountGbp) {
        return new CashTransaction(date, Account.II, TransactionType.CONTRIBUTION, "GBP",
                0.0, amountGbp, "GBP", 1.0, amountGbp, null, null, date);
    }

    private static PriceBar bar(String symbol, String close) {
        double c = Double.parseDouble(close);
        return new PriceBar(symbol, LocalDate.now().minusDays(1), null, null, null,
                c, c, 1.0, null, "GBP");
    }

    /**
     * A direct holding: no sector weights, so the classifier does not read it as a fund. Both axes
     * are given enough metrics to clear {@code min.metrics} — an under-covered position lands in
     * UNKNOWN and is not an income holding at all, which would test the wrong thing here.
     */
    private static QuoteSummary quote(String symbol, String dividendYield, String revenueGrowth) {
        return new QuoteSummary(symbol, "GBP", null, null,
                new BigDecimal("10"), new BigDecimal("10"), null, null, null, null, null,
                Map.of("dividendYield", new BigDecimal(dividendYield),
                        "priceToSalesTrailing12Months", new BigDecimal("1.5"),
                        "revenueGrowth", new BigDecimal(revenueGrowth),
                        "earningsGrowth", new BigDecimal(revenueGrowth)),
                Map.of(), false);
    }

    private static BigDecimal gbp(String v) {
        return new BigDecimal(v);
    }

    private static BigDecimal frac(String v) {
        return new BigDecimal(v);
    }

    private static BigDecimal round6(BigDecimal v) {
        return v == null ? null : v.setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal round2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
