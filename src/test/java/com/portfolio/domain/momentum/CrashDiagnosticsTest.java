package com.portfolio.domain.momentum;

import com.portfolio.domain.model.PriceBar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The crash-setup episode dedup ("cluster dedup"): contiguous qualifying month-ends collapse into
 * one episode — overlapping windows from a single bear market are not independent evidence — and
 * windows running past the section end are censored, counted, never truncated. The signal-variants
 * work reuses this engine untouched; this pins its behaviour.
 */
class CrashDiagnosticsTest {

    private static final LocalDate START = LocalDate.parse("2020-01-01");

    private static MomentumSeries series(DoubleUnaryOperator px, int days) {
        List<PriceBar> bars = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            double p = px.applyAsDouble(i);
            bars.add(new PriceBar("QQQ", START.plusDays(i), null, null, null, p, p, 1.0, null, "USD"));
        }
        LinkedHashMap<String, List<PriceBar>> u = new LinkedHashMap<>();
        u.put("QQQ", bars);
        return MomentumSeries.of(u, new LinkedHashMap<>(), 5);
    }

    @Test
    void contiguousSetupsCollapseToEpisodesAndTailWindowsAreCensored() {
        // Index path by calendar month (consecutive-day calendar):
        //   Jan–Feb 100 · Mar–May 80 (20% below the 100 high → setups) · Jun–Aug 100 (dd 0)
        //   Sep 120 (new high) · Oct–Nov 95 (20.8% below 120 → setups) · Dec 120.
        // Setups: Mar Apr May | Oct Nov → 5 setups, 2 episodes.
        // crashWindowMonths = 2: Nov's window needs Dec + Jan-2021 (absent) → 1 censored.
        DoubleUnaryOperator px = d -> {
            if (d < 60) return 100.0;    // Jan, Feb
            if (d < 152) return 80.0;    // Mar, Apr, May
            if (d < 244) return 100.0;   // Jun, Jul, Aug
            if (d < 274) return 120.0;   // Sep
            if (d < 335) return 95.0;    // Oct, Nov
            return 120.0;                // Dec
        };
        MomentumSeries s = series(px, 370);   // Dec 31 completes (Jan 4 2021 exists)

        MomentumConfig cfg = MomentumConfig.builder()
                .universe(List.of("QQQ")).regimeSymbol("QQQ")
                .topNs(new int[]{1}).minEligibleFactor(1)
                .crashDrawdownPct(15).crashWindowMonths(2)
                .build();

        Map<String, Double> months = new java.util.LinkedHashMap<>();
        for (int m = 1; m <= 12; m++) months.put(String.format("2020-%02d", m), 0.0);

        CrashDiagnostics.Result r = CrashDiagnostics.compute(s, cfg, months, months);

        assertEquals(5, r.setupMonths());
        assertEquals(2, r.episodes(), "Mar–May and Oct–Nov must collapse to one episode each");
        assertEquals(4, r.windows());
        assertEquals(1, r.censored(), "Nov's 2-month window runs past Dec and must be censored");
        assertEquals(r.setupMonths(), r.windows() + r.censored());
    }
}
