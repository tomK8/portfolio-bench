package com.portfolio.domain.trend;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

class TrendStatsTest {

    private static TrendSeries series(double... closes) {
        return TrendSeries.of(
                TrendConfig.builder().maLengths(new int[]{2}).monthlyMaLengths(new int[]{2})
                        .flatCashRatePct(0).build(),
                TrendSeriesTest.bars("QQQ", LocalDate.parse("2026-01-05"), 1.0, 1.0, closes),
                null, null);
    }

    @Test
    void buyAndHoldStatsMatchHandComputation() {
        TrendSeries s = series(100, 110, 99, 108.9);
        Backtester.Result r = Backtester.run(s, Backtester.buyAndHold(s.size()), 0,
                new Backtester.Params(Backtester.Execution.CLOSE, 0, 0, s.cashDaily));
        TrendStats st = TrendStats.of(s, r);

        assertEquals(0.089, st.totalReturn(), 1e-12);
        long days = ChronoUnit.DAYS.between(LocalDate.parse("2026-01-05"), LocalDate.parse("2026-01-08"));
        assertEquals(Math.pow(1.089, 365.25 / days) - 1, st.cagr(), 1e-9);
        assertEquals(99.0 / 110 - 1, st.maxDrawdown(), 1e-12);
        // The 110 peak is never regained → censored drawdown spans peak to the data edge.
        assertEquals(ChronoUnit.DAYS.between(LocalDate.parse("2026-01-06"),
                LocalDate.parse("2026-01-08")) / 30.44, st.longestDrawdownMonths(), 1e-9);
        assertEquals(1.0, st.timeInvested(), 1e-12);
        assertEquals(0, st.switches());
        assertEquals(0, st.roundTrips());
        assertTrue(st.vol() > 0);
        assertTrue(Double.isFinite(st.sharpe()));
    }

    @Test
    void recoveredDrawdownDurationIsPeakToRecovery() {
        // Peak at bar 1, trough at bar 2, recovered above the old peak at bar 4, new high at 5.
        TrendSeries s = series(100, 110, 99, 105, 111, 112);
        Backtester.Result r = Backtester.run(s, Backtester.buyAndHold(s.size()), 0,
                new Backtester.Params(Backtester.Execution.CLOSE, 0, 0, s.cashDaily));
        TrendStats st = TrendStats.of(s, r);

        long days = ChronoUnit.DAYS.between(s.dates[1], s.dates[4]);
        assertEquals(days / 30.44, st.longestDrawdownMonths(), 1e-9);
    }

    @Test
    void timeInvestedIsMeanExposure() {
        TrendSeries s = series(100, 100, 100, 100);
        boolean[] target = {true, false, false, false};
        Backtester.Result r = Backtester.run(s, target, 0,
                new Backtester.Params(Backtester.Execution.CLOSE, 0.5, 0, s.cashDaily));
        TrendStats st = TrendStats.of(s, r);

        // Exposures per day: 1 (start), 1, 0.5, 0.5 → mean 0.75.
        assertEquals(0.75, st.timeInvested(), 1e-12);
        assertEquals(1, st.switches());
        assertEquals(0, st.roundTrips(), "an exit with no re-entry is not a round trip");
    }
}
