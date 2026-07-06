package com.portfolio.domain.swing;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * §B — per-calendar-year breakdown of flush events. Answers "in which market years did the trigger
 * fire, and how did it do in each, versus what the stock did anyway?". Events are the cooldown-deduped
 * set (§5 semantics), resolved at the reference time stop {@link SwingConfig#eventLogTimeStop()}, so a
 * year's outcome counts reconcile as {@code events = TP + SL + Time + Ambiguous + Open}. Each row also
 * carries the name's own calendar-year <em>total</em> return (from {@code adjClose}) for drift context;
 * pooled rows average that across contributing members (equal weight). Years with a calendar return
 * but no events are still shown (the trigger stayed silent while the stock moved).
 */
public final class AnnualBreakdown {

    private AnnualBreakdown() {
    }

    public static List<AnnualRow> annual(List<SwingSeries> seriesList, LocalDate cutoff,
                                         SwingConfig cfg, FlushTrigger trig) {
        int timeStop = cfg.eventLogTimeStop();
        double tp = cfg.takeProfit(), sl = cfg.stopLoss();
        Map<Integer, YearAcc> byYear = new TreeMap<>();
        Map<Integer, List<Double>> calByYear = new TreeMap<>();

        for (SwingSeries s : seriesList) {
            for (int i : Flushes.detectEvents(s, cutoff, trig, cfg.flushCooldown(), null)) {
                int y = s.dates[i].getYear();
                byYear.computeIfAbsent(y, k -> new YearAcc())
                        .add(Flushes.simulateTrade(s, i, tp, sl, timeStop));
            }
            for (var e : calendarReturns(s, cutoff).entrySet())
                calByYear.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
        }

        TreeSet<Integer> years = new TreeSet<>();
        years.addAll(byYear.keySet());
        years.addAll(calByYear.keySet());
        List<AnnualRow> out = new ArrayList<>();
        for (int y : years) {
            YearAcc a = byYear.get(y);
            Double cal = mean(calByYear.get(y));
            if (a == null) out.add(new AnnualRow(y, 0, 0, 0, 0, 0, 0, null, null, cal, false));
            else out.add(a.toRow(y, cal, cfg.smallNFloor()));
        }
        return out;
    }

    /** Calendar-year total return per year that touches the window, off {@code adjClose}. */
    private static Map<Integer, Double> calendarReturns(SwingSeries s, LocalDate cutoff) {
        TreeMap<Integer, Double> lastByYear = new TreeMap<>();
        TreeMap<Integer, Double> firstByYear = new TreeMap<>();
        TreeSet<Integer> yearsInWindow = new TreeSet<>();
        int startWin = s.startIndex(cutoff);
        for (int i = 0; i < s.size(); i++) {
            int y = s.dates[i].getYear();
            firstByYear.putIfAbsent(y, s.adjClose[i]);
            lastByYear.put(y, s.adjClose[i]);
            if (i >= startWin) yearsInWindow.add(y);
        }
        Map<Integer, Double> out = new TreeMap<>();
        for (int y : yearsInWindow) {
            Double end = lastByYear.get(y);
            Integer priorYear = lastByYear.lowerKey(y);            // greatest year < y present in the data
            Double base = priorYear != null ? lastByYear.get(priorYear) : firstByYear.get(y);
            if (end != null && base != null && base > 0) out.put(y, end / base - 1.0);
        }
        return out;
    }

    private static Double mean(List<Double> xs) {
        if (xs == null || xs.isEmpty()) return null;
        double sum = 0;
        int n = 0;
        for (Double x : xs) if (x != null && !x.isNaN()) { sum += x; n++; }
        return n == 0 ? null : sum / n;
    }

    private static final class YearAcc {
        int events, tp, sl, time, amb, open;
        final List<Double> returns = new ArrayList<>();

        void add(Flushes.Trade t) {
            events++;
            if (t == null) { open++; return; }
            switch (t.outcome()) {
                case TP -> { tp++; returns.add(t.ret()); }
                case SL -> { sl++; returns.add(t.ret()); }
                case TIME -> { time++; returns.add(t.ret()); }
                case AMBIGUOUS -> amb++;
                case OPEN -> open++;
            }
        }

        AnnualRow toRow(int year, Double cal, int floor) {
            double[] r = Flushes.toArray(returns);
            Double mean = returns.isEmpty() ? null : Stats.mean(r);
            Double median = returns.isEmpty() ? null : Stats.median(r);
            return new AnnualRow(year, events, tp, sl, time, amb, open, mean, median, cal,
                    events > 0 && returns.size() < floor);
        }
    }

    /**
     * One calendar year. {@code events} reconciles as {@code tp + sl + time + ambiguous + open};
     * {@code meanReturn}/{@code medianReturn} are over the resolved-with-a-return set (null when
     * none). {@code calendarReturn} is the name's own total return that year (pooled: member mean).
     * {@code smallN} flags a year with events but fewer than {@code smallNFloor} resolved trades.
     */
    public record AnnualRow(int year, int events, int tp, int sl, int time, int ambiguous, int open,
                            Double meanReturn, Double medianReturn, Double calendarReturn, boolean smallN) {
    }
}
