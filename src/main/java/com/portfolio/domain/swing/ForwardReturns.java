package com.portfolio.domain.swing;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * §2–§4 — forward-return behaviour, the heart of the "does the dip-buy edge exist?" question.
 * Operates on a list of {@link SwingSeries} so a single method serves both the per-symbol case
 * (one series) and the pooled-cluster case (concatenate observations across the cluster). All
 * entries are at a day's <em>close</em>; forward returns are <em>simple</em> (P&L-like, per the
 * spec) even though volatility elsewhere uses log returns.
 *
 * <ul>
 *   <li>§2 baseline — unconditional forward returns from every eligible day (the control every
 *       conditional number must be read against).</li>
 *   <li>§3 conditional — forward returns after a down-day trigger. Two trigger families: σ-based
 *       (day return ≤ −kσ, where σ is the look-ahead-free trailing daily σ from
 *       {@link SwingSeries}) and absolute (day return ≤ −p%).</li>
 *   <li>§4 continuation — {@code P(next-day ≤ 0)} and {@code P(next-day ≤ −big%)} after a trigger,
 *       vs. the same unconditional probabilities. Tests whether the first big red day tends to
 *       keep falling (i.e. day-one dip-buying is early).</li>
 * </ul>
 *
 * <p>Only bars on or after {@code cutoff} are eligible as entries (the §9 regime split slices by
 * date); the σ look-back and the forward horizon may reach outside that window, which is correct —
 * σ is what was known going in, and a bounce can land after the window edge.
 */
public final class ForwardReturns {

    /** Tolerance so a return the user thinks of as exactly −10% (stored as −0.0999999998) still fires. */
    private static final double EPS = 1e-9;

    private ForwardReturns() {
    }

    @FunctionalInterface
    interface TriggerFn {
        boolean fires(SwingSeries s, int i);
    }

    public static Result compute(List<SwingSeries> seriesList, LocalDate cutoff, SwingConfig cfg) {
        List<ForwardStat> baseline = baseline(seriesList, cutoff, cfg);
        double[] baseNext = unconditionalNextDay(seriesList, cutoff, cfg.continuationDrop());

        List<ConditionalBlock> conditional = new ArrayList<>();
        List<ContinuationStat> continuation = new ArrayList<>();
        for (double k : cfg.sigmaMultiples()) {
            String label = "≤ −" + trim(k) + "σ";
            TriggerFn fn = (s, i) -> !Double.isNaN(s.trailSigma[i]) && !Double.isNaN(s.simpleRet[i])
                    && s.simpleRet[i] <= -k * s.trailSigma[i] + EPS;
            conditional.add(conditionalBlock(seriesList, cutoff, cfg, label, "sigma", k, fn));
            continuation.add(continuationStat(seriesList, cutoff, cfg, label, fn));
        }
        for (double p : cfg.absDrops()) {
            String label = "≤ −" + trim(p * 100) + "%";
            TriggerFn fn = (s, i) -> !Double.isNaN(s.simpleRet[i]) && s.simpleRet[i] <= -p + EPS;
            conditional.add(conditionalBlock(seriesList, cutoff, cfg, label, "abs", p, fn));
            continuation.add(continuationStat(seriesList, cutoff, cfg, label, fn));
        }
        return new Result(baseline, frac(baseNext, 0.0), frac(baseNext, -cfg.continuationDrop()),
                baseNext.length, conditional, continuation);
    }

    private static List<ForwardStat> baseline(List<SwingSeries> seriesList, LocalDate cutoff, SwingConfig cfg) {
        List<ForwardStat> out = new ArrayList<>();
        for (int h : cfg.horizons()) {
            List<Double> fwd = new ArrayList<>();
            for (SwingSeries s : seriesList) {
                int n = s.size();
                for (int i = Math.max(1, s.startIndex(cutoff)); i < n; i++) {
                    if (i + h < n) fwd.add(forward(s, i, h));
                }
            }
            out.add(stat(h, fwd, cfg.smallNFloor()));
        }
        return out;
    }

