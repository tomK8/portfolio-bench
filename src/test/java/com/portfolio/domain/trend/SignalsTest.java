package com.portfolio.domain.trend;

import com.portfolio.domain.model.PriceBar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SignalsTest {

    private static TrendSeries series(double... closes) {
        List<PriceBar> bars = TrendSeriesTest.bars("QQQ", LocalDate.parse("2026-01-05"), 1.0, 1.0, closes);
        return TrendSeries.of(TrendConfig.builder().maLengths(new int[]{3})
                .monthlyMaLengths(new int[]{2}).build(), bars, null, null);
    }

    @Test
    void firstTouchFlipsOnEveryCrossingWithEqualityCountingAsIn() {
        TrendSeries s = series(100, 100, 100, 60, 60, 200, 200);
        boolean[] t = Signals.firstTouch(3).targets(s);
        assertArrayEquals(new boolean[]{true, true, true, false, false, true, true}, t);
    }

    @Test
    void monthAnchoredOnlyActsOnItsCheckDays() {
        TrendSeries s = series(100, 100, 100, 60, 60, 200, 200);
        // firstValid = 2 (init: in). Offset 0 → checks at i=4 (60 < sma 73.3 → out) and i=6 (in).
        // The i=3 crash and the i=5 recovery both fall between checks and are ignored.
        boolean[] t0 = Signals.monthAnchored(3, 2, 0).targets(s);
        assertArrayEquals(new boolean[]{true, true, true, true, false, false, true}, t0);
        // Offset 1 shifts the clock: checks at i=3 (out) and i=5 (in) — different history, same rule.
        boolean[] t1 = Signals.monthAnchored(3, 2, 1).targets(s);
        assertArrayEquals(new boolean[]{true, true, true, false, false, true, true}, t1);
    }

    @Test
    void bufferedPctHoldsInsideTheBand() {
        // i=3: close 95 < sma 98.33 — first-touch exits, but 95 is above the −10% band → holds.
        // i=4: 70 < 88.33×0.9 → exits. i=5: 100 > 88.33×1.1 → re-enters.
        TrendSeries s = series(100, 100, 100, 95, 70, 100);
        boolean[] buffered = Signals.bufferedPct(3, 0.10).targets(s);
        assertArrayEquals(new boolean[]{true, true, true, true, false, true}, buffered);
        boolean[] touch = Signals.firstTouch(3).targets(s);
        assertFalse(touch[3], "sanity: the plain rule would have exited at i=3");
    }

    @Test
    void bufferedCountNeedsConsecutiveClosesAndResetsOnOpposite() {
        TrendSeries s = series(100, 100, 100, 99, 101, 98, 97, 105, 106);
        boolean[] t = Signals.bufferedCount(3, 2).targets(s);
        // Single below-closes at i=3 and i=5 don't exit (reset by i=4); i=5+i=6 do; two above re-enter.
        assertArrayEquals(new boolean[]{true, true, true, true, true, true, false, false, true}, t);
    }

    @Test
    void monthlyCloseActsOnCompletedMonthEndsOnly() {
        // Jan–early-Apr weekday bars (so March completes): 100 through February's end, then 80.
        int n = 65;
        double[] closes = new double[n];
        TrendSeries probe = series(new double[n]);   // just to locate month ends for this calendar
        int febEnd = probe.monthEndIdx[1];
        for (int i = 0; i < n; i++) closes[i] = i <= febEnd ? 100 : 80;
        TrendSeries s = series(closes);

        TrendSignal sig = Signals.monthlyClose(2);
        assertEquals(febEnd, sig.firstValid(s), "valid from the 2nd completed month end");
        boolean[] t = sig.targets(s);
        assertTrue(t[febEnd], "Jan/Feb mean 100, close 100 → in");
        int marEnd = s.monthEndIdx[2];
        assertTrue(t[marEnd - 1], "state carries between month ends despite the drop");
        assertFalse(t[marEnd], "March end: close 80 < mean(100, 80) = 90 → out");
    }

    @Test
    void initialStateIsThePlainComparisonAtFirstValid() {
        TrendSeries below = series(100, 90, 60);   // sma=83.3 at i=2, close 60 → starts out
        for (TrendSignal sig : List.of(Signals.firstTouch(3), Signals.monthAnchored(3, 21, 0),
                Signals.bufferedPct(3, 0.02), Signals.bufferedCount(3, 3))) {
            assertFalse(sig.targets(below)[2], sig.key() + " should start out");
        }
    }

    @Test
    void targetsAreLookAheadFree() {
        TrendSeries a = series(100, 100, 100, 95, 70, 100, 100);
        TrendSeries b = series(100, 100, 100, 95, 70, 100, 20);   // only the last bar differs
        for (TrendSignal sig : List.of(Signals.firstTouch(3), Signals.monthAnchored(3, 2, 0),
                Signals.bufferedPct(3, 0.10), Signals.bufferedCount(3, 2))) {
            boolean[] ta = sig.targets(a), tb = sig.targets(b);
            for (int i = 0; i < a.size() - 1; i++) {
                assertEquals(ta[i], tb[i], sig.key() + " differs at " + i + " on a future-only change");
            }
        }
    }

    @Test
    void gridVariantsCoverEveryFamilyPerMaLength() {
        TrendConfig cfg = TrendConfig.builder().maLengths(new int[]{150, 200})
                .bufferPcts(new double[]{0.01, 0.02}).bufferNs(new int[]{3}).build();
        List<TrendSignal> grid = Signals.gridVariants(cfg);
        assertEquals(2 * (1 + 1 + 2 + 1), grid.size());
        assertEquals(3, Signals.monthlyVariants(TrendConfig.builder().build()).size());
    }
}
