package com.portfolio.domain.range;

import com.portfolio.domain.model.PriceBar;
import com.portfolio.domain.swing.Stats;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One symbol's daily series pre-processed into the aligned primitive arrays the range engine
 * reads, plus the trailing percentile bands.
 *
 * <p><b>Conventions</b> (same as {@code SwingSeries}/{@code TrendSeries}): {@code close} is
 * Yahoo's split-adjusted, jump-free close — signals, barriers and MAE run on it because that is
 * what a real resting limit order sees. {@code adjClose} is the total-return index — all P&L and
 * end-of-data marks run on it so multi-year holds credit dividends (a barrier fill at price
 * {@code p} on bar {@code j} maps into TR space as {@code p × adjClose[j]/close[j]}, the same
 * convention {@code TrendSeries} uses for {@code adjOpen}). {@code high}/{@code low} fall back to
 * {@code close} when the source bar omits them.
 *
 * <p><b>Percentile bands are look-ahead-free</b>: {@code trailingLevels} computes each bar's
 * level over the {@code window} closes strictly <em>before</em> it (indices
 * {@code i−window … i−1}), so a crash day cannot drag its own floor down with it. {@code NaN}
 * until a full window of prior closes exists. Linear-interpolation quantile — the same rule as
 * {@link Stats#quantile}.
 *
 * <p><b>Data hygiene</b>: any |close-to-close| move above {@code maxDailyMove} is recorded as a
 * {@link DataError} — flagged, never repaired or dropped.
 */
public final class RangeSeries {

    private final String symbol;
    private final String currency;
    final LocalDate[] dates;
    final double[] close;
    final double[] adjClose;
    final double[] high;
    final double[] low;
    private final List<DataError> dataErrors;

    /** A daily move breaching the hygiene threshold — reported, never silently fixed. */
    public record DataError(LocalDate date, double movePct) {
    }

    private RangeSeries(String symbol, String currency, LocalDate[] dates, double[] close,
                        double[] adjClose, double[] high, double[] low, List<DataError> dataErrors) {
        this.symbol = symbol;
        this.currency = currency;
        this.dates = dates;
        this.close = close;
        this.adjClose = adjClose;
        this.high = high;
        this.low = low;
        this.dataErrors = dataErrors;
    }

    /** Build from ascending daily bars; {@code null} when there are too few bars to be useful. */
    public static RangeSeries of(String symbol, List<PriceBar> barsAscending, double maxDailyMove) {
        if (barsAscending == null || barsAscending.size() < 2) return null;
        int n = barsAscending.size();
        LocalDate[] dates = new LocalDate[n];
        double[] close = new double[n];
        double[] adjClose = new double[n];
        double[] high = new double[n];
        double[] low = new double[n];
        List<DataError> errors = new ArrayList<>();
        String currency = null;
        for (int i = 0; i < n; i++) {
            PriceBar b = barsAscending.get(i);
            dates[i] = b.date();
            close[i] = b.close();
            adjClose[i] = b.adjClose();
            high[i] = b.high() != null ? b.high() : b.close();
            low[i] = b.low() != null ? b.low() : b.close();
            if (currency == null && b.currency() != null) currency = b.currency();
            if (i > 0 && close[i - 1] > 0 && close[i] > 0) {
                double move = close[i] / close[i - 1] - 1.0;
                if (Math.abs(move) > maxDailyMove) errors.add(new DataError(b.date(), move));
            }
        }
        return new RangeSeries(symbol, currency, dates, close, adjClose, high, low, errors);
    }

    /**
     * Trailing percentile levels for each requested {@code p}, sharing one sorted window per bar.
     * {@code out[k][i]} = the {@code ps[k]} quantile of {@code close[i−window … i−1]};
     * {@code NaN} while fewer than {@code window} prior closes exist.
     */
    public double[][] trailingLevels(double[] ps, int window) {
        int n = close.length;
        double[][] out = new double[ps.length][n];
        for (double[] row : out) Arrays.fill(row, Double.NaN);
        double[] win = new double[window];
        for (int i = window; i < n; i++) {
            System.arraycopy(close, i - window, win, 0, window);
            double[] sorted = win.clone();
            Arrays.sort(sorted);
            for (int k = 0; k < ps.length; k++) {
                out[k][i] = sortedQuantile(sorted, ps[k]);
            }
        }
        return out;
    }

    /** Linear-interpolation quantile over a pre-sorted window (same rule as {@link Stats#quantile}). */
    private static double sortedQuantile(double[] sorted, double p) {
        int n = sorted.length;
        if (n == 1) return sorted[0];
        double idx = p * (n - 1);
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        if (lo == hi) return sorted[lo];
        double frac = idx - lo;
        return sorted[lo] * (1 - frac) + sorted[hi] * frac;
    }

    /** Fraction of the trailing {@code window} prior closes strictly below {@code close[i]}. */
    double percentileRankAt(int i, int window) {
        if (i < window) return Double.NaN;
        int below = 0;
        for (int j = i - window; j < i; j++) {
            if (close[j] < close[i]) below++;
        }
        return (double) below / window;
    }

    /** TR value basis of a fill at price {@code p} on bar {@code j} — {@code p × adj/close}. */
    double trBasis(int j, double fillPrice) {
        return fillPrice * adjClose[j] / close[j];
    }

    public int size() {
        return close.length;
    }

    public String symbol() {
        return symbol;
    }

    /** Yahoo listing currency, verbatim ({@code "GBp"} = pence for .L names). */
    public String currency() {
        return currency;
    }

    public LocalDate dateAt(int i) {
        return dates[i];
    }

    public LocalDate firstDate() {
        return dates[0];
    }

    public LocalDate lastDate() {
        return dates[dates.length - 1];
    }

    public double closeAt(int i) {
        return close[i];
    }

    public double adjCloseAt(int i) {
        return adjClose[i];
    }

    public List<DataError> dataErrors() {
        return dataErrors;
    }

    /** First bar index whose date is on or after {@code cutoff} (or {@code size()} if none). */
    public int firstIdxOnOrAfter(LocalDate cutoff) {
        for (int i = 0; i < dates.length; i++) {
            if (!dates[i].isBefore(cutoff)) return i;
        }
        return dates.length;
    }
}
