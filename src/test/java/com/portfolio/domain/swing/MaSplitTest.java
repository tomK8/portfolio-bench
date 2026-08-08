package com.portfolio.domain.swing;

import com.portfolio.domain.model.PriceBar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MaSplitTest {

    private static SwingSeries series(double... closes) {
        List<PriceBar> bars = new ArrayList<>();
        LocalDate d = LocalDate.parse("2026-01-01");
        for (double c : closes) {
            bars.add(new PriceBar("X", d, null, c, c, c, c, 1.0, null, "USD"));
            d = d.plusDays(1);
        }
        return SwingSeries.of("X", bars, 30, 3);        // 3-bar MA so the flag is defined by idx3
    }

    private static SwingConfig cfg() {
        return SwingConfig.builder()
                .flushDays(new int[]{2}).flushThresholds(new double[]{0.10})
                .timeStops(new int[]{5}).barrierFlushDays(2).barrierFlushDrop(0.10)
                .eventLogTimeStop(5).smallNFloor(1).episodeFloor(1).build();
    }

    @Test
    void splitsFlushOutcomesByMaStateAndReconciles() {
        // Flush entry at idx3 (85): sma[3] = mean(100,100,85) = 95, so 85 < MA → the "below" state.
        SwingSeries s = series(100, 100, 100, 85, 95, 95, 95, 95, 95, 95);
        MaSplit.Result ms = MaSplit.split(List.of(s), null, cfg(), Flushes.absoluteBarrierTrigger(cfg()));

        assertEquals(cfg().eventLogTimeStop(), ms.timeStop());
        assertEquals(1, ms.below().flush().events(), "the sub-MA flush lands in the below state");
        assertEquals(0, ms.above().flush().events());
        assertReconciles(ms.below().flush());
        assertReconciles(ms.above().flush());
        // Too few events/pool to bootstrap within-state → flagged underpowered, no bootstrap.
        assertTrue(ms.below().underpowered());
        assertNull(ms.below().bootstrap());
    }

    private static void assertReconciles(Flushes.BarrierResult b) {
        assertEquals(b.events(), b.tpFirst() + b.slFirst() + b.timeCount() + b.ambiguous() + b.open());
    }
}
