package com.portfolio.domain.trend;

import java.util.ArrayList;
import java.util.List;

/**
 * The exportable trade log: one row per exit → re-entry pair, with the signal-day MA diagnostics
 * and the trip's total-return arithmetic. Prices are display (raw) fills; {@code
 * reentryVsExitPct} compares the total-return fills (so a dividend paid while out counts), and
 * {@code tripEffectPct} is the trip's compounded effect versus having stayed in — cash earned
 * times both cost sides over the market move — the same factor {@link Whipsaw} compounds.
 *
 * <p>A window that starts out of the market opens with a re-entry-only row (null exit side,
 * {@code daysOut} counted from the analysis start); a trailing exit that hasn't re-entered keeps
 * null re-entry fields with {@code daysOut} elapsed so far — censored, shown, never dropped.
 */
public final class TradeLog {

    public record Row(
            String exitDate, Double exitPrice, Double exitMa, Double exitDistancePct,
            String reentryDate, Double reentryPrice, Double reentryMa, Double reentryDistancePct,
            Integer daysOut, Double reentryVsExitPct, Double tripEffectPct) {
    }

    private TradeLog() {
    }

    public static List<Row> rows(TrendSeries s, Backtester.Result r, TrendSignal sig, double costPerSide) {
        double[] ma = sig.maSeries(s);
        List<Row> out = new ArrayList<>();
        Backtester.Trade exit = null;
        for (Backtester.Trade t : r.trades()) {
            if (!t.entering()) {
                exit = t;
                continue;
            }
            if (exit == null) {
                out.add(new Row(null, null, null, null,
                        s.dates[t.fillIndex()].toString(), t.fillDisplay(),
                        maAt(ma, t.signalIndex()), distance(s, ma, t.signalIndex()),
                        t.fillIndex() - r.start(), null, null));
                continue;
            }
            double cash = 1;
            for (int j = exit.fillIndex() + 1; j <= t.fillIndex(); j++) cash *= 1 + s.cashDaily[j];
            double stayedIn = t.fillAdj() / exit.fillAdj();
            out.add(new Row(
                    s.dates[exit.fillIndex()].toString(), exit.fillDisplay(),
                    maAt(ma, exit.signalIndex()), distance(s, ma, exit.signalIndex()),
                    s.dates[t.fillIndex()].toString(), t.fillDisplay(),
                    maAt(ma, t.signalIndex()), distance(s, ma, t.signalIndex()),
                    t.fillIndex() - exit.fillIndex(),
                    stayedIn - 1,
                    cash * (1 - costPerSide) * (1 - costPerSide) / stayedIn - 1));
            exit = null;
        }
        if (exit != null) {
            out.add(new Row(
                    s.dates[exit.fillIndex()].toString(), exit.fillDisplay(),
                    maAt(ma, exit.signalIndex()), distance(s, ma, exit.signalIndex()),
                    null, null, null, null,
                    s.size() - 1 - exit.fillIndex(), null, null));
        }
        return out;
    }

    private static Double maAt(double[] ma, int i) {
        return Double.isNaN(ma[i]) ? null : ma[i];
    }

    private static Double distance(TrendSeries s, double[] ma, int i) {
        return Double.isNaN(ma[i]) ? null : s.signal[i] / ma[i] - 1;
    }
}
