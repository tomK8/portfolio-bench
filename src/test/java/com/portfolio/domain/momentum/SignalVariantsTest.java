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
 * The signal-comparison spec's engine tests: hand-checked composite z-scores (V3), exact V2
 * window offsets and the constant-eligibility guard, the V4 rf gate on a synthetic series that
 * crosses the threshold, the V5 trailing stop firing at the correct close (cost booked, slot in
 * cash until the next rebalance, peak reset on re-entry), the V1 reproduction assertion (the
 * generalized ranker path is bit-identical to the incumbent fixed-signal path), and holdout
 * separation for the new stop-overlay path.
 */
class SignalVariantsTest {

    private static final LocalDate START = LocalDate.parse("2020-01-01");

    private static List<PriceBar> bars(String symbol, int days, DoubleUnaryOperator px) {
        List<PriceBar> out = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            double p = px.applyAsDouble(i);
            out.add(new PriceBar(symbol, START.plusDays(i), null, null, null, p, p, 1.0, null, "USD"));
        }
        return out;
    }

    private static MomentumSeries series(Map<String, DoubleUnaryOperator> universe, int days) {
        LinkedHashMap<String, List<PriceBar>> u = new LinkedHashMap<>();
        universe.forEach((sym, fn) -> u.put(sym, bars(sym, days, fn)));
        return MomentumSeries.of(u, new LinkedHashMap<>(), 5);
    }

    private static MomentumConfig cfg(double rfPct, double costBps) {
        return MomentumConfig.builder()
                .topNs(new int[]{1})
                .lookbackSessions(10).skipSessions(2)
                .costPerSideBps(costBps).rfAnnualPct(rfPct)
                .minEligibleFactor(1).bootstrapIterations(10)
                .build();
    }

    // ---- V3: composite z-score, hand-checked --------------------------------

    /**
     * Spreadsheet check (population stdev, ÷N). A: price d+1, B: flat 100, C: 100−d; bar i=10,
     * skip 1, lookbacks {4, 8}.
     *
     * <pre>
     * lb4 returns:  A 10/7−1 = 0.428571428571   B 0   C 91/94−1 = −0.031914893617
     *   mean 0.132218844985, popsd 0.209957583744
     *   z:  A 1.411487874369   B −0.629740743949   C −0.781747130420
     * lb8 returns:  A 10/3−1 = 2.333333333333   B 0   C 91/98−1 = −0.071428571429
     *   mean 0.753968253968, popsd 1.117160403476
     *   z:  A 1.413731702673   B −0.674897043990   C −0.738834658683
     * composite (average of the two z's):
     *   A 1.412609788521   B −0.652318893970   C −0.760290894552
     * </pre>
     */
    @Test
    void compositeZScoreMatchesHandCalculation() {
        MomentumSeries s = series(new LinkedHashMap<>(Map.of(
                "A", d -> d + 1.0, "B", d -> 100.0, "C", d -> 100.0 - d)), 20);
        Ranker v3 = SignalRankers.compositeZ(new int[]{4, 8}, 1);

        List<Momentum.Ranked> ranked = v3.rank(s, 10);
        assertEquals(3, ranked.size());
        assertEquals("A", ranked.get(0).symbol());
        assertEquals(1.412609788521, ranked.get(0).score(), 1e-9);
        assertEquals("B", ranked.get(1).symbol());
        assertEquals(-0.652318893970, ranked.get(1).score(), 1e-9);
        assertEquals("C", ranked.get(2).symbol());
        assertEquals(-0.760290894552, ranked.get(2).score(), 1e-9);
        assertEquals(1, ranked.get(0).rank());
        assertEquals(3, ranked.get(2).rank());
    }

    @Test
    void compositeDegenerateComponentContributesZeroNotNaN() {
        // Both names flat → every component stdev is 0 → all composites 0, nothing NaN.
        MomentumSeries s = series(new LinkedHashMap<>(Map.of(
                "A", d -> 100.0, "B", d -> 100.0)), 20);
        List<Momentum.Ranked> ranked = SignalRankers.compositeZ(new int[]{4, 8}, 1).rank(s, 10);
        assertEquals(2, ranked.size());
        assertEquals(0.0, ranked.get(0).score(), 0.0);
        assertEquals("A", ranked.get(0).symbol());   // alphabetical tie-break
    }

    // ---- V2: exact window offsets + constant eligibility ---------------------

    @Test
    void v2WindowBoundariesAreExactSessionOffsets() {
        // Price = day ordinal + 1: the score identifies exactly which two bars were read.
        MomentumSeries s = series(new LinkedHashMap<>(Map.of("A", d -> d + 1.0)), 40);
        Ranker v2 = SignalRankers.lookbackSkip(8, 2, 12);

        List<Momentum.Ranked> ranked = v2.rank(s, 20);
        // adj[i−skip]/adj[i−lookback] − 1 = 19/13 − 1 — any off-by-one gives a different value.
        assertEquals(19.0 / 13.0 - 1, ranked.get(0).score(), 1e-12);

        // Eligibility is the FULL 12-session history, not the 8-session scoring window.
        assertTrue(v2.rank(s, 11).isEmpty(), "not evaluable before the eligibility window");
        assertEquals(1, v2.rank(s, 12).size());
    }

    @Test
    void v2EligibilityMatchesV1NotItsOwnShorterWindow() {
        // LATE has bars only from day 6; FULL from day 0. At i=14 LATE's 8-session scoring
        // window is complete (needs day 6) but its 12-session eligibility anchor (day 2) is not —
        // a pure 6-1 rule would admit it; the constant-eligibility rule must not.
        LinkedHashMap<String, List<PriceBar>> u = new LinkedHashMap<>();
        u.put("FULL", bars("FULL", 40, d -> 100.0));
        u.put("LATE", bars("LATE", 40, d -> 100.0 + d).subList(6, 40));
        MomentumSeries s = MomentumSeries.of(u, new LinkedHashMap<>(), 5);
        Ranker v2 = SignalRankers.lookbackSkip(8, 2, 12);

        List<Momentum.Ranked> at14 = v2.rank(s, 14);
        assertEquals(1, at14.size());
        assertEquals("FULL", at14.get(0).symbol());

        // Once day i−12 reaches LATE's first bar, it joins.
        assertEquals(2, v2.rank(s, 18).size());
    }

    // ---- V4: the rf gate ------------------------------------------------------

    @Test
    void gateHoldsQualifiersAndParksFailingSlotsInCash() {
        // rf 25.2% → rfDaily exactly 0.001; hurdle over the 8-session window = 1.001^8 − 1 ≈ 0.803%.
        // A returns +8% over the Jan-31 momentum window (passes), then flat (0% < hurdle → gated).
        // B is flat throughout (never passes).
        MomentumSeries s = series(new LinkedHashMap<>(Map.of(
                "A", d -> d <= 20 ? 100.0 : (d <= 28 ? 100.0 + (d - 20) : 108.0),
                "B", d -> 100.0)), 91);
        MomentumConfig cfg = MomentumConfig.builder()
                .topNs(new int[]{2}).lookbackSessions(10).skipSessions(2)
                .costPerSideBps(0).rfAnnualPct(25.2)
                .minEligibleFactor(1).bootstrapIterations(10)
                .build();
        double hurdle = Math.pow(1.001, 8) - 1;
        int start = RotationBacktester.analysisStart(s, cfg);
        assertEquals("2020-01-31", s.dateAt(start).toString());

        RotationBacktester.Result r = RotationBacktester.run(
                s, cfg, Ranker.incumbent(cfg), RotationBacktester.gatedTopN(2, hurdle),
                2, start, s.size() - 1, null);

        RotationBacktester.RebalanceEvent jan = r.events().get(0);
        // A's window return: day 21→28 window is days 21..28 → adj[28]/adj[21]... window is
        // [i−10, i−2] = days 20..28 → 108/100 − 1 = 8% > hurdle. B: 0% < hurdle → gated.
        assertEquals(1, jan.held().size());
        assertEquals("A", jan.held().get(0).symbol());
        assertEquals(2, jan.eligible());
        assertEquals(0.5, jan.cashWeight(), 1e-12);   // slots = 2, one slot gated into cash

        RotationBacktester.RebalanceEvent feb = r.events().get(1);
        // Feb window (days 49..57) is flat for both → both gated → all cash.
        assertEquals(0, feb.held().size());
        assertEquals(1.0, feb.cashWeight(), 1e-12);
        assertEquals(List.of("A"), feb.out());

        // Fully in cash after Feb: each day earns exactly rfDaily.
        int febK = 59 - start;   // 2020-02-29 is day 59
        assertEquals(0.001, r.dailyRet()[febK + 1], 1e-12);

        // Gate-bind accounting the service derives: min(n, eligible) − held.
        assertEquals(1, Math.min(2, jan.eligible()) - jan.held().size());
        assertEquals(2, Math.min(2, feb.eligible()) - feb.held().size());
    }

    // ---- V5: the trailing stop --------------------------------------------------

    /**
     * A's path: enter at 100 (Jan 31), run to a 110 peak, then close exactly at
     * 110 × 0.85 = 93.5 on day 40 → the stop must fire at that close (at-or-through), book the
     * cost, and idle the slot in cash until the Feb 29 rebalance re-enters A. After re-entry the
     * peak must be the NEW entry's peak (95), not the stale 110: the 85 close on day 63 sits
     * below the stale threshold (93.5) but above the fresh one (80.75), so nothing may fire
     * until 80 ≤ 80.75 on day 70.
     */
    @Test
    void trailingStopFiresAtCorrectCloseBooksCostAndIdlesUntilRebalance() {
        DoubleUnaryOperator a = d -> {
            if (d <= 30) return 100.0;
            if (d <= 35) return 100.0 + 2 * (d - 30);   // → 110 peak
            if (d <= 39) return 100.0;
            if (d == 40) return 93.5;                    // exactly peak × 0.85
            if (d <= 59) return 93.5;                    // idle until Feb 29 re-entry
            if (d <= 62) return 95.0;                    // fresh peak after re-entry
            if (d <= 69) return 85.0;                    // above fresh threshold 80.75
            return 80.0;                                 // ≤ 80.75 → second stop
        };
        MomentumSeries s = series(new LinkedHashMap<>(Map.of("A", a, "B", d -> 100.0)), 95);
        MomentumConfig cfg = cfg(0, 10);
        int start = RotationBacktester.analysisStart(s, cfg);
        assertEquals("2020-01-31", s.dateAt(start).toString());

        RotationBacktester.Result r = RotationBacktester.run(
                s, cfg, Ranker.incumbent(cfg), RotationBacktester.topN(1), 1,
                start, s.size() - 1, 0.15);

        assertEquals(2, r.stops().size());

        RotationBacktester.StopEvent first = r.stops().get(0);
        assertEquals(START.plusDays(40), first.date());
        assertEquals("A", first.symbol());
        assertEquals(93.5, first.close(), 1e-12);
        assertEquals(110.0, first.peak(), 1e-12);
        assertEquals(-0.15, first.drawdownPct(), 1e-12);
        assertEquals(1.0, first.weightSold(), 1e-12);          // fully invested single slot
        double c = cfg.costPerSide();
        assertEquals(c, first.cost(), 1e-12);

        // The stop day's return: ride the fall to the close, then pay the exit cost.
        int k40 = 40 - start;
        assertEquals((93.5 / 100.0) * (1 - c) - 1, r.dailyRet()[k40], 1e-12);

        // Days 41..58: all cash at rf 0 → exactly zero daily return, no trades.
        assertEquals(0.0, r.dailyRet()[45 - start], 0.0);
        assertEquals(0.0, r.dailyRet()[55 - start], 0.0);

        // Feb 29 re-enters A (free re-entry, no re-arm condition).
        RotationBacktester.RebalanceEvent feb = r.events().get(1);
        assertEquals("2020-02-29", feb.date().toString());
        assertEquals(List.of("A"), feb.in());

        // Second stop proves the peak reset: fires at 80 against peak 95 — not against stale 110.
        RotationBacktester.StopEvent second = r.stops().get(1);
        assertEquals(START.plusDays(70), second.date());
        assertEquals(95.0, second.peak(), 1e-12);
        assertEquals(80.0 / 95.0 - 1, second.drawdownPct(), 1e-12);
    }

    // ---- V1 reproduction ----------------------------------------------------------

    @Test
    void generalizedPathReproducesIncumbentPathExactly() {
        MomentumSeries s = series(new LinkedHashMap<>(Map.of(
                "A", d -> 100 + 10 * Math.sin(d / 7.0),
                "B", d -> 100 + 10 * Math.sin(d / 5.0 + 1),
                "C", d -> 100 + 10 * Math.cos(d / 9.0),
                "D", d -> 100 + d * 0.05)), 360);
        MomentumConfig cfg = MomentumConfig.builder()
                .topNs(new int[]{2}).lookbackSessions(10).skipSessions(2)
                .minEligibleFactor(1).bootstrapIterations(10)
                .build();
        int start = RotationBacktester.analysisStart(s, cfg);
        int end = s.size() - 1;

        RotationBacktester.Result incumbent = RotationBacktester.run(
                s, cfg, RotationBacktester.topN(2), 2, start, end);
        RotationBacktester.Result generalized = RotationBacktester.run(
                s, cfg, Ranker.incumbent(cfg), RotationBacktester.topN(2), 2, start, end, null);

        // Bit-identical, not approximately equal: same selections, same costs, same equity.
        assertArrayEquals(incumbent.equity(), generalized.equity(), 0.0);
        assertArrayEquals(incumbent.dailyRet(), generalized.dailyRet(), 0.0);
        assertEquals(incumbent.events(), generalized.events());
        assertTrue(generalized.stops().isEmpty());
    }

    // ---- holdout separation for the stop-overlay path -------------------------------

    @Test
    void stopOverlayTruncatedRunMatchesFullRunOverSharedWindow() {
        MomentumSeries s = series(new LinkedHashMap<>(Map.of(
                "A", d -> 100 + 30 * Math.sin(d / 11.0),
                "B", d -> 100 + 30 * Math.sin(d / 13.0 + 1),
                "C", d -> 100 + 30 * Math.cos(d / 17.0))), 360);
        MomentumConfig cfg = cfg(4, 10);
        int start = RotationBacktester.analysisStart(s, cfg);
        int cutoff = s.monthEndIdx()[6];

        RotationBacktester.Result full = RotationBacktester.run(
                s, cfg, Ranker.incumbent(cfg), RotationBacktester.topN(1), 1,
                start, s.size() - 1, 0.15);
        RotationBacktester.Result truncated = RotationBacktester.run(
                s, cfg, Ranker.incumbent(cfg), RotationBacktester.topN(1), 1,
                start, cutoff, 0.15);

        for (int k = 0; k <= cutoff - start; k++) {
            assertEquals(truncated.dailyRet()[k], full.dailyRet()[k], 0.0,
                    "daily return diverged at bar " + k);
        }
        // Stop events over the shared window are identical too.
        List<RotationBacktester.StopEvent> fullShared = full.stops().stream()
                .filter(e -> e.barIdx() <= cutoff).toList();
        assertEquals(truncated.stops(), fullShared);
    }
}
