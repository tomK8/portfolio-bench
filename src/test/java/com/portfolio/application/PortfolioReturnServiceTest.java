package com.portfolio.application;

import com.portfolio.application.PortfolioReturnService.ReturnTimeline;
import com.portfolio.application.PortfolioValueService.DailyValue;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.port.HistoricalFxRateProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class PortfolioReturnServiceTest {

    private static final HistoricalFxRateProvider EMPTY_FX = (ccy, from, to) -> new TreeMap<>();

    @TempDir
    Path dbDir;

    /** Real cashRepo + KV store on a temp DB; PortfolioValueService is stubbed to feed a fixed daily V series. */
    private PortfolioReturnService service(List<DailyValue> values, List<CashTransaction> contribs) {
        return service(values, contribs, EMPTY_FX);
    }

    private PortfolioReturnService service(List<DailyValue> values,
                                           List<CashTransaction> contribs,
                                           HistoricalFxRateProvider fx) {
        JdbcConnectionFactory cf = new JdbcConnectionFactory(dbDir);
        KeyValueStore kv = new KeyValueStore(dbDir);
        CashTransactionRepository repo = new CashTransactionRepository(cf, kv);
        if (!contribs.isEmpty()) repo.saveRothIra(List.of(), BigDecimal.ZERO);  // ensure table init
        for (CashTransaction t : contribs) {
            // Use saveII so contributions land as-is without dedup confusion; II key is by
            // (date, type, symbol, amount, currency) which is fine for our synthetic rows.
            repo.saveII(List.of(t));
        }
        PortfolioValueService stub = new PortfolioValueService(null, null, null, null, null) {
            @Override
            public List<DailyValue> dailyValues() { return values; }
        };
        return new PortfolioReturnService(stub, repo, kv, fx);
    }

    private static DailyValue dv(String date, String value) {
        return new DailyValue(LocalDate.parse(date), new BigDecimal(value));
    }

    private static CashTransaction contrib(String date, double amountGbp) {
        return new CashTransaction(date, Account.II, TransactionType.CONTRIBUTION, "GBP",
                0.0, amountGbp, "GBP", 1.0, amountGbp, null, null, "test");
    }

    @Test
    void emptyValueSeriesProducesEmptyTimeline() {
        ReturnTimeline t = service(List.of(), List.of()).timeline();
        assertTrue(t.growthPoints().isEmpty());
        assertNull(t.summary().trailing1y());
        assertNull(t.summary().sinceInception());
    }

    @Test
    void pureCapitalGrowthChainsToCorrectMultiple() {
        // £100 → £110 over one day = +10% on day 2; no contributions.
        ReturnTimeline t = service(List.of(
                dv("2024-01-01", "100"),
                dv("2024-01-02", "110")
        ), List.of()).timeline();

        assertEquals(2, t.growthPoints().size());
        assertEquals(0, new BigDecimal("1.0000000000").compareTo(t.growthPoints().get(0).growth()),
                "growth anchors at 1.0 on the first day with a non-zero value");
        assertEquals(0, new BigDecimal("1.1000000000").compareTo(t.growthPoints().get(1).growth()),
                "+10% day → growth ×1.10");
    }

    @Test
    void contributionDoesNotInflateReturn() {
        // £100 → £150 with a £50 contribution that day → r = 0, growth stays at 1.0.
        ReturnTimeline t = service(List.of(
                dv("2024-01-01", "100"),
                dv("2024-01-02", "150")
        ), List.of(contrib("2024-01-02", 50.0))).timeline();

        assertEquals(0, BigDecimal.ONE.compareTo(t.growthPoints().get(1).growth()),
                "the £50 added came from outside; TWR must be flat");
    }

    @Test
    void cumulativeContributionsAccumulateOnSampleDates() {
        ReturnTimeline t = service(List.of(
                dv("2024-01-01", "100"),
                dv("2024-01-02", "200"),
                dv("2024-01-03", "300")
        ), List.of(
                contrib("2024-01-02", 50.0),
                contrib("2024-01-03", 25.0)
        )).timeline();

        // contribPoints align with growthPoints (one per included day).
        assertEquals(3, t.contributionPoints().size());
        assertEquals(0, new BigDecimal("0").compareTo(t.contributionPoints().get(0).cumulativeGbp()));
        assertEquals(0, new BigDecimal("50").compareTo(t.contributionPoints().get(1).cumulativeGbp()));
        assertEquals(0, new BigDecimal("75").compareTo(t.contributionPoints().get(2).cumulativeGbp()));
    }

    @Test
    void chainStartsOnFirstDayWithNonZeroValue() {
        // Leading zero-V days (ledger has rows but nothing's invested yet) are skipped from the chain.
        ReturnTimeline t = service(List.of(
                dv("2024-01-01", "0"),
                dv("2024-01-02", "0"),
                dv("2024-01-03", "100"),
                dv("2024-01-04", "120")
        ), List.of()).timeline();

        assertEquals(2, t.growthPoints().size(), "chain starts when value first goes positive");
        assertEquals("2024-01-03", t.growthPoints().get(0).date());
        assertEquals(0, new BigDecimal("1.2000000000").compareTo(t.growthPoints().get(1).growth()));
    }

    @Test
    void trailing1yIsRawPeriodReturnSinceInceptionIsAnnualised() {
        // Two days, ~5 years apart, value doubled → annualised since-inception ≈ 2^(1/5) − 1 ≈ 0.1487.
        ReturnTimeline t = service(List.of(
                dv("2020-01-01", "100"),
                dv("2025-01-01", "200")
        ), List.of()).timeline();

        BigDecimal since = t.summary().sinceInception();
        assertNotNull(since);
        // 5y compounded ratio 2.0; 1827 days; (2.0)^(365.25/1827) − 1 ≈ 0.14869.
        double expected = Math.pow(2.0, 365.25 / 1827.0) - 1.0;
        assertEquals(expected, since.doubleValue(), 1e-4);

        // 1y trailing window starts well before any data → no data point on or before windowStart → null.
        assertNull(t.summary().trailing1y(), "history shorter than 1y from windowStart → null");
    }

    @Test
    void drawdownTracksUnderwaterFromRunningPeak() {
        // V: 100 → 120 → 90 → 150. Growth chain (no contributions): 1.0, 1.2, 0.9, 1.5.
        // Drawdown: 0% (peak=1.0), 0% (peak→1.2), (0.9-1.2)/1.2 = -25%, 0% (peak→1.5).
        ReturnTimeline t = service(List.of(
                dv("2024-01-01", "100"),
                dv("2024-01-02", "120"),
                dv("2024-01-03", "90"),
                dv("2024-01-04", "150")
        ), List.of()).timeline();

        assertEquals(4, t.drawdownPoints().size());
        assertEquals(0.0, t.drawdownPoints().get(0).drawdown().doubleValue(), 1e-9);
        assertEquals(0.0, t.drawdownPoints().get(1).drawdown().doubleValue(), 1e-9);
        assertEquals(-0.25, t.drawdownPoints().get(2).drawdown().doubleValue(), 1e-6);
        assertEquals(0.0, t.drawdownPoints().get(3).drawdown().doubleValue(), 1e-9);

        // Summary picks the deepest underwater point and its date.
        assertEquals(-0.25, t.summary().maxDrawdown().doubleValue(), 1e-6);
        assertEquals("2024-01-03", t.summary().maxDrawdownDate());
    }

    @Test
    void drawdownIgnoresContributionInflatedGains() {
        // V: 100 → 150 entirely from a £50 contribution → growth flat at 1.0 → drawdown stays 0%.
        // (Without TWR, a naive value-based drawdown would treat the next dip as recovered from
        // a higher peak — this asserts we don't make that mistake.)
        ReturnTimeline t = service(List.of(
                dv("2024-01-01", "100"),
                dv("2024-01-02", "150"),
                dv("2024-01-03", "90")
        ), List.of(contrib("2024-01-02", 50.0))).timeline();

        // Day 3: TWR = 0.9 / 1.0 → growth chain hits 0.6 (because (90−150−0)/150 = −0.4, ×1.0 = 0.6).
        // Wait — the chain step uses prev V (150), so r = (90 − 150 − 0) / 150 = −0.4. New growth = 0.6.
        // Peak so far = 1.0. Drawdown = (0.6 − 1.0)/1.0 = −40%.
        assertEquals(-0.40, t.summary().maxDrawdown().doubleValue(), 1e-6);
    }

    @Test
    void annualReturnsBucketByCalendarYear() {
        // 2022 starts at growth=1.0 (anchor day-1), ends at 1.20 → +20%.
        // 2023 anchor = 1.20 (Dec 31 2022), close = 1.50 → +25%.
        // Inception year bar is the partial 2022 because day-1 isn't Jan 1.
        ReturnTimeline t = service(List.of(
                dv("2022-03-01", "100"),
                dv("2022-12-31", "120"),
                dv("2023-12-31", "150")
        ), List.of()).timeline();

        assertEquals(2, t.annualReturns().size());

        var y2022 = t.annualReturns().get(0);
        assertEquals(2022, y2022.year());
        assertEquals(0.20, y2022.returnPct().doubleValue(), 1e-6);
        assertTrue(y2022.partial(), "inception year started mid-year → partial");

        var y2023 = t.annualReturns().get(1);
        assertEquals(2023, y2023.year());
        assertEquals(0.25, y2023.returnPct().doubleValue(), 1e-6);
        assertFalse(y2023.partial(), "full year Jan–Dec");
    }

    @Test
    void trailing1yReturnsRawPeriodForExactlyOneYear() {
        // Dense daily V = 100 from 2024-01-01 inclusive to 2025-01-01 (the last day jumps to 110).
        // Growth chain is flat at 1.0 until the last day, then ×1.10. trailing1y windowStart
        // = 2025-01-01 − 1y = 2024-01-01, anchor matches the first point exactly, returns +10%.
        java.util.List<DailyValue> values = new java.util.ArrayList<>();
        LocalDate start = LocalDate.parse("2024-01-01");
        LocalDate end = LocalDate.parse("2025-01-01");
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            values.add(new DailyValue(d, d.equals(end) ? new BigDecimal("110") : new BigDecimal("100")));
        }

        ReturnTimeline t = service(values, List.of()).timeline();
        BigDecimal r = t.summary().trailing1y();
        assertNotNull(r);
        assertEquals(0.10, r.doubleValue(), 1e-6);
    }
}
