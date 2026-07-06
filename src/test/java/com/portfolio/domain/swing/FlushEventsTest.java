package com.portfolio.domain.swing;

import com.portfolio.domain.model.PriceBar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FlushEventsTest {

    private static SwingSeries series(double... closes) {
        List<PriceBar> bars = new ArrayList<>();
        LocalDate d = LocalDate.parse("2026-01-01");
        for (double c : closes) {
            bars.add(new PriceBar("X", d, null, c, c, c, c, 1.0, null, "USD"));
            d = d.plusDays(1);
        }
        return SwingSeries.of("X", bars, 30, 3);        // small MA window so the MA flag is defined
    }

    private static SwingConfig cfg() {
        return SwingConfig.builder()
                .flushDays(new int[]{2}).flushThresholds(new double[]{0.10})
                .timeStops(new int[]{5}).barrierFlushDays(2).barrierFlushDrop(0.10)
                .eventLogTimeStop(5).smallNFloor(1).episodeFloor(1).build();
    }

    @Test
    void oneRowPerEventWithOutcomeAndFields() {
        // 2-day −15% flush at idx3 (85/100), then a +11.8% bounce to 95 (≥ +10% TP).
        SwingSeries s = series(100, 100, 100, 85, 95, 95, 95, 95, 95, 95);
        List<FlushEvents.FlushEventRow> rows =
                FlushEvents.log(List.of(s), null, cfg(), Flushes.absoluteBarrierTrigger(cfg()), null);

        assertEquals(1, rows.size());
        FlushEvents.FlushEventRow r = rows.get(0);
        assertEquals("X", r.symbol());
        assertEquals("2026-01-04", r.date());
        assertEquals("abs", r.kind());
        assertEquals(-0.15, r.cumReturn(), 1e-9);
        assertEquals("TP", r.outcome());
        assertEquals(0.10, r.tradeReturn(), 1e-9);
        assertEquals(0.10, r.conservativeReturn(), 1e-9);
        assertEquals(1, r.sessionsHeld());
        assertFalse(r.earningsVerified(), "no earnings map → unverified");
        assertFalse(r.earningsWithinWindow());
        assertNull(r.tradingDaysToEarnings());
    }

    @Test
    void openEventIsCensoredNotDropped() {
        // Flush at idx7; too few forward bars for the 5-session stop and no barrier touch → OPEN.
        SwingSeries s = series(100, 100, 100, 100, 100, 100, 100, 85, 86, 86);
        List<FlushEvents.FlushEventRow> rows =
                FlushEvents.log(List.of(s), null, cfg(), Flushes.absoluteBarrierTrigger(cfg()), null);
        assertEquals(1, rows.size());
        FlushEvents.FlushEventRow r = rows.get(0);
        assertEquals("OPEN", r.outcome());
        assertNull(r.tradeReturn());
        assertNull(r.exitDate());
    }
}
