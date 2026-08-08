package com.portfolio.domain.range;

import com.portfolio.domain.range.RangeBacktester.Direction;
import com.portfolio.domain.range.RangeBacktester.ExitType;
import com.portfolio.domain.range.RangeBacktester.Params;
import com.portfolio.domain.range.RangeBacktester.Trade;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RangeBacktesterTest {

    private static final int WINDOW = 5;
    private static final int ABOVE_RUN = 3;

    private static Params floorParams(RangeSeries s, double tp, double sl) {
        double[] entry = s.trailingLevels(new double[]{0.10}, WINDOW)[0];
        return new Params(entry, Direction.FLOOR, ABOVE_RUN, tp, null, sl, WINDOW);
    }

    /**
     * Direction-of-touch: a dip from an unarmed state (run of closes above the band shorter than
     * {@code aboveRun}) must not enter; only after a full run does the next touch enter.
     */
    @Test
    void aboveRunConditionGatesTheEntry() {
        // Bars 0-4 build the band; bars 5-6 are only 2 sessions above (< 3); bar 7 dips — no
        // entry. Bars 8-10 complete a 3-session run; bar 11 dips — entry.
        RangeSeries s = RangeSeriesTest.series(100, 100, 100, 100, 100,
                200, 200, 90, 200, 200, 200, 50);
        List<Trade> trades = RangeBacktester.run(s, floorParams(s, 0.10, Double.NaN));

        assertEquals(1, trades.size(), "the unarmed dip at bar 7 must not enter");
        assertEquals(11, trades.get(0).entryIdx());
        assertEquals(50.0, trades.get(0).fill(), 1e-9);
    }

    /**
     * The anti-machine-gun rule: after a stop in a monotonic downtrend the strategy never re-arms
     * (no run of closes above the falling band), so exactly one entry is produced.
     */
    @Test
    void reArmRuleProducesExactlyOneEntryInMonotonicDowntrend() {
        RangeSeries s = RangeSeriesTest.series(100, 100, 100, 100, 100,
                110, 111, 112,          // 3-session run arms the signal
                60,                     // entry
                50, 45, 40, 35, 30, 25, 20);   // stop hit, then relentless decline
        List<Trade> trades = RangeBacktester.run(s, floorParams(s, 0.10, 0.15));

        assertEquals(1, trades.size(), "downtrend must not machine-gun re-entries");
        Trade t = trades.get(0);
        assertEquals(8, t.entryIdx());
        assertEquals(ExitType.SL, t.exitType());
        assertEquals(60 * 0.85, t.exitFill(), 1e-9);
        assertEquals(9, t.exitIdx());
    }

    /** An entry whose exit never arrives is censored OPEN — counted, marked, never resolved. */
    @Test
    void openEndOfDataTradeIsCensoredNotDropped() {
        RangeSeries s = RangeSeriesTest.series(100, 100, 100, 100, 100,
                110, 111, 112,
                60,                     // entry; TP would be 66
                61, 62);                // never touches 66, data ends
        List<Trade> trades = RangeBacktester.run(s, floorParams(s, 0.10, Double.NaN));

        assertEquals(1, trades.size());
        Trade t = trades.get(0);
        assertEquals(ExitType.OPEN, t.exitType());
        assertTrue(t.open());
        assertEquals(10, t.exitIdx(), "censored at the last bar");
        assertEquals(62.0 / 60.0 - 1.0, t.trRet(), 1e-9, "end-of-data TR mark");
        assertTrue(Double.isNaN(t.exitFill()), "no exit fill for a censored trade");

        RangeTradeStats stats = RangeTradeStats.of(trades, 0.15);
        assertEquals(1, stats.events());
        assertEquals(1, stats.open());
        assertEquals(0, stats.tp());
        assertNull(stats.meanTrRet(), "open trades never enter outcome statistics");
        assertEquals(1, stats.maeDist().n(), "MAE is still reported for open trades");
    }

    /** TP exit fills at the barrier on the touch session (OHLC-touch convention). */
    @Test
    void takeProfitFillsAtBarrierOnTouch() {
        RangeSeries s = RangeSeriesTest.series(100, 100, 100, 100, 100,
                110, 111, 112,
                60,                     // entry, TP 66
                59, 70);                // bar 10 high 70 ≥ 66 → TP at 66
        List<Trade> trades = RangeBacktester.run(s, floorParams(s, 0.10, Double.NaN));

        assertEquals(1, trades.size());
        Trade t = trades.get(0);
        assertEquals(ExitType.TP, t.exitType());
        assertEquals(66.0, t.exitFill(), 1e-9);
        assertEquals(0.10, t.priceRet(), 1e-9);
        assertEquals(10, t.exitIdx());
        assertEquals(59.0 / 60.0 - 1.0, t.mae(), 1e-9, "MAE from the intraday low");
        assertEquals(1, t.underwaterSessions());
    }
}
