package com.portfolio.domain.swing;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * §5–§6 — flush events and the barrier / time-stop simulation, the calibration core of the study.
 * Pools across a list of {@link SwingSeries} just like {@link ForwardReturns}.
 *
 * <p><b>Flush event</b>: a session whose cumulative {@code days}-session return is ≤ −{@code
 * threshold}. Overlapping flushes on consecutive sessions are collapsed via a {@code flushCooldown}
 * so one down-leg counts once. Session {@code high}/{@code low} (available in {@code price_history})
 * drive §5's "traded ≥ +10%" and "drawdown to the low"; these are legitimate intraday extremes, not
 * the spec's deferred intraday-<em>barrier-touch</em> extension.
 *
 * <p><b>§5 per event</b>: further drawdown from the event close to the lowest low of the next
 * {@code drawdownWindow} sessions; sessions until the high first trades ≥ +{@code tp} above the
 * event close (censored past {@code followSessions}); and which of +{@code tp} / −{@code sl} is
 * touched first. When both are touched on the same session the daily bar can't order them, so it's
 * recorded as ambiguous rather than guessed.
 *
 * <p><b>§6 barrier simulation</b>: a hypothetical long entered at each event close (seeded by the
 * {@code barrierFlush*} definition), exited on a <em>close-to-close</em> basis at the first close
 * ≥ +{@code tp} (take-profit), ≤ −{@code sl} (stop-loss), or at the close of session N (time stop),
 * swept over {@code timeStops}. Realized return is the actual breaching close, so a gap through a
 * barrier is reported honestly rather than clipped. Trades that can't reach their time stop for
 * lack of forward data are dropped (not counted as time-stop exits).
 */
public final class Flushes {

    /** Tolerance so a round threshold (e.g. −10% stored as −0.0999999998) still registers. */
    private static final double EPS = 1e-9;

    private Flushes() {
    }

    // ---- §5 -----------------------------------------------------------------

    public static List<FlushProfile> profiles(List<SwingSeries> seriesList, LocalDate cutoff, SwingConfig cfg) {
        List<FlushProfile> out = new ArrayList<>();
        for (int days : cfg.flushDays()) {
            for (double thr : cfg.flushThresholds()) {
                out.add(profile(seriesList, cutoff, cfg, days, thr));
            }
        }
        return out;
    }

    private static FlushProfile profile(List<SwingSeries> seriesList, LocalDate cutoff,
                                        SwingConfig cfg, int days, double threshold) {
        double tp = cfg.takeProfit();
        double sl = cfg.stopLoss();
        String label = days + "d ≤ −" + pct(threshold);

        int eventCount = 0, censored = 0, tpFirst = 0, slFirst = 0, neither = 0, ambiguous = 0;
        List<Double> furtherDd = new ArrayList<>();
        List<Double> daysToTarget = new ArrayList<>();

        for (SwingSeries s : seriesList) {
            int n = s.size();
            for (int i : detectEvents(s, cutoff, days, threshold, cfg.flushCooldown())) {
                eventCount++;
                double entry = s.close[i];

                // Further drawdown to the lowest low of the next drawdownWindow sessions.
                double minLow = Double.NaN;
                for (int j = i + 1; j <= i + cfg.drawdownWindow() && j < n; j++) {
                    minLow = Double.isNaN(minLow) ? s.low[j] : Math.min(minLow, s.low[j]);
                }
                if (!Double.isNaN(minLow) && entry > 0) furtherDd.add(minLow / entry - 1.0);

                // Days until the high first trades ≥ +tp above the entry (censored past the window).
                double tpLevel = entry * (1 + tp);
                double slLevel = entry * (1 - sl);
                int hitDay = -1;
                boolean fullWindow = i + cfg.followSessions() < n;
                for (int j = i + 1; j <= i + cfg.followSessions() && j < n; j++) {
                    if (s.high[j] >= tpLevel) { hitDay = j - i; break; }
                }
                if (hitDay > 0) daysToTarget.add((double) hitDay);
                else if (fullWindow) censored++;   // full window observed, never reached target

                // Which barrier is touched first (over the same follow window).
                int outcome = touchFirst(s, i, n, tpLevel, slLevel, cfg.followSessions());
                switch (outcome) {
                    case TP_FIRST -> tpFirst++;
                    case SL_FIRST -> slFirst++;
                    case BOTH -> ambiguous++;
                    case NONE -> { if (fullWindow) neither++; }   // else truncated → unclassified
                    default -> { }
                }
            }
        }
        return new FlushProfile(label, days, threshold, eventCount, eventCount < cfg.smallNFloor(),
                Stats.dist(toArray(furtherDd)), Stats.dist(toArray(daysToTarget)),
                censored, tpFirst, slFirst, neither, ambiguous);
    }

    private static final int TP_FIRST = 1, SL_FIRST = 2, BOTH = 3, NONE = 0, TRUNCATED = -1;

    private static int touchFirst(SwingSeries s, int i, int n, double tpLevel, double slLevel, int follow) {
        boolean truncated = i + follow >= n;
        for (int j = i + 1; j <= i + follow && j < n; j++) {
            boolean tp = s.high[j] >= tpLevel;
            boolean sl = s.low[j] <= slLevel;
            if (tp && sl) return BOTH;
            if (tp) return TP_FIRST;
            if (sl) return SL_FIRST;
        }
        return truncated ? TRUNCATED : NONE;
    }

