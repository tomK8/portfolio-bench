package com.portfolio.domain.swing;

import com.portfolio.domain.model.PriceBar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FlushTriggerTest {

    private static SwingSeries series(int sigmaWindow, double... closes) {
        List<PriceBar> bars = new ArrayList<>();
        LocalDate d = LocalDate.parse("2026-01-01");
        for (double c : closes) {
            bars.add(new PriceBar("X", d, null, c, c, c, c, 1.0, null, "USD"));
            d = d.plusDays(1);
        }
        return SwingSeries.of("X", bars, sigmaWindow, 200);
    }

    @Test
    void absoluteFiresOnCumulativeDrop() {
        SwingSeries s = series(3, 100, 100, 94, 88, 88);
        FlushTrigger t = FlushTrigger.absolute(2, 0.10);

        assertNull(t.fire(s, 2), "100→94 over 2 days is only −6%");
        FlushTrigger.Fire f = t.fire(s, 3);                 // base close[1]=100, close[3]=88 → −12%
        assertNotNull(f);
        assertEquals(-0.12, f.cumReturn(), 1e-9);
        assertEquals(2, t.days());
        assertEquals("abs", t.kind());
        assertEquals(0.10, t.magnitude(), 1e-12);
        assertEquals("2d ≤ −10%", t.label());
    }

    @Test
    void sigmaScalesToThePreWindowVol() {
        // ±1% wiggle (σ ≈ 1%/day) then a −16% two-day flush → many σ.
        SwingSeries s = series(4, 100, 99, 100, 99, 100, 99, 100, 99, 100, 90, 84);

        FlushTrigger k2 = FlushTrigger.sigma(2, 2.0);
        FlushTrigger.Fire f = k2.fire(s, 10);
        assertNotNull(f, "−16% is far beyond 2σ of a 1% wiggle");
        assertTrue(f.sigmaMultiple() < -5, "should read as many σ down: " + f.sigmaMultiple());
        assertEquals("2d ≤ −2σ", k2.label());
        assertEquals("sigma", k2.kind());

        assertNull(FlushTrigger.sigma(2, 20.0).fire(s, 10), "−16% is not beyond 20σ");
    }

    @Test
    void sigmaCannotFireBeforeItsWindowExists() {
        SwingSeries s = series(4, 100, 99, 100, 85, 85);
        assertNull(FlushTrigger.sigma(2, 2.0).fire(s, 3), "no full σ window yet → no σ trigger");
    }
}
