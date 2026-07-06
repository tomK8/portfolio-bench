package com.portfolio.domain.swing;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * §5–§6 — flush events and the barrier / time-stop simulation, the calibration core of the study.
 * Pools across a list of {@link SwingSeries} just like {@link ForwardReturns}.
 *
 * <p><b>Flush event</b>: a session whose cumulative {@code days}-session return is ≤ −{@code
 * threshold}. Session {@code high}/{@code low} (available in {@code price_history}) drive both §5's
 * profile and §6's barrier touches — these are legitimate intraday extremes.
 *
 * <p><b>Event de-duplication (stated in the output).</b> The two sections dedup differently, on
 * purpose:
 * <ul>
 *   <li>§5 is a <em>descriptive</em> profile of what typically follows a flush, so it keeps every
 *       flush subject to a fixed {@code flushCooldown}-session cooldown (one down-leg counts once).</li>
 *   <li>§6 is a <em>tradeable</em> simulation, so it is strictly non-overlapping: after an entry, the
 *       next entry waits until the prior simulated trade resolves (TP/SL/ambiguous touch or the time
 *       stop). Overlapping trades are therefore excluded, and the event count legitimately shrinks as
 *       the time stop lengthens.</li>
 * </ul>
 *
 * <p><b>Touch convention (OHLC).</b> Barriers are evaluated intraday from the session's OHLC: a
 * take-profit is touched when {@code high ≥ entry×(1+tp)}, a stop when {@code low ≤ entry×(1−sl)}.
 * Fills are assumed at the barrier level; a time-stop exit is the close of session N. When both
 * barriers are touched in the <em>same</em> session the daily bar can't order them — that case is
 * recorded as <b>ambiguous</b> (excluded from the standard mean return; a separate conservative
 * figure treats every ambiguous as a stop-loss, a pessimistic bound). An event whose outcome window
 * extends past the last available bar is <b>open/censored</b> — never silently dropped, never
 * counted as an outcome.
 *
 * <p><b>Reconciliation identity</b>, enforced in every table: {@code events = tpFirst + slFirst +
 * timeStop(neither) + ambiguous + open}.
 */
public final class Flushes {

    /** Tolerance so a round threshold (e.g. −10% stored as −0.0999999998) still registers. */
    static final double EPS = 1e-9;

    private Flushes() {
    }

    // ---- shared trade simulation (reused by §6 and the placebo/bootstrap control) ----

    /** How a simulated trade ended. */
    enum Outcome { TP, SL, TIME, AMBIGUOUS, OPEN }

    /**
     * One simulated trade's outcome. {@code ret} is the realized simple return (the barrier level
     * for TP/SL, the close for TIME) and is {@link Double#NaN} for AMBIGUOUS and OPEN;
     * {@code holdingDays} is the exit offset in sessions ({@code −1} for OPEN); {@code date} is the
     * entry (event) date, used for episode de-duplication when pooling.
     */
    record Trade(Outcome outcome, double ret, int holdingDays, LocalDate date) {
    }

    /**
     * Simulate a long entered at {@code close[i]} with an OHLC triple barrier and a {@code timeStop}.
     * Returns {@code null} only when the entry close is non-positive.
     */
    static Trade simulateTrade(SwingSeries s, int i, double tp, double sl, int timeStop) {
        int n = s.size();
        double entry = s.close[i];
        if (entry <= 0) return null;
        double tpLevel = entry * (1 + tp);
        double slLevel = entry * (1 - sl);
        int last = Math.min(i + timeStop, n - 1);
        for (int j = i + 1; j <= last; j++) {
            boolean tpTouch = s.high[j] >= tpLevel - EPS;
            boolean slTouch = s.low[j] <= slLevel + EPS;
            if (tpTouch && slTouch) return new Trade(Outcome.AMBIGUOUS, Double.NaN, j - i, s.dates[i]);
            if (tpTouch) return new Trade(Outcome.TP, tp, j - i, s.dates[i]);
            if (slTouch) return new Trade(Outcome.SL, -sl, j - i, s.dates[i]);
        }
        if (i + timeStop <= n - 1) {                       // full window observed, no barrier touched
            double ret = s.close[i + timeStop] / entry - 1.0;
            return new Trade(Outcome.TIME, ret, timeStop, s.dates[i]);
        }
        return new Trade(Outcome.OPEN, Double.NaN, -1, s.dates[i]);   // window runs past the data
    }

