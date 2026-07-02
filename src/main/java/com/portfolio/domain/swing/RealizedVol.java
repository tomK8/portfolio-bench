package com.portfolio.domain.swing;

import java.util.ArrayList;
import java.util.List;

/**
 * §1 — realized volatility. Sample daily σ of log returns over each trailing window, reported both
 * as the raw daily figure and annualised by {@code √252} (the same constant the portfolio Risk tab
 * and {@link com.portfolio.domain.PriceStats} use). Measured over the most recent {@code window}
 * log returns in the series. The 30-day daily σ is what anchors the σ-based triggers elsewhere.
 */
public final class RealizedVol {

    private static final double TRADING_DAYS = 252.0;

    private RealizedVol() {
    }

    public static List<VolStat> compute(SwingSeries s, int[] windows) {
        List<VolStat> out = new ArrayList<>();
        if (s == null) return out;
        for (int w : windows) {
            out.add(one(s, w));
        }
        return out;
    }

    private static VolStat one(SwingSeries s, int window) {
        int n = s.size();
        double[] win = new double[Math.min(window, n)];
        int filled = 0;
        for (int i = n - 1; i >= 1 && filled < window; i--) {
            double r = s.logRet[i];
            if (!Double.isNaN(r)) win[filled++] = r;
        }
        if (filled < 2) {
            return new VolStat(window, null, null, filled, true);
        }
        double daily = Stats.stdevSample(java.util.Arrays.copyOf(win, filled));
        double annual = daily * Math.sqrt(TRADING_DAYS);
        return new VolStat(window, daily, annual, filled, filled < window);
    }

    /**
     * Realized-vol figure for one trailing window. {@code dailySigma}/{@code annualSigma} are
     * fractions ({@code 0.02} = 2%/day). {@code n} is the number of returns actually used;
     * {@code smallN} flags a window that couldn't be fully filled.
     */
    public record VolStat(int window, Double dailySigma, Double annualSigma, int n, boolean smallN) {
    }
}
