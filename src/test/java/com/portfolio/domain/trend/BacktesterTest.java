package com.portfolio.domain.trend;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class BacktesterTest {

    /** No cash series and a 0% flat rate → cashDaily all zero, so the market legs test cleanly. */
    private static TrendSeries series(double openRatio, double... closes) {
        return TrendSeries.of(
                TrendConfig.builder().maLengths(new int[]{2}).monthlyMaLengths(new int[]{2})
                        .flatCashRatePct(0).build(),
                TrendSeriesTest.bars("QQQ", LocalDate.parse("2026-01-05"), 1.0, openRatio, closes),
                null, null);
    }

    private static Backtester.Params params(TrendSeries s, Backtester.Execution exec,
                                            double outWeight, double costPerSide) {
        return new Backtester.Params(exec, outWeight, costPerSide, s.cashDaily);
    }

    @Test
    void closeExecutionFullSwitchTracksHandComputedEquity() {
        TrendSeries s = series(1.0, 100, 110, 99, 108.9);
        boolean[] target = {true, false, false, true};
        Backtester.Result r = Backtester.run(s, target, 0, params(s, Backtester.Execution.CLOSE, 0, 0));

        // In for the +10% day, out (at 0% cash) for the −10% day and the +10% recovery day.
        assertEquals(1.1, r.equity()[1], 1e-12);
        assertEquals(1.1, r.equity()[2], 1e-12);
        assertEquals(1.1, r.equity()[3], 1e-12);
        assertArrayEquals(new double[]{1, 1, 0, 0}, r.weight(), 1e-12);
        assertEquals(0.0, r.dailyRet()[2], 1e-12);

        assertEquals(2, r.trades().size());
        Backtester.Trade exit = r.trades().get(0);
        assertFalse(exit.entering());
        assertEquals(1, exit.signalIndex());
        assertEquals(1, exit.fillIndex(), "CLOSE mode fills on the signal bar");
        assertEquals(110, exit.fillAdj(), 1e-12);
        assertEquals(110, exit.fillDisplay(), 1e-12);
        assertTrue(r.trades().get(1).entering());
        assertEquals(1, r.roundTrips());
    }

    @Test
    void cashAccruesOnTheOutWeight() {
        TrendSeries s = series(1.0, 100, 110, 99, 108.9);
        double[] cash = new double[s.size()];
        Arrays.fill(cash, 0.001);
        boolean[] target = {true, false, false, true};
        Backtester.Result r = Backtester.run(s, target, 0,
                new Backtester.Params(Backtester.Execution.CLOSE, 0, 0, cash));

        assertEquals(1.1 * 1.001 * 1.001, r.equity()[3], 1e-12,
                "two fully-out days each earn the daily cash rate");
    }

    @Test
    void eachSideOfASwitchPaysItsCost() {
        TrendSeries s = series(1.0, 100, 110, 99, 108.9);
        boolean[] target = {true, false, false, true};
        Backtester.Result r = Backtester.run(s, target, 0, params(s, Backtester.Execution.CLOSE, 0, 0.01));

        assertEquals(1.1 * 0.99 * 0.99, r.equity()[3], 1e-12);
    }

    @Test
    void nextOpenExecutionFillsAtTheFollowingOpen() {
        // Opens sit 5% below their close; exit decided at close 1 fills at open 2, re-entry
        // decided at close 2 fills at open 3.
        TrendSeries s = series(0.95, 100, 110, 99, 108.9);
        boolean[] target = {true, false, true, true};
        Backtester.Result r = Backtester.run(s, target, 0, params(s, Backtester.Execution.NEXT_OPEN, 0, 0));

        assertEquals(1.1, r.equity()[1], 1e-12);
        // Day 2: overnight in (close 110 → open 94.05), intraday out.
        assertEquals(1.1 * (94.05 / 110), r.equity()[2], 1e-12);
        // Day 3: overnight out, re-entered at open 103.455 for the intraday leg to 108.9.
        assertEquals(1.1 * (94.05 / 110) * (108.9 / 103.455), r.equity()[3], 1e-9);

        assertEquals(2, r.trades().size());
        Backtester.Trade exit = r.trades().get(0);
        assertEquals(1, exit.signalIndex());
        assertEquals(2, exit.fillIndex(), "decision at close 1 fills at open 2");
        assertEquals(94.05, exit.fillAdj(), 1e-9);
        assertEquals(1, r.roundTrips());
    }

    @Test
    void decisionOnTheLastBarStaysUnfilledInNextOpenMode() {
        TrendSeries s = series(0.95, 100, 110, 99, 108.9);
        boolean[] target = {true, true, true, false};   // exit decided at the final close
        Backtester.Result r = Backtester.run(s, target, 0, params(s, Backtester.Execution.NEXT_OPEN, 0, 0));

        assertTrue(r.trades().isEmpty(), "no next open exists — the switch is pending, not executed");
        assertEquals(108.9 / 100, r.equity()[3], 1e-12);
    }

    @Test
    void halfSwitchScalesExposureAndCost() {
        TrendSeries s = series(1.0, 100, 110, 99);
        boolean[] target = {true, false, false};
        Backtester.Result r = Backtester.run(s, target, 0, params(s, Backtester.Execution.CLOSE, 0.5, 0.01));

        // Exit at close 1 moves only half the book (cost on Δw = 0.5), then half rides the −10%.
        double afterExit = 1.1 * (1 - 0.01 * 0.5);
        assertEquals(afterExit, r.equity()[1], 1e-12);
        assertEquals(afterExit * (1 + 0.5 * (99.0 / 110 - 1)), r.equity()[2], 1e-12);
        assertEquals(0.5, r.weight()[2], 1e-12);
    }

    @Test
    void buyAndHoldMatchesTheTotalReturnSeriesWithNoTrades() {
        TrendSeries s = series(0.95, 100, 110, 99, 108.9);
        Backtester.Result r = Backtester.run(s, Backtester.buyAndHold(s.size()), 0,
                params(s, Backtester.Execution.NEXT_OPEN, 0, 0.01));

        assertTrue(r.trades().isEmpty());
        assertEquals(1.089, r.equity()[3], 1e-12);
    }

    @Test
    void entriesBeforeStartAreNaNAndStartIsCostFree() {
        TrendSeries s = series(1.0, 100, 110, 99, 108.9);
        boolean[] target = {true, true, false, false};
        Backtester.Result r = Backtester.run(s, target, 1, params(s, Backtester.Execution.CLOSE, 0, 0.01));

        assertTrue(Double.isNaN(r.weight()[0]));
        assertTrue(Double.isNaN(r.dailyRet()[1]));
        assertEquals(1.0, r.equity()[1], 1e-12);
        assertEquals(0.99 * 99 / 110, r.equity()[2], 1e-12, "exit at close 2 pays cost; entry at start didn't");
    }
}
