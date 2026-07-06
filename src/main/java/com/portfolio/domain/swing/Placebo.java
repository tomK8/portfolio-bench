package com.portfolio.domain.swing;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * §6 control statistics — does the flush trigger add anything over plain drift? Runs the identical
 * OHLC triple-barrier / time-stop simulation ({@link Flushes#simulateTrade}) with entries
 * <em>not</em> conditioned on a flush, so the flush-triggered numbers can be read against a null.
 *
 * <ul>
 *   <li><b>All-days placebo</b> — a hypothetical entry at <em>every</em> fully-evaluable session
 *       close in the window (overlaps allowed; this is a control statistic, not a portfolio). By
 *       construction it never censors: only entries with a full time-stop window ahead are taken, so
 *       {@code open == 0}. Reported with the same columns as §6.</li>
 *   <li><b>Matched random bootstrap</b> — draw {@code N} entry dates uniformly at random (without
 *       replacement) from that same pool, where {@code N} is the number of flush events in the
 *       window; run the simulation; repeat {@code bootstrapIterations} times. Reports the
 *       distribution of per-trade %TP-first and mean return (median, q1–q3, 5th–95th) and the
 *       percentile rank of the actual flush-triggered result within it. If the flush result sits
 *       inside the bootstrap's interquartile range, the trigger is contributing nothing beyond the
 *       regime drift.</li>
 * </ul>
 *
 * <p>The bootstrap draws from the precomputed all-days pool (each entry's outcome is deterministic),
 * so 1,000 iterations are cheap. The RNG is seeded from {@link SwingConfig#randomSeed()} (plus the
 * time stop and pool size) so a re-run reproduces the same figures.
 */
public final class Placebo {

    private Placebo() {
    }

    /** All-days control result for one time stop — same shape as a §6 {@link Flushes.BarrierResult}. */
    public static Flushes.BarrierResult allDays(List<SwingSeries> seriesList, LocalDate cutoff,
                                                SwingConfig cfg, int timeStop) {
        return allDays(seriesList, cutoff, cfg, timeStop, null);
    }

    /** All-days control, optionally excluding earnings-window entry dates (§D like-for-like). */
    public static Flushes.BarrierResult allDays(List<SwingSeries> seriesList, LocalDate cutoff,
                                                SwingConfig cfg, int timeStop, EventFilter exclude) {
        List<Flushes.Trade> pool = pool(seriesList, cutoff, cfg, timeStop, exclude);
        Set<LocalDate> dates = new HashSet<>();
        for (Flushes.Trade t : pool) dates.add(t.date());
        return Flushes.barrierResult(timeStop, pool, dates, cfg.stopLoss(), cfg);
    }

    /** Every fully-evaluable entry in the window (a full {@code timeStop} forward window ahead). */
    static List<Flushes.Trade> pool(List<SwingSeries> seriesList, LocalDate cutoff,
                                    SwingConfig cfg, int timeStop) {
        return pool(seriesList, cutoff, cfg, timeStop, null);
    }

    /** As {@link #pool}, dropping entries whose date is in {@code exclude} (§D). */
    static List<Flushes.Trade> pool(List<SwingSeries> seriesList, LocalDate cutoff,
                                    SwingConfig cfg, int timeStop, EventFilter exclude) {
        List<Flushes.Trade> out = new ArrayList<>();
        for (Flushes.MaTrade mt : poolMa(seriesList, cutoff, cfg, timeStop, exclude)) out.add(mt.trade());
        return out;
    }

    /** The all-days pool with each entry tagged by its 200d-MA state, for the §E within-state split. */
    static List<Flushes.MaTrade> poolMa(List<SwingSeries> seriesList, LocalDate cutoff,
                                        SwingConfig cfg, int timeStop, EventFilter exclude) {
        double tp = cfg.takeProfit(), sl = cfg.stopLoss();
        List<Flushes.MaTrade> pool = new ArrayList<>();
        for (SwingSeries s : seriesList) {
            int n = s.size();
            for (int i = Math.max(1, s.startIndex(cutoff)); i + timeStop <= n - 1; i++) {
                if (s.close[i] <= 0) continue;
                if (exclude != null && exclude.excludes(s.symbol(), s.dates[i])) continue;
                Flushes.Trade t = Flushes.simulateTrade(s, i, tp, sl, timeStop);
                if (t != null && t.outcome() != Flushes.Outcome.OPEN) pool.add(new Flushes.MaTrade(t, Flushes.aboveMa(s, i)));
            }
        }
        return pool;
    }

    /**
     * Matched random bootstrap for one time stop. {@code drawN} = the flush-event count in the
     * window; {@code actualTpPct}/{@code actualMeanReturn} are the flush-triggered figures whose
     * percentile rank within the bootstrap distribution is reported ({@code null} → rank omitted).
     */
    public static Bootstrap bootstrap(List<SwingSeries> seriesList, LocalDate cutoff, SwingConfig cfg,
                                      int timeStop, int drawN, Double actualTpPct, Double actualMeanReturn) {
        return bootstrap(seriesList, cutoff, cfg, timeStop, drawN, actualTpPct, actualMeanReturn, null);
    }

    /** As {@link #bootstrap}, drawing from the earnings-excluded pool when {@code exclude} is set (§D). */
    public static Bootstrap bootstrap(List<SwingSeries> seriesList, LocalDate cutoff, SwingConfig cfg,
                                      int timeStop, int drawN, Double actualTpPct, Double actualMeanReturn,
                                      EventFilter exclude) {
        return bootstrapFromPool(pool(seriesList, cutoff, cfg, timeStop, exclude), cfg,
                timeStop, drawN, actualTpPct, actualMeanReturn);
    }

    /**
     * Matched random bootstrap over a precomputed entry pool. Extracted so both the whole-window
     * control ({@link #bootstrap}) and the §E within-MA-state split can share the identical sampling
     * and percentile-rank logic. The RNG seed folds in {@code timeStop} and the pool size, so a
     * within-state pool (a different size) gets its own reproducible stream.
     */
    static Bootstrap bootstrapFromPool(List<Flushes.Trade> pool, SwingConfig cfg,
                                       int timeStop, int drawN, Double actualTpPct, Double actualMeanReturn) {
        int poolSize = pool.size();
        int iterations = cfg.bootstrapIterations();
        int sampleN = Math.min(drawN, poolSize);

        if (poolSize == 0 || sampleN == 0) {
            double nan = Double.NaN;
            return new Bootstrap(timeStop, iterations, drawN, poolSize, true,
                    nan, nan, nan, nan, nan, nan, nan, nan, nan, nan,
                    actualTpPct, actualMeanReturn, null, null);
        }

        double sl = cfg.stopLoss();
        double[] tpDist = new double[iterations];
        double[] retDist = new double[iterations];
        Random rng = new Random(cfg.randomSeed() + 1000L * timeStop + poolSize);
        int[] idx = new int[poolSize];
        for (int k = 0; k < poolSize; k++) idx[k] = k;

        for (int it = 0; it < iterations; it++) {
            // Partial Fisher–Yates: the first sampleN slots become a uniform sample without replacement.
            for (int k = 0; k < sampleN; k++) {
                int j = k + rng.nextInt(poolSize - k);
                int tmp = idx[k]; idx[k] = idx[j]; idx[j] = tmp;
            }
            List<Flushes.Trade> sample = new ArrayList<>(sampleN);
            for (int k = 0; k < sampleN; k++) sample.add(pool.get(idx[k]));
            Flushes.BarrierAgg a = Flushes.aggregate(sample, sl);
            tpDist[it] = a.pctTp();
            retDist[it] = a.meanReturn();
        }

        Double tpRank = actualTpPct == null ? null : percentileRank(tpDist, actualTpPct);
        Double retRank = actualMeanReturn == null ? null : percentileRank(retDist, actualMeanReturn);
        boolean smallN = sampleN < cfg.smallNFloor();
        return new Bootstrap(timeStop, iterations, drawN, poolSize, smallN,
                Stats.quantile(tpDist, 0.05), Stats.quantile(tpDist, 0.25), Stats.quantile(tpDist, 0.50),
                Stats.quantile(tpDist, 0.75), Stats.quantile(tpDist, 0.95),
                Stats.quantile(retDist, 0.05), Stats.quantile(retDist, 0.25), Stats.quantile(retDist, 0.50),
                Stats.quantile(retDist, 0.75), Stats.quantile(retDist, 0.95),
                actualTpPct, actualMeanReturn, tpRank, retRank);
    }

    /** Fraction of the (finite) distribution values ≤ {@code actual}. */
    private static double percentileRank(double[] dist, double actual) {
        int le = 0, n = 0;
        for (double v : dist) {
            if (Double.isNaN(v)) continue;
            n++;
            if (v <= actual) le++;
        }
        return n == 0 ? Double.NaN : (double) le / n;
    }

    // ---- DTO ----------------------------------------------------------------

    /**
     * Bootstrap distribution for one time stop. {@code drawN} entries were sampled from a pool of
     * {@code poolSize} eligible entries, {@code iterations} times. {@code tp*}/{@code ret*} are the
     * 5th/25th/50th/75th/95th percentiles of the per-iteration %TP-first and mean return.
     * {@code actualTpPct}/{@code actualMeanReturn} are the flush-triggered figures and
     * {@code *PercentileRank} their rank within the bootstrap (a rank inside ~0.25–0.75 means the
     * flush trigger is indistinguishable from random timing of the same count).
     */
    public record Bootstrap(int timeStop, int iterations, int drawN, int poolSize, boolean smallN,
                            double tpP05, double tpQ1, double tpMedian, double tpQ3, double tpP95,
                            double retP05, double retQ1, double retMedian, double retQ3, double retP95,
                            Double actualTpPct, Double actualMeanReturn,
                            Double tpPercentileRank, Double retPercentileRank) {
    }
}
