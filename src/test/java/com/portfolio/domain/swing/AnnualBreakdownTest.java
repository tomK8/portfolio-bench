package com.portfolio.domain.swing;

import com.portfolio.domain.model.PriceBar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnnualBreakdownTest {

    private static SwingConfig cfg() {
        return SwingConfig.builder()
                .flushDays(new int[]{2}).flushThresholds(new double[]{0.10})
                .timeStops(new int[]{5}).barrierFlushDays(2).barrierFlushDrop(0.10)
                .eventLogTimeStop(5).smallNFloor(1).episodeFloor(1).build();
    }

    /** 45 daily bars spanning 2025 → 2026, with a −15% flush inside each calendar year. */
    private static SwingSeries twoYearSeries() {
        double[] closes = new double[45];
        for (int i = 0; i < 45; i++) closes[i] = 100;
        closes[9] = 92; closes[10] = 85;              // flush at idx10 (2025-12-11)
        for (int i = 11; i <= 33; i++) closes[i] = 95;
        closes[34] = 88; closes[35] = 80;             // flush at idx35 (2026-01-05)
        for (int i = 36; i < 45; i++) closes[i] = 90;

        List<PriceBar> bars = new ArrayList<>();
        LocalDate d = LocalDate.parse("2025-12-01");
        for (double c : closes) {
            bars.add(new PriceBar("X", d, null, c, c, c, c, 1.0, null, "USD"));
            d = d.plusDays(1);
        }
        return SwingSeries.of("X", bars, 30, 3);
    }

    @Test
    void breaksEventsOutByYearAndReconciles() {
        SwingSeries s = twoYearSeries();
        List<AnnualBreakdown.AnnualRow> rows =
                AnnualBreakdown.annual(List.of(s), null, cfg(), Flushes.absoluteBarrierTrigger(cfg()));

        AnnualBreakdown.AnnualRow y2025 = rows.stream().filter(r -> r.year() == 2025).findFirst().orElseThrow();
        AnnualBreakdown.AnnualRow y2026 = rows.stream().filter(r -> r.year() == 2026).findFirst().orElseThrow();

        assertEquals(1, y2025.events());
        assertEquals(1, y2026.events());
        for (AnnualBreakdown.AnnualRow r : rows) {
            assertEquals(r.events(), r.tp() + r.sl() + r.time() + r.ambiguous() + r.open(),
                    "year " + r.year() + " must reconcile");
        }
        // 2026 has a prior-year close to anchor the calendar-year return against.
        assertNotNull(y2026.calendarReturn());
    }
}
