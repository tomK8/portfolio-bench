package com.portfolio.domain.swing;

import com.portfolio.domain.model.PriceBar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ForwardReturnsTest {

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
                .horizons(new int[]{1, 2})
                .absDrops(new double[]{0.10})
                .sigmaMultiples(new double[]{2})
                .smallNFloor(1)
                .build();
    }

    // 100 flat, then −10% (idx5), then a +10% bounce that holds.
    private static final double[] PATH = {100, 100, 100, 100, 100, 90, 99, 99, 99, 99};

    private static ForwardReturns.ConditionalBlock absBlock(ForwardReturns.Result r) {
        return r.conditional().stream()
                .filter(b -> b.kind().equals("abs")).findFirst().orElseThrow();
    }

    @Test
    void conditionalTriggerFiresOnceWithKnownBounce() {
        ForwardReturns.Result r = ForwardReturns.compute(List.of(series(PATH)), null, cfg());
        ForwardReturns.ConditionalBlock abs = absBlock(r);
        assertEquals(1, abs.triggerDays());
        // Entry at the −10% close (90); +1d and +2d both close at 99 → +10%.
        assertEquals(0.10, abs.horizons().get(0).mean(), 1e-9);
        assertEquals(1, abs.horizons().get(0).n());
        assertEquals(0.10, abs.horizons().get(1).mean(), 1e-9);
    }

    @Test
    void baselineIsUnconditionalOverEveryDay() {
        ForwardReturns.Result r = ForwardReturns.compute(List.of(series(PATH)), null, cfg());
        ForwardReturns.ForwardStat base1 = r.baseline().get(0);
        // Days 1..8 have a +1d forward; their returns net to zero (one −10% offset by one +10%).
        assertEquals(8, base1.n());
        assertEquals(0.0, base1.mean(), 1e-9);
    }

    @Test
    void continuationAfterTriggerReadsNextDay() {
        ForwardReturns.Result r = ForwardReturns.compute(List.of(series(PATH)), null, cfg());
        ForwardReturns.ContinuationStat cont = r.continuation().stream()
                .filter(c -> c.label().contains("%")).findFirst().orElseThrow();
        // The single trigger's next day is +10% → P(next ≤ 0) = 0.
        assertEquals(0.0, cont.pDown(), 1e-9);
        assertEquals(0.0, cont.pBig(), 1e-9);
    }

    @Test
    void poolingConcatenatesObservations() {
        SwingSeries s = series(PATH);
        ForwardReturns.Result single = ForwardReturns.compute(List.of(s), null, cfg());
        ForwardReturns.Result pooled = ForwardReturns.compute(List.of(s, s), null, cfg());
        assertEquals(2 * absBlock(single).triggerDays(), absBlock(pooled).triggerDays());
        assertEquals(2 * single.baseline().get(0).n(), pooled.baseline().get(0).n());
    }
}