    /**
     * Aggregate simulated trades into counts and return statistics. The standard mean return / dist
     * / holding-days are over the resolved-with-a-return set (TP/SL/TIME); ambiguous and open are
     * excluded from those but counted. {@code meanReturnConservative} additionally books every
     * ambiguous at {@code −sl}.
     */
    static BarrierAgg aggregate(List<Trade> trades, double sl) {
        int tp = 0, slc = 0, time = 0, amb = 0, open = 0;
        List<Double> stdReturns = new ArrayList<>();
        List<Double> stdDays = new ArrayList<>();
        List<Double> consReturns = new ArrayList<>();
        for (Trade t : trades) {
            switch (t.outcome()) {
                case TP -> { tp++; stdReturns.add(t.ret()); stdDays.add((double) t.holdingDays()); consReturns.add(t.ret()); }
                case SL -> { slc++; stdReturns.add(t.ret()); stdDays.add((double) t.holdingDays()); consReturns.add(t.ret()); }
                case TIME -> { time++; stdReturns.add(t.ret()); stdDays.add((double) t.holdingDays()); consReturns.add(t.ret()); }
                case AMBIGUOUS -> { amb++; consReturns.add(-sl); }
                case OPEN -> open++;
            }
        }
        double[] retArr = toArray(stdReturns);
        double meanRet = stdReturns.isEmpty() ? Double.NaN : Stats.mean(retArr);
        double meanDays = stdDays.isEmpty() ? Double.NaN : Stats.mean(toArray(stdDays));
        double meanCons = consReturns.isEmpty() ? Double.NaN : Stats.mean(toArray(consReturns));
        return new BarrierAgg(trades.size(), tp, slc, time, amb, open,
                meanRet, meanDays, Stats.dist(retArr), meanCons);
    }

    /**
     * Intermediate aggregate used by §6 and the placebo/bootstrap control. {@code %TP-first} is
     * over the <em>closed</em> set (everything but open), so the placebo pool (which has no open
     * trades) and the flush set are compared on the same basis.
     */
    record BarrierAgg(int total, int tp, int sl, int time, int ambiguous, int open,
                      double meanReturn, double meanHoldingDays, Stats.Dist returnDist,
                      double meanReturnConservative) {
        int closed() { return tp + sl + time + ambiguous; }

        double pctTp() { return closed() == 0 ? Double.NaN : (double) tp / closed(); }
    }

    // ---- trigger families ---------------------------------------------------

    /** §5 absolute flush definitions: {@code flushDays × flushThresholds}. */
    public static List<FlushTrigger> absoluteFlushTriggers(SwingConfig cfg) {
        List<FlushTrigger> out = new ArrayList<>();
        for (int days : cfg.flushDays())
            for (double thr : cfg.flushThresholds())
                out.add(FlushTrigger.absolute(days, thr));
        return out;
    }

    /** §5 σ-scaled flush definitions (§C): {@code flushDays × flushSigmaMultiples}. */
    public static List<FlushTrigger> sigmaFlushTriggers(SwingConfig cfg) {
        List<FlushTrigger> out = new ArrayList<>();
        for (int days : cfg.flushDays())
            for (double k : cfg.flushSigmaMultiples())
                out.add(FlushTrigger.sigma(days, k));
        return out;
    }

    /** The single absolute definition that seeds the §6 barrier simulation. */
    public static FlushTrigger absoluteBarrierTrigger(SwingConfig cfg) {
        return FlushTrigger.absolute(cfg.barrierFlushDays(), cfg.barrierFlushDrop());
    }

