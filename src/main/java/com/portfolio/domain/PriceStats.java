package com.portfolio.domain;

import com.portfolio.domain.model.IntradayBar;
import com.portfolio.domain.model.PriceBar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
     * Annualised realized volatility from intraday (1-minute) bars — the right estimator for
     * short horizons (5D/10D) where a handful of daily returns is too noisy. Uses the realized-
     * variance approach: sum the squared <em>intra-session</em> log returns (overnight gaps
     * skipped), divide by the number of distinct trading days observed to get a per-day
     * variance, then annualise by {@code √252}. This is robust to the exchange's bars-per-day
     * (it doesn't assume a fixed count). Returns {@code null} with fewer than two usable returns.
     */
    public static BigDecimal annualizedVolIntraday(List<IntradayBar> barsAscending) {
        if (barsAscending == null || barsAscending.size() < 2) return null;
        double rvSum = 0;
        int usedReturns = 0;
        Set<LocalDate> days = new HashSet<>();
        for (int i = 1; i < barsAscending.size(); i++) {
            IntradayBar prev = barsAscending.get(i - 1);
            IntradayBar cur = barsAscending.get(i);
            LocalDate dPrev = prev.ts().atZone(ZoneOffset.UTC).toLocalDate();
            LocalDate dCur = cur.ts().atZone(ZoneOffset.UTC).toLocalDate();
            days.add(dPrev);
            days.add(dCur);
            if (!dPrev.equals(dCur)) continue;        // skip the overnight gap
            double a = prev.close(), b = cur.close();
            if (a <= 0 || b <= 0) continue;
            double r = Math.log(b / a);
            rvSum += r * r;
            usedReturns++;
        }
        if (usedReturns < 2 || days.isEmpty()) return null;
        double perDayVariance = rvSum / days.size();
        double annual = Math.sqrt(perDayVariance * TRADING_DAYS);
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
     * Close {@code n} trading sessions before {@code today}: the close of the n-th most recent bar
     * strictly dated before {@code today}. {@code n = 1} → the previous session's close (the base
     * for a one-session / "today" return); {@code n = 2} → two sessions ago (the base for a
     * two-session return — i.e. today's move plus the previous session's); and so on. Returns
     * {@code null} when fewer than {@code n} such bars exist.
     *
     * <p>Session-based, unlike {@link #closeOnOrBefore}: over a weekend/holiday a calendar anchor
     * collapses adjacent short windows onto the same base (on a Monday, {@code today−1}, {@code −2}
     * and {@code −3} all resolve to Friday), making Today/2d/3d identical. Counting sessions keeps
     * each window one trading day apart regardless of the calendar. Any bar dated {@code today}
     * itself is skipped, so a live intraday {@code current} is compared against completed sessions.
     *
     * <p>Holidays need no calendar here — the daily series only has rows for real sessions on the
     * symbol's own exchange (a US holiday leaves no US bar; a London name that traded that day keeps
     * its bar), so walking bars is automatically per-exchange. Weekend-dated bars <em>are</em>
     * skipped, though: {@code GiltPriceFetchJob} rolls the last price forward into {@code price_history}
     * on Sat/Sun, and those phantom rows must not be counted as sessions.
     */
    public static Double closeNSessionsBefore(List<PriceBar> barsAscending, LocalDate today, int n) {
        if (barsAscending == null || n < 1) return null;
        int seen = 0;
        for (int i = barsAscending.size() - 1; i >= 0; i--) {
            LocalDate d = barsAscending.get(i).date();
            if (!d.isBefore(today)) continue;                     // today's own bar or later
            DayOfWeek dow = d.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) continue;  // phantom weekend row
            if (++seen == n) return barsAscending.get(i).close();
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
