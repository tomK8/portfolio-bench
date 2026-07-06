package com.portfolio.domain.swing;

import com.portfolio.domain.model.PriceBar;

import java.time.LocalDate;
import java.util.List;

/**
 * One symbol's daily price series, pre-processed into the aligned primitive arrays every swing
 * engine reads. Built once per symbol per report and shared, so the return/σ maths is computed a
 * single time.
 *
 * <p>Valued on {@code close} (Yahoo's split-adjusted, jump-free close) — the same convention as
 * {@link com.portfolio.domain.PriceStats}: these are price-behaviour signals for timing a trade,
 * not total-return figures, so dividends are deliberately excluded. Index {@code i} of every
 * array refers to the same bar. {@code logRet[i]}/{@code simpleRet[i]} are the returns
 * <em>into</em> bar {@code i} (from {@code i−1}); index 0 is {@link Double#NaN}. {@code high}/
 * {@code low} fall back to {@code close} when the source bar omits them.
 *
 * <p>{@code trailSigma[i]} is the sample daily σ of the {@code sigmaWindow} log returns ending
 * <em>strictly before</em> bar {@code i} (indices {@code i−sigmaWindow … i−1}). Anchoring the σ
 * on the prior close makes the σ-based triggers look-ahead-free: bar {@code i}'s move is judged
 * against volatility that was already known going into the session. {@code NaN} until a full
 * window of prior returns exists.
 *
 * <p>{@code sma[i]} is the trailing simple moving average of {@code close} over {@code maWindow}
 * sessions (indices {@code i−maWindow+1 … i}); {@code NaN} until a full window exists. It anchors
 * the §A/§E 200d-MA regime flag ("was the entry above or below its long-term trend?").
 */
public final class SwingSeries {

    final String symbol;
    final LocalDate[] dates;
    final double[] close;
    final double[] adjClose;
    final double[] high;
    final double[] low;
    final double[] logRet;
    final double[] simpleRet;
    final double[] trailSigma;
    final double[] sma;

    private SwingSeries(String symbol, LocalDate[] dates, double[] close, double[] adjClose,
                        double[] high, double[] low, double[] logRet, double[] simpleRet,
                        double[] trailSigma, double[] sma) {
        this.symbol = symbol;
        this.dates = dates;
        this.close = close;
        this.adjClose = adjClose;
        this.high = high;
        this.low = low;
        this.logRet = logRet;
        this.simpleRet = simpleRet;
        this.trailSigma = trailSigma;
        this.sma = sma;
    }

    /** Build with the default 200-session MA window. */
    public static SwingSeries of(String symbol, List<PriceBar> barsAscending, int sigmaWindow) {
        return of(symbol, barsAscending, sigmaWindow, 200);
    }

    /** Build from ascending daily bars. Returns {@code null} when there are too few bars to be useful. */
    public static SwingSeries of(String symbol, List<PriceBar> barsAscending, int sigmaWindow, int maWindow) {
        if (barsAscending == null || barsAscending.size() < 2) return null;
        int n = barsAscending.size();
        LocalDate[] dates = new LocalDate[n];
        double[] close = new double[n];
        double[] adjClose = new double[n];
        double[] high = new double[n];
        double[] low = new double[n];
        double[] logRet = new double[n];
        double[] simpleRet = new double[n];
        for (int i = 0; i < n; i++) {
            PriceBar b = barsAscending.get(i);
            dates[i] = b.date();
            close[i] = b.close();
            adjClose[i] = b.adjClose();
            high[i] = b.high() != null ? b.high() : b.close();
            low[i] = b.low() != null ? b.low() : b.close();
            if (i == 0) {
                logRet[i] = Double.NaN;
                simpleRet[i] = Double.NaN;
            } else {
                double prev = close[i - 1];
                if (prev > 0 && close[i] > 0) {
                    logRet[i] = Math.log(close[i] / prev);
                    simpleRet[i] = close[i] / prev - 1.0;
                } else {
                    logRet[i] = Double.NaN;
                    simpleRet[i] = Double.NaN;
                }
            }
        }
        double[] trailSigma = trailingSigma(logRet, sigmaWindow);
        double[] sma = trailingSma(close, maWindow);
        return new SwingSeries(symbol, dates, close, adjClose, high, low, logRet, simpleRet, trailSigma, sma);
    }

    /** Trailing simple moving average of {@code close} over {@code window} sessions ending at each index. */
    private static double[] trailingSma(double[] close, int window) {
        int n = close.length;
        double[] sma = new double[n];
        for (int i = 0; i < n; i++) {
            if (i + 1 < window) {           // not enough history for a full window yet
                sma[i] = Double.NaN;
                continue;
            }
            double sum = 0;
            for (int j = i - window + 1; j <= i; j++) sum += close[j];
            sma[i] = sum / window;
        }
        return sma;
    }

    /**
     * Sample daily σ of the returns strictly <em>before</em> a {@code days}-session flush window
     * ending at bar {@code i} — i.e. {@code trailSigma[i−days+1]}, so the flush's own down-days do
     * not inflate the σ it is measured against. {@code NaN} when the index is out of range.
     */
    double preWindowSigma(int i, int days) {
        int idx = i - days + 1;
        if (idx < 0 || idx >= trailSigma.length) return Double.NaN;
        return trailSigma[idx];
    }

    /** Sample daily σ of the {@code window} log returns ending strictly before each index. */
    private static double[] trailingSigma(double[] logRet, int window) {
        int n = logRet.length;
        double[] sigma = new double[n];
        for (int i = 0; i < n; i++) {
            // Returns strictly before i: indices [i-window, i-1].
            int start = i - window;
            if (start < 1) {                    // index 0 has no return; need a full window
                sigma[i] = Double.NaN;
                continue;
            }
            double[] win = new double[window];
            int filled = 0;
            for (int j = start; j <= i - 1; j++) {
                if (!Double.isNaN(logRet[j])) win[filled++] = logRet[j];
            }
            sigma[i] = filled >= 2 ? Stats.stdevSample(java.util.Arrays.copyOf(win, filled)) : Double.NaN;
        }
        return sigma;
    }

    public int size() {
        return close.length;
    }

    public String symbol() {
        return symbol;
    }

    public LocalDate firstDate() {
        return dates[0];
    }

    /** Date of bar {@code i} (ascending). Public so the application layer can align earnings dates. */
    public LocalDate dateAt(int i) {
        return dates[i];
    }

    public LocalDate lastDate() {
        return dates[dates.length - 1];
    }

    /** First bar index whose date is on or after {@code cutoff} (or {@code size()} if none). */
    int startIndex(LocalDate cutoff) {
        if (cutoff == null) return 0;
        for (int i = 0; i < dates.length; i++) {
            if (!dates[i].isBefore(cutoff)) return i;
        }
        return dates.length;
    }
}
