package com.portfolio.domain.momentum;

import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * §E-convention regime split: every held month is bucketed by whether the regime index (QQQ) sat
 * above or below its 200d MA at the <em>positioning</em> rebalance — the month-end bar that chose
 * that month's holdings — so the split is look-ahead-free. Months whose positioning bar predates
 * a full MA window go to "unknown", flagged rather than dropped.
 */
public final class RegimeSplit {

    public record Bucket(String label, int months, double meanMonthly, double annReturn,
                         double annVol, double sharpe, Double hitRateVsBaseline, boolean thin) {
    }

    public record Result(Bucket above, Bucket below, Bucket unknown) {
    }

    /** Months with fewer samples than this are flagged thin (dimmed in the UI, never hidden). */
    public static final int THIN_MONTHS = 12;

    private RegimeSplit() {
    }

    /**
     * Regime state per held month: key "YYYY-MM" → "above" / "below" / "unknown". The state at
     * month-end bar {@code i} applies to the <em>following</em> calendar month.
     */
    public static Map<String, String> statesByHeldMonth(MomentumSeries s, MomentumConfig cfg) {
        Map<String, String> out = new LinkedHashMap<>();
        int ri = s.indexOf(cfg.regimeSymbol());
        int window = cfg.regimeMaWindow();
        for (int i : s.monthEndIdx()) {
            String held = YearMonth.from(s.dateAt(i)).plusMonths(1).toString();
            out.put(held, ri < 0 ? "unknown" : stateAt(s, ri, i, window));
        }
        return out;
    }

    private static String stateAt(MomentumSeries s, int ri, int i, int window) {
        if (i + 1 < window) return "unknown";
        double px = s.close(ri, i);
        if (Double.isNaN(px)) return "unknown";
        double sum = 0;
        for (int j = i - window + 1; j <= i; j++) {
            double c = s.close(ri, j);
            if (Double.isNaN(c)) return "unknown";
            sum += c;
        }
        return px >= sum / window ? "above" : "below";
    }

    /**
     * Split a variant's monthly returns by regime state. {@code baselineMonths} may be null;
     * hit rate is only reported where both sides have the month.
     */
    public static Result compute(Map<String, Double> months, Map<String, String> states,
                                 Map<String, Double> baselineMonths, double rfAnnualPct) {
        return new Result(
                bucket("above", months, states, baselineMonths, rfAnnualPct),
                bucket("below", months, states, baselineMonths, rfAnnualPct),
                bucket("unknown", months, states, baselineMonths, rfAnnualPct));
    }

    private static Bucket bucket(String label, Map<String, Double> months, Map<String, String> states,
                                 Map<String, Double> baselineMonths, double rfAnnualPct) {
        int n = 0, beat = 0, comparable = 0;
        double sum = 0, sumSq = 0, compound = 1;
        for (Map.Entry<String, Double> e : months.entrySet()) {
            if (!label.equals(states.getOrDefault(e.getKey(), "unknown"))) continue;
            double v = e.getValue();
            n++;
            sum += v;
            sumSq += v * v;
            compound *= 1 + v;
            Double base = baselineMonths == null ? null : baselineMonths.get(e.getKey());
            if (base != null) {
                comparable++;
                if (v > base) beat++;
            }
        }
        if (n == 0) return new Bucket(label, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, null, true);
        double mean = sum / n;
        double var = n > 1 ? (sumSq - n * mean * mean) / (n - 1) : Double.NaN;
        double annVol = Math.sqrt(Math.max(var, 0)) * Math.sqrt(12);
        double annReturn = Math.pow(compound, 12.0 / n) - 1;
        double rfMonthly = rfAnnualPct / 100.0 / 12.0;
        double sharpe = annVol > 0 ? (mean - rfMonthly) * 12 / annVol : Double.NaN;
        Double hitRate = comparable > 0 ? (double) beat / comparable : null;
        return new Bucket(label, n, mean, annReturn, annVol, sharpe, hitRate, n < THIN_MONTHS);
    }
}