    // ---- §6 -----------------------------------------------------------------

    public static List<BarrierResult> barriers(List<SwingSeries> seriesList, LocalDate cutoff, SwingConfig cfg) {
        List<BarrierResult> out = new ArrayList<>();
        for (int stop : cfg.timeStops()) {
            out.add(barrier(seriesList, cutoff, cfg, stop));
        }
        return out;
    }

    private static BarrierResult barrier(List<SwingSeries> seriesList, LocalDate cutoff,
                                         SwingConfig cfg, int timeStop) {
        double tp = cfg.takeProfit();
        double sl = cfg.stopLoss();
        int tpCount = 0, slCount = 0, timeCount = 0;
        List<Double> returns = new ArrayList<>();
        List<Double> holdingDays = new ArrayList<>();

        for (SwingSeries s : seriesList) {
            int n = s.size();
            for (int i : detectEvents(s, cutoff, cfg.barrierFlushDays(), cfg.barrierFlushDrop(), cfg.flushCooldown())) {
                double entry = s.close[i];
                if (entry <= 0) continue;
                String reason = null;
                double ret = 0;
                int exitDay = 0;
                int last = Math.min(i + timeStop, n - 1);
                for (int j = i + 1; j <= last; j++) {
                    double r = s.close[j] / entry - 1.0;
                    if (r >= tp - EPS) { reason = "TP"; ret = r; exitDay = j - i; break; }
                    if (r <= -sl + EPS) { reason = "SL"; ret = r; exitDay = j - i; break; }
                }
                if (reason == null) {
                    if (i + timeStop <= n - 1) {          // time stop reachable with data
                        reason = "TIME";
                        ret = s.close[i + timeStop] / entry - 1.0;
                        exitDay = timeStop;
                    } else {
                        continue;                         // incomplete — can't evaluate this stop
                    }
                }
                switch (reason) {
                    case "TP" -> tpCount++;
                    case "SL" -> slCount++;
                    default -> timeCount++;
                }
                returns.add(ret);
                holdingDays.add((double) exitDay);
            }
        }
        int trades = returns.size();
        double[] retArr = toArray(returns);
        return new BarrierResult(timeStop, trades, trades < cfg.smallNFloor(),
                pct(tpCount, trades), pct(slCount, trades), pct(timeCount, trades),
                trades == 0 ? null : Stats.mean(retArr),
                trades == 0 ? null : Stats.mean(toArray(holdingDays)),
                Stats.dist(retArr));
    }

    // ---- shared -------------------------------------------------------------

    /** Event bar indices where the cumulative {@code days}-session return ≤ −threshold, cooldown-deduped. */
    private static List<Integer> detectEvents(SwingSeries s, LocalDate cutoff, int days,
                                              double threshold, int cooldown) {
        List<Integer> events = new ArrayList<>();
        int n = s.size();
        int start = Math.max(days, s.startIndex(cutoff));
        int nextAllowed = start;
        for (int i = start; i < n; i++) {
            if (i < nextAllowed) continue;
            double base = s.close[i - days];
            if (base <= 0) continue;
            double cum = s.close[i] / base - 1.0;
            if (cum <= -threshold + EPS) {
                events.add(i);
                nextAllowed = i + Math.max(1, cooldown);
            }
        }
        return events;
    }

    private static double pct(int part, int whole) {
        return whole == 0 ? 0.0 : (double) part / whole;
    }

    private static String pct(double fraction) {
        double v = fraction * 100;
        return v == Math.rint(v) ? Integer.toString((int) v) + "%" : v + "%";
    }

    private static double[] toArray(List<Double> list) {
        double[] a = new double[list.size()];
        for (int i = 0; i < list.size(); i++) a[i] = list.get(i);
        return a;
    }

    // ---- DTOs ---------------------------------------------------------------

    /**
     * §5 profile for one flush definition. Distributions are over the events that had forward data;
     * {@code censored} counts events that observed a full follow window without reaching +tp.
     * {@code tpFirst}/{@code slFirst}/{@code neither}/{@code ambiguous} sum to the classifiable
     * events (truncated ones near the series end are excluded). {@code furtherDrawdown} fractions
     * are negative (drawdown below the entry); {@code daysToTarget} is in sessions.
     */
    public record FlushProfile(String label, int days, double threshold, int eventCount, boolean smallN,
                               Stats.Dist furtherDrawdown, Stats.Dist daysToTarget,
                               int censored, int tpFirst, int slFirst, int neither, int ambiguous) {
    }

    /**
     * §6 barrier outcome for one time-stop N. {@code pctTp}/{@code pctSl}/{@code pctTime} are
     * fractions of the {@code trades} that exited by each route; {@code meanReturn} and
     * {@code meanHoldingDays} average over all trades; {@code returnDist} is the full return
     * distribution.
     */
    public record BarrierResult(int timeStop, int trades, boolean smallN,
                                double pctTp, double pctSl, double pctTime,
                                Double meanReturn, Double meanHoldingDays, Stats.Dist returnDist) {
    }
}
