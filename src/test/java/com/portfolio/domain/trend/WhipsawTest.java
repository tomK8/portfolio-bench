package com.portfolio.domain.trend;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class WhipsawTest {

    private static TrendSeries series(double... closes) {
        return TrendSeries.of(
                TrendConfig.builder().maLengths(new int[]{2}).monthlyMaLengths(new int[]{2})
                        .flatCashRatePct(0).build(),
                TrendSeriesTest.bars("QQQ", LocalDate.parse("2026-01-05"), 1.0, 1.0, closes),
                null, null);
    }

    private static Backtester.Result run(TrendSeries s, boolean[] target, double cost) {
        return Backtester.run(s, target, 0,
                new Backtester.Params(Backtester.Execution.CLOSE, 0, cost, s.cashDaily));
    }

    @Test
    void boughtBackHigherTripsCompoundIntoTheDragAndReconcileWithEquity() {
        TrendSeries s = series(100, 110, 120, 108, 132);
        boolean[] target = {true, false, true, false, true};   // two exits, both re-entered higher
        Backtester.Result r = run(s, target, 0);
        Whipsaw.Result w = Whipsaw.compute(s, r, 0);

        assertEquals(2, w.roundTrips());
        assertEquals(2, w.boughtBackHigher());
        double expected = (110.0 / 120) * (108.0 / 132) - 1;
        assertEquals(expected, w.whipsawDrag(), 1e-12);
        assertEquals(expected, w.netTimingEffect(), 1e-12);
        // Cross-check: with zero cash/costs, net timing effect == strategy/buy-and-hold − 1.
        double bh = 132.0 / 100;
        assertEquals(r.equity()[4] / bh - 1, w.netTimingEffect(), 1e-12);
        assertEquals(1, w.perDecade().size());
        assertEquals("2020s", w.perDecade().get(0).decade());
    }

    @Test
    void aSavingTripCountsInNetEffectButNotInWhipsawDrag() {
        TrendSeries s = series(100, 110, 90, 99);
        boolean[] target = {true, false, false, true};   // exited 110, re-entered 99 — a save
        Whipsaw.Result w = Whipsaw.compute(s, run(s, target, 0), 0);

        assertEquals(1, w.roundTrips());
        assertEquals(0, w.boughtBackHigher());
        assertEquals(0.0, w.whipsawDrag(), 1e-12, "no bought-back-higher trips → no whipsaw drag");
        assertEquals(110.0 / 99 - 1, w.netTimingEffect(), 1e-12);
    }

    @Test
    void costsAndCashEnterTheTripFactor() {
        TrendSeries s = series(100, 110, 90, 99);
        boolean[] target = {true, false, false, true};
        double cost = 0.01;
        Whipsaw.Result w = Whipsaw.compute(s, run(s, target, cost), cost);

        assertEquals((110.0 / 99) * 0.99 * 0.99 - 1, w.netTimingEffect(), 1e-12);
    }

    @Test
    void leadingEntryAndTrailingExitAreNotTrips() {
        TrendSeries s = series(100, 110, 120, 108);
        boolean[] target = {false, true, true, false};   // starts out, ends on an open exit
        Whipsaw.Result w = Whipsaw.compute(s, run(s, target, 0), 0);

        assertEquals(0, w.roundTrips());
        assertEquals(0.0, w.netTimingEffect(), 1e-12);
        assertTrue(w.perDecade().isEmpty());
    }
}
