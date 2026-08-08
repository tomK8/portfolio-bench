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
 * The spec's required engine tests: hand-checked ranks, skip-window boundaries, keep-held
 * rebalance logic, exact cost accounting on a synthetic two-trade example, holdout separation
 * (no post-cutoff data reaches the fit window), bootstrap determinism, and the
 * fewer-than-N-eligible cash rule.
 */
class RotationBacktesterTest {

    private static final LocalDate START = LocalDate.parse("2020-01-01");

    /** Consecutive-calendar-day bars from START; {@code px} maps day ordinal → price. */
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

    private static MomentumConfig cfg(int... topNs) {
        return MomentumConfig.builder()
                .topNs(topNs.length == 0 ? new int[]{1} : topNs)
                .lookbackSessions(10).skipSessions(2)
                .costPerSideBps(10).rfAnnualPct(0)
                .minEligibleFactor(1).bootstrapIterations(10)
                .build();
    }

    // ---- 1. rank calculation, hand-checked ----------------------------------

    @Test
    void rankUsesHandCheckedTwelveMinusOneReturns() {
        // A: price = 100 + day (steady climber). B: flat 100. C: 100 − day/2 (decliner).
        MomentumSeries s = series(new LinkedHashMap<>(Map.of(
                "A", d -> 100.0 + d, "B", d -> 100.0, "C", d -> 100.0 - d / 2.0)), 40);
        int i = 20;   // lookback 10, skip 2 → window is day 10 → day 18
        List<Momentum.Ranked> ranked = Momentum.rank(s, i, 10, 2);

        assertEquals(3, ranked.size());
        assertEquals("A", ranked.get(0).symbol());
        assertEquals(118.0 / 110.0 - 1, ranked.get(0).score(), 1e-12);   // hand-computed
        assertEquals("B", ranked.get(1).symbol());
        assertEquals(0.0, ranked.get(1).score(), 1e-12);
        assertEquals("C", ranked.get(2).symbol());
        assertEquals(91.0 / 95.0 - 1, ranked.get(2).score(), 1e-12);
        assertEquals(1, ranked.get(0).rank());
        assertEquals(3, ranked.get(2).rank());
    }

    // ---- 2. skip-window boundaries ------------------------------------------

    @Test
    void skipWindowBoundariesAreExact() {
        // Price equals the day ordinal + 1, so a score identifies exactly which two bars it read.
        MomentumSeries s = series(new LinkedHashMap<>(Map.of("A", d -> d + 1.0)), 40);
        int i = 30, lookback = 10, skip = 2;
        List<Momentum.Ranked> ranked = Momentum.rank(s, i, lookback, skip);
        // Must be adj[i−skip]/adj[i−lookback] − 1 = 29/21 − 1 — any off-by-one gives a different value.
        assertEquals(29.0 / 21.0 - 1, ranked.get(0).score(), 1e-12);

        // The window must not be evaluable before i == lookback.
        assertTrue(Momentum.rank(s, lookback - 1, lookback, skip).isEmpty());
        assertEquals(1, Momentum.rank(s, lookback, lookback, skip).size());
    }

    // ---- 3 + 4. keep-held logic and exact two-trade cost accounting ----------

    @Test
    void keptNameDoesNotChurnAndSwapCostsAreExact() {
        // A flat at 100 throughout. B jumps to 200 on day 53 (2020-02-23) — inside the Feb 29
        // rebalance's momentum window (days 49→57), so the rank flips there.
        MomentumSeries s = series(new LinkedHashMap<>(Map.of(
                "A", d -> 100.0,
                "B", d -> d <= 52 ? 100.0 : 200.0)), 91);
        MomentumConfig cfg = cfg(1);
        int start = RotationBacktester.analysisStart(s, cfg);
        assertEquals("2020-01-31", s.dateAt(start).toString());   // first month-end, both eligible

        RotationBacktester.Result r = RotationBacktester.run(
                s, cfg, RotationBacktester.topN(1), 1, start, s.size() - 1);

        assertEquals(2, r.events().size());   // Jan 31 + Feb 29 (March never completes)
        RotationBacktester.RebalanceEvent jan = r.events().get(0);
        RotationBacktester.RebalanceEvent feb = r.events().get(1);

        // Jan: tie (both score 0) breaks alphabetically → A enters from all-cash.
        assertEquals(List.of("A"), jan.in());
        assertEquals(List.of(), jan.out());
        assertEquals(0.5, jan.turnover(), 1e-12);            // Σ|Δ|/2 = 1/2
        double c = cfg.costPerSide();
        assertEquals(c, jan.cost(), 1e-12);

        // Feb: B's momentum flipped the rank → full swap, Σ|Δ| = 2.
        assertEquals(List.of("B"), feb.in());
        assertEquals(List.of("A"), feb.out());
        assertEquals(1.0, feb.turnover(), 1e-12);
        assertEquals(2 * c, feb.cost(), 1e-12);

        // Prices are flat while held, so final equity is exactly the two cost hits.
        assertEquals((1 - c) * (1 - 2 * c), r.equity()[r.equity().length - 1], 1e-12);
    }

