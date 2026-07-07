package com.portfolio.domain.momentum;

import com.portfolio.domain.swing.Stats;

import java.util.Random;

/**
 * The matched random-selection bootstrap — the exercise's core control. Each iteration picks N
 * names uniformly at random from the <em>same eligible universe</em> at each rebalance, under
 * identical costs and rules (same {@link RotationBacktester} path), destroying only the momentum
 * ranking. The distribution of CAGR / Sharpe / max drawdown across iterations is what "random
 * selection at this turnover" achieves; the actual variant's percentile inside it is the verdict
 * (inside the IQR = indistinguishable from random picking, same convention as the flush
 * harness's §6 bootstrap).
 *
 * <p>Runs on the <b>fit window only</b> — the holdout never feeds the control. Seeded per variant
 * ({@code seed + N}) so a re-run reproduces the same figures; the seed is reported.
 *
 * <p>Percentiles are the fraction of bootstrap values ≤ the actual value: high CAGR/Sharpe
 * percentile = better than random; for max drawdown (negative numbers) a <em>low</em> percentile
 * means the actual rule drew down worse than most random picks.
 */
public record RotationBootstrap(
        int iterations,
        long seed,
        Stats.Dist cagr,
        Stats.Dist sharpe,
        Stats.Dist maxDrawdown,
        double cagrPercentile,
        double sharpePercentile,
        double maxDrawdownPercentile) {

    public static RotationBootstrap run(MomentumSeries s, MomentumConfig cfg, int n,
                                        int startIdx, int fitEndIdx, MomentumStats actual) {
        long seed = cfg.randomSeed() + n;
        Random rng = new Random(seed);
        int iters = cfg.bootstrapIterations();
        double[] cagrs = new double[iters];
        double[] sharpes = new double[iters];
        double[] dds = new double[iters];
        for (int it = 0; it < iters; it++) {
            RotationBacktester.Result r = RotationBacktester.run(
                    s, cfg, RotationBacktester.randomN(n, rng), n, startIdx, fitEndIdx);
            MomentumStats st = MomentumStats.of(s, r, 0, fitEndIdx - startIdx, cfg.rfDaily(), null);
            cagrs[it] = st.cagr();
            sharpes[it] = st.sharpe();
            dds[it] = st.maxDrawdown();
        }
        return new RotationBootstrap(iters, seed,
                Stats.dist(cagrs), Stats.dist(sharpes), Stats.dist(dds),
                Stats.fractionAtMost(cagrs, actual.cagr()),
                Stats.fractionAtMost(sharpes, actual.sharpe()),
                Stats.fractionAtMost(dds, actual.maxDrawdown()));
    }

    /** Inside the interquartile range on CAGR — the "indistinguishable from random" dimming cue. */
    public boolean cagrInsideIqr() {
        return cagrPercentile >= 0.25 && cagrPercentile <= 0.75;
    }
}
