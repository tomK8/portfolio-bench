package com.portfolio.domain.swing;

import com.portfolio.domain.model.PriceBar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReturnCorrelationTest {

    private static SwingSeries series(String sym, double[] closes) {
        List<PriceBar> bars = new ArrayList<>();
        LocalDate d = LocalDate.parse("2026-01-01");
        for (double c : closes) {
            bars.add(new PriceBar(sym, d, null, null, null, c, c, 1.0, null, "USD"));
            d = d.plusDays(1);
        }
        return SwingSeries.of(sym, bars, 30);
    }

    private static final double[] A = {100, 110, 105, 116, 108};

    /** Closes whose daily simple returns are the exact negatives of {@code src}'s. */
    private static double[] antiCloses(double[] src) {
        double[] out = new double[src.length];
        out[0] = 100;
        for (int i = 1; i < src.length; i++) {
            double srcRet = src[i] / src[i - 1] - 1.0;
            out[i] = out[i - 1] * (1 - srcRet);
        }
        return out;
    }

    private static SwingConfig cfg() {
        return SwingConfig.builder().cluster(List.of("A", "B")).build();
    }

    @Test
    void identicalReturnsCorrelatePlusOne() {
        var r = ReturnCorrelation.compute(List.of(series("A", A), series("B", A)), cfg());
        assertEquals(2, r.symbols().size());
        assertEquals(1.0, r.matrix30().get(0).get(1).doubleValue(), 1e-6);
    }

    @Test
    void oppositeReturnsCorrelateMinusOne() {
        var r = ReturnCorrelation.compute(List.of(series("A", A), series("C", antiCloses(A))), cfg());
        assertEquals(-1.0, r.matrix30().get(0).get(1).doubleValue(), 1e-6);
    }

    @Test
    void diagonalIsOne() {
        var r = ReturnCorrelation.compute(List.of(series("A", A), series("B", A)), cfg());
        assertEquals(1.0, r.matrix30().get(0).get(0).doubleValue(), 1e-9);
        assertEquals(1.0, r.matrix30().get(1).get(1).doubleValue(), 1e-9);
    }

    @Test
    void downDayCountUsesClusterMean() {
        // Two identical series → cluster mean each day equals A's return; two days are ≤ −2%.
        var r = ReturnCorrelation.compute(List.of(series("A", A), series("B", A)), cfg());
        assertEquals(2, r.downDayCount());
        assertEquals(0.02, r.downDayThreshold(), 1e-12);
    }
}
