package com.portfolio.domain.trend;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LiveStateTest {

    private static TrendSeries series(TrendConfig cfg, double... closes) {
        return TrendSeries.of(cfg,
                TrendSeriesTest.bars("QQQ", LocalDate.parse("2026-01-05"), 1.0, 1.0, closes),
                null, null);
    }

    @Test
    void maReadoutsReportDistanceAndSkipTooShortLengths() {
        TrendConfig cfg = TrendConfig.builder().maLengths(new int[]{3, 50})
                .monthlyMaLengths(new int[]{2}).build();
        TrendSeries s = series(cfg, 100, 100, 100, 110);

        List<LiveState.MaReadout> r = LiveState.maReadouts(s, cfg.maLengths());
        assertEquals(1, r.size(), "the 50-day MA has no full window yet");
        assertEquals(3, r.get(0).length());
        double ma = (100 + 100 + 110) / 3.0;
        assertEquals(110 / ma - 1, r.get(0).distancePct(), 1e-12);
        assertTrue(r.get(0).above());
    }

    @Test
    void variantStatesReportTodayAndPendingSwitches() {
        TrendConfig cfg = TrendConfig.builder().maLengths(new int[]{3})
                .bufferPcts(new double[]{0.05}).bufferNs(new int[]{2})
                .monthlyMaLengths(new int[]{2}).anchorEvery(2).build();
        // Last close crashes through every band: first-touch flips to out on the final bar.
        TrendSeries s = series(cfg, 100, 100, 100, 100, 100, 60);

        List<LiveState.VariantState> states = LiveState.variantStates(s, cfg);
        LiveState.VariantState touch = states.stream()
                .filter(v -> v.key().startsWith("touch")).findFirst().orElseThrow();
        assertFalse(touch.invested());
        assertTrue(touch.pendingSwitch(), "flipped at the latest close");

        LiveState.VariantState anchored = states.stream()
                .filter(v -> v.key().startsWith("anchored")).findFirst().orElseThrow();
        assertTrue(anchored.note().contains("next check in"));

        LiveState.VariantState buffered = states.stream()
                .filter(v -> v.key().startsWith("bufpct")).findFirst().orElseThrow();
        assertFalse(buffered.invested(), "60 is more than 5% below the 3-day MA");
        assertTrue(buffered.note().startsWith("re-enters above"));
    }
}
