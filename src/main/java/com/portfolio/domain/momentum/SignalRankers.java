package com.portfolio.domain.momentum;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The challenger signal rankers for the signal-speed comparison (Momentum signals tab).
 *
 * <p><b>Eligibility is held constant across variants.</b> Every ranker here requires the full
 * {@code eligibilityLookback} (252) sessions of history even when its scoring window is shorter —
 * otherwise a faster signal would also get a broader early universe and the comparison would
 * muddle signal speed with universe breadth. So V2 (6-1) ranks by the 126-session window among
 * exactly the names V1 considers, and V3's composite needs all three component windows anyway.
 */
public final class SignalRankers {

    private SignalRankers() {
    }

    /**
     * Single lookback/skip return ranking (V2 = 126/21), gated on {@code eligibilityLookback}
     * sessions of history. Ties break alphabetically, same as {@link Momentum#rank}.
     */
    public static Ranker lookbackSkip(int lookback, int skip, int eligibilityLookback) {
        return (s, i) -> {
            List<Momentum.Ranked> out = new ArrayList<>();
            if (i - Math.max(lookback, eligibilityLookback) < 0) return out;
            for (int idx = 0; idx < s.universeCount(); idx++) {
                double now = s.adj(idx, i);
                double at = s.adj(idx, i - skip);
                double from = s.adj(idx, i - lookback);
                double gate = s.adj(idx, i - eligibilityLookback);
                if (Double.isNaN(now) || Double.isNaN(at) || Double.isNaN(from)
                        || Double.isNaN(gate) || from <= 0) continue;
                out.add(new Momentum.Ranked(idx, s.nameOf(idx), at / from - 1, 0));
            }
            return sortAndRank(out);
        };
    }

    /**
     * V3: equal-weighted average of the cross-sectional z-scores of several lookback/skip returns
     * (3-1, 6-1, 12-1). Z-scores use the population stdev (÷N) over the eligible set at that
     * rebalance; a degenerate component (stdev ≈ 0) contributes 0 for every name rather than NaN.
     * Eligibility requires every component window endpoint — the longest window is the binding
     * constraint, so the eligible set matches V1's exactly.
     */
    public static Ranker compositeZ(int[] lookbacks, int skip) {
        return (s, i) -> {
            int max = 0;
            for (int lb : lookbacks) max = Math.max(max, lb);
            if (i - max < 0) return List.of();

            List<Integer> idxs = new ArrayList<>();
            List<double[]> rets = new ArrayList<>();   // per eligible name, per component
            outer:
            for (int idx = 0; idx < s.universeCount(); idx++) {
                double now = s.adj(idx, i);
                double at = s.adj(idx, i - skip);
                if (Double.isNaN(now) || Double.isNaN(at)) continue;
                double[] r = new double[lookbacks.length];
                for (int c = 0; c < lookbacks.length; c++) {
                    double from = s.adj(idx, i - lookbacks[c]);
                    if (Double.isNaN(from) || from <= 0) continue outer;
                    r[c] = at / from - 1;
                }
                idxs.add(idx);
                rets.add(r);
            }
            int n = idxs.size();
            if (n == 0) return List.of();

            double[] composite = new double[n];
            for (int c = 0; c < lookbacks.length; c++) {
                double sum = 0;
                for (double[] r : rets) sum += r[c];
                double mean = sum / n;
                double sq = 0;
                for (double[] r : rets) sq += (r[c] - mean) * (r[c] - mean);
                double sd = Math.sqrt(sq / n);
                if (sd < 1e-12) continue;                     // degenerate component → z = 0
                for (int k = 0; k < n; k++) composite[k] += (rets.get(k)[c] - mean) / sd;
            }
            List<Momentum.Ranked> out = new ArrayList<>(n);
            for (int k = 0; k < n; k++) {
                out.add(new Momentum.Ranked(idxs.get(k), s.nameOf(idxs.get(k)),
                        composite[k] / lookbacks.length, 0));
            }
            return sortAndRank(out);
        };
    }

    /** Sort strongest-first with alphabetical tie-break and assign 1-based ranks. */
    private static List<Momentum.Ranked> sortAndRank(List<Momentum.Ranked> out) {
        out.sort(Comparator.comparingDouble((Momentum.Ranked r) -> -r.score())
                .thenComparing(Momentum.Ranked::symbol));
        List<Momentum.Ranked> ranked = new ArrayList<>(out.size());
        for (int r = 0; r < out.size(); r++) {
            Momentum.Ranked x = out.get(r);
            ranked.add(new Momentum.Ranked(x.symbolIdx(), x.symbol(), x.score(), r + 1));
        }
        return ranked;
    }
}
