package com.portfolio.domain.swing;

import com.portfolio.domain.model.PriceBar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FlushesTest {

    /** Bars with high = low = close (OHLC touch collapses to close-to-close). */
    private static SwingSeries series(double... closes) {
        List<PriceBar> bars = new ArrayList<>();
        LocalDate d = LocalDate.parse("2026-01-01");
        for (double c : closes) {
            bars.add(new PriceBar("X", d, null, c, c, c, c, 1.0, null, "USD"));
            d = d.plusDays(1);
        }
        return SwingSeries.of("X", bars, 30);
    }

    /** Bars given as {close, high, low} triples so OHLC touches can differ from the close. */
    private static SwingSeries ohlc(double[]... rows) {
        List<PriceBar> bars = new ArrayList<>();
        LocalDate d = LocalDate.parse("2026-01-01");
        for (double[] r : rows) {
            bars.add(new PriceBar("X", d, null, r[1], r[2], r[0], r[0], 1.0, null, "USD"));
            d = d.plusDays(1);
        }
        return SwingSeries.of("X", bars, 30);
    }

    private static SwingConfig cfg() {
        return SwingConfig.builder()
                .flushDays(new int[]{2})
                .flushThresholds(new double[]{0.10})
                .timeStops(new int[]{5})
                .barrierFlushDays(2)
                .barrierFlushDrop(0.10)
                .smallNFloor(1)
                .episodeFloor(1)
                .build();                                // tp=0.10, sl=0.15 (defaults)
    }

    @Test
    void takeProfitExitBeforeStop() {
        // 2-day −15% flush at idx3 (85/100), then a +11.8% bounce to 95 (≥ +10% TP).
        SwingSeries s = series(100, 100, 100, 85, 95, 95, 95, 95, 95, 95);
        var barrier = Flushes.barriers(List.of(s), null, cfg()).get(0);
        assertEquals(1, barrier.events());
        assertEquals(1, barrier.tpFirst());
        assertEquals(1.0, barrier.pctTp(), 1e-9);
        assertEquals(0.0, barrier.pctSl(), 1e-9);
        assertEquals(1.0, barrier.meanHoldingDays(), 1e-9);
        // OHLC fill is at the barrier: realized return = +tp exactly.
        assertEquals(0.10, barrier.meanReturn(), 1e-9);
        assertReconciles(barrier);

        var profile = Flushes.profiles(List.of(s), null, cfg()).get(0);
        assertEquals(1, profile.eventCount());
        assertEquals(1, profile.tpFirst());
        assertEquals(0, profile.slFirst());
        assertProfileReconciles(profile);
    }

    @Test
    void stopLossExitBeforeTarget() {
        // Flush to 85, then a bar that dips to 72 (≤ −15% SL) intraday but closes back at 91 — so
        // idx4's own 2-day window (91/100 = −9%) does NOT re-trigger a second flush.
        SwingSeries s = ohlc(
                new double[]{100, 100, 100}, new double[]{100, 100, 100}, new double[]{100, 100, 100},
                new double[]{85, 85, 85},
                new double[]{91, 91, 72},                       // low 72 ≤ 72.25 → SL touched
                new double[]{91, 91, 91}, new double[]{91, 91, 91},
                new double[]{91, 91, 91}, new double[]{91, 91, 91}, new double[]{91, 91, 91});
        var barrier = Flushes.barriers(List.of(s), null, cfg()).get(0);
        assertEquals(1, barrier.events());
        assertEquals(1.0, barrier.pctSl(), 1e-9);
        assertEquals(0.0, barrier.pctTp(), 1e-9);
        assertEquals(-0.15, barrier.meanReturn(), 1e-9);   // filled at the stop level
        assertReconciles(barrier);

        var profile = Flushes.profiles(List.of(s), null, cfg()).get(0);
        assertEquals(1, profile.slFirst());
        assertEquals(0, profile.tpFirst());
    }

    @Test
    void timeStopExitWhenNeitherBarrierHit() {
        // Flush then drifts +1.2%/day — never hits ±barrier within 5 sessions.
        SwingSeries s = series(100, 100, 100, 85, 86, 86, 86, 86, 86, 86);
        var barrier = Flushes.barriers(List.of(s), null, cfg()).get(0);
        assertEquals(1, barrier.events());
        assertEquals(1, barrier.timeCount());
        assertEquals(1.0, barrier.pctTime(), 1e-9);
        assertEquals(5.0, barrier.meanHoldingDays(), 1e-9);
        assertEquals(86.0 / 85.0 - 1.0, barrier.meanReturn(), 1e-9);
        assertReconciles(barrier);
    }

    @Test
    void ohlcTouchFiresIntradayEvenWhenCloseDoesnt() {
        // Flush to 85, then a bar whose HIGH (94) crosses +tp (93.5) but CLOSES at 91.
        // Close-to-close would call this a time-stop; OHLC touch must record a TP. Close 91 keeps
        // idx4's 2-day window at −9%, so no spurious second flush.
        SwingSeries s = ohlc(
                new double[]{100, 100, 100}, new double[]{100, 100, 100}, new double[]{100, 100, 100},
                new double[]{85, 85, 85},                       // idx3 flush entry
                new double[]{91, 94, 85},                       // idx4: high 94 ≥ 93.5 → TP touched
                new double[]{91, 91, 91}, new double[]{91, 91, 91},
                new double[]{91, 91, 91}, new double[]{91, 91, 91}, new double[]{91, 91, 91});
        var barrier = Flushes.barriers(List.of(s), null, cfg()).get(0);
        assertEquals(1, barrier.events());
        assertEquals(1, barrier.tpFirst());
        assertEquals(0, barrier.timeCount());
        assertEquals(1.0, barrier.meanHoldingDays(), 1e-9);
    }

    @Test
    void ambiguousWhenBothBarriersTouchedSameSession() {
        // idx4 straddles both barriers: high 96 ≥ 93.5 (TP) and low 70 ≤ 72.25 (SL). Close 91 keeps
        // idx4's own 2-day window above the flush threshold, so only one event is detected.
        SwingSeries s = ohlc(
                new double[]{100, 100, 100}, new double[]{100, 100, 100}, new double[]{100, 100, 100},
                new double[]{85, 85, 85},
                new double[]{91, 96, 70},                       // both touched
                new double[]{91, 91, 91}, new double[]{91, 91, 91},
                new double[]{91, 91, 91}, new double[]{91, 91, 91}, new double[]{91, 91, 91});
        var barrier = Flushes.barriers(List.of(s), null, cfg()).get(0);
        assertEquals(1, barrier.events());
        assertEquals(1, barrier.ambiguous());
        assertEquals(0, barrier.tpFirst());
        assertEquals(0, barrier.slFirst());
        // Ambiguous is excluded from the standard mean return (no resolved-with-return trade)…
        assertNull(barrier.meanReturn());
        // …but the conservative variant books it as a stop-loss.
        assertEquals(-0.15, barrier.meanReturnConservative(), 1e-9);
        assertReconciles(barrier);
    }

    @Test
    void openCensoredWhenWindowRunsPastData() {
        // Flush at idx7; only two forward bars exist, far short of the 5-session stop and no touch.
        SwingSeries s = series(100, 100, 100, 100, 100, 100, 100, 85, 86, 86);
        var barrier = Flushes.barriers(List.of(s), null, cfg()).get(0);
        assertEquals(1, barrier.events());
        assertEquals(1, barrier.open());
        assertEquals(0, barrier.tpFirst() + barrier.slFirst() + barrier.timeCount() + barrier.ambiguous());
        assertNull(barrier.meanReturn());                 // nothing resolved with a return
        assertReconciles(barrier);
    }

    @Test
    void nonOverlappingUntilTradeResolves() {
        // Two flush legs. The first trade resolves quickly (TP at idx4), so a second flush at
        // idx5 is allowed; a fixed cooldown would have masked it.
        SwingSeries s = series(100, 100, 100, 85, 95, 82, 92, 92, 92, 92, 92, 92);
        var barrier = Flushes.barriers(List.of(s), null, cfg()).get(0);
        assertTrue(barrier.events() >= 1);
        assertReconciles(barrier);
    }

    @Test
    void cooldownCollapsesConsecutiveFlushDays() {
        // idx3 and idx4 both qualify as 2-day ≤ −10% flushes; the §5 cooldown counts one event.
        SwingSeries s = series(100, 100, 100, 88, 80, 95, 95, 95, 95, 95);
        var profile = Flushes.profiles(List.of(s), null, cfg()).get(0);
        assertEquals(1, profile.eventCount());
    }

    @Test
    void poolingAddsEventsAcrossSeries() {
        SwingSeries s = series(100, 100, 100, 85, 95, 95, 95, 95, 95, 95);
        var pooled = Flushes.barriers(List.of(s, s), null, cfg()).get(0);
        assertEquals(2, pooled.events());
        // Same calendar dates on both series → one distinct episode.
        assertEquals(1, pooled.episodes());
    }

    private static void assertReconciles(Flushes.BarrierResult b) {
        assertEquals(b.events(),
                b.tpFirst() + b.slFirst() + b.timeCount() + b.ambiguous() + b.open(),
                "events must equal TP + SL + Time + Ambiguous + Open");
    }

    private static void assertProfileReconciles(Flushes.FlushProfile p) {
        assertEquals(p.eventCount(),
                p.tpFirst() + p.slFirst() + p.neither() + p.ambiguous() + p.open(),
                "eventCount must equal TP + SL + neither + Ambiguous + Open");
    }
}
