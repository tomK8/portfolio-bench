package com.portfolio.domain.momentum;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The single accounting path for every momentum variant, control and bootstrap iteration —
 * top-N, bottom-N (reversal sanity check), equal-weight baseline and random-N all run through
 * {@link #run}, differing only in their {@link Selector}. No variant gets its own P&amp;L math.
 *
 * <p><b>Rebalance mechanics.</b> On each completed-month-end bar (at that close): rank the
 * eligible universe, select, set target weights — {@code 1/slots} per chosen name, remainder in
 * cash when fewer than {@code slots} qualify; the EW baseline passes {@code slots = null} for
 * {@code 1/chosen}. A kept name is re-equalised to {@code 1/slots}, so it pays only the
 * drift-correction cost (tiny; membership churn dominates). Cost = {@code costPerSide × Σ|Δw|}
 * over equity legs (cash moves are free). Turnover = {@code Σ|Δw| / 2} (one-sided fraction of
 * the portfolio replaced). The initial entry at the analysis start is charged like any other
 * rebalance — every variant and control pays it identically, so comparisons are unaffected.
 *
 * <p><b>Between rebalances</b> weights drift with each name's total return; the cash slot earns
 * the flat rf daily. A held name whose price goes NaN mid-hold (gap beyond the forward-fill
 * window) is frozen (0 return) until it prices again or is sold — visible in the gap log, never
 * silently dropped.
 *
 * <p><b>Common analysis window:</b> {@link #analysisStart} picks the first month-end with
 * ≥ {@code minEligibleFactor × max(topNs)} eligible names, and every variant, control and
 * bootstrap iteration starts there — no variant gets a head start.
 */
public final class RotationBacktester {

    /** Chooses which of the ranked-eligible names to hold; ranked is strongest-first. */
    public interface Selector {
        List<Momentum.Ranked> select(List<Momentum.Ranked> ranked);
    }

    /** Everything that happened at one rebalance, for the event log. */
    public record RebalanceEvent(LocalDate date, int eligible, List<String> in, List<String> out,
                                 List<Momentum.Ranked> held, double turnover, double cost,
                                 double cashWeight) {
    }

    /**
     * A trailing-stop exit (V5 overlay): the holding closed at or below {@code peak × (1 − stop)}
     * and was sold at that close. {@code drawdownPct} is {@code close/peak − 1} (split-adjusted
     * closes — what a real stop order watches); the freed weight sat in cash until the next
     * rebalance. {@code barIdx} is the series bar of the exit, for TR-space whipsaw accounting.
     */
    public record StopEvent(LocalDate date, int barIdx, String symbol, double weightSold,
                            double cost, double close, double peak, double drawdownPct) {
    }

    /**
     * Daily results from {@code startIdx} to {@code endIdx}. {@code equity[k]} / {@code dailyRet[k]}
     * refer to series bar {@code startIdx + k}. The initial entry's cost is folded into bar 0
     * ({@code dailyRet[0] = −cost}, {@code equity[0] = 1 − cost}), so window returns compound
     * cleanly from a base of 1 and fit/holdout slices partition exactly. {@code stops} is empty
     * unless the run had a trailing-stop overlay.
     */
    public record Result(int startIdx, double[] equity, double[] dailyRet,
                         List<RebalanceEvent> events, List<StopEvent> stops) {
    }

    private RotationBacktester() {
    }

    /** Hold the {@code n} strongest (or all eligible when fewer than {@code n} qualify). */
    public static Selector topN(int n) {
        return ranked -> ranked.subList(0, Math.min(n, ranked.size()));
    }

    /** Hold the {@code n} weakest — the reversed-signal sanity check. */
    public static Selector bottomN(int n) {
        return ranked -> ranked.subList(Math.max(0, ranked.size() - n), ranked.size());
    }

    /**
     * Top-{@code n} with an absolute gate (V4): a selected name is only held if its own score
     * exceeds {@code minScore} (the rf accrual over the momentum window). A failing slot stays in
     * cash — the caller passes {@code slots = n} so the shortfall isn't redistributed.
     */
    public static Selector gatedTopN(int n, double minScore) {
        return ranked -> {
            List<Momentum.Ranked> out = new ArrayList<>();
            for (Momentum.Ranked r : ranked.subList(0, Math.min(n, ranked.size()))) {
                if (r.score() > minScore) out.add(r);
            }
            return out;
        };
    }

    /** Hold every eligible name — the equal-weight universe baseline. */
    public static Selector allEligible() {
        return ranked -> ranked;
    }

    /** Hold {@code n} names drawn uniformly from the eligible set — the matched bootstrap control. */
    public static Selector randomN(int n, Random rng) {
        return ranked -> {
            if (ranked.size() <= n) return ranked;
            List<Momentum.Ranked> copy = new ArrayList<>(ranked);
            Collections.shuffle(copy, rng);
            return copy.subList(0, n);
        };
    }

    /**
     * First completed-month-end bar where ≥ {@code minEligibleFactor × max(topNs)} names are
     * rank-eligible; -1 when the data never gets there. Shared by every variant and control.
     */
    public static int analysisStart(MomentumSeries s, MomentumConfig cfg) {
        int maxN = 0;
        for (int n : cfg.topNs()) maxN = Math.max(maxN, n);
        int need = cfg.minEligibleFactor() * maxN;
        for (int i : s.monthEndIdx()) {
            if (Momentum.rank(s, i, cfg.lookbackSessions(), cfg.skipSessions()).size() >= need) return i;
        }
        return -1;
    }

    /**
     * Buy-and-hold of a single symbol (benchmarks) as a {@link Result} so the same
     * {@link MomentumStats} path serves it. Days where the symbol has no price return 0.
     */
    public static Result buyAndHold(MomentumSeries s, int symbolIdx, int startIdx, int endIdx) {
        int n = endIdx - startIdx + 1;
        double[] equity = new double[n];
        double[] dailyRet = new double[n];
        double eq = 1;
        equity[0] = 1;
        for (int i = startIdx + 1; i <= endIdx; i++) {
            double p0 = s.adj(symbolIdx, i - 1), p1 = s.adj(symbolIdx, i);
            double r = (Double.isNaN(p0) || Double.isNaN(p1) || p0 <= 0) ? 0 : p1 / p0 - 1;
            eq *= 1 + r;
            dailyRet[i - startIdx] = r;
            equity[i - startIdx] = eq;
        }
        return new Result(startIdx, equity, dailyRet, List.of(), List.of());
    }

    /**
     * Run one full backtest with the incumbent signal (12-1 from the config) and no stop overlay —
     * the original entry point; every existing caller (variants, controls, bootstrap) routes here.
     */
    public static Result run(MomentumSeries s, MomentumConfig cfg, Selector selector,
                             Integer slots, int startIdx, int endIdx) {
        return run(s, cfg, Ranker.incumbent(cfg), selector, slots, startIdx, endIdx, null);
    }

    /**
     * Run one full backtest. {@code slots} is the intended portfolio size N (chosen names get
     * {@code 1/N} each, shortfall stays in cash); {@code null} means equal-weight across all
     * chosen (the EW baseline). {@code endIdx} caps the run (inclusive) — pass
     * {@code s.size() - 1} for the full window; the holdout-separation test relies on a truncated
     * run matching the full run over the shared window.
     *
     * <p>{@code trailingStopPct} (nullable) is the V5 overlay: between rebalances, a holding whose
     * split-adjusted close falls to or below {@code (1 − stop) × its highest close since entry} is
     * sold at that close (cost charged on the weight sold), the slot idling in cash until the next
     * rebalance. On a rebalance day the rebalance supersedes — no separate stop trade is booked.
     * The peak includes the entry close and resets on every fresh entry, so a stopped name that
     * re-ranks top-N later re-enters with a new peak (whipsaw cost is part of what's measured).
     */
    public static Result run(MomentumSeries s, MomentumConfig cfg, Ranker ranker, Selector selector,
                             Integer slots, int startIdx, int endIdx, Double trailingStopPct) {
        int n = endIdx - startIdx + 1;
        double[] equity = new double[n];
        double[] dailyRet = new double[n];
        List<RebalanceEvent> events = new ArrayList<>();
        List<StopEvent> stops = new ArrayList<>();

        Set<Integer> rebalanceIdx = new LinkedHashSet<>();
        for (int i : s.monthEndIdx()) if (i >= startIdx && i <= endIdx) rebalanceIdx.add(i);

        int m = s.universeCount();
        double[] w = new double[m];
        double[] peak = new double[m];   // highest close since entry, NaN when not held
        java.util.Arrays.fill(peak, Double.NaN);
        double cash = 1.0;
        double eq = 1.0;
        equity[0] = 1.0;
        Set<Integer> held = new LinkedHashSet<>();
        double rf = cfg.rfDaily();
        double costPerSide = cfg.costPerSide();

        for (int i = startIdx; i <= endIdx; i++) {
            if (i > startIdx) {
                // Drift: each held name moves with its total return, cash earns rf.
                double dayRet = cash * rf;
                double[] grown = new double[m];
                double grownCash = cash * (1 + rf);
                double total = grownCash;
                for (int idx : held) {
                    double p0 = s.adj(idx, i - 1), p1 = s.adj(idx, i);
                    double r = (Double.isNaN(p0) || Double.isNaN(p1) || p0 <= 0) ? 0 : p1 / p0 - 1;
                    dayRet += w[idx] * r;
                    grown[idx] = w[idx] * (1 + r);
                    total += grown[idx];
                }
                eq *= 1 + dayRet;
                dailyRet[i - startIdx] = dayRet;
                for (int idx : held) w[idx] = grown[idx] / total;
                cash = grownCash / total;
            }

            boolean rebalanceDay = rebalanceIdx.contains(i);

            if (trailingStopPct != null && i > startIdx && !held.isEmpty()) {
                // Peaks absorb every session's close (rebalance days included); the trigger check
                // is skipped on rebalance days — there the ranking decides.
                for (int idx : held) {
                    double c = s.close(idx, i);
                    if (Double.isNaN(c)) continue;   // frozen name: no price to read today
                    if (Double.isNaN(peak[idx]) || c > peak[idx]) peak[idx] = c;
                }
                if (!rebalanceDay) {
                    List<Integer> triggered = new ArrayList<>();
                    for (int idx : held) {
                        double c = s.close(idx, i);
                        if (Double.isNaN(c) || Double.isNaN(peak[idx])) continue;
                        if (c <= peak[idx] * (1 - trailingStopPct)) triggered.add(idx);
                    }
                    double soldWeight = 0;
                    for (int idx : triggered) {
                        double c = s.close(idx, i);
                        stops.add(new StopEvent(s.dateAt(i), i, s.nameOf(idx), w[idx],
                                costPerSide * w[idx], c, peak[idx], c / peak[idx] - 1));
                        soldWeight += w[idx];
                        cash += w[idx];
                        w[idx] = 0;
                        peak[idx] = Double.NaN;
                        held.remove(idx);
                    }
                    if (soldWeight > 0) {
                        double cost = costPerSide * soldWeight;
                        eq *= 1 - cost;
                        dailyRet[i - startIdx] = (1 + dailyRet[i - startIdx]) * (1 - cost) - 1;
                    }
                }
            }

            if (rebalanceDay) {
                List<Momentum.Ranked> ranked = ranker.rank(s, i);
                List<Momentum.Ranked> chosen = selector.select(ranked);
                double perName = chosen.isEmpty() ? 0
                        : 1.0 / (slots == null ? chosen.size() : Math.max(slots, chosen.size()));

                double[] target = new double[m];
                double invested = 0;
                for (Momentum.Ranked r : chosen) {
                    target[r.symbolIdx()] = perName;
                    invested += perName;
                }

                double deltaSum = 0;
                for (int idx = 0; idx < m; idx++) deltaSum += Math.abs(target[idx] - w[idx]);
                double cost = costPerSide * deltaSum;
                eq *= 1 - cost;

                List<String> in = new ArrayList<>(), out = new ArrayList<>();
                Set<Integer> newHeld = new LinkedHashSet<>();
                for (Momentum.Ranked r : chosen) {
                    newHeld.add(r.symbolIdx());
                    if (!held.contains(r.symbolIdx())) in.add(r.symbol());
                }
                for (int idx : held) if (!newHeld.contains(idx)) out.add(s.nameOf(idx));

                if (trailingStopPct != null) {
                    // Fresh entries start their peak at the entry close; exits release theirs.
                    // Kept names keep the peak they've been accumulating.
                    for (int idx : newHeld) if (!held.contains(idx)) peak[idx] = s.close(idx, i);
                    for (int idx : held) if (!newHeld.contains(idx)) peak[idx] = Double.NaN;
                }

                events.add(new RebalanceEvent(s.dateAt(i), ranked.size(), in, out,
                        List.copyOf(chosen), deltaSum / 2, cost, 1 - invested));

                w = target;
                cash = 1 - invested;
                held = newHeld;
                // Fold the switch cost into the day's return so equity and dailyRet stay consistent.
                dailyRet[i - startIdx] = (1 + dailyRet[i - startIdx]) * (1 - cost) - 1;
            }

            equity[i - startIdx] = eq;
        }
        return new Result(startIdx, equity, dailyRet, events, stops);
    }
}
