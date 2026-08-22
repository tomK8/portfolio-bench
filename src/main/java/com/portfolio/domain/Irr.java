package com.portfolio.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * Internal rate of return over dated cash flows — the solver behind the cash-ledger's
 * Annualised return column and {@code PortfolioReturnService}'s money-weighted summary.
 *
 * <p>Solves for the annualised {@code r} making the NPV of the flows zero:
 * <pre>  Σ CF_i / (1 + r)^(days_i / basis) = 0 </pre>
 * where {@code days_i} counts from the earliest flow. Signs are the caller's: money out
 * negative, money in positive.
 *
 * <p><b>Newton-Raphson first, bisection as the fallback</b>, and the fallback is not
 * decorative — Newton is fast but genuinely unreliable here. The NPV curve is nearly flat
 * over most of its range when one flow dwarfs the others (a £50 dividend against a £20,000
 * purchase), so a derivative step can throw the iterate past {@code -1} where {@code (1+r)^-dt}
 * is undefined, and it can cycle when flows alternate sign more than once. Any iterate that
 * leaves the bracket, stops being finite, or fails to converge inside {@link #MAX_NEWTON_ITERS}
 * hands over to bisection, which cannot fail on a bracketed root. {@link Solution#method()}
 * records which one answered, so a suspicious figure can be traced without re-deriving it.
 *
 * <p><b>The bracket is {@code [-0.99, +10.0]}</b> — a total loss short of −100% (at exactly
 * −1 the NPV is undefined, not zero) up to +1000%/yr. A root outside it is not a return anyone
 * should read off a table, and is reported as {@code null} rather than clamped to the edge.
 *
 * <p><b>Day-count basis is a parameter, and the two callers legitimately differ.</b>
 * {@link #DAYS_PER_YEAR_XIRR} (365) is the spreadsheet XIRR convention, so a figure can be
 * checked against Excel or Sheets cell-for-cell — that is what the cash-ledger column uses.
 * {@link #DAYS_PER_YEAR_CALENDAR} (365.25) is the house annualisation convention used by
 * Attribution and Period diff; the money-weighted summary stays on it so its tiles remain
 * comparable with the ones beside them. The gap is ~0.07% relative on the rate, invisible on
 * screen and wrong to unify by fiat in either direction.
 *
 * <p>Returns {@code null} rather than a number whenever the answer would be fiction: fewer
 * than two flows, flows that never change sign (no root exists — you cannot earn a return on
 * money you never put in), or a non-finite NPV at either bracket end.
 *
 * <p><b>Multiple roots are a real possibility and are not this class's problem to solve.</b>
 * Flows that change sign more than once can admit several rates; bisection returns whichever
 * one its bracket converges on. The caller's job is to hand over a flow series that has one —
 * see {@link PositionIrr}, which splits a position into episodes at each return to zero
 * precisely so that it does.
 */
public final class Irr {

    /** Spreadsheet XIRR convention — use when a figure must reconcile with Excel/Sheets. */
    public static final double DAYS_PER_YEAR_XIRR = 365.0;

    /** House annualisation convention (Attribution, Period diff, MWR tiles). */
    public static final double DAYS_PER_YEAR_CALENDAR = 365.25;

    /** Total loss short of −100%: at exactly −1 the discount factor is undefined. */
    public static final double MIN_RATE = -0.99;

    /** +1000%/yr. Past this a "return" is an artefact of near-zero invested capital. */
    public static final double MAX_RATE = 10.0;

    static final int MAX_NEWTON_ITERS = 60;
    static final int BISECTION_ITERS = 200;

    /** Relative NPV tolerance — scaled by flow magnitude so £1 and £1m converge alike. */
    private static final double NPV_TOL_RELATIVE = 1e-10;

    /** Successive-iterate tolerance on the rate itself (1e-10 ≈ 1e-8 of a percentage point). */
    private static final double RATE_TOL = 1e-10;

    private Irr() {
    }

    /** One dated cash flow. {@code amount} is signed: money out negative, money in positive. */
    public record Flow(LocalDate date, double amount) {
    }

    /** Which solver produced the rate — recorded so a suspicious figure can be traced. */
    public enum Method {
        /** Newton-Raphson converged from the naive seed. */
        NEWTON,
        /** Newton diverged, cycled or left the bracket; bisection answered. */
        BISECTION
    }

    /** A solved rate and the solver that produced it. */
    public record Solution(double rate, Method method) {
    }

    /**
     * Annualised IRR on the spreadsheet XIRR basis, or {@code null} when no rate in
     * {@code [MIN_RATE, MAX_RATE]} zeroes the NPV.
     */
    public static Double solve(List<Flow> flows) {
        return solve(flows, DAYS_PER_YEAR_XIRR);
    }

    /** Annualised IRR on the given day-count basis; {@code null} when unsolvable. */
    public static Double solve(List<Flow> flows, double dayBasis) {
        Solution s = solveDetailed(flows, dayBasis);
        return s == null ? null : s.rate();
    }

    /**
     * Annualised IRR plus the method that found it. {@code null} when the flows admit no
     * root in the bracket — see the class doc for the three ways that happens.
     */
    public static Solution solveDetailed(List<Flow> flows, double dayBasis) {
        if (flows == null || flows.size() < 2 || dayBasis <= 0) return null;

        LocalDate t0 = flows.get(0).date();
        for (Flow f : flows) {
            if (f.date() == null) return null;
            if (f.date().isBefore(t0)) t0 = f.date();
        }

        // Time offsets and amounts as parallel arrays: the solvers evaluate the NPV tens to
        // hundreds of times and re-walking records to re-derive day counts each pass is waste.
        int n = flows.size();
        double[] dt = new double[n];
        double[] cf = new double[n];
        double magnitude = 0.0;
        boolean anyPositive = false, anyNegative = false;
        for (int i = 0; i < n; i++) {
            Flow f = flows.get(i);
            dt[i] = (f.date().toEpochDay() - t0.toEpochDay()) / dayBasis;
            cf[i] = f.amount();
            magnitude += Math.abs(cf[i]);
            if (cf[i] > 0) anyPositive = true;
            if (cf[i] < 0) anyNegative = true;
        }
        // No sign change, no root: all-outflow or all-inflow series have an NPV that never
        // crosses zero. Bisection would report its bracket midpoint as an answer.
        if (!anyPositive || !anyNegative) return null;

        double tol = Math.max(NPV_TOL_RELATIVE * magnitude, 1e-12);

        Double newton = newton(dt, cf, seed(dt, cf, dayBasis), tol);
        if (newton != null) return new Solution(newton, Method.NEWTON);

        Double bisected = bisect(dt, cf, tol);
        return bisected == null ? null : new Solution(bisected, Method.BISECTION);
    }

    /**
     * Naive seed: the rate that would take total money-in to total money-out over the full
     * span, {@code (in/out)^(1/years) − 1}. Crude — it ignores when each flow landed — but it
     * lands Newton in the right neighbourhood and on the right side of zero, which is most of
     * what the seed has to do. Falls back to 10% for a same-day span or a degenerate ratio.
     */
    private static double seed(double[] dt, double[] cf, double dayBasis) {
        double in = 0.0, out = 0.0, maxDt = 0.0;
        for (int i = 0; i < cf.length; i++) {
            if (cf[i] > 0) in += cf[i]; else out -= cf[i];
            if (dt[i] > maxDt) maxDt = dt[i];
        }
        if (maxDt <= 0 || in <= 0 || out <= 0) return 0.1;
        double guess = Math.pow(in / out, 1.0 / maxDt) - 1.0;
        if (!Double.isFinite(guess)) return 0.1;
        return clampToBracket(guess);
    }

    private static double clampToBracket(double r) {
        if (r <= MIN_RATE) return MIN_RATE / 2;
        if (r >= MAX_RATE) return MAX_RATE / 2;
        return r;
    }

    /**
     * Newton-Raphson. Returns {@code null} — deliberately, not an approximate answer — the
     * moment the iteration leaves the bracket, hits a flat derivative, or goes non-finite, so
     * the caller falls through to bisection rather than printing a number nobody solved for.
     */
    private static Double newton(double[] dt, double[] cf, double seed, double tol) {
        double r = seed;
        for (int i = 0; i < MAX_NEWTON_ITERS; i++) {
            double f = npv(dt, cf, r);
            if (!Double.isFinite(f)) return null;
            if (Math.abs(f) < tol) return withinBracket(r);
            double df = npvDerivative(dt, cf, r);
            // A flat derivative sends the next iterate to infinity; hand over rather than leap.
            if (!Double.isFinite(df) || Math.abs(df) < 1e-14) return null;
            double next = r - f / df;
            if (!Double.isFinite(next) || next <= MIN_RATE || next >= MAX_RATE) return null;
            if (Math.abs(next - r) < RATE_TOL) return withinBracket(next);
            r = next;
        }
        return null;
    }

    private static Double withinBracket(double r) {
        return (r > MIN_RATE && r < MAX_RATE) ? r : null;
    }

    /**
     * Bisection on {@code [MIN_RATE, MAX_RATE]}. Cannot fail on a bracketed root, and 200
     * halvings drive the interval below double precision, so the result is exact to the
     * representation regardless of how ill-conditioned the flows are. {@code null} when the
     * NPV does not change sign across the bracket — the root, if any, lies outside it.
     */
    private static Double bisect(double[] dt, double[] cf, double tol) {
        double lo = MIN_RATE, hi = MAX_RATE;
        double npvLo = npv(dt, cf, lo), npvHi = npv(dt, cf, hi);
        if (!Double.isFinite(npvLo) || !Double.isFinite(npvHi)) return null;
        if (npvLo == 0.0) return lo;
        if (npvHi == 0.0) return hi;
        if (npvLo * npvHi > 0) return null;

        for (int i = 0; i < BISECTION_ITERS; i++) {
            double mid = 0.5 * (lo + hi);
            double npvMid = npv(dt, cf, mid);
            if (!Double.isFinite(npvMid)) return null;
            if (Math.abs(npvMid) < tol) return mid;
            if (npvLo * npvMid < 0) {
                hi = mid;
            } else {
                lo = mid;
                npvLo = npvMid;
            }
        }
        return 0.5 * (lo + hi);
    }

    /** Σ CF_i × (1+r)^(−dt_i). */
    static double npv(double[] dt, double[] cf, double r) {
        double base = 1.0 + r;
        if (base <= 0) return Double.NaN;
        double s = 0.0;
        for (int i = 0; i < cf.length; i++) s += cf[i] * Math.pow(base, -dt[i]);
        return s;
    }

    /** d/dr of {@link #npv}: Σ −dt_i × CF_i × (1+r)^(−dt_i−1). */
    private static double npvDerivative(double[] dt, double[] cf, double r) {
        double base = 1.0 + r;
        if (base <= 0) return Double.NaN;
        double s = 0.0;
        for (int i = 0; i < cf.length; i++) {
            if (dt[i] == 0.0) continue;            // a flow at t0 has no rate sensitivity
            s -= dt[i] * cf[i] * Math.pow(base, -dt[i] - 1.0);
        }
        return s;
    }
}
