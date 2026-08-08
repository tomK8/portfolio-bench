package com.portfolio.domain.trend;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@link TrendSignal} target schedule into an equity curve, daily returns, exposures and
 * a fill list. One engine serves every grid cell, both controls and the bootstrap, so a
 * conclusion can never come from two subtly different accounting paths.
 *
 * <p><b>Conventions.</b> The backtest starts at {@code start} with the position {@code
 * target[start]} taken at that close, cost-free — buy-and-hold pays the same initial entry, so
 * the comparison is unaffected. A state change at close {@code i} fills at that close
 * ({@link Execution#CLOSE}, the idealised case) or at the next day's open
 * ({@link Execution#NEXT_OPEN}, the realistic case — the overnight leg stays at the old weight).
 * All market legs run on the total-return series ({@code adjClose}/{@code adjOpen}); the
 * out-of-market weight earns {@code cashDaily}; each fill costs {@code costPerSide × |Δweight|}
 * of equity. A decision on the final bar has no next open and stays unfilled (surfaced by the
 * live-state readout, never silently executed). On {@code NEXT_OPEN} switch days the day's cash
 * accrues against the new weight for the whole day — a ≤1-day approximation on ~2% annual rates,
 * immaterial next to the fill-price difference being measured.
 *
 * <p>Arrays are full series length; entries before {@code start} are {@code NaN} (equity 1.0) so
 * misuse is loud. {@code weight[i]} is the exposure held during day {@code i}'s intraday session;
 * {@code dailyRet[i]} is the cost-inclusive equity change into bar {@code i}.
 */
public final class Backtester {

    public enum Execution { CLOSE, NEXT_OPEN }

    /**
     * @param execution   fill timing for state changes
     * @param outWeight   invested weight while "out" (0 = full switch, 0.5 = half switch)
     * @param costPerSide switch cost as an equity fraction per side (10 bps → 0.001)
     * @param cashDaily   per-bar cash-leg return (the series' historical array, a zeros array for
     *                    the 0%-rate sensitivity, or a flat-rate array)
     */
    public record Params(Execution execution, double outWeight, double costPerSide, double[] cashDaily) {
    }

    /**
     * One fill. {@code signalIndex} is the bar whose close decided the switch; {@code fillIndex}
     * the bar it executed on (same bar for CLOSE, next bar for NEXT_OPEN). {@code fillAdj} is the
     * total-return fill used by all P&amp;L math; {@code fillDisplay} the human price (raw close,
     * or the raw open recovered from {@code adjOpen}).
     */
    public record Trade(int signalIndex, int fillIndex, boolean entering,
                        double fillAdj, double fillDisplay, double weightBefore, double weightAfter) {
    }

    public record Result(int start, double[] equity, double[] dailyRet, double[] weight, List<Trade> trades) {

        /** Completed exit→re-entry pairs; a leading entry (started out) or a trailing exit doesn't count. */
        public int roundTrips() {
            int trips = 0;
            boolean pendingExit = false;
            for (Trade t : trades) {
                if (!t.entering()) pendingExit = true;
                else if (pendingExit) { trips++; pendingExit = false; }
            }
            return trips;
        }
    }

    private Backtester() {
    }

    /** All-in schedule for the buy-and-hold control. */
    public static boolean[] buyAndHold(int n) {
        boolean[] t = new boolean[n];
        java.util.Arrays.fill(t, true);
        return t;
    }

    public static Result run(TrendSeries s, boolean[] target, int start, Params p) {
        int n = s.size();
        if (target.length != n) throw new IllegalArgumentException("target length != series length");
        if (start < 0 || start >= n - 1) throw new IllegalArgumentException("start out of range: " + start);

        double[] equity = new double[n];
        double[] dailyRet = new double[n];
        double[] weight = new double[n];
        List<Trade> trades = new ArrayList<>();
        for (int i = 0; i < start; i++) {
            equity[i] = 1.0;
            dailyRet[i] = Double.NaN;
            weight[i] = Double.NaN;
        }
        dailyRet[start] = Double.NaN;
        equity[start] = 1.0;
        weight[start] = w(target[start], p);

        for (int i = start + 1; i < n; i++) {
            double e;
            if (p.execution() == Execution.CLOSE) {
                double wPrev = w(target[i - 1], p);
                double mret = s.adjClose[i] / s.adjClose[i - 1] - 1;
                e = equity[i - 1] * (1 + wPrev * mret + (1 - wPrev) * p.cashDaily()[i]);
                weight[i] = wPrev;
                if (target[i] != target[i - 1]) {
                    double wNew = w(target[i], p);
                    e *= 1 - p.costPerSide() * Math.abs(wNew - wPrev);
                    trades.add(new Trade(i, i, target[i], s.adjClose[i], s.close[i], wPrev, wNew));
                }
            } else {
                double wOvernight = w(target[Math.max(i - 2, start)], p);
                double wIntraday = w(target[i - 1], p);
                double overnight = s.adjOpen[i] / s.adjClose[i - 1] - 1;
                double intraday = s.adjClose[i] / s.adjOpen[i] - 1;
                e = equity[i - 1]
                        * (1 + wOvernight * overnight)
                        * (1 + wIntraday * intraday + (1 - wIntraday) * p.cashDaily()[i]);
                weight[i] = wIntraday;
                if (wIntraday != wOvernight) {
                    e *= 1 - p.costPerSide() * Math.abs(wIntraday - wOvernight);
                    double rawOpen = s.adjOpen[i] * s.close[i] / s.adjClose[i];
                    trades.add(new Trade(i - 1, i, target[i - 1], s.adjOpen[i], rawOpen, wOvernight, wIntraday));
                }
            }
            equity[i] = e;
            dailyRet[i] = e / equity[i - 1] - 1;
        }
        return new Result(start, equity, dailyRet, weight, trades);
    }

    private static double w(boolean invested, Params p) {
        return invested ? 1.0 : p.outWeight();
    }
}
