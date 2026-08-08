package com.portfolio.domain.range;

import com.portfolio.domain.model.PriceBar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RangeSeriesTest {

    static PriceBar bar(LocalDate d, double close) {
        return new PriceBar("TEST", d, close, close, close, close, close, 1.0, 0L, "USD");
    }

    static RangeSeries series(double... closes) {
        List<PriceBar> bars = new ArrayList<>();
        LocalDate d = LocalDate.of(2025, 1, 1);
        for (double c : closes) {
            bars.add(bar(d, c));
            d = d.plusDays(1);
        }
        return RangeSeries.of("TEST", bars, 0.40);
    }

    /** Hand-checked percentile band: linear interpolation over the strictly-prior window. */
    @Test
    void percentileBandAtHandCheckedDate() {
        RangeSeries s = series(10, 20, 30, 40, 50);
        double[][] levels = s.trailingLevels(new double[]{0.10, 0.50, 0.75}, 4);

        // Bar 4's window is closes[0..3] = [10,20,30,40] — bar 4's own close (50) excluded.
        assertEquals(13.0, levels[0][4], 1e-9);   // P10: idx 0.3 → 10 + 0.3×10
        assertEquals(25.0, levels[1][4], 1e-9);   // P50: idx 1.5 → midpoint of 20,30
        assertEquals(32.5, levels[2][4], 1e-9);   // P75: idx 2.25 → 30 + 0.25×10

        // Look-ahead-free: NaN until a full prior window exists.
        for (int i = 0; i < 4; i++) {
            assertTrue(Double.isNaN(levels[1][i]), "level must be NaN at bar " + i);
        }
    }

    @Test
    void flagsDailyMovesOverThresholdAsDataErrors() {
        RangeSeries s = series(100, 100, 150, 150);   // +50% day
        assertEquals(1, s.dataErrors().size());
        assertEquals(0.5, s.dataErrors().get(0).movePct(), 1e-9);
    }
}
