package com.portfolio.application;

import com.portfolio.adapter.YahooQuoteSummaryFetcher.QuoteSummary;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.StyleClassifier;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.domain.model.TransactionType;
import com.portfolio.persistence.CashTransactionRepository;
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