    /** The single σ-scaled definition that seeds the §6 barrier simulation (§C). */
    public static FlushTrigger sigmaBarrierTrigger(SwingConfig cfg) {
        return FlushTrigger.sigma(cfg.barrierFlushDays(), cfg.barrierSigmaK());
    }

    // ---- §5 -----------------------------------------------------------------

    /** §5 profiles for the absolute flush family (backward-compatible convenience). */
    public static List<FlushProfile> profiles(List<SwingSeries> seriesList, LocalDate cutoff, SwingConfig cfg) {
        return profiles(seriesList, cutoff, cfg, absoluteFlushTriggers(cfg), null);
    }

    /** §5 profiles for an explicit set of triggers, optionally excluding a set of event dates (§D). */
    public static List<FlushProfile> profiles(List<SwingSeries> seriesList, LocalDate cutoff, SwingConfig cfg,
                                              List<FlushTrigger> triggers, EventFilter exclude) {
        List<FlushProfile> out = new ArrayList<>();
        for (FlushTrigger trig : triggers) out.add(profile(seriesList, cutoff, cfg, trig, exclude));
        return out;
    }

    private static FlushProfile profile(List<SwingSeries> seriesList, LocalDate cutoff,
                                        SwingConfig cfg, FlushTrigger trig, EventFilter exclude) {
        double tp = cfg.takeProfit();
        double sl = cfg.stopLoss();
        String label = trig.label();

        int eventCount = 0, targetCensored = 0, tpFirst = 0, slFirst = 0, neither = 0, ambiguous = 0, open = 0;
        Set<LocalDate> episodeDates = new HashSet<>();
        List<Double> furtherDd = new ArrayList<>();
        List<Double> daysToTarget = new ArrayList<>();

        for (SwingSeries s : seriesList) {
            int n = s.size();
            for (int i : detectEvents(s, cutoff, trig, cfg.flushCooldown(), exclude)) {
                eventCount++;
                episodeDates.add(s.dates[i]);
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
                else if (fullWindow) targetCensored++;   // full window observed, never reached target

                // Which barrier is touched first (over the same follow window) — full 5-way accounting.
                int outcome = touchFirst(s, i, n, tpLevel, slLevel, cfg.followSessions());
                switch (outcome) {
                    case TP_FIRST -> tpFirst++;
                    case SL_FIRST -> slFirst++;
                    case BOTH -> ambiguous++;
                    case NONE -> neither++;        // full window observed, neither barrier touched
                    case TRUNCATED -> open++;      // window runs past the data → open/censored
                    default -> { }
                }
            }
        }
        int episodes = episodeDates.size();
        return new FlushProfile(label, trig.days(), trig.magnitude(), eventCount, episodes,
                eventCount < cfg.smallNFloor(), episodes < cfg.episodeFloor(),
                Stats.dist(toArray(furtherDd)), Stats.dist(toArray(daysToTarget)),
                targetCensored, tpFirst, slFirst, neither, ambiguous, open);
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

    /** §6 barriers seeded by the absolute flush trigger (backward-compatible convenience). */
    public static List<BarrierResult> barriers(List<SwingSeries> seriesList, LocalDate cutoff, SwingConfig cfg) {
        return barriers(seriesList, cutoff, cfg, absoluteBarrierTrigger(cfg), null);
    }

    /** §6 barriers seeded by an explicit trigger, optionally excluding earnings-window dates (§D). */
    public static List<BarrierResult> barriers(List<SwingSeries> seriesList, LocalDate cutoff, SwingConfig cfg,
                                               FlushTrigger trig, EventFilter exclude) {
        List<BarrierResult> out = new ArrayList<>();
        for (int stop : cfg.timeStops()) {
            out.add(barrier(seriesList, cutoff, cfg, trig, stop, exclude));
        }
        return out;
    }

    private static BarrierResult barrier(List<SwingSeries> seriesList, LocalDate cutoff,
                                         SwingConfig cfg, FlushTrigger trig, int timeStop, EventFilter exclude) {
        List<Trade> trades = new ArrayList<>();
        Set<LocalDate> episodeDates = new HashSet<>();
        for (SwingSeries s : seriesList) {
            for (MaTrade mt : barrierTrades(s, cutoff, cfg, trig, timeStop, exclude)) {
                trades.add(mt.trade());
                episodeDates.add(mt.trade().date());
            }
        }
        return barrierResult(timeStop, trades, episodeDates, cfg.stopLoss(), cfg);
    }

    /**
     * Non-overlapping barrier walk for one series + time stop: at each qualifying flush (per
     * {@code trig}), enter and simulate; the next entry waits until the prior trade resolves. An
     * event whose date is in {@code exclude} (§D earnings window) is skipped <em>without</em>
     * consuming a slot, so the ex-earnings variant simply removes those trades. Each returned trade
     * is tagged with its entry 200d-MA state (§E).
     */
    static List<MaTrade> barrierTrades(SwingSeries s, LocalDate cutoff, SwingConfig cfg,
                                       FlushTrigger trig, int timeStop, EventFilter exclude) {
        List<MaTrade> out = new ArrayList<>();
        int n = s.size();
        int start = Math.max(trig.days(), s.startIndex(cutoff));
        int nextAllowed = start;                           // non-overlapping: wait until prior trade resolves
        for (int i = start; i < n; i++) {
            if (i < nextAllowed) continue;
            if (trig.fire(s, i) == null) continue;
            if (exclude != null && exclude.excludes(s.symbol(), s.dates[i])) continue;
            Trade t = simulateTrade(s, i, cfg.takeProfit(), cfg.stopLoss(), timeStop);
            if (t == null) continue;
            out.add(new MaTrade(t, aboveMa(s, i)));
            int resolveDay = t.holdingDays() > 0 ? t.holdingDays() : timeStop;   // OPEN → lock past data
            nextAllowed = i + Math.max(1, resolveDay);
        }
        return out;
    }

    /** Entry-bar 200d-MA state: {@code TRUE} at/above, {@code FALSE} below, {@code null} when the MA is undefined. */
    static Boolean aboveMa(SwingSeries s, int i) {
        double ma = s.sma[i];
        if (Double.isNaN(ma)) return null;
        return s.close[i] >= ma;
    }

    /** A simulated trade tagged with its entry bar's 200d-MA state (for §E partitioning). */
    record MaTrade(Trade trade, Boolean aboveMa) {
    }

    /** Build a {@link BarrierResult} from a set of simulated trades (shared by §6 and the placebo). */
    static BarrierResult barrierResult(int timeStop, List<Trade> trades, Set<LocalDate> episodeDates,
                                       double sl, SwingConfig cfg) {
        BarrierAgg agg = aggregate(trades, sl);
        int events = trades.size();
        int episodes = episodeDates.size();
        return new BarrierResult(timeStop, events, episodes,
                events < cfg.smallNFloor(), episodes < cfg.episodeFloor(),
                agg.tp(), agg.sl(), agg.time(), agg.ambiguous(), agg.open(),
                nullIfNaN(agg.meanReturn()), nullIfNaN(agg.meanHoldingDays()),
                agg.returnDist(), nullIfNaN(agg.meanReturnConservative()));
    }

    // ---- shared -------------------------------------------------------------

    /**
     * §C — number of dates on which both {@code a} and {@code b} fire (summed across series). Used to
     * report the absolute/σ family overlap; the two families are never pooled into a combined table,
     * so this is only a diagnostic count.
     */
    public static int overlapCount(List<SwingSeries> seriesList, LocalDate cutoff, FlushTrigger a, FlushTrigger b) {
        int overlap = 0;
        for (SwingSeries s : seriesList) {
            Set<LocalDate> ad = firedDates(s, cutoff, a);
            ad.retainAll(firedDates(s, cutoff, b));
            overlap += ad.size();
        }
        return overlap;
    }

    private static Set<LocalDate> firedDates(SwingSeries s, LocalDate cutoff, FlushTrigger trig) {
        Set<LocalDate> out = new HashSet<>();
        int start = Math.max(trig.days(), s.startIndex(cutoff));
        for (int i = start; i < s.size(); i++) if (trig.fire(s, i) != null) out.add(s.dates[i]);
        return out;
    }

    /**
     * Cooldown-deduped event bar indices where {@code trig} fires, excluding any bar whose date is in
     * {@code exclude} (§D earnings window). Shared by §5 and the §A/§B engines so all three see the
     * same descriptive event set.
     */
    static List<Integer> detectEvents(SwingSeries s, LocalDate cutoff, FlushTrigger trig,
                                      int cooldown, EventFilter exclude) {
        List<Integer> events = new ArrayList<>();
        int n = s.size();
        int start = Math.max(trig.days(), s.startIndex(cutoff));
        int nextAllowed = start;
        for (int i = start; i < n; i++) {
            if (i < nextAllowed) continue;
            if (trig.fire(s, i) == null) continue;
            if (exclude != null && exclude.excludes(s.symbol(), s.dates[i])) continue;
            events.add(i);
            nextAllowed = i + Math.max(1, cooldown);
        }
        return events;
    }

    static double[] toArray(List<Double> list) {
        double[] a = new double[list.size()];
        for (int i = 0; i < list.size(); i++) a[i] = list.get(i);
        return a;
    }

    static Double nullIfNaN(double v) {
        return Double.isNaN(v) ? null : v;
    }

    // ---- DTOs ---------------------------------------------------------------

    /**
     * §5 profile for one flush definition. {@code eventCount} reconciles as
     * {@code tpFirst + slFirst + neither + ambiguous + open}. {@code episodes} is the number of
     * distinct event <em>dates</em> (equals {@code eventCount} for a single symbol; smaller when
     * pooling a cluster that flushes on shared calendar dates); {@code thinEpisodes} flags fewer
     * than {@code episodeFloor} episodes. {@code targetCensored} counts full-window events that
     * never reached +tp (a days-to-target censoring, distinct from the barrier {@code open}).
     * {@code furtherDrawdown} fractions are negative; {@code daysToTarget} is in sessions.
     */
    public record FlushProfile(String label, int days, double threshold, int eventCount, int episodes,
                               boolean smallN, boolean thinEpisodes,
                               Stats.Dist furtherDrawdown, Stats.Dist daysToTarget,
                               int targetCensored, int tpFirst, int slFirst, int neither, int ambiguous, int open) {
    }

    /**
     * §6 barrier outcome for one time-stop N. {@code events} reconciles as
     * {@code tpFirst + slFirst + timeCount + ambiguous + open}. Percentages are derived over the
     * <em>closed</em> set (everything but {@code open}). {@code meanReturn}/{@code returnDist}/
     * {@code meanHoldingDays} are over the resolved-with-a-return set (TP/SL/TIME);
     * {@code meanReturnConservative} additionally books each ambiguous at −sl.
     */
    public record BarrierResult(int timeStop, int events, int episodes, boolean smallN, boolean thinEpisodes,
                                int tpFirst, int slFirst, int timeCount, int ambiguous, int open,
                                Double meanReturn, Double meanHoldingDays, Stats.Dist returnDist,
                                Double meanReturnConservative) {

        /** Resolved-and-decided trades: everything but the still-open ones. */
        public int closed() { return tpFirst + slFirst + timeCount + ambiguous; }

        public double pctTp() { return closed() == 0 ? 0.0 : (double) tpFirst / closed(); }

        public double pctSl() { return closed() == 0 ? 0.0 : (double) slFirst / closed(); }

        public double pctTime() { return closed() == 0 ? 0.0 : (double) timeCount / closed(); }
    }
}
