package com.portfolio.domain.trend;

import java.util.ArrayList;
import java.util.List;

/**
 * Factories for the signal variants (the "three clocks" of the spec, plus the classic monthly
 * cross-check). All compare the signal source to a trailing SMA; equality counts as <em>in</em>
 * everywhere, so the same bar can never read as both above and below.
 *
 * <ul>
 *   <li>{@link #monthAnchored}: evaluate only every {@code every} sessions (offset-shifted);
 *       carry state between checks. The anchor sweep runs every offset {@code 0..every−1}.</li>
 *   <li>{@link #firstTouch}: act on any daily close crossing the MA, either direction.</li>
 *   <li>{@link #bufferedPct}: hysteresis band — exit on close below MA×(1−x), re-enter on close
 *       above MA×(1+x); inside the band the state holds.</li>
 *   <li>{@link #bufferedCount}: exit after N consecutive closes below the MA, re-enter after N
 *       consecutive at-or-above; any opposite close resets the count.</li>
 *   <li>{@link #monthlyClose}: the classic monthly formulation — on each completed month's last
 *       trading day, in iff that close ≥ the mean of the last M month-end closes.</li>
 * </ul>
 */
public final class Signals {

    private Signals() {
    }

    /** The daily-signal grid: for each MA length — anchored, first-touch, each buffer variant. */
    public static List<TrendSignal> gridVariants(TrendConfig cfg) {
        List<TrendSignal> out = new ArrayList<>();
        for (int len : cfg.maLengths()) {
            out.add(monthAnchored(len, cfg.anchorEvery(), cfg.anchorOffset()));
            out.add(firstTouch(len));
            for (double x : cfg.bufferPcts()) out.add(bufferedPct(len, x));
            for (int n : cfg.bufferNs()) out.add(bufferedCount(len, n));
        }
        return out;
    }

    /** The monthly-close cross-check cells (their own MA concept, months not sessions). */
    public static List<TrendSignal> monthlyVariants(TrendConfig cfg) {
        List<TrendSignal> out = new ArrayList<>();
        for (int m : cfg.monthlyMaLengths()) out.add(monthlyClose(m));
        return out;
    }

    public static TrendSignal monthAnchored(int len, int every, int offset) {
        return new MonthAnchored(len, every, offset);
    }

    public static TrendSignal firstTouch(int len) {
        return new FirstTouch(len);
    }

    public static TrendSignal bufferedPct(int len, double x) {
        return new BufferedPct(len, x);
    }

    public static TrendSignal bufferedCount(int len, int n) {
        return new BufferedCount(len, n);
    }

    public static TrendSignal monthlyClose(int months) {
        return new MonthlyClose(months);
    }

    // ---- implementations -----------------------------------------------------

    private record MonthAnchored(int len, int every, int offset) implements TrendSignal {
        @Override public String key() { return "anchored-" + len + (offset == 0 ? "" : "@" + offset); }
        @Override public String family() { return "anchored"; }
        @Override public String label() { return "Month-anchored (every " + every + "d)"; }
        @Override public Integer maLength() { return len; }
        @Override public int firstValid(TrendSeries s) { return Math.min(len - 1, s.size()); }

        @Override
        public boolean[] targets(TrendSeries s) {
            double[] sma = s.sma(len);
            int n = s.size(), fv = firstValid(s);
            boolean[] t = new boolean[n];
            if (fv >= n) return t;
            boolean state = s.signal[fv] >= sma[fv];
            for (int i = 0; i < n; i++) {
                if (i > fv && (i - fv) >= offset && (i - fv - offset) % every == 0) {
                    state = s.signal[i] >= sma[i];
                }
                t[i] = state;
            }
            return t;
        }
    }

    private record FirstTouch(int len) implements TrendSignal {
        @Override public String key() { return "touch-" + len; }
        @Override public String family() { return "touch"; }
        @Override public String label() { return "Daily first-touch"; }
        @Override public Integer maLength() { return len; }
        @Override public int firstValid(TrendSeries s) { return Math.min(len - 1, s.size()); }

        @Override
        public boolean[] targets(TrendSeries s) {
            double[] sma = s.sma(len);
            int n = s.size(), fv = firstValid(s);
            boolean[] t = new boolean[n];
            if (fv >= n) return t;
            for (int i = fv; i < n; i++) t[i] = s.signal[i] >= sma[i];
            for (int i = 0; i < fv; i++) t[i] = t[fv];
            return t;
        }
    }

    private record BufferedPct(int len, double x) implements TrendSignal {
        @Override public String key() { return "bufpct-" + len + "-" + fmt(x * 100); }
        @Override public String family() { return "bufferPct"; }
        @Override public String label() { return "Buffered ±" + fmt(x * 100) + "%"; }
        @Override public Integer maLength() { return len; }
        @Override public int firstValid(TrendSeries s) { return Math.min(len - 1, s.size()); }

        @Override
        public boolean[] targets(TrendSeries s) {
            double[] sma = s.sma(len);
            int n = s.size(), fv = firstValid(s);
            boolean[] t = new boolean[n];
            if (fv >= n) return t;
            boolean state = s.signal[fv] >= sma[fv];
            for (int i = 0; i <= fv; i++) t[i] = state;
            for (int i = fv + 1; i < n; i++) {
                if (state && s.signal[i] < sma[i] * (1 - x)) state = false;
                else if (!state && s.signal[i] > sma[i] * (1 + x)) state = true;
                t[i] = state;
            }
            return t;
        }
    }

    private record BufferedCount(int len, int n) implements TrendSignal {
        @Override public String key() { return "bufn-" + len + "-" + n; }
        @Override public String family() { return "bufferN"; }
        @Override public String label() { return "Buffered " + n + "-in-a-row"; }
        @Override public Integer maLength() { return len; }
        @Override public int firstValid(TrendSeries s) { return Math.min(len - 1, s.size()); }

        @Override
        public boolean[] targets(TrendSeries s) {
            double[] sma = s.sma(len);
            int size = s.size(), fv = firstValid(s);
            boolean[] t = new boolean[size];
            if (fv >= size) return t;
            boolean state = s.signal[fv] >= sma[fv];
            for (int i = 0; i <= fv; i++) t[i] = state;
            int below = 0, above = 0;
            for (int i = fv + 1; i < size; i++) {
                if (s.signal[i] < sma[i]) { below++; above = 0; }
                else { above++; below = 0; }
                if (state && below >= n) state = false;
                else if (!state && above >= n) state = true;
                t[i] = state;
            }
            return t;
        }
    }

    private record MonthlyClose(int months) implements TrendSignal {
        @Override public String key() { return "monthly-" + months; }
        @Override public String family() { return "monthly"; }
        @Override public String label() { return "Monthly close, " + months + "-mo MA"; }
        @Override public Integer maLength() { return null; }

        @Override
        public int firstValid(TrendSeries s) {
            return s.monthEndIdx.length >= months ? s.monthEndIdx[months - 1] : s.size();
        }

        @Override
        public boolean[] targets(TrendSeries s) {
            double[] msma = s.monthlySmaFor(months);
            int n = s.size(), fv = firstValid(s);
            boolean[] t = new boolean[n];
            if (fv >= n) return t;
            boolean state = s.signal[fv] >= msma[fv];
            for (int i = 0; i <= fv; i++) t[i] = state;
            for (int i = fv + 1; i < n; i++) {
                if (!Double.isNaN(msma[i])) state = s.signal[i] >= msma[i];
                t[i] = state;
            }
            return t;
        }
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
