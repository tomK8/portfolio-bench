package com.portfolio.application;

import com.portfolio.application.PortfolioReturnService.ReturnTimeline;
import com.portfolio.application.PortfolioValueService.DailyValue;
import com.portfolio.application.PortfolioValueService.PeriodValues;
import com.portfolio.application.PortfolioReturnService.PeriodDiff;
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
        return service(values, contribs, fx, List.of());
    }

    private PortfolioReturnService service(List<DailyValue> values,
                                           List<CashTransaction> contribs,
                                           HistoricalFxRateProvider fx,
                                           List<String> unpriced) {
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

            /** Mimics the real windowing: inclusive both ends, clamped to what the series has. */
            @Override
            public PeriodValues dailyValuesIn(LocalDate from, LocalDate to) {
                List<DailyValue> in = values.stream()
                        .filter(v -> (from == null || !v.date().isBefore(from))
                                && (to == null || !v.date().isAfter(to)))
                        .toList();
                return new PeriodValues(in, unpriced);
            }
        };
        return new PortfolioReturnService(stub, repo, kv, fx);
    }

    private static DailyValue dv(String date, String value) {
        BigDecimal v = new BigDecimal(value);
        return new DailyValue(LocalDate.parse(date), v, v);  // existing fixtures have no cash → invested = total
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
    void investedOnlyReturnIgnoresCashDrag() {
        // Total = £500 invested + £500 cash → £1000.
        // Day 2: invested grows to £600 (positions +20%); cash unchanged at £500 → total £1100.
        // Whole-portfolio TWR = +10%. Invested-only TWR = +20%. The 10pp gap is cash drag.
        List<DailyValue> values = List.of(
                new DailyValue(LocalDate.parse("2024-01-01"),
                        new BigDecimal("1000"), new BigDecimal("500")),
                new DailyValue(LocalDate.parse("2024-12-31"),
                        new BigDecimal("1100"), new BigDecimal("600"))
        );
        ReturnTimeline t = service(values, List.of()).timeline();
        var year = t.annualReturns().get(0);
        assertEquals(0.10, year.returnPct().doubleValue(), 1e-6);
        assertEquals(0.20, year.investedReturnPct().doubleValue(), 1e-6);
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
            BigDecimal vv = d.equals(end) ? new BigDecimal("110") : new BigDecimal("100");
            values.add(new DailyValue(d, vv, vv));
        }

        ReturnTimeline t = service(values, List.of()).timeline();
        BigDecimal r = t.summary().trailing1y();
        assertNotNull(r);
        assertEquals(0.10, r.doubleValue(), 1e-6);
    }

    // ---- periodDiff -------------------------------------------------------

    @Test
    void periodDiffSeparatesMarketGainFromContributions() {
        // £100 → £150 but £50 of that walked in the door.
        PeriodDiff d = service(List.of(
                dv("2024-01-01", "100"),
                dv("2024-01-02", "150")
        ), List.of(contrib("2024-01-02", 50.0)))
                .periodDiff(LocalDate.parse("2024-01-01"), LocalDate.parse("2024-01-02"));

        assertEquals(0, new BigDecimal("50").compareTo(d.valueChangeGbp()));
        assertEquals(0, new BigDecimal("50").compareTo(d.contributionsGbp()));
        assertEquals(0, BigDecimal.ZERO.compareTo(d.gainGbp()), "nothing was earned");
        assertEquals(0, BigDecimal.ZERO.compareTo(d.totalReturn()), "TWR is contribution-neutral");
    }

    @Test
    void periodDiffChainsTwrAcrossTheWindow() {
        PeriodDiff d = service(List.of(
                dv("2024-01-01", "100"),
                dv("2024-01-02", "110"),
                dv("2024-01-03", "121")
        ), List.of()).periodDiff(LocalDate.parse("2024-01-01"), LocalDate.parse("2024-01-03"));

        assertEquals(0.21, d.totalReturn().doubleValue(), 1e-9, "1.10 × 1.10 − 1");
        assertEquals(0.21, d.investedReturn().doubleValue(), 1e-9,
                "no cash leg in the fixture, so the positions-only chain matches");
        assertEquals(2, d.spanDays());
    }

    @Test
    void periodDiffCountsFlowsAfterTheStartDateOnly() {
        // The £50 landed on the start date, so it is part of the opening balance — the window
        // neither credits it as a contribution nor lets it dent the return.
        PeriodDiff d = service(List.of(
                dv("2024-01-01", "150"),
                dv("2024-01-02", "150")
        ), List.of(contrib("2024-01-01", 50.0)))
                .periodDiff(LocalDate.parse("2024-01-01"), LocalDate.parse("2024-01-02"));

        assertEquals(0, BigDecimal.ZERO.compareTo(d.contributionsGbp()));
        assertEquals(0, BigDecimal.ZERO.compareTo(d.totalReturn()));
        assertEquals(0, new BigDecimal("150").compareTo(d.start().totalGbp()));
    }

    @Test
    void periodDiffValuesOnlyTheRequestedDates() {
        PeriodDiff d = service(List.of(
                dv("2024-01-01", "100"),
                dv("2024-01-02", "200"),
                dv("2024-01-03", "300"),
                dv("2024-01-04", "400")
        ), List.of()).periodDiff(LocalDate.parse("2024-01-02"), LocalDate.parse("2024-01-03"));

        assertEquals("2024-01-02", d.from());
        assertEquals("2024-01-03", d.to());
        assertEquals(0, new BigDecimal("200").compareTo(d.start().totalGbp()));
        assertEquals(0, new BigDecimal("300").compareTo(d.end().totalGbp()));
        assertEquals(0, new BigDecimal("100").compareTo(d.valueChangeGbp()));
    }

    @Test
    void periodDiffClampsToAvailableHistoryAndSaysSo() {
        PeriodDiff d = service(List.of(
                dv("2024-01-01", "100"),
                dv("2024-01-02", "110")
        ), List.of()).periodDiff(LocalDate.parse("2020-01-01"), LocalDate.parse("2024-01-02"));

        assertEquals("2024-01-01", d.from(), "echoes the date actually used");
        assertTrue(d.warnings().stream().anyMatch(w -> w.contains("2024-01-01")),
                "a clamped window must be visible, not silent: " + d.warnings());
    }

    @Test
    void periodDiffRejectsAReversedWindow() {
        PeriodDiff d = service(List.of(
                dv("2024-01-01", "100"),
                dv("2024-01-02", "110")
        ), List.of()).periodDiff(LocalDate.parse("2024-01-02"), LocalDate.parse("2024-01-01"));

        assertNull(d.start());
        assertNull(d.totalReturn());
        assertEquals(1, d.warnings().size());
    }

    @Test
    void periodDiffNeedsTwoSamples() {
        PeriodDiff d = service(List.of(dv("2024-01-01", "100")), List.of())
                .periodDiff(LocalDate.parse("2024-01-01"), LocalDate.parse("2024-01-02"));

        assertNull(d.start());
        assertFalse(d.warnings().isEmpty());
    }

    @Test
    void periodDiffAnnualisesOnlyBeyondThirtyDays() {
        PeriodDiff shortWindow = service(List.of(
                dv("2024-01-01", "100"),
                dv("2024-01-02", "110")
        ), List.of()).periodDiff(LocalDate.parse("2024-01-01"), LocalDate.parse("2024-01-02"));
        assertNull(shortWindow.annualisedReturn(),
                "compounding a one-day move 365× is noise, not a return");

        PeriodDiff halfYear = service(List.of(
                dv("2024-01-01", "100"),
                dv("2024-07-01", "121")
        ), List.of()).periodDiff(LocalDate.parse("2024-01-01"), LocalDate.parse("2024-07-01"));
        assertEquals(Math.pow(1.21, 365.25 / 182.0) - 1.0,
                halfYear.annualisedReturn().doubleValue(), 1e-6);
    }

    @Test
    void periodDiffSurfacesHoldingsThatCouldNotBePriced() {
        PeriodDiff d = service(List.of(
                dv("2024-01-01", "100"),
                dv("2024-01-02", "110")
        ), List.of(), EMPTY_FX, List.of("GILT 4.25% 2032"))
                .periodDiff(LocalDate.parse("2024-01-01"), LocalDate.parse("2024-01-02"));

        assertEquals(List.of("GILT 4.25% 2032"), d.unpricedSymbols());
        assertTrue(d.warnings().stream().anyMatch(w -> w.contains("GILT 4.25% 2032")),
                "a position valued at zero on both ends silently shrinks the portfolio");
    }

    @Test
    void periodDiffSplitsValueIntoPositionsAndCash() {
        PeriodDiff d = service(List.of(
                new DailyValue(LocalDate.parse("2024-01-01"), new BigDecimal("100"), new BigDecimal("80")),
                new DailyValue(LocalDate.parse("2024-01-02"), new BigDecimal("120"), new BigDecimal("95"))
        ), List.of()).periodDiff(LocalDate.parse("2024-01-01"), LocalDate.parse("2024-01-02"));

        assertEquals(0, new BigDecimal("80").compareTo(d.start().investedGbp()));
        assertEquals(0, new BigDecimal("20").compareTo(d.start().cashGbp()));
        assertEquals(0, new BigDecimal("25").compareTo(d.end().cashGbp()));
    }


    @Test
    void positionsChainAnchorsAtTheFirstDayPositionsExist() {
        // Window opens on pure cash, buys on day 2, then the position gains 10%. The
        // positions return is that 10% — not "undefined because day 1 held nothing".
        PeriodDiff d = service(List.of(
                new DailyValue(LocalDate.parse("2024-01-01"), new BigDecimal("100"), BigDecimal.ZERO),
                new DailyValue(LocalDate.parse("2024-01-02"), new BigDecimal("100"), new BigDecimal("100")),
                new DailyValue(LocalDate.parse("2024-01-03"), new BigDecimal("110"), new BigDecimal("110"))
        ), List.of()).periodDiff(LocalDate.parse("2024-01-01"), LocalDate.parse("2024-01-03"));

        assertEquals(0.10, d.investedReturn().doubleValue(), 1e-9);
        assertEquals("2024-01-02", d.investedFrom(),
                "the tile must be able to say the positions leg started later than the window");
    }

}
