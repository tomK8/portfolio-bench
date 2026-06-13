package com.portfolio.application;

import com.portfolio.application.CorrelationService.Universe;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorrelationServiceTest {

    @Test
    void correlationOfTwoIdenticalSeriesIsOne() {
        double[][] m = {
                {0.01, 0.02, -0.01, 0.03, -0.02},
                {0.01, 0.02, -0.01, 0.03, -0.02}
        };
        double[][] c = CorrelationService.correlationMatrix(m);
        assertEquals(1.0, c[0][1], 1e-9);
        assertEquals(1.0, c[1][0], 1e-9);
    }

    @Test
    void correlationOfPerfectlyAntiCorrelatedIsMinusOne() {
        double[][] m = {
                {0.01, 0.02, -0.01, 0.03, -0.02},
                {-0.01, -0.02, 0.01, -0.03, 0.02}
        };
        double[][] c = CorrelationService.correlationMatrix(m);
        assertEquals(-1.0, c[0][1], 1e-9);
    }

    @Test
    void correlationOfTwoSamePlusOneFlatIsZero() {
        // A flat series (all zeros) has no variance — pairwise correlation must
        // degrade to 0 rather than NaN; the service clamps in this case.
        double[][] m = {
                {0.01, 0.02, -0.01, 0.03, -0.02},
                {0.0, 0.0, 0.0, 0.0, 0.0}
        };
        double[][] c = CorrelationService.correlationMatrix(m);
        assertEquals(0.0, c[0][1], 1e-9);
    }

    @Test
    void annualisedVolScalesBySqrt252() {
        // Daily stdev 0.01 → annualised ≈ 0.01 × √252 ≈ 0.15875.
        double[][] m = {
                {0.01, -0.01, 0.01, -0.01, 0.01, -0.01, 0.01, -0.01, 0.01, -0.01}
        };
        double[] v = CorrelationService.annualisedVols(m);
        double dailySd = stdevOf(m[0]);
        assertEquals(dailySd * Math.sqrt(252.0), v[0], 1e-9);
    }

    @Test
    void portfolioVolEqualsWeightedVolWhenPerfectlyCorrelated() {
        // Two perfectly-correlated assets (ρ=1), σ₁=0.2, σ₂=0.3, w=[0.5, 0.5].
        // σ_p = √(w'Σw) = √(0.25·0.04 + 0.25·0.09 + 2·0.25·0.06) = √0.0625 = 0.25.
        // Weighted average = 0.5·0.2 + 0.5·0.3 = 0.25. So DR = 1 (no diversification).
        double[] w = { 0.5, 0.5 };
        double[] vols = { 0.2, 0.3 };
        double[][] corr = { {1.0, 1.0}, {1.0, 1.0} };
        double pvol = CorrelationService.portfolioVol(w, vols, corr);
        assertEquals(0.25, pvol, 1e-9);
    }

    @Test
    void portfolioVolDropsWhenUncorrelated() {
        // Same weights and vols but ρ=0: σ_p = √(0.01 + 0.0225) = √0.0325 ≈ 0.1803.
        // DR = 0.25 / 0.1803 ≈ 1.387.
        double[] w = { 0.5, 0.5 };
        double[] vols = { 0.2, 0.3 };
        double[][] corr = { {1.0, 0.0}, {0.0, 1.0} };
        double pvol = CorrelationService.portfolioVol(w, vols, corr);
        assertEquals(Math.sqrt(0.0325), pvol, 1e-9);
    }

    @Test
    void diversificationRatioIsInvariantToAddingZeroVolUncorrelatedCash() {
        // Two-asset equity portfolio: σ_p ≈ 0.1803, w-avg σ = 0.25, DR ≈ 1.387.
        // Add 50% cash (σ=0, ρ=0 with both): weights become [0.25, 0.25, 0.5].
        // σ_p_new = √(0.25·0.5²·0.2² + 0.25·0.5²·0.3²) = 0.5·√0.0325 = ½ × original σ_p.
        // w-avg σ_new = 0.25·0.2 + 0.25·0.3 = 0.125 = ½ × original. Ratio unchanged.
        double[] equityW = { 0.5, 0.5 };
        double[] equityVols = { 0.2, 0.3 };
        double[][] equityCorr = { {1.0, 0.0}, {0.0, 1.0} };
        double equityPvol = CorrelationService.portfolioVol(equityW, equityVols, equityCorr);
        double equityWavg = 0.5 * 0.2 + 0.5 * 0.3;
        double equityDR = equityWavg / equityPvol;

        double[] withCashW = { 0.25, 0.25, 0.5 };
        double[] withCashVols = { 0.2, 0.3, 0.0 };
        double[][] withCashCorr = { {1.0, 0.0, 0.0}, {0.0, 1.0, 0.0}, {0.0, 0.0, 1.0} };
        double withCashPvol = CorrelationService.portfolioVol(withCashW, withCashVols, withCashCorr);
        double withCashWavg = 0.25 * 0.2 + 0.25 * 0.3 + 0.5 * 0.0;
        double withCashDR = withCashWavg / withCashPvol;

        assertEquals(equityDR, withCashDR, 1e-9);
        // And portfolio vol halved as expected.
        assertEquals(equityPvol / 2.0, withCashPvol, 1e-9);
    }

    @Test
    void clusterOrderPutsHighCorrelationGroupsTogether() {
        // Two clusters: {0,1} strongly correlated, {2,3} strongly correlated,
        // weak link across. Reorder should land them in two adjacent blocks
        // (either {0,1,2,3} or {2,3,0,1} depending on tie-breaking).
        double[][] corr = {
                {1.0, 0.9, 0.1, 0.1},
                {0.9, 1.0, 0.1, 0.1},
                {0.1, 0.1, 1.0, 0.9},
                {0.1, 0.1, 0.9, 1.0}
        };
        int[] order = CorrelationService.clusterOrder(corr);
        assertEquals(4, order.length);
        // The two natural pairs should remain adjacent in the leaf order.
        int p0 = indexOf(order, 0), p1 = indexOf(order, 1);
        int p2 = indexOf(order, 2), p3 = indexOf(order, 3);
        assertEquals(1, Math.abs(p0 - p1), "0 and 1 should sit next to each other");
        assertEquals(1, Math.abs(p2 - p3), "2 and 3 should sit next to each other");
    }

    @Test
    void universePicksLatestCommonWindow() {
        Map<String, NavigableMap<LocalDate, Double>> returns = new LinkedHashMap<>();
        // Two symbols, 40 days, all common. Window 25 → take the latest 25.
        NavigableMap<LocalDate, Double> a = new TreeMap<>();
        NavigableMap<LocalDate, Double> b = new TreeMap<>();
        LocalDate d0 = LocalDate.of(2025, 1, 6);
        for (int i = 0; i < 40; i++) {
            a.put(d0.plusDays(i), 0.01);
            b.put(d0.plusDays(i), 0.02);
        }
        returns.put("A", a);
        returns.put("B", b);

        Universe u = CorrelationService.pickUniverse(returns, 25);
        assertEquals(List.of("A", "B"), u.symbols());
        assertEquals(25, u.dates().size());
        assertEquals(d0.plusDays(39), u.dates().get(u.dates().size() - 1));
        assertTrue(u.dropped().isEmpty());
    }

    @Test
    void universeDropsSymbolsWithoutEnoughCoverage() {
        Map<String, NavigableMap<LocalDate, Double>> returns = new LinkedHashMap<>();
        NavigableMap<LocalDate, Double> longSeries = new TreeMap<>();
        NavigableMap<LocalDate, Double> shortSeries = new TreeMap<>();
        LocalDate d0 = LocalDate.of(2025, 1, 6);
        // A has 100 days; B only has 5 days at the start, so < 70% of the recent
        // lookback window — must be dropped.
        for (int i = 0; i < 100; i++) longSeries.put(d0.plusDays(i), 0.01);
        for (int i = 0; i < 5; i++) shortSeries.put(d0.plusDays(i), 0.02);
        returns.put("LONG", longSeries);
        returns.put("SHORT", shortSeries);

        Universe u = CorrelationService.pickUniverse(returns, 30);
        assertEquals(List.of("LONG"), u.symbols());
        assertEquals(List.of("SHORT"), u.dropped());
    }

    // ---- helpers ----

    private static int indexOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return -1;
    }

    private static double stdevOf(double[] x) {
        double mean = 0;
        for (double v : x) mean += v;
        mean /= x.length;
        double s = 0;
        for (double v : x) s += (v - mean) * (v - mean);
        return Math.sqrt(s / (x.length - 1));
    }
}
