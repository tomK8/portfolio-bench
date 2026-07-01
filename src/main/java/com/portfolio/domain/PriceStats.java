package com.portfolio.domain;

import com.portfolio.domain.model.PriceBar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Pure price-series statistics for the watchlist screen: realized volatility and
 * trailing-window returns. Framework-free and side-effect-free so the maths is unit-testable
 * in isolation.
 *
 * <p>All inputs are daily {@link PriceBar}s in ascending date order, valued on
 * {@code close} — Yahoo's split-adjusted close, which is continuous across splits (no need to
 * undo the adjustment here; we want a jump-free return series). {@code adj_close} is
 * deliberately <em>not</em> used: these are price-behaviour signals for timing a trade, not
 * total-return figures, so folding dividends back in would only muddy them.
 */
public final class PriceStats {

    private static final int SCALE = 8;
    /** Trading days per year — the same annualisation constant the portfolio Risk tab uses. */
    private static final double TRADING_DAYS = 252.0;

    private PriceStats() {
    }

    /**
     * Annualised realized volatility = sample standard deviation of the last {@code windowReturns}
     * daily log returns, scaled by {@code √252}. Returns {@code null} when there aren't at least
     * two returns in the window (can't form a sample stdev). A fraction: {@code 0.32} = 32%/yr.
     */
    public static BigDecimal annualizedVol(List<PriceBar> barsAscending, int windowReturns) {
        if (barsAscending == null || barsAscending.size() < 2 || windowReturns < 2) return null;

        int n = barsAscending.size();
        // Collect up to windowReturns most-recent log returns, walking backwards.
        int wanted = Math.min(windowReturns, n - 1);
        double[] rets = new double[wanted];
        int filled = 0;
        for (int i = n - 1; i >= 1 && filled < wanted; i--) {
            double prev = barsAscending.get(i - 1).close();
            double cur = barsAscending.get(i).close();
            if (prev <= 0 || cur <= 0) continue;          // guard log of non-positive
            rets[filled++] = Math.log(cur / prev);
        }
        if (filled < 2) return null;

        double mean = 0;
        for (int i = 0; i < filled; i++) mean += rets[i];
        mean /= filled;
        double sumSq = 0;
        for (int i = 0; i < filled; i++) {
            double d = rets[i] - mean;
            sumSq += d * d;
        }
        double variance = sumSq / (filled - 1);           // sample variance
        double daily = Math.sqrt(variance);
        double annual = daily * Math.sqrt(TRADING_DAYS);
        return BigDecimal.valueOf(annual).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Close of the most recent bar dated on or before {@code date}, or {@code null} if none.
     * Used to anchor trailing-window returns ("price 30 days ago") on a calendar basis, which
     * matches how the user thinks about momentum ("last 5 days", "last 30 days") and is robust
     * to weekends/holidays — it just walks back to the last trading day.
     */
    public static Double closeOnOrBefore(List<PriceBar> barsAscending, LocalDate date) {
        if (barsAscending == null || barsAscending.isEmpty()) return null;
        for (int i = barsAscending.size() - 1; i >= 0; i--) {
            PriceBar b = barsAscending.get(i);
            if (!b.date().isAfter(date)) return b.close();
        }
        return null;
    }

    /**
     * Simple percentage change {@code (current − base) / base} as a fraction, or {@code null}
     * when either input is missing or {@code base} is zero.
     */
    public static BigDecimal pctChange(Double current, Double base) {
        if (current == null || base == null || base == 0.0) return null;
        return BigDecimal.valueOf((current - base) / base).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
