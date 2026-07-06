package com.portfolio.domain.swing;

/**
 * Abstraction over "does a flush fire at bar {@code i}?" — the single knob that lets the whole
 * §5/§6/placebo/bootstrap machinery run for two trigger <em>families</em> in parallel (§C):
 *
 * <ul>
 *   <li><b>Absolute</b> — the {@code days}-session cumulative return is ≤ −{@code threshold}
 *       (e.g. {@code 2d ≤ −10%}). Fires at a fixed magnitude regardless of the current vol regime.</li>
 *   <li><b>σ-scaled</b> — the cumulative return is ≤ −{@code k}×σ×√{@code days}, where σ is the
 *       look-ahead-free trailing daily σ measured strictly <em>before</em> the flush window
 *       ({@link SwingSeries#preWindowSigma}). Fires at each regime's own scale, so a low-vol bear
 *       (2022) generates events an absolute trigger would miss.</li>
 * </ul>
 *
 * <p>Both families read the cumulative return off {@code close} (split-adjusted) and share the
 * {@code 1e-9} epsilon so a round threshold stored as {@code −0.0999999998} still registers. All
 * cooldown / non-overlap / episode bookkeeping stays in {@link Flushes} — the trigger only decides
 * whether a single bar qualifies.
 */
public interface FlushTrigger {

    /** Tolerance so a threshold the user thinks of as exact still fires. */
    double EPS = 1e-9;

    /**
     * A fired flush. {@code cumReturn} is the (negative) {@code days}-session cumulative return;
     * {@code sigma} is the pre-window daily σ; {@code sigmaMultiple} = {@code cumReturn / (σ×√days)}
     * (how many σ the move was), {@link Double#NaN} when σ is unavailable.
     */
    record Fire(double cumReturn, double sigma, double sigmaMultiple) {
    }

    /** Look-back length of the flush window, in sessions. */
    int days();

    /** The trigger magnitude — the drop fraction for {@code abs}, the σ multiple {@code k} for {@code sigma}. */
    double magnitude();

    /** {@code "abs"} or {@code "sigma"}. */
    String kind();

    /** Human label, e.g. {@code "2d ≤ −10%"} or {@code "2d ≤ −2.5σ"}. */
    String label();

    /** Evaluate at bar {@code i}; returns {@code null} when the trigger does not fire (or can't be evaluated). */
    Fire fire(SwingSeries s, int i);

    static FlushTrigger absolute(int days, double threshold) {
        return new AbsoluteFlush(days, threshold);
    }

    static FlushTrigger sigma(int days, double k) {
        return new SigmaFlush(days, k);
    }

    /** Shared cumulative-return + σ-multiple computation; {@code null} when the window can't be formed. */
    private static Fire measure(SwingSeries s, int i, int days) {
        if (i < days) return null;
        double base = s.close[i - days];
        if (base <= 0 || s.close[i] <= 0) return null;
        double cum = s.close[i] / base - 1.0;
        double sigma = s.preWindowSigma(i, days);
        double mult = (Double.isNaN(sigma) || sigma <= 0)
                ? Double.NaN : cum / (sigma * Math.sqrt(days));
        return new Fire(cum, sigma, mult);
    }

    /** Trim a trailing ".0" so labels read "2.5σ" not "2.5σ" vs "2σ" not "2.0σ". */
    private static String trim(double v) {
        if (v == Math.rint(v)) return Integer.toString((int) v);
        return Double.toString(v);
    }

    private static String pct(double fraction) {
        double v = fraction * 100;
        return v == Math.rint(v) ? (int) v + "%" : v + "%";
    }

    /** {@code days}-session cumulative return ≤ −{@code threshold} (a fraction, e.g. 0.10 = 10%). */
    final class AbsoluteFlush implements FlushTrigger {
        private final int days;
        private final double threshold;

        AbsoluteFlush(int days, double threshold) {
            this.days = days;
            this.threshold = threshold;
        }

        @Override public int days() { return days; }
        @Override public double magnitude() { return threshold; }
        @Override public String kind() { return "abs"; }
        @Override public String label() { return days + "d ≤ −" + pct(threshold); }

        @Override public Fire fire(SwingSeries s, int i) {
            Fire m = measure(s, i, days);
            if (m == null) return null;
            return m.cumReturn() <= -threshold + EPS ? m : null;
        }
    }

    /** {@code days}-session cumulative return ≤ −{@code k}×σ×√{@code days} (σ = pre-window daily σ). */
    final class SigmaFlush implements FlushTrigger {
        private final int days;
        private final double k;

        SigmaFlush(int days, double k) {
            this.days = days;
            this.k = k;
        }

        @Override public int days() { return days; }
        @Override public double magnitude() { return k; }
        @Override public String kind() { return "sigma"; }
        @Override public String label() { return days + "d ≤ −" + trim(k) + "σ"; }

        @Override public Fire fire(SwingSeries s, int i) {
            Fire m = measure(s, i, days);
            if (m == null || Double.isNaN(m.sigma()) || m.sigma() <= 0) return null;   // need σ to scale
            double scale = k * m.sigma() * Math.sqrt(days);
            return m.cumReturn() <= -scale + EPS ? m : null;
        }
    }
}
