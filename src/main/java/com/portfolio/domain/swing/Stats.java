package com.portfolio.domain.swing;

import java.util.Arrays;

/**
 * Small pure descriptive-statistics helpers shared by the swing-strategy engines. Kept separate
 * from {@link com.portfolio.domain.PriceStats} (which is price-series specific) and deliberately
 * duplicated rather than reaching into {@code PortfolioRiskService}'s private mean/stdev — this
 * package must stay framework-free and self-contained.
 *
 * <p>All methods ignore {@code NaN} inputs so callers can pass a raw returns array with sentinel
 * gaps without pre-filtering. Every reducer returns {@code NaN} for an empty (or all-NaN) sample
 * so downstream code can map that to a JSON {@code null}.
 */
public final class Stats {

    private Stats() {
    }

    /** Count of finite (non-NaN) values. */
    public static int count(double[] a) {
        int n = 0;
        for (double v : a) if (!Double.isNaN(v)) n++;
        return n;
    }

    /** Arithmetic mean of the finite values; {@code NaN} if none. */
    public static double mean(double[] a) {
        double s = 0;
        int n = 0;
        for (double v : a) {
            if (Double.isNaN(v)) continue;
            s += v;
            n++;
        }
        return n == 0 ? Double.NaN : s / n;
    }

    /** Sample standard deviation (N−1 denominator) of the finite values; {@code NaN} if fewer than 2. */
    public static double stdevSample(double[] a) {
        double m = mean(a);
        if (Double.isNaN(m)) return Double.NaN;
        double s = 0;
        int n = 0;
        for (double v : a) {
            if (Double.isNaN(v)) continue;
            double d = v - m;
            s += d * d;
            n++;
        }
        return n < 2 ? Double.NaN : Math.sqrt(s / (n - 1));
    }

    /** Median (50th percentile, linear interpolation); {@code NaN} if empty. */
    public static double median(double[] a) {
        return percentile(finiteSorted(a), 0.5);
    }

    /** Fraction of finite values that are strictly greater than zero; {@code NaN} if none. */
    public static double fractionPositive(double[] a) {
        int n = 0, pos = 0;
        for (double v : a) {
            if (Double.isNaN(v)) continue;
            n++;
            if (v > 0) pos++;
        }
        return n == 0 ? Double.NaN : (double) pos / n;
    }

    /** Fraction of finite values ≤ {@code threshold}; {@code NaN} if none. */
    public static double fractionAtMost(double[] a, double threshold) {
        int n = 0, hit = 0;
        for (double v : a) {
            if (Double.isNaN(v)) continue;
            n++;
            if (v <= threshold) hit++;
        }
        return n == 0 ? Double.NaN : (double) hit / n;
    }

    /**
     * Five-number distribution summary (min, q1, median, q3, max) plus the mean, over the finite
     * values. Quartiles use the same linear-interpolation rule as {@link #median}. All fields
     * {@code NaN} when the sample is empty.
     */
    public static Dist dist(double[] a) {
        double[] s = finiteSorted(a);
        if (s.length == 0) {
            return new Dist(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        return new Dist(s.length,
                s[0],
                percentile(s, 0.25),
                percentile(s, 0.50),
                percentile(s, 0.75),
                s[s.length - 1],
                mean(a));
    }

    /**
     * Linear-interpolation quantile over the finite values of {@code a}. {@code p} in [0,1]
     * ({@code 0.05} = 5th percentile). {@code NaN} for an empty sample. Public so the bootstrap
     * engine can summarise its iteration distributions with the same rule the {@link Dist}
     * quartiles use.
     */
    public static double quantile(double[] a, double p) {
        return percentile(finiteSorted(a), p);
    }

    private static double[] finiteSorted(double[] a) {
        double[] tmp = new double[a.length];
        int n = 0;
        for (double v : a) if (!Double.isNaN(v)) tmp[n++] = v;
        double[] out = Arrays.copyOf(tmp, n);
        Arrays.sort(out);
        return out;
    }

    /** Linear-interpolation percentile over a pre-sorted finite array. {@code p} in [0,1]. */
    private static double percentile(double[] sorted, double p) {
        int n = sorted.length;
        if (n == 0) return Double.NaN;
        if (n == 1) return sorted[0];
        double idx = p * (n - 1);
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        if (lo == hi) return sorted[lo];
        double frac = idx - lo;
        return sorted[lo] * (1 - frac) + sorted[hi] * frac;
    }

    /** Distribution summary. {@code n} is the finite-sample size; all quantiles are {@code NaN} when empty. */
    public record Dist(int n, double min, double q1, double median, double q3, double max, double mean) {
    }
}
