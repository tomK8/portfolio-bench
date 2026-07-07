package com.portfolio.domain.trend;

import com.portfolio.domain.swing.Stats;

import java.time.temporal.ChronoUnit;

/**
 * Headline statistics of one backtest run. All fractions are signed ({@code maxDrawdown} is
 * negative); the UI formats. {@code timeInvested} is the mean exposure over the window, so a
 * half-switch run that is "out" half the time reads 0.75, which is the honest exposure figure.
 * {@code sharpe} uses the same cash leg the backtest ran on as its risk-free rate.
 * {@code longestDrawdownMonths} is the longest peak-to-recovery span in calendar months
 * (30.44-day months); an unrecovered drawdown running into the end of the data counts at its
 * censored length rather than being dropped.
 */
public record TrendStats(
        double totalReturn,
        double cagr,
        double vol,
        double maxDrawdown,
        double longestDrawdownMonths,
        double timeInvested,
        int switches,
        int roundTrips,
        double sharpe) {

    public static TrendStats of(TrendSeries s, Backtester.Result r) {
        int start = r.start(), end = s.size() - 1;
        double[] equity = r.equity();

        double totalReturn = equity[end] / equity[start] - 1;
        long days = ChronoUnit.DAYS.between(s.dates[start], s.dates[end]);
        double cagr = days > 0 ? Math.pow(equity[end] / equity[start], 365.25 / days) - 1 : Double.NaN;

        double vol = Stats.stdevSample(slice(r.dailyRet(), start + 1, end)) * Math.sqrt(252);

        double maxDd = 0, longestDays = 0;
        double peak = equity[start];
        int peakIdx = start;
        for (int i = start + 1; i <= end; i++) {
            if (equity[i] >= peak) {
                longestDays = Math.max(longestDays, ChronoUnit.DAYS.between(s.dates[peakIdx], s.dates[i]));
                peak = equity[i];
                peakIdx = i;
            } else {
                maxDd = Math.min(maxDd, equity[i] / peak - 1);
            }
        }
        longestDays = Math.max(longestDays, ChronoUnit.DAYS.between(s.dates[peakIdx], s.dates[end]));

        double timeInvested = Stats.mean(slice(r.weight(), start, end));

        double[] excess = new double[end - start];
        for (int i = start + 1; i <= end; i++) {
            excess[i - start - 1] = r.dailyRet()[i] - cashDailyFor(r, s, i);
        }
        double sharpe = Stats.mean(excess) / Stats.stdevSample(excess) * Math.sqrt(252);

        return new TrendStats(totalReturn, cagr, vol, maxDd, longestDays / 30.44,
                timeInvested, r.trades().size(), r.roundTrips(), sharpe);
    }

    /**
     * The cash leg the run used is not carried on the result, so Sharpe reads the series'
     * historical cash — callers running zero/flat sensitivities get Sharpe vs the historical
     * short rate, which is the comparable convention across the sensitivity tables.
     */
    private static double cashDailyFor(Backtester.Result r, TrendSeries s, int i) {
        return s.cashDaily[i];
    }

    private static double[] slice(double[] a, int from, int toInclusive) {
        double[] out = new double[toInclusive - from + 1];
        System.arraycopy(a, from, out, 0, out.length);
        return out;
    }
}
