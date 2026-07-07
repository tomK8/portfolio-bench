package com.portfolio.domain.trend;

/**
 * One in/out timing rule. {@link #targets} returns the <em>desired</em> invested state decided at
 * each bar's close using only data up to and including that close (look-ahead-free); the
 * backtester turns a state change at bar {@code i} into a fill at that close or the next open.
 *
 * <p>Entries before {@link #firstValid} carry the initial state but are meaningless — callers
 * must start any backtest at or after {@code firstValid} (the grid starts every variant at the
 * common latest {@code firstValid} so all cells and buy-and-hold cover the identical window).
 * The initial state at {@code firstValid} is the plain comparison {@code signal ≥ MA} for every
 * variant (buffer bands and anchor clocks apply from the next bar on).
 */
public interface TrendSignal {

    /** Stable id for the report/UI, e.g. {@code "anchored-200"}. */
    String key();

    /** Variant family: {@code anchored | touch | bufferPct | bufferN | monthly}. */
    String family();

    /** Human label, e.g. {@code "Buffered ±2%"}. */
    String label();

    /** SMA length in sessions; {@code null} for the monthly-close variants. */
    Integer maLength();

    /** First bar index with a defined state; {@code series.size()} when the series is too short. */
    int firstValid(TrendSeries s);

    /** Desired invested state per bar, decided at that bar's close. */
    boolean[] targets(TrendSeries s);

    /**
     * The MA series this signal compares against (per-bar; {@code NaN} where undefined — for the
     * monthly variants that is everywhere except completed month ends). Used by the trade log to
     * report the MA level and distance at each signal bar.
     */
    double[] maSeries(TrendSeries s);
}