    @Test
    void keptNamePaysNoCostWhenNothingMoves() {
        MomentumSeries s = series(new LinkedHashMap<>(Map.of(
                "A", d -> 100.0 + d,        // A stays top at every rebalance
                "B", d -> 100.0)), 91);
        MomentumConfig cfg = cfg(1);
        int start = RotationBacktester.analysisStart(s, cfg);
        RotationBacktester.Result r = RotationBacktester.run(
                s, cfg, RotationBacktester.topN(1), 1, start, s.size() - 1);

        RotationBacktester.RebalanceEvent second = r.events().get(1);
        assertEquals(List.of(), second.in());
        assertEquals(List.of(), second.out());
        // Fully invested in one name → weight is exactly 1 both sides → zero drift correction.
        assertEquals(0.0, second.turnover(), 1e-12);
        assertEquals(0.0, second.cost(), 1e-12);
    }

    // ---- 5. holdout separation ----------------------------------------------

    @Test
    void truncatedRunMatchesFullRunOverSharedWindow() {
        // Wiggly deterministic prices so selections genuinely change over time.
        MomentumSeries s = series(new LinkedHashMap<>(Map.of(
                "A", d -> 100 + 10 * Math.sin(d / 7.0),
                "B", d -> 100 + 10 * Math.sin(d / 5.0 + 1),
                "C", d -> 100 + 10 * Math.cos(d / 9.0),
                "D", d -> 100 + d * 0.05)), 360);
        MomentumConfig cfg = cfg(2);
        int start = RotationBacktester.analysisStart(s, cfg);
        int cutoff = s.monthEndIdx()[6];   // truncate mid-history

        RotationBacktester.Result full = RotationBacktester.run(
                s, cfg, RotationBacktester.topN(2), 2, start, s.size() - 1);
        RotationBacktester.Result truncated = RotationBacktester.run(
                s, cfg, RotationBacktester.topN(2), 2, start, cutoff);

        // No post-cutoff data may influence the shared window: every daily return identical.
        for (int k = 0; k <= cutoff - start; k++) {
            assertEquals(truncated.dailyRet()[k], full.dailyRet()[k], 0.0,
                    "daily return diverged at bar " + k);
        }
        assertEquals(truncated.equity()[cutoff - start], full.equity()[cutoff - start], 0.0);
    }

    // ---- 6. bootstrap determinism -------------------------------------------

    @Test
    void bootstrapIsDeterministicPerSeed() {
        MomentumSeries s = series(new LinkedHashMap<>(Map.of(
                "A", d -> 100 + 10 * Math.sin(d / 7.0),
                "B", d -> 100 + 10 * Math.sin(d / 5.0 + 1),
                "C", d -> 100 + 10 * Math.cos(d / 9.0))), 240);
        MomentumConfig cfg = cfg(1);
        int start = RotationBacktester.analysisStart(s, cfg);
        int end = s.size() - 1;
        RotationBacktester.Result actual = RotationBacktester.run(
                s, cfg, RotationBacktester.topN(1), 1, start, end);
        MomentumStats st = MomentumStats.of(s, actual, 0, end - start, cfg.rfDaily(), null);

        RotationBootstrap b1 = RotationBootstrap.run(s, cfg, 1, start, end, st);
        RotationBootstrap b2 = RotationBootstrap.run(s, cfg, 1, start, end, st);
        assertEquals(b1, b2, "same seed must reproduce the identical distribution");

        MomentumConfig other = MomentumConfig.builder()
                .topNs(new int[]{1}).lookbackSessions(10).skipSessions(2)
                .minEligibleFactor(1).bootstrapIterations(10).randomSeed(99L).build();
        RotationBootstrap b3 = RotationBootstrap.run(s, other, 1, start, end, st);
        assertNotEquals(b1.cagr(), b3.cagr(), "a different seed must draw different picks");
    }

