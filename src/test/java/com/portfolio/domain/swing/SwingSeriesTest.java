package com.portfolio.domain.swing;

import com.portfolio.domain.model.PriceBar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SwingSeriesTest {

    private static List<PriceBar> series(double... closes) {
        List<PriceBar> out = new ArrayList<>();
        LocalDate d = LocalDate.parse("2026-01-01");
        for (double c : closes) {
            out.add(new PriceBar("X", d, null, null, null, c, c, 1.0, null, "USD"));
            d = d.plusDays(1);
        }
        return out;
    }

    @Test
    void returnsComputedFromClose() {
        SwingSeries s = SwingSeries.of("X", series(100, 110, 99), 30);
        assertNotNull(s);
        assertTrue(Double.isNaN(s.simpleRet[0]));
        assertEquals(0.10, s.simpleRet[1], 1e-12);
        assertEquals(99.0 / 110.0 - 1.0, s.simpleRet[2], 1e-12);
        assertEquals(Math.log(1.1), s.logRet[1], 1e-12);
    }

    @Test
    void trailingSigmaIsLookAheadFree() {
        // Flat for a while, then a big jump. The trailing σ at the jump bar excludes the jump's own
        // return (it only sees prior, flat returns) → σ ≈ 0; the σ one bar later includes it → > 0.
        SwingSeries s = SwingSeries.of("X",
                series(100, 100, 100, 100, 100, 100, 100, 100, 120, 120), 3);
        int jump = 8;                                   // return into bar 8 is +20%
        assertEquals(0.20, s.simpleRet[jump], 1e-9);
        assertEquals(0.0, s.trailSigma[jump], 1e-9);    // window is the flat run before the jump
        assertTrue(s.trailSigma[jump + 1] > 0, "σ one bar later reflects the jump");
    }

    @Test
    void startIndexWalksToCutoff() {
        SwingSeries s = SwingSeries.of("X", series(1, 2, 3, 4, 5), 30);
        assertEquals(0, s.startIndex(LocalDate.parse("2026-01-01")));
        assertEquals(2, s.startIndex(LocalDate.parse("2026-01-03")));
        assertEquals(5, s.startIndex(LocalDate.parse("2030-01-01")));  // past the end
    }

    @Test
    void tooFewBarsReturnsNull() {
        assertNull(SwingSeries.of("X", series(100), 30));
        assertNull(SwingSeries.of("X", List.of(), 30));
    }
}
