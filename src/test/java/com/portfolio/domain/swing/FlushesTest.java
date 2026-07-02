package com.portfolio.domain.swing;

import com.portfolio.domain.model.PriceBar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FlushesTest {

    private static SwingSeries series(double... closes) {
        List<PriceBar> bars = new ArrayList<>();
        LocalDate d = LocalDate.parse("2026-01-01");
        for (double c : closes) {
            bars.add(new PriceBar("X", d, null, null, null, c, c, 1.0, null, "USD"));
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
                .build();                                // tp=0.10, sl=0.15 (defaults)
    }

    @Test
    void takeProfitExitBeforeStop() {
        // 2-day −15% flush at idx3 (85/100), then a +11.8% bounce to 95 (≥ +10% TP).
        SwingSeries s = series(100, 100, 100, 85, 95, 95, 95, 95, 95, 95);
        var barrier = Flushes.barriers(List.of(s), null, cfg()).get(0);
        assertEquals(1, barrier.trades());
        assertEquals(1.0, barrier.pctTp(), 1e-9);
        assertEquals(0.0, barrier.pctSl(), 1e-9);
        assertEquals(1.0, barrier.meanHoldingDays(), 1e-9);

        var profile = Flushes.profiles(List.of(s), null, cfg()).get(0);
        assertEquals(1, profile.eventCount());
        assertEquals(1, profile.tpFirst());
        assertEquals(0, profile.slFirst());
    }

    @Test
    void stopLossExitBeforeTarget() {
        // Same flush, then a drop to 70 (−17.6% from the 85 entry ≤ −15% SL).
        SwingSeries s = series(100, 100, 100, 85, 70, 70, 70, 70, 70, 70);
        var barrier = Flushes.barriers(List.of(s), null, cfg()).get(0);
        assertEquals(1, barrier.trades());
        assertEquals(1.0, barrier.pctSl(), 1e-9);
        assertEquals(0.0, barrier.pctTp(), 1e-9);

        var profile = Flushes.profiles(List.of(s), null, cfg()).get(0);
        assertEquals(1, profile.slFirst());
        assertEquals(0, profile.tpFirst());
    }

    @Test
    void timeStopExitWhenNeitherBarrierHit() {
        // Flush then drifts +1.2%/day — never hits ±barrier within 5 sessions.
        SwingSeries s = series(100, 100, 100, 85, 86, 86, 86, 86, 86, 86);
        var barrier = Flushes.barriers(List.of(s), null, cfg()).get(0);
        assertEquals(1, barrier.trades());
        assertEquals(1.0, barrier.pctTime(), 1e-9);
        assertEquals(5.0, barrier.meanHoldingDays(), 1e-9);
        assertEquals(86.0 / 85.0 - 1.0, barrier.meanReturn(), 1e-9);
    }

    @Test
    void cooldownCollapsesConsecutiveFlushDays() {
        // idx3 and idx4 both qualify as 2-day ≤ −10% flushes; the cooldown counts one event.
        SwingSeries s = series(100, 100, 100, 88, 80, 95, 95, 95, 95, 95);
        var profile = Flushes.profiles(List.of(s), null, cfg()).get(0);
        assertEquals(1, profile.eventCount());
    }

    @Test
    void poolingAddsEventsAcrossSeries() {
        SwingSeries s = series(100, 100, 100, 85, 95, 95, 95, 95, 95, 95);
        var pooled = Flushes.barriers(List.of(s, s), null, cfg()).get(0);
        assertEquals(2, pooled.trades());
    }
}