    // ---- 7. eligibility and the cash rule -----------------------------------

    @Test
    void fewerThanNEligibleHoldsQualifiersPlusCashAtRf() {
        MomentumSeries s = series(new LinkedHashMap<>(Map.of(
                "A", d -> 100.0, "B", d -> 100.0)), 91);
        // rf chosen so rfDaily is exactly 0.001; N = 3 with only 2 names ever eligible.
        MomentumConfig cfg = MomentumConfig.builder()
                .topNs(new int[]{3}).lookbackSessions(10).skipSessions(2)
                .costPerSideBps(0).rfAnnualPct(25.2)
                .minEligibleFactor(1).bootstrapIterations(10).build();
        int start = s.monthEndIdx()[0];

        RotationBacktester.Result r = RotationBacktester.run(
                s, cfg, RotationBacktester.topN(3), 3, start, s.size() - 1);

        RotationBacktester.RebalanceEvent first = r.events().get(0);
        assertEquals(2, first.eligible());
        assertEquals(1.0 / 3, first.cashWeight(), 1e-12);    // 2 slots filled at 1/3, one in cash
        // Flat prices: the whole daily return is the cash slot earning rf.
        assertEquals(1.0 / 3 * 0.001, r.dailyRet()[1], 1e-12);
    }

    @Test
    void longGapExcludesFromRankingAndIsLogged() {
        // B has no bars 2020-02-05..2020-02-20 (16 sessions > maxFfill 5); A is complete.
        List<PriceBar> a = bars("A", 91, d -> 100.0);
        List<PriceBar> b = new ArrayList<>();
        for (PriceBar bar : bars("B", 91, d -> 100.0)) {
            if (!bar.date().isBefore(LocalDate.parse("2020-02-05"))
                    && !bar.date().isAfter(LocalDate.parse("2020-02-20"))) continue;
            b.add(bar);
        }
        LinkedHashMap<String, List<PriceBar>> u = new LinkedHashMap<>();
        u.put("A", a);
        u.put("B", b);
        MomentumSeries s = MomentumSeries.of(u, new LinkedHashMap<>(), 5);

        assertEquals(1, s.gaps().size());
        MomentumSeries.Gap gap = s.gaps().get(0);
        assertEquals("B", gap.symbol());
        assertEquals(LocalDate.parse("2020-02-05"), gap.from());
        assertEquals(LocalDate.parse("2020-02-20"), gap.to());
        assertEquals(16, gap.sessions());

        // Deep inside the gap (beyond the 5-session fill) B must drop out of the ranking.
        int mid = 0;
        while (!s.dateAt(mid).equals(LocalDate.parse("2020-02-15"))) mid++;
        List<Momentum.Ranked> ranked = Momentum.rank(s, mid, 10, 2);
        assertEquals(1, ranked.size());
        assertEquals("A", ranked.get(0).symbol());
    }

    // ---- MomentumStats monthly compounding ----------------------------------

    @Test
    void monthlyReturnsCompoundTheDailySlice() {
        MomentumSeries s = series(new LinkedHashMap<>(Map.of("A", d -> 100 * Math.pow(1.01, d))), 91);
        MomentumConfig cfg = cfg(1);
        int start = s.monthEndIdx()[0];   // 2020-01-31
        RotationBacktester.Result r = RotationBacktester.run(
                s, cfg, RotationBacktester.topN(1), 1, start, s.size() - 1);
        MomentumStats st = MomentumStats.of(s, r, 0, s.size() - 1 - start, cfg.rfDaily(), null);

        // February has 29 daily bars of +1% each; entry cost sits in January's bar 0.
        assertEquals(Math.pow(1.01, 29) - 1, st.months().get("2020-02"), 1e-9);
        assertEquals(1 - cfg.costPerSide() - 1, st.months().get("2020-01"), 1e-12);
    }
}
