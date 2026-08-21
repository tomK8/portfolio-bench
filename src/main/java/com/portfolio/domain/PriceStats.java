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
        Double daily = dailyVolRaw(barsAscending, windowReturns);
        if (daily == null) return null;
        return BigDecimal.valueOf(daily * Math.sqrt(TRADING_DAYS)).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Realized <em>daily</em> volatility = sample standard deviation of the last {@code windowReturns}
     * daily log returns, <strong>not</strong> annualised. Returns {@code null} with fewer than two
     * usable returns. A fraction: {@code 0.02} = 2% per day. {@link #annualizedVol} is exactly this
     * scaled by {@code √252}.
     */
    public static BigDecimal dailyVol(List<PriceBar> barsAscending, int windowReturns) {
        Double daily = dailyVolRaw(barsAscending, windowReturns);
        if (daily == null) return null;
        return BigDecimal.valueOf(daily).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Shared unrounded daily-σ engine for {@link #annualizedVol} / {@link #dailyVol}; null if &lt;2 returns. */
    private static Double dailyVolRaw(List<PriceBar> barsAscending, int windowReturns) {
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
        return Math.sqrt(variance);
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

    /**
     * Signed distance from the {@code current} price to a {@code limit} price, measured in units of
     * the return volatility over a {@code horizonSessions}-session horizon
     * ({@code σ_h = dailyVol × √horizon}). Log-space: {@code ln(limit / current) / σ_h}. Positive when
     * the limit sits above the current market (price must rise to reach it), negative below. Returns
     * {@code null} when any input is missing/non-positive or the volatility is zero. Currency-free —
     * both prices only enter as a ratio, so they need only share one currency (not GBP specifically).
     */
    public static BigDecimal sigmaDistance(BigDecimal current, BigDecimal limit,
                                           BigDecimal dailyVol, int horizonSessions) {
        if (current == null || limit == null || dailyVol == null || horizonSessions < 1) return null;
        double c = current.doubleValue(), l = limit.doubleValue(), v = dailyVol.doubleValue();
        if (c <= 0 || l <= 0 || v <= 0) return null;
        double sigmaH = v * Math.sqrt(horizonSessions);
        if (sigmaH <= 0) return null;
        return BigDecimal.valueOf(Math.log(l / c) / sigmaH).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Trading sessions in {@code (from, to]} — every weekday, exclusive of {@code from} and
     * inclusive of {@code to}. Zero when {@code to} is not after {@code from}.
     *
     * <p>Everything the touch model consumes is measured in sessions — {@link #sigmaDistance}
     * scales σ by {@code √sessions}, and the daily vol it uses comes from bars that exist only on
     * trading days. An order's expiry, though, is a calendar date, so translating one into the
     * other is what lets a view say "this order expires 2026-08-28, about 41 sessions away". The
     * two are not interchangeable: 30 calendar days is about 21 sessions. Weekends only — exchange
     * holidays are not modelled, which leaves the count a session or two long across a long
     * horizon and is the same simplification {@code GiltPriceFetchJob.isTradingDay} makes.
     */
    public static int tradingSessionsBetween(LocalDate from, LocalDate to) {
        if (from == null || to == null || !to.isAfter(from)) return 0;
        int sessions = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) sessions++;
        }
        return sessions;
    }

    /**
     * Probability that a driftless geometric Brownian motion starting at the current price touches a
     * barrier {@code |sigmaDistance|} volatility-units away at least once within the horizon: the
     * first-passage reflection result {@code P = 2·Φ(−|d|)}, clamped to {@code [0, 1]}. At the barrier
     * ({@code d = 0}) this is 1. Driftless is the neutral convention for a short (≈5-session) window —
     * over a handful of days the drift term is negligible against the diffusion term. Returns
     * {@code null} when {@code sigmaDistance} is null.
     */
    public static BigDecimal touchProbability(BigDecimal sigmaDistance) {
        if (sigmaDistance == null) return null;
        double d = Math.abs(sigmaDistance.doubleValue());
        double p = 2.0 * normalCdf(-d);
        p = Math.max(0.0, Math.min(1.0, p));
        return BigDecimal.valueOf(p).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Probability that a geometric Brownian motion <b>with drift</b> touches the barrier at least
     * once within {@code sessions} — the drifted counterpart of {@link #touchProbability}, and the
     * branch {@code VolForecast} takes when drift is switched on and the asset class has one.
     *
     * <p>With {@code b = ln(limit/current)}, {@code ν} the log drift per session and
     * {@code σ_h = σ√N}:
     * <pre>
     *   P = Φ((−|b| + sgn(b)·νN)/σ_h) + exp(2νb/σ²)·Φ((−|b| − sgn(b)·νN)/σ_h)
     * </pre>
     * <b>The drift term is signed by which side of the market the barrier is on</b>, which the
     * textbook statement of the upper-barrier case hides. A rising asset is <em>less</em> likely to
     * reach a limit below it and <em>more</em> likely to reach one above; writing the upper-barrier
     * form and feeding it {@code |b|} would raise both, which for a buy limit — the common case in
     * this panel — is backwards.
     *
     * <p>At {@code ν = 0} this reduces exactly to {@code 2·Φ(−|b|/σ_h)}: both Φ terms collapse to
     * the same argument and {@code exp(0) = 1}. That identity is asserted directly in
     * {@code PriceStatsTest}, and it is why the caller's short-circuit at {@code ν = 0} is a
     * numerical safeguard rather than a second model — {@code 2νb/σ²} is {@code 0/0} when σ is
     * degenerate, and this form would return NaN where the driftless one is well defined.
     *
     * <p>The second term is computed in log space so a large {@code exp} against an underflowed Φ
     * gives 0 rather than {@code ∞ × 0 = NaN}; anything still non-finite falls back to the
     * driftless value rather than propagating. Clamped to {@code [0,1]}; at the barrier ({@code b =
     * 0}) it is 1 whatever the drift, as it must be.
     *
     * @param driftPerSession log drift per trading session — see {@link AssetClass#dailyDrift()}.
     */
    public static BigDecimal touchProbabilityDrifted(BigDecimal current, BigDecimal limit,
                                                     BigDecimal sigmaPerSession, int sessions,
                                                     BigDecimal driftPerSession) {
        if (current == null || limit == null || sigmaPerSession == null || sessions < 1) return null;
        double s = current.doubleValue(), k = limit.doubleValue(), sigma = sigmaPerSession.doubleValue();
        if (s <= 0 || k <= 0 || sigma <= 0) return null;
        double nu = driftPerSession == null ? 0.0 : driftPerSession.doubleValue();

        double b = Math.log(k / s);
        double absB = Math.abs(b);
        double sign = b >= 0 ? 1.0 : -1.0;
        double sigmaH = sigma * Math.sqrt(sessions);
        double z1 = (-absB + sign * nu * sessions) / sigmaH;
        double z2 = (-absB - sign * nu * sessions) / sigmaH;
        double p = normalCdf(z1) + Math.exp(2.0 * nu * b / (sigma * sigma) + Math.log(normalCdf(z2)));
        if (!Double.isFinite(p)) {
            return touchProbability(sigmaDistance(current, limit, sigmaPerSession, sessions));
        }
        p = Math.max(0.0, Math.min(1.0, p));
        return BigDecimal.valueOf(p).setScale(4, RoundingMode.HALF_UP);
    }

    /** Standard normal CDF {@code Φ(x)} via the {@link #erfc} approximation. */
    static double normalCdf(double x) {
        return 0.5 * erfc(-x / Math.sqrt(2.0));
    }

    /**
     * Complementary error function via the Numerical Recipes {@code erfcc} rational approximation
     * (fractional error &lt; 1.2e-7 everywhere) — ample for a touch-probability estimate and free of
     * any external dependency.
     */
    static double erfc(double x) {
        double z = Math.abs(x);
        double t = 1.0 / (1.0 + 0.5 * z);
        double ans = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196
                + t * (0.09678418 + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398
                + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0.0 ? ans : 2.0 - ans;
    }
}
