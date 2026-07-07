package com.portfolio.domain.trend;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the filter says <em>today</em>: the latest close against each MA, and each signal
 * variant's current state plus the action it implies. Values are in signal-source units (close
 * by default). {@code pendingSwitch} means the state changed at the latest close — under
 * next-open execution that order hasn't filled yet.
 */
public final class LiveState {

    public record MaReadout(int length, double close, double ma, double distancePct, boolean above) {
    }

    public record VariantState(String key, String label, Integer maLength, boolean invested,
                               boolean pendingSwitch, String note) {
    }

    private LiveState() {
    }

    /** Latest close vs each MA length; lengths the series is too short for are omitted. */
    public static List<MaReadout> maReadouts(TrendSeries s, int[] lengths) {
        List<MaReadout> out = new ArrayList<>();
        int last = s.size() - 1;
        for (int len : lengths) {
            double ma = s.sma(len)[last];
            if (Double.isNaN(ma)) continue;
            double close = s.signal[last];
            out.add(new MaReadout(len, close, ma, close / ma - 1, close >= ma));
        }
        return out;
    }

    /** Today's state + implied action for every grid and monthly variant. */
    public static List<VariantState> variantStates(TrendSeries s, TrendConfig cfg) {
        List<VariantState> out = new ArrayList<>();
        int last = s.size() - 1;
        List<TrendSignal> all = new ArrayList<>(Signals.gridVariants(cfg));
        all.addAll(Signals.monthlyVariants(cfg));
        for (TrendSignal sig : all) {
            if (sig.firstValid(s) >= last) continue;
            boolean[] t = sig.targets(s);
            boolean invested = t[last];
            boolean pending = t[last] != t[last - 1];
            out.add(new VariantState(sig.key(), sig.label(), sig.maLength(), invested, pending,
                    note(s, cfg, sig, t, invested)));
        }
        return out;
    }

    private static String note(TrendSeries s, TrendConfig cfg, TrendSignal sig, boolean[] t, boolean invested) {
        int last = s.size() - 1;
        switch (sig.family()) {
            case "anchored" -> {
                int fv = sig.firstValid(s);
                int d = last - fv - cfg.anchorOffset();
                int next = d < 0 ? -d : (d % cfg.anchorEvery() == 0
                        ? cfg.anchorEvery() : cfg.anchorEvery() - d % cfg.anchorEvery());
                return (d >= 0 && d % cfg.anchorEvery() == 0 ? "checked today; " : "")
                        + "next check in " + next + " session" + (next == 1 ? "" : "s");
            }
            case "touch" -> {
                return "acts on the next close " + (invested ? "below" : "at/above") + " the MA";
            }
            case "bufferPct" -> {
                double ma = s.sma(sig.maLength())[last];
                double x = bandOf(sig);
                return invested
                        ? String.format(Locale.ROOT, "exits below %.2f (MA −%s%%)", ma * (1 - x), trim(x * 100))
                        : String.format(Locale.ROOT, "re-enters above %.2f (MA +%s%%)", ma * (1 + x), trim(x * 100));
            }
            case "bufferN" -> {
                double[] sma = s.sma(sig.maLength());
                int streak = 0;
                boolean below = s.signal[last] < sma[last];
                for (int i = last; i >= 0 && (s.signal[i] < sma[i]) == below; i--) streak++;
                return "needs consecutive closes " + (invested ? "below" : "at/above") + " the MA; current streak: "
                        + streak + (below ? " below" : " at/above");
            }
            case "monthly" -> {
                int m = Integer.parseInt(sig.key().substring("monthly-".length()));
                int ends = s.monthEndIdx.length;
                String asOf = ends == 0 ? "n/a" : s.dates[s.monthEndIdx[ends - 1]].toString();
                String tentative = "n/a";
                if (ends >= m - 1 && m >= 1) {
                    double sum = s.signal[last];
                    for (int j = ends - (m - 1); j < ends; j++) sum += s.signal[s.monthEndIdx[j]];
                    tentative = s.signal[last] >= sum / m ? "in" : "out";
                }
                return "state from month end " + asOf + "; tentative at today's close: " + tentative;
            }
            default -> {
                return "";
            }
        }
    }

    private static double bandOf(TrendSignal sig) {
        // key form: bufpct-<len>-<percent>
        String[] parts = sig.key().split("-");
        return Double.parseDouble(parts[parts.length - 1]) / 100.0;
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
