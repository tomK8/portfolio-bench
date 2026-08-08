package com.portfolio.domain.swing;

import com.portfolio.domain.model.PriceBar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlaceboTest {

    private static SwingSeries wavy(int n) {
        List<PriceBar> bars = new ArrayList<>();
        LocalDate d = LocalDate.parse("2024-01-01");
        double c = 100;
        double[] rets = {0.02, -0.03, 0.05, -0.06, 0.04, -0.02, 0.03, -0.05, 0.06, -0.04};
        for (int i = 0; i < n; i++) {
            if (i > 0) c *= (1 + rets[i % rets.length]);
            bars.add(new PriceBar("X", d, null, c, c, c, c, 1.0, null, "USD"));
            d = d.plusDays(1);
        }
        return SwingSeries.of("X", bars, 30);
    }

    private static SwingConfig cfg() {
        return SwingConfig.builder()
                .timeStops(new int[]{5})
                .smallNFloor(1)
                .episodeFloor(1)
                .bootstrapIterations(200)
                .build();
    }

    @Test
    void allDaysControlNeverCensors() {
        SwingSeries s = wavy(60);
        var placebo = Placebo.allDays(List.of(s), null, cfg(), 5);
        assertEquals(0, placebo.open(), "every all-days entry has a full forward window by construction");
        assertTrue(placebo.events() > 0);
        assertEquals(placebo.events(),
                placebo.tpFirst() + placebo.slFirst() + placebo.timeCount() + placebo.ambiguous(),
                "no open trades → closed set equals events");
    }

    @Test
    void bootstrapIsReproducibleForAFixedSeed() {
        SwingSeries s = wavy(60);
        var a = Placebo.bootstrap(List.of(s), null, cfg(), 5, 12, 0.5, 0.0);
        var b = Placebo.bootstrap(List.of(s), null, cfg(), 5, 12, 0.5, 0.0);
        assertEquals(a.tpMedian(), b.tpMedian(), 1e-12);
        assertEquals(a.retMedian(), b.retMedian(), 1e-12);
        assertEquals(a.tpPercentileRank(), b.tpPercentileRank());
    }

    @Test
    void bootstrapPercentileRanksAreProbabilities() {
        SwingSeries s = wavy(60);
        var boot = Placebo.bootstrap(List.of(s), null, cfg(), 5, 12, 0.5, 0.01);
        assertTrue(boot.poolSize() > 0);
        assertNotNull(boot.tpPercentileRank());
        assertTrue(boot.tpPercentileRank() >= 0.0 && boot.tpPercentileRank() <= 1.0);
        assertTrue(boot.retPercentileRank() >= 0.0 && boot.retPercentileRank() <= 1.0);
        // Quartiles are ordered.
        assertTrue(boot.tpQ1() <= boot.tpMedian() + 1e-12);
        assertTrue(boot.tpMedian() <= boot.tpQ3() + 1e-12);
    }

    @Test
    void emptyPoolYieldsFlaggedBootstrap() {
        SwingSeries s = wavy(60);
        // Time stop far larger than the series → no fully-evaluable entry.
        var boot = Placebo.bootstrap(List.of(s), null, cfg(), 500, 12, 0.5, 0.0);
        assertEquals(0, boot.poolSize());
        assertTrue(boot.smallN());
        assertNull(boot.tpPercentileRank());
    }
}
