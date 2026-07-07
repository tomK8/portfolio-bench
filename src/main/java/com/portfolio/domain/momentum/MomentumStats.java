package com.portfolio.domain.momentum;

import com.portfolio.domain.swing.Stats;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Performance metrics over a window of a {@link RotationBacktester.Result}, computed from the
 * daily return slice so fit and holdout sections partition exactly (window return =
 * Π(1+dailyRet) over the slice; the initial entry cost sits in bar 0 of the fit slice).
 *
 * <p>Conventions: CAGR annualises over calendar days (365.25); vol = sample stdev of daily
 * returns × √252; Sharpe = mean daily excess (over the flat rf) × 252 / vol; monthly returns
 * compound the daily slice within each calendar month; hit rate = fraction of months beating the
 * equal-weight baseline's same month.
 *
 * @param months monthly returns keyed by "YYYY-MM", in order — feeds the grid, regime split and
 *               crash diagnostics
 */
public record MomentumStats(
        double totalReturn,
        double cagr,
        double annVol,
        double sharpe,
        double maxDrawdown,
        LocalDate maxDrawdownPeak,
        LocalDate maxDrawdownTrough,
        double calmar,
        Double hitRateVsBaseline,
        String bestMonth,
        double bestMonthReturn,
        String worstMonth,
        double worstMonthReturn,
        Map<String, Double> months) {

    /**
     * Compute over result bars {@code fromK..toK} inclusive (result coordinates, i.e. series bar
     * {@code startIdx + k}). {@code baselineMonths} may be null (the baseline itself, benchmarks).
     */
    public static MomentumStats of(MomentumSeries s, RotationBacktester.Result r,
                                   int fromK, int toK, double rfDaily,
                                   Map<String, Double> baselineMonths) {
        double[] ret = r.dailyRet();
        int n = toK - fromK + 1;

        // Window equity path from base 1, with drawdown peak/trough dates.
        double eq = 1, peak = 1, maxDd = 0;
        LocalDate peakDate = s.dateAt(r.startIdx() + fromK);
        LocalDate ddPeakDate = peakDate, ddTroughDate = peakDate;
        double excessSum = 0;
        double[] daily = new double[n];
        for (int k = fromK; k <= toK; k++) {
            double d = ret[k];
            daily[k - fromK] = d;
            excessSum += d - rfDaily;
            eq *= 1 + d;
            LocalDate date = s.dateAt(r.startIdx() + k);
            if (eq > peak) {
                peak = eq;
                peakDate = date;
            }
            double dd = eq / peak - 1;
            if (dd < maxDd) {
                maxDd = dd;
                ddPeakDate = peakDate;
                ddTroughDate = date;
            }
        }
        double totalReturn = eq - 1;

        long days = java.time.temporal.ChronoUnit.DAYS.between(
                s.dateAt(r.startIdx() + fromK), s.dateAt(r.startIdx() + toK));
        double cagr = days > 0 ? Math.pow(eq, 365.25 / days) - 1 : Double.NaN;
        double vol = Stats.stdevSample(daily) * Math.sqrt(252);
        double sharpe = vol > 0 ? (excessSum / n) * 252 / vol : Double.NaN;
        double calmar = maxDd < 0 ? cagr / -maxDd : Double.NaN;

        Map<String, Double> months = monthlyReturns(s, r, fromK, toK);
        String best = null, worst = null;
        double bestRet = Double.NEGATIVE_INFINITY, worstRet = Double.POSITIVE_INFINITY;
        int beat = 0, comparable = 0;
        for (Map.Entry<String, Double> e : months.entrySet()) {
            double v = e.getValue();
            if (v > bestRet) {
                bestRet = v;
                best = e.getKey();
            }
            if (v < worstRet) {
                worstRet = v;
                worst = e.getKey();
            }
            Double base = baselineMonths == null ? null : baselineMonths.get(e.getKey());
            if (base != null) {
                comparable++;
                if (v > base) beat++;
            }
        }
        Double hitRate = comparable > 0 ? (double) beat / comparable : null;

        return new MomentumStats(totalReturn, cagr, vol, sharpe, maxDd, ddPeakDate, ddTroughDate,
                calmar, hitRate, best, months.isEmpty() ? Double.NaN : bestRet,
                worst, months.isEmpty() ? Double.NaN : worstRet, months);
    }

    /** Compound the daily slice within each calendar month, keyed "YYYY-MM", insertion-ordered. */
    public static Map<String, Double> monthlyReturns(MomentumSeries s, RotationBacktester.Result r,
                                                     int fromK, int toK) {
        Map<String, Double> out = new LinkedHashMap<>();
        YearMonth current = null;
        double factor = 1;
        for (int k = fromK; k <= toK; k++) {
            YearMonth ym = YearMonth.from(s.dateAt(r.startIdx() + k));
            if (current != null && !ym.equals(current)) {
                out.put(current.toString(), factor - 1);
                factor = 1;
            }
            current = ym;
            factor *= 1 + r.dailyRet()[k];
        }
        if (current != null) out.put(current.toString(), factor - 1);
        return out;
    }

    /** The month keys in order — shared x-axis for grid/regime/crash work. */
    public List<String> monthKeys() {
        return new ArrayList<>(months.keySet());
    }
}