    private static ConditionalBlock conditionalBlock(List<SwingSeries> seriesList, LocalDate cutoff,
                                                     SwingConfig cfg, String label, String kind,
                                                     double magnitude, TriggerFn fn) {
        int triggerDays = 0;
        List<List<Double>> perHorizon = new ArrayList<>();
        for (int ignored : cfg.horizons()) perHorizon.add(new ArrayList<>());
        for (SwingSeries s : seriesList) {
            int n = s.size();
            for (int i = Math.max(1, s.startIndex(cutoff)); i < n; i++) {
                if (!fn.fires(s, i)) continue;
                triggerDays++;
                for (int hi = 0; hi < cfg.horizons().length; hi++) {
                    int h = cfg.horizons()[hi];
                    if (i + h < n) perHorizon.get(hi).add(forward(s, i, h));
                }
            }
        }
        List<ForwardStat> horizons = new ArrayList<>();
        for (int hi = 0; hi < cfg.horizons().length; hi++) {
            horizons.add(stat(cfg.horizons()[hi], perHorizon.get(hi), cfg.smallNFloor()));
        }
        return new ConditionalBlock(label, kind, magnitude, triggerDays,
                triggerDays < cfg.smallNFloor(), horizons);
    }

    private static ContinuationStat continuationStat(List<SwingSeries> seriesList, LocalDate cutoff,
                                                     SwingConfig cfg, String label, TriggerFn fn) {
        List<Double> next = new ArrayList<>();
        for (SwingSeries s : seriesList) {
            int n = s.size();
            for (int i = Math.max(1, s.startIndex(cutoff)); i < n; i++) {
                if (!fn.fires(s, i)) continue;
                if (i + 1 < n) next.add(s.simpleRet[i + 1]);
            }
        }
        double[] arr = toArray(next);
        return new ContinuationStat(label, arr.length, arr.length < cfg.smallNFloor(),
                frac(arr, 0.0), frac(arr, -cfg.continuationDrop()));
    }

    private static double[] unconditionalNextDay(List<SwingSeries> seriesList, LocalDate cutoff, double drop) {
        List<Double> next = new ArrayList<>();
        for (SwingSeries s : seriesList) {
            int n = s.size();
            for (int i = Math.max(1, s.startIndex(cutoff)); i + 1 < n; i++) {
                if (!Double.isNaN(s.simpleRet[i + 1])) next.add(s.simpleRet[i + 1]);
            }
        }
        return toArray(next);
    }

    private static double forward(SwingSeries s, int i, int h) {
        double base = s.close[i];
        return base > 0 ? s.close[i + h] / base - 1.0 : Double.NaN;
    }

    private static ForwardStat stat(int horizon, List<Double> values, int floor) {
        double[] a = toArray(values);
        int n = Stats.count(a);
        if (n == 0) return new ForwardStat(horizon, 0, true, null, null, null);
        return new ForwardStat(horizon, n, n < floor,
                Stats.mean(a), Stats.median(a), Stats.fractionPositive(a));
    }

    private static Double frac(double[] a, double threshold) {
        double v = Stats.fractionAtMost(a, threshold);
        return Double.isNaN(v) ? null : v;
    }

    private static double[] toArray(List<Double> list) {
        double[] a = new double[list.size()];
        for (int i = 0; i < list.size(); i++) a[i] = list.get(i);
        return a;
    }

    /** Trim a trailing ".0" so labels read "−2σ" / "−8%" not "−2.0σ". */
    private static String trim(double v) {
        if (v == Math.rint(v)) return Integer.toString((int) v);
        return Double.toString(v);
    }

    // ---- DTOs ---------------------------------------------------------------

    /**
     * Forward-return summary at one horizon. All fractions; {@code null} when {@code n == 0}.
     * {@code smallN} flags a thin sample per the config floor.
     */
    public record ForwardStat(int horizon, int n, boolean smallN,
                              Double mean, Double median, Double pctPositive) {
    }

    /**
     * One down-day trigger's forward-return profile. {@code kind} is {@code "sigma"} or
     * {@code "abs"}; {@code magnitude} is the σ-multiple or the drop fraction. {@code triggerDays}
     * is the raw count of trigger occurrences (each horizon's {@code n} may be smaller near the
     * series end where forward data runs out).
     */
    public record ConditionalBlock(String label, String kind, double magnitude,
                                   int triggerDays, boolean smallN, List<ForwardStat> horizons) {
    }

    /** §4 continuation probabilities after a trigger. {@code pDown} = P(next-day ≤ 0), {@code pBig} = P(next-day ≤ −big%). */
    public record ContinuationStat(String label, int triggerDays, boolean smallN,
                                   Double pDown, Double pBig) {
    }

    /**
     * §2–§4 bundle. {@code baseline} + {@code basePDown}/{@code basePBig} (unconditional next-day
     * probabilities over {@code baselineDays} observations) are the controls the conditional
     * blocks are read against.
     */
    public record Result(List<ForwardStat> baseline, Double basePDown, Double basePBig,
                         int baselineDays,
                         List<ConditionalBlock> conditional, List<ContinuationStat> continuation) {
    }
}
