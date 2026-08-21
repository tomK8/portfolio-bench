package com.portfolio.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The volatility forecast behind the open-orders touch probabilities, and the single point where
 * {@code sigma_eff} is resolved.
 *
 * <p><b>Why a forecast rather than a trailing window.</b> The probability that a resting limit is
 * touched within {@code N} sessions is scaled by the volatility <em>over those N sessions</em>,
 * which is a forecast, not a measurement. Feeding it a trailing realized vol measured over the same
 * length of window is the natural-looking mistake: realized vol mean-reverts, so a name that has
 * just had a violent fortnight is priced as though the next quarter will be equally violent, and a
 * name asleep for a month is priced as though it stays asleep. What the model wants is the expected
 * <em>average</em> variance over the horizon ahead.
 *
 * <p>So current state is blended toward a long-run anchor, with the weight decaying over the
 * horizon at a tunable half-life:
 * <pre>
 *   kappa   = ln(2) / halfLifeDays
 *   var_now = RV20²                       (daily-return variance, current state)
 *   var_LR  = RV250²                      (daily-return variance, long-run anchor)
 *   decay   = (1 − exp(−kappa·N)) / (kappa·N)
 *   var_N   = var_LR + (var_now − var_LR)·decay
 *   sigma_eff = √var_N
 * </pre>
 * {@code decay} is the average of {@code exp(−kappa·t)} over {@code t ∈ [0, N]} — it goes to 1 as
 * {@code N → 0} (a one-session horizon is all current state) and to 0 as {@code N} grows (a
 * multi-year horizon is all anchor). Every input is a <b>daily-return standard deviation</b>, the
 * same units {@code sigma_eff} comes back in and the same units {@link PriceStats#sigmaDistance}
 * consumes; nothing here is annualised.
 *
 * <p><b>Total variance still grows with the horizon</b>, which is what keeps the probability
 * well-behaved: {@code N·var_N = N·var_LR + (var_now − var_LR)(1 − e^{−kN})/k}, whose derivative
 * {@code var_LR + (var_now − var_LR)e^{−kN}} is bounded below by {@code min(var_now, var_LR) > 0}.
 * So {@code σ_h = sigma_eff·√N} is strictly increasing in {@code N} even when the blend is pulling
 * {@code sigma_eff} <em>down</em> — P(touch) never goes backwards as the horizon lengthens.
 *
 * <p><b>Everything routes through {@link #resolve}.</b> The first-passage call site takes whatever
 * {@code sigma_eff} comes back and asks no questions, so a later implied-vol or event-variance
 * source can be swapped in here without touching it — that is what the {@code useImpliedVol} and
 * {@code useEventVar} flags on {@link Config} are reserved for; both default to false and are
 * no-ops today. {@code useDrift} is wired: see {@link #touch}.
 */
public final class VolForecast {

    /** Which estimate {@code sigma_eff} actually came from — recorded per order for calibration. */
    public enum Source {
        /** The mean-reversion blend of current state and the long-run anchor: the normal path. */
        BLEND,
        /** No long-run anchor (short history) — current state used flat, decay reported as 1. */
        CURRENT_ONLY,
        /** No current-state window — the anchor used flat, decay reported as 0. */
        ANCHOR_ONLY,
        /** Nothing usable; no probability can be formed. */
        NONE
        // Later sources (implied vol, event-adjusted variance) add their constants here, so a
        // calibration record always says which model produced the number it is judging.
    }

    /**
     * Knobs for the forecast. The defaults are the shipped ones; a caller may override them per
     * request, which is why {@code halfLifeDays} and {@code gtcHorizonSessions} are parameters
     * rather than constants.
     *
     * @param halfLifeDays       sessions over which the weight on current state halves. Short =
     *                           trust the recent window and revert fast; long = let today's regime
     *                           carry further out.
     * @param gtcHorizonSessions horizon for an order with no dated expiry. A GTC order has no
     *                           deadline of its own, so one has to be assumed to price it at all;
     *                           90 sessions is about two quarters.
     * @param useDrift           use the drifted first passage, with the drift of the instrument's
     *                           {@link AssetClass}. Default false: driftless is the neutral
     *                           convention, and a wrong drift is worse than none.
     * @param useEventVar        reserved: add scheduled-event (earnings) variance inside the
     *                           horizon. No-op.
     * @param useImpliedVol      reserved: take {@code sigma_eff} from an option-implied term
     *                           structure instead of realized windows. No-op.
     */
    public record Config(double halfLifeDays, int gtcHorizonSessions,
                         boolean useDrift, boolean useEventVar, boolean useImpliedVol) {

        public static final double DEFAULT_HALF_LIFE_DAYS = 20.0;
        public static final int DEFAULT_GTC_HORIZON_SESSIONS = 90;

        public static final Config DEFAULT = new Config(
                DEFAULT_HALF_LIFE_DAYS, DEFAULT_GTC_HORIZON_SESSIONS, false, false, false);

        public Config {
            if (halfLifeDays <= 0) halfLifeDays = DEFAULT_HALF_LIFE_DAYS;
            if (gtcHorizonSessions < 1) gtcHorizonSessions = DEFAULT_GTC_HORIZON_SESSIONS;
        }

        /** Defaults for anything the caller left null — the query-param entry point. */
        public static Config of(Double halfLifeDays, Integer gtcHorizonSessions) {
            return of(halfLifeDays, gtcHorizonSessions, null);
        }

        public static Config of(Double halfLifeDays, Integer gtcHorizonSessions, Boolean useDrift) {
            return new Config(
                    halfLifeDays == null ? DEFAULT_HALF_LIFE_DAYS : halfLifeDays,
                    gtcHorizonSessions == null ? DEFAULT_GTC_HORIZON_SESSIONS : gtcHorizonSessions,
                    useDrift != null && useDrift, false, false);
        }
    }

    /**
     * The realized-vol windows a forecast is built from, all daily-return standard deviations.
     * {@code rv20} is current state and {@code rv250} the long-run anchor; {@code rv10} and
     * {@code rv30} carry the displayed columns and bracket the headline with a plausible range.
     */
    public record Inputs(BigDecimal rv10, BigDecimal rv20, BigDecimal rv30, BigDecimal rv250) {

        public static final Inputs NONE = new Inputs(null, null, null, null);
    }

    /** A resolved {@code sigma_eff}, with the provenance a calibration record needs. */
    public record Forecast(BigDecimal sigmaEff, BigDecimal decay, Source source) {

        public static final Forecast NONE = new Forecast(null, null, Source.NONE);
    }

    /**
     * A priced horizon: the forecast, the σ distance to the limit and the touch probability, plus
     * the same probability recomputed off RV10 and RV30 as a plausibility range.
     *
     * <p>The range is the honest part of the display. The headline is one point estimate from one
     * vol model; if the low end already clears whatever threshold the reader is deciding against,
     * the call is robust to the vol input, and if only the high end does, the number is doing more
     * work than it can bear.
     */
    public record Touch(int sessions, BigDecimal sigmaEff, BigDecimal decay, Source source,
                        BigDecimal sigmaToLimit, BigDecimal probability,
                        BigDecimal probabilityLow, BigDecimal probabilityHigh,
                        /** Log drift per session actually applied; ZERO on the driftless branch. */
                        BigDecimal drift) {

        public static Touch none(int sessions) {
            return new Touch(sessions, null, null, Source.NONE, null, null, null, null,
                    BigDecimal.ZERO);
        }

        /** True when the drifted first passage priced this, rather than the driftless default. */
        public boolean drifted() {
            return drift != null && drift.signum() != 0;
        }

        /** True when the RV10/RV30 bracket exists and is wide enough to be worth reading. */
        public boolean hasRange() {
            return probabilityLow != null && probabilityHigh != null;
        }
    }

    private static final int SCALE = 8;

    private VolForecast() {
    }

    /**
     * The weight on current state over an {@code N}-session horizon:
     * {@code (1 − exp(−kappa·N)) / (kappa·N)}, the mean of {@code exp(−kappa·t)} over the horizon.
     *
     * <p>Both ends are limits rather than special cases, and the {@code N → 0} one is a genuine
     * 0/0: the series {@code 1 − kN/2 + (kN)²/6 − …} is used below {@link #SMALL_KN} so the value
     * approaches 1 smoothly instead of dividing two numbers that are both on their way to zero.
     */
    public static double decayWeight(double halfLifeDays, double horizonSessions) {
        if (horizonSessions <= 0) return 1.0;                 // no horizon: entirely current state
        if (halfLifeDays <= 0) return 0.0;                    // instant reversion: entirely anchor
        double kappa = Math.log(2.0) / halfLifeDays;
        double kn = kappa * horizonSessions;
        if (kn < SMALL_KN) return 1.0 - kn / 2.0 + kn * kn / 6.0;
        return (1.0 - Math.exp(-kn)) / kn;
    }

    /** Below this the closed form is 0/0 in floating point and the series expansion is used. */
    private static final double SMALL_KN = 1e-6;

    /**
     * {@code sigma_eff} for an {@code N}-session horizon — <b>the single resolution point</b>. Every
     * touch probability in the app is scaled by whatever this returns, so a later vol source
     * (implied, event-adjusted) replaces the body of this method and nothing downstream changes.
     *
     * <p>Degrades rather than failing: with no anchor the current window is used flat (decay 1),
     * with no current window the anchor is (decay 0), with neither the forecast is
     * {@link Forecast#NONE} and the caller renders "—".
     */
    public static Forecast resolve(Inputs in, int horizonSessions, Config cfg) {
        Config c = cfg == null ? Config.DEFAULT : cfg;
        int n = Math.max(1, horizonSessions);

        // Reserved sources. Both are no-ops today and fall through to the realized blend; they are
        // named here so that when one arrives it arrives in this method and nowhere else.
        if (c.useImpliedVol()) {
            // future: implied-vol term structure interpolated at n sessions
        }
        if (c.useEventVar()) {
            // future: + scheduled-event variance for earnings dates inside the horizon
        }

        BigDecimal now = positiveOrNull(in == null ? null : in.rv20());
        BigDecimal anchor = positiveOrNull(in == null ? null : in.rv250());
        if (now == null && anchor == null) return Forecast.NONE;
        if (anchor == null) return new Forecast(now, BigDecimal.ONE, Source.CURRENT_ONLY);
        if (now == null) return new Forecast(anchor, BigDecimal.ZERO, Source.ANCHOR_ONLY);

        double varNow = now.doubleValue() * now.doubleValue();
        double varLr = anchor.doubleValue() * anchor.doubleValue();
        double decay = decayWeight(c.halfLifeDays(), n);
        double varN = varLr + (varNow - varLr) * decay;
        if (varN <= 0) return Forecast.NONE;   // only reachable if both windows are degenerate
        return new Forecast(
                BigDecimal.valueOf(Math.sqrt(varN)).setScale(SCALE, RoundingMode.HALF_UP),
                BigDecimal.valueOf(decay).setScale(4, RoundingMode.HALF_UP),
                Source.BLEND);
    }

    /**
     * Price the touch of {@code limit} from {@code current} over {@code sessions}, with the
     * horizon-matched {@code sigma_eff} — the call the whole panel goes through.
     *
     * <p>No asset class stated, so no drift can be applied whatever the config says. This is the
     * form a caller uses when it genuinely doesn't know what it is pricing.
     */
    public static Touch touch(Inputs in, BigDecimal current, BigDecimal limit,
                              int sessions, Config cfg) {
        return touch(in, current, limit, sessions, cfg, AssetClass.UNKNOWN);
    }

    /**
     * Price the touch of {@code limit} from {@code current} over {@code sessions}, for an
     * instrument of the given asset class.
     *
     * <p>Two models, one branch, chosen here:
     * <pre>
     *   useDrift &amp;&amp; μ ≠ 0  →  drifted first passage   (PriceStats.touchProbabilityDrifted)
     *   otherwise          →  2·Φ(−|b| / (σ_eff·√N))  (PriceStats.touchProbability)
     * </pre>
     * <b>The two are mathematically identical at μ = 0</b>, so the short-circuit is a numerical
     * safeguard rather than a behavioural switch: the drifted form carries an
     * {@code exp(2νb/σ²)} term that is {@code 0/0} when σ is degenerate, and taking the driftless
     * branch avoids evaluating it at all when it could only ever contribute {@code exp(0) = 1}.
     * The equivalence is asserted both ways — directly on the two formulas, and through this
     * method with the flag <em>on</em> against a zero-drift asset class, which is what shows the
     * wiring didn't change the maths.
     *
     * <p>Drift comes from the {@link AssetClass}, never from the ticker: an expected return per
     * name would be a forecast of that name wearing a parameter's clothing. Default is
     * {@code useDrift = false} — driftless is the neutral convention over a short horizon, and over
     * a long one a wrong drift does more damage than no drift.
     *
     * <p>The RV10/RV30 range takes the same branch as the headline. A drifted headline bracketed by
     * driftless bounds would be a range around a different question.
     */
    public static Touch touch(Inputs in, BigDecimal current, BigDecimal limit,
                              int sessions, Config cfg, AssetClass assetClass) {
        Config c = cfg == null ? Config.DEFAULT : cfg;
        int n = Math.max(1, sessions);
        Forecast f = resolve(in, n, c);
        if (f.sigmaEff() == null) return Touch.none(n);

        AssetClass ac = assetClass == null ? AssetClass.UNKNOWN : assetClass;
        BigDecimal mu = c.useDrift() ? ac.dailyDrift() : BigDecimal.ZERO;

        BigDecimal sigmaToLimit = PriceStats.sigmaDistance(current, limit, f.sigmaEff(), n);
        BigDecimal p = probability(current, limit, f.sigmaEff(), n, mu, sigmaToLimit);

        BigDecimal a = probability(current, limit, positiveOrNull(in.rv10()), n, mu, null);
        BigDecimal b = probability(current, limit, positiveOrNull(in.rv30()), n, mu, null);
        return new Touch(n, f.sigmaEff(), f.decay(), f.source(), sigmaToLimit, p,
                min(a, b), max(a, b), mu);
    }

    /**
     * The branch itself. {@code sigmaDistance} is passed in when the caller already has it (the
     * headline), so the driftless path doesn't recompute the same logarithm twice.
     */
    private static BigDecimal probability(BigDecimal current, BigDecimal limit, BigDecimal sigma,
                                          int sessions, BigDecimal mu, BigDecimal sigmaDistance) {
        if (sigma == null) return null;
        if (mu != null && mu.signum() != 0) {
            return PriceStats.touchProbabilityDrifted(current, limit, sigma, sessions, mu);
        }
        return PriceStats.touchProbability(sigmaDistance != null ? sigmaDistance
                : PriceStats.sigmaDistance(current, limit, sigma, sessions));
    }

    private static BigDecimal positiveOrNull(BigDecimal v) {
        return (v == null || v.signum() <= 0) ? null : v;
    }

    private static BigDecimal min(BigDecimal a, BigDecimal b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static BigDecimal max(BigDecimal a, BigDecimal b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.compareTo(b) >= 0 ? a : b;
    }
}
