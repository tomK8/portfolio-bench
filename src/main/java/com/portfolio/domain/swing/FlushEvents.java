package com.portfolio.domain.swing;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * §A — the event log: one diagnostic row per flush event, the primary view that exposes the
 * clustering the aggregate tables hide. Events are the <em>cooldown-deduped</em> set (every down-leg
 * counts once, per §5 semantics — <b>not</b> the §6 non-overlap subset), so nothing is dropped just
 * because a prior simulated trade hadn't resolved. Each event is resolved at the reference time stop
 * {@link SwingConfig#eventLogTimeStop()} via the shared {@link Flushes#simulateTrade}, so its outcome
 * columns reconcile with §6.
 *
 * <p>Pools across {@code seriesList} (concatenate rows). {@code earnings} maps each symbol to its
 * bound {@link EarningsLookup}; a missing entry falls back to {@link EarningsLookup#UNVERIFIED}.
 */
public final class FlushEvents {

    private FlushEvents() {
    }

    public static List<FlushEventRow> log(List<SwingSeries> seriesList, LocalDate cutoff, SwingConfig cfg,
                                          FlushTrigger trig, Map<String, EarningsLookup> earnings) {
        List<FlushEventRow> out = new ArrayList<>();
        int timeStop = cfg.eventLogTimeStop();
        double tp = cfg.takeProfit(), sl = cfg.stopLoss();

        for (SwingSeries s : seriesList) {
            int n = s.size();
            EarningsLookup el = earnings == null ? EarningsLookup.UNVERIFIED
                    : earnings.getOrDefault(s.symbol(), EarningsLookup.UNVERIFIED);

            for (int i : Flushes.detectEvents(s, cutoff, trig, cfg.flushCooldown(), null)) {
                FlushTrigger.Fire f = trig.fire(s, i);   // detectEvents guarantees this fires
                double entry = s.close[i];

                // 200d-MA state + % distance
                Boolean above = Flushes.aboveMa(s, i);
                Double maDist = null;
                double ma = s.sma[i];
                if (!Double.isNaN(ma) && ma > 0) maDist = entry / ma - 1.0;

                // outcome at the reference time stop
                Flushes.Trade t = Flushes.simulateTrade(s, i, tp, sl, timeStop);
                Flushes.Outcome oc = t == null ? Flushes.Outcome.OPEN : t.outcome();
                Integer held = null;
                String exitDate = null;
                Double ret = null, cons = null;
                if (t != null && t.holdingDays() > 0) {
                    held = t.holdingDays();
                    int exIdx = i + t.holdingDays();
                    if (exIdx < n) exitDate = s.dates[exIdx].toString();
                }
                switch (oc) {
                    case TP, SL, TIME -> { ret = t.ret(); cons = t.ret(); }
                    case AMBIGUOUS -> cons = -sl;   // pessimistic booking, per §6 convention
                    case OPEN -> { }
                }

                // further drawdown to the lowest low of the next drawdownWindow sessions
                Double furtherDd = null;
                double minLow = Double.NaN;
                for (int j = i + 1; j <= i + cfg.drawdownWindow() && j < n; j++)
                    minLow = Double.isNaN(minLow) ? s.low[j] : Math.min(minLow, s.low[j]);
                if (!Double.isNaN(minLow) && entry > 0) furtherDd = minLow / entry - 1.0;

                // sessions-to-target (high first ≥ +tp); censored if the full follow window never hit it
                double tpLevel = entry * (1 + tp);
                Integer toTarget = null;
                boolean censored = false;
                boolean fullWindow = i + cfg.followSessions() < n;
                for (int j = i + 1; j <= i + cfg.followSessions() && j < n; j++) {
                    if (s.high[j] >= tpLevel) { toTarget = j - i; break; }
                }
                if (toTarget == null && fullWindow) censored = true;

                EarningsLookup.Info info = el.at(i);
                out.add(new FlushEventRow(
                        s.dates[i].toString(), s.symbol(), trig.label(), trig.kind(),
                        f == null ? Double.NaN : f.cumReturn(),
                        f == null ? null : nz(f.sigma()),
                        f == null ? null : nz(f.sigmaMultiple()),
                        above, maDist, entry,
                        oc.name(), exitDate, held, ret, cons, furtherDd, toTarget, censored,
                        el.verified(), info.tradingDaysToNearest(), info.withinWindow()));
            }
        }
        return out;
    }

    private static Double nz(double v) {
        return Double.isNaN(v) ? null : v;
    }

    /**
     * One flush event row (§A). {@code kind} is {@code "abs"}/{@code "sigma"}; {@code cumReturn} is
     * the flush's cumulative return; {@code sigmaMultiple} = {@code cumReturn / (σ×√N)}.
     * {@code aboveMa}/{@code maDistancePct} give the entry's position relative to its 200d MA.
     * {@code tradeReturn} is {@code null} for AMBIGUOUS/OPEN; {@code conservativeReturn} books an
     * ambiguous at −sl. {@code sessionsToTarget} is {@code null} when +target was never reached;
     * {@code targetCensored} flags a full-window event that never reached it. Earnings fields carry
     * the ±window flag and {@code earningsVerified=false} for unsourceable names.
     */
    public record FlushEventRow(
            String date, String symbol, String definition, String kind,
            double cumReturn, Double sigma, Double sigmaMultiple,
            Boolean aboveMa, Double maDistancePct, double entryClose,
            String outcome, String exitDate, Integer sessionsHeld,
            Double tradeReturn, Double conservativeReturn,
            Double furtherDrawdown, Integer sessionsToTarget, boolean targetCensored,
            boolean earningsVerified, Integer tradingDaysToEarnings, boolean earningsWithinWindow) {
    }
}
