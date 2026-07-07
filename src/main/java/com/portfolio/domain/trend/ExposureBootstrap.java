package com.portfolio.domain.trend;

import com.portfolio.domain.swing.Stats;

import java.util.Random;

/**
 * The matched random-exposure control — the trend analogue of the flush module's placebo. Take
 * the strategy's actual out-segment lengths, shuffle them, and scatter them uniformly across the
 * window (random composition of the in-day gaps), preserving the overall time-invested fraction
 * and the switch count while destroying the <em>timing</em>. Each random schedule runs through
 * the identical {@link Backtester}. If the MA rule's CAGR sits inside this distribution's IQR,
 * being out of the market some fraction of the time explains the result; the exits' timing adds
 * nothing.
 *
 * <p>Percentiles are the fraction of random schedules the actual result beats: for CAGR higher
 * is better; for max drawdown a higher percentile means a <em>shallower</em> (less negative)
 * drawdown than that fraction of schedules. A strategy that never exits has no out-segments to
 * scatter — {@link #run} returns {@code null} and the UI must say so rather than show an empty
 * distribution.
 */
public final class ExposureBootstrap {

    public record Result(int iterations, int outSegments, double timeInvested,
                         Stats.Dist cagrDist, Stats.Dist maxDdDist,
                         double cagrPercentile, double maxDdPercentile) {
    }

    private ExposureBootstrap() {
    }

    public static Result run(TrendSeries s, boolean[] actualTarget, int start, Backtester.Params p,
                             TrendStats actual, int iterations, long seed) {
        int n = s.size();
        // Out-segment lengths over [start, n).
        java.util.List<Integer> segments = new java.util.ArrayList<>();
        int run = 0;
        for (int i = start; i < n; i++) {
            if (!actualTarget[i]) run++;
            else if (run > 0) { segments.add(run); run = 0; }
        }
        if (run > 0) segments.add(run);
        if (segments.isEmpty()) return null;

        int window = n - start;
        int totalOut = segments.stream().mapToInt(Integer::intValue).sum();
        int totalIn = window - totalOut;
        int k = segments.size();

        Random rng = new Random(seed);
        double[] cagrs = new double[iterations];
        double[] maxDds = new double[iterations];
        boolean[] schedule = new boolean[n];

        for (int it = 0; it < iterations; it++) {
            java.util.Collections.shuffle(segments, rng);
            // k+1 in-gaps summing to totalIn: k sorted uniform cuts in [0, totalIn].
            int[] cuts = new int[k + 2];
            for (int c = 1; c <= k; c++) cuts[c] = rng.nextInt(totalIn + 1);
            cuts[k + 1] = totalIn;
            java.util.Arrays.sort(cuts);

            int pos = start;
            java.util.Arrays.fill(schedule, start, n, true);
            for (int seg = 0; seg < k; seg++) {
                pos += cuts[seg + 1] - cuts[seg];           // in-gap before this out-segment
                int len = segments.get(seg);
                java.util.Arrays.fill(schedule, pos, pos + len, false);
                pos += len;
            }

            Backtester.Result r = Backtester.run(s, schedule, start, p);
            double[] cd = cagrAndMaxDd(s, r);
            cagrs[it] = cd[0];
            maxDds[it] = cd[1];
        }

        return new Result(iterations, k, (double) totalIn / window,
                Stats.dist(cagrs), Stats.dist(maxDds),
                percentileBeaten(cagrs, actual.cagr()),
                percentileBeaten(maxDds, actual.maxDrawdown()));
    }

    /** One-pass CAGR + max drawdown, so the 1000-iteration loop skips the full stats build. */
    private static double[] cagrAndMaxDd(TrendSeries s, Backtester.Result r) {
        int start = r.start(), end = s.size() - 1;
        double[] eq = r.equity();
        double peak = eq[start], maxDd = 0;
        for (int i = start + 1; i <= end; i++) {
            if (eq[i] >= peak) peak = eq[i];
            else maxDd = Math.min(maxDd, eq[i] / peak - 1);
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(s.dates[start], s.dates[end]);
        double cagr = days > 0 ? Math.pow(eq[end] / eq[start], 365.25 / days) - 1 : Double.NaN;
        return new double[]{cagr, maxDd};
    }

    /** Percent of iteration values strictly below {@code actual} (ties count half). */
    private static double percentileBeaten(double[] values, double actual) {
        int below = 0, ties = 0;
        for (double v : values) {
            if (v < actual) below++;
            else if (v == actual) ties++;
        }
        return 100.0 * (below + ties / 2.0) / values.length;
    }
}
