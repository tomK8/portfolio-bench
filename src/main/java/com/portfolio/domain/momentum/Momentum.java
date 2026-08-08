package com.portfolio.domain.momentum;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The 12-1 cross-sectional momentum ranking. At bar {@code i} a universe symbol's score is its
 * total return from {@code i − lookback} to {@code i − skip} (split- and dividend-adjusted
 * closes): {@code adj[i−skip] / adj[i−lookback] − 1}. The most recent {@code skip} sessions are
 * excluded because 1-month returns mean-revert.
 *
 * <p><b>Eligibility</b> at bar {@code i}: a price at {@code i} (real or forward-filled within the
 * allowed window — a longer gap is NaN and excludes the name that month) and prices at both
 * window endpoints {@code i−skip} and {@code i−lookback}. Ineligible names are excluded from the
 * ranking, never silently filled.
 */
public final class Momentum {

    /** One eligible symbol at a rebalance: its momentum score and rank (1 = strongest). */
    public record Ranked(int symbolIdx, String symbol, double score, int rank) {
    }

    private Momentum() {
    }

    /**
     * Ranked eligible universe at bar {@code i}, strongest first. Ties break alphabetically so the
     * ordering (and therefore every downstream result) is deterministic.
     */
    public static List<Ranked> rank(MomentumSeries s, int i, int lookback, int skip) {
        List<Ranked> out = new ArrayList<>();
        if (i - lookback < 0) return out;
        for (int idx = 0; idx < s.universeCount(); idx++) {
            double now = s.adj(idx, i);
            double at = s.adj(idx, i - skip);
            double from = s.adj(idx, i - lookback);
            if (Double.isNaN(now) || Double.isNaN(at) || Double.isNaN(from) || from <= 0) continue;
            out.add(new Ranked(idx, s.nameOf(idx), at / from - 1, 0));
        }
        out.sort(Comparator.comparingDouble((Ranked r) -> -r.score())
                .thenComparing(Ranked::symbol));
        List<Ranked> ranked = new ArrayList<>(out.size());
        for (int r = 0; r < out.size(); r++) {
            Ranked x = out.get(r);
            ranked.add(new Ranked(x.symbolIdx(), x.symbol(), x.score(), r + 1));
        }
        return ranked;
    }
}
