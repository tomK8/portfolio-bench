package com.portfolio.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

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
 * <p><b>Drift, when it is switched on, is netted and then Itô-corrected here.</b> Two conversions,
 * in this order, and both matter:
 * <pre>
 *   mu_price = assetClass.dailyTotalReturn() − forwardYield/252   (total return → price drift)
 *   nu       = mu_price − sigma_eff²/2                            (arithmetic → log drift)
 * </pre>
 * The first exists because the first passage is over a <em>price</em> path while an asset class
 * carries a <em>total</em> return: a resting limit is touched by price, not by price plus
 * reinvested dividends, and {@code PriceStats} deliberately measures σ off dividend-excluded
 * closes. Without it the drift leg and the diffusion leg sit on different bases and every dividend
 * payer is over-drifted. The second exists because the barrier algebra is written in log space.
 * Because σ_eff depends on the horizon, so does ν — the same class produces a different log drift
 * at 5 sessions and at 250.
 *
 * <p>The yield is resolved <em>per symbol</em> by the caller and passed in as a {@link Yield}; the
 * class-level figure it is subtracted from stays a modelling convention. That split is deliberate:
 * the dispersion of yields inside "equity" is what the netting is for, but an expected return per
 * name would be a forecast wearing a parameter's clothing.
 *
 * <p><b>Everything routes through {@link #resolve}.</b> The first-passage call site takes whatever
 * {@code sigma_eff} comes back and asks no questions, so a later implied-vol or event-variance
 * source can be swapped in here without touching it — that is what the {@code useImpliedVol} flag
 * on {@link Config} is reserved for; it defaults to false and is a no-op today. {@code useDrift}
 * and {@code useEventVar} are both wired: see {@link #touch} and {@code withEvents}.
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
     * @param useEventVar        add scheduled-event (earnings) variance for prints falling inside
     *                           the horizon — see {@code withEvents}. Default false.
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
            return of(halfLifeDays, gtcHorizonSessions, useDrift, null);
        }

        public static Config of(Double halfLifeDays, Integer gtcHorizonSessions,
                                Boolean useDrift, Boolean useEventVar) {
            return new Config(
                    halfLifeDays == null ? DEFAULT_HALF_LIFE_DAYS : halfLifeDays,
                    gtcHorizonSessions == null ? DEFAULT_GTC_HORIZON_SESSIONS : gtcHorizonSessions,
                    useDrift != null && useDrift, useEventVar != null && useEventVar, false);
        }
    }

    /**
     * The realized-vol windows a forecast is built from, all daily-return standard deviations.
     * {@code rv20} is current state and {@code rv250} the long-run anchor; {@code rv10} and
     * {@code rv30} carry the displayed columns and bracket the headline with a plausible range.
     */
    public record Inputs(BigDecimal rv10, BigDecimal rv20, BigDecimal rv30, BigDecimal rv250,
                         List<Event> events) {

        public static final Inputs NONE = new Inputs(null, null, null, null);

        public Inputs {
            events = events == null ? List.of() : List.copyOf(events);
        }

        /** The realized windows alone — no scheduled events known, or none wanted. */
        public Inputs(BigDecimal rv10, BigDecimal rv20, BigDecimal rv30, BigDecimal rv250) {
            this(rv10, rv20, rv30, rv250, List.of());
        }
    }

    /**
     * A scheduled event inside (or beyond) the horizon — an earnings print, in practice.
     *
     * <p>Measured in <b>sessions away</b> rather than as a date, so the forecast never has to know
     * what today is and the same input can be tested at any horizon: {@link #resolve} keeps the
     * events with {@code 0 ≤ sessionsAway ≤ N} and ignores the rest, which is how one input set
     * yields two prints at N=126 and one at N=63.
     *
     * @param move typical absolute one-day move on the event, as a fraction (0.06 = 6%). See
     *             {@link EventMoves}.
     */
    public record Event(int sessionsAway, BigDecimal move) {

        boolean insideHorizon(int sessions) {
            return sessionsAway >= 0 && sessionsAway <= sessions && move != null && move.signum() > 0;
        }
    }

    /**
     * The forward dividend yield netted off an asset class's total return to reach the price drift,
     * with the one bit of provenance that cannot be recovered from the number itself.
     *
     * <p><b>{@code fallback} is not redundant with a zero {@code annual}.</b> An accumulating fund
     * genuinely yields nothing and correctly keeps the full drift; a name whose yield we could not
     * obtain is also priced at zero, and wrongly keeps it. Both are {@code 0.00} in the arithmetic
     * and only this flag separates them afterwards — which matters because Yahoo publishes no
     * distribution yield for any fund held here, so the second case is the common one.
     *
     * <p>The value arrives already validated: the caller owns the "is this a plausible yield"
     * question because rejecting one has to be logged, and this class is framework-free.
     *
     * @param annual   forward yield as an annual fraction ({@code 0.0234} = 2.34%), never null.
     * @param fallback true when {@code annual} is a substituted zero rather than a resolved figure.
     */
    public record Yield(BigDecimal annual, boolean fallback) {

        /** Nothing resolved — priced at zero drift-offset and flagged as such. */
        public static final Yield NONE = new Yield(BigDecimal.ZERO, true);

        public Yield {
            if (annual == null) annual = BigDecimal.ZERO;
        }

        /** A yield that was actually resolved for the instrument. */
        public static Yield of(BigDecimal annual) {
            return annual == null ? NONE : new Yield(annual, false);
        }
    }

    /**
     * A resolved {@code sigma_eff}, with the provenance a calibration record needs.
     *
     * @param sigmaEffDiffusive σ_eff before any event variance was folded in — kept so a record
     *                          says what the diffusive model alone thought, which is the only way
     *                          to see afterwards how much of a probability the calendar supplied.
     * @param eventCount        scheduled events found inside the horizon.
     * @param eventVariance     Σ move² added to the total variance over the horizon.
     */
    public record Forecast(BigDecimal sigmaEff, BigDecimal decay, Source source,
                           BigDecimal sigmaEffDiffusive, int eventCount, BigDecimal eventVariance) {

        public static final Forecast NONE =
                new Forecast(null, null, Source.NONE, null, 0, BigDecimal.ZERO);

        public Forecast(BigDecimal sigmaEff, BigDecimal decay, Source source) {
            this(sigmaEff, decay, source, sigmaEff, 0, BigDecimal.ZERO);
        }
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
                        /** μ — the asset class's expected annual return, per session. ZERO when drift is off. */
                        BigDecimal drift,
                        /**
                         * ν = μ − σ_eff²/2 — the <em>log</em> drift the first passage actually
                         * consumed. Horizon-dependent, because σ_eff is: the same μ produces a
                         * different ν at 5 sessions and at 250. ZERO on the driftless branch.
                         */
                        BigDecimal logDrift,
                        /**
                         * The class's gross expected <em>total</em> return per session, before the
                         * dividend yield was netted off. Kept beside {@link #drift} so a
                         * calibration record can attribute a miss between the vol estimate, the
                         * return assumption and the yield — three inputs that are indistinguishable
                         * once collapsed into one number. ZERO when drift is off.
                         */
                        BigDecimal muTotal,
                        /** The forward yield subtracted, as an annual fraction. ZERO when none applied. */
                        BigDecimal forwardYield,
                        /** True when {@link #forwardYield} is a substituted zero, not a resolved figure. */
                        boolean yieldFallback,
                        /** σ_eff before event variance — equal to {@link #sigmaEff} when none applied. */
                        BigDecimal sigmaEffDiffusive,
                        /** Scheduled events found inside the horizon. */
                        int eventCount,
                        /** Σ move² folded into the horizon's total variance. */
                        BigDecimal eventVariance) {

        /**
         * The plain case: a diffusive, driftless, event-free pricing. Spelled out so callers that
         * genuinely have none of the extras (and the fixtures that stand in for them) don't have to
         * pass a row of zeroes whose meaning has to be looked up.
         */
        public static Touch diffusive(int sessions, BigDecimal sigmaEff, BigDecimal decay,
                                      Source source, BigDecimal sigmaToLimit, BigDecimal probability,
                                      BigDecimal low, BigDecimal high) {
            return new Touch(sessions, sigmaEff, decay, source, sigmaToLimit, probability, low, high,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false,
                    sigmaEff, 0, BigDecimal.ZERO);
        }

        public static Touch none(int sessions) {
            return new Touch(sessions, null, null, Source.NONE, null, null, null, null,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false,
                    null, 0, BigDecimal.ZERO);
        }

        /** True when the calendar contributed variance to this figure. */
        public boolean eventAdjusted() {
            return eventCount > 0;
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

    /**
     * A priced <b>sequence</b> of barriers — the ladder's round trip, where the single-barrier
     * {@link Touch} is one rung's fill.
     *
     * <p>Same forecast, same drift, same RV10/RV30 bracket as {@link Touch}; what differs is the
     * question. A round trip needs two things to happen in order and inside one window, so the
     * second leg gets only what the first leaves of the horizon and starts from the first barrier
     * rather than from today's price — see {@link PriceStats#sequentialTouchProbability}.
     *
     * @param legs how many passages are still to come: 2 for a round trip not yet started, 1 once a
     *             leg has filled and only the close is left, 0 when both remaining legs are already
     *             through the market. The number is on the record because it is the whole reason a
     *             part-executed ladder reads higher than it did when it was created, and a reader
     *             comparing two ladders has to be able to see that they were not asked the same
     *             question.
     */
    public record Sequence(int sessions, int legs, BigDecimal sigmaEff, BigDecimal decay,
                           Source source, BigDecimal probability, BigDecimal probabilityLow,
                           BigDecimal probabilityHigh, BigDecimal drift, BigDecimal logDrift) {

        public static Sequence none(int sessions, int legs) {
            return new Sequence(sessions, legs, null, null, Source.NONE, null, null, null,
                    BigDecimal.ZERO, BigDecimal.ZERO);
        }

        /** True when the RV10/RV30 bracket exists — the honest width around the point estimate. */
        public boolean hasRange() {
            return probabilityLow != null && probabilityHigh != null;
        }

        public boolean drifted() {
            return drift != null && drift.signum() != 0;
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

        // Reserved source. A no-op today, falling through to the realized blend; named here so that
        // when it arrives it arrives in this method and nowhere else.
        if (c.useImpliedVol()) {
            // future: implied-vol term structure interpolated at n sessions
        }

        BigDecimal now = positiveOrNull(in == null ? null : in.rv20());
        BigDecimal anchor = positiveOrNull(in == null ? null : in.rv250());
        if (now == null && anchor == null) return Forecast.NONE;
        // The degraded paths fold events in too: a short-history name with a print next week is
        // exactly the case where the calendar is carrying most of the information.
        if (anchor == null) return withEvents(now, BigDecimal.ONE, Source.CURRENT_ONLY, in, n, c);
        if (now == null) return withEvents(anchor, BigDecimal.ZERO, Source.ANCHOR_ONLY, in, n, c);

        double varNow = now.doubleValue() * now.doubleValue();
        double varLr = anchor.doubleValue() * anchor.doubleValue();
        double decay = decayWeight(c.halfLifeDays(), n);
        double varN = varLr + (varNow - varLr) * decay;
        if (varN <= 0) return Forecast.NONE;   // only reachable if both windows are degenerate
        return withEvents(
                BigDecimal.valueOf(Math.sqrt(varN)).setScale(SCALE, RoundingMode.HALF_UP),
                BigDecimal.valueOf(decay).setScale(4, RoundingMode.HALF_UP),
                Source.BLEND, in, n, c);
    }

    /**
     * Fold scheduled-event variance into a diffusive forecast:
     * <pre>
     *   totalVar  = var_N · N + Σ move²      (events with 0 ≤ sessionsAway ≤ N)
     *   sigma_eff = √(totalVar / N)
     * </pre>
     *
     * <p><b>Events enter as variance, not as volatility.</b> A trailing window cannot see a date in
     * the calendar: a name quiet for twenty sessions that reports next month is not a quiet name,
     * and no amount of blending RV20 toward RV250 will discover that. Adding the print's move in
     * quadrature is the arithmetic that says "the horizon contains one ordinary diffusion of N
     * sessions <em>and</em> one jump", rather than pretending the jump was in the trailing sample.
     *
     * <p>A 90-session horizon usually contains two prints (quarterly ≈ 63 sessions apart), so every
     * event inside the horizon is summed rather than just the next one. Outside it, none: the same
     * input set therefore yields a different σ_eff at 63 sessions and at 126, which is correct and
     * is what makes the editable horizon box meaningful for a name with a print coming.
     *
     * <p>With the flag off, or with no events supplied, this returns the diffusive forecast
     * untouched — including the case that matters most: a symbol whose earnings date we could not
     * obtain contributes nothing and is reported as unadjusted, rather than being given a guessed
     * date that would look identical to a real one.
     */
    private static Forecast withEvents(BigDecimal sigmaDiffusive, BigDecimal decay, Source source,
                                       Inputs in, int sessions, Config cfg) {
        if (!cfg.useEventVar() || in == null || in.events().isEmpty() || sigmaDiffusive == null) {
            return new Forecast(sigmaDiffusive, decay, source);
        }
        double eventVar = 0.0;
        int count = 0;
        for (Event e : in.events()) {
            if (!e.insideHorizon(sessions)) continue;
            double move = e.move().doubleValue();
            eventVar += move * move;
            count++;
        }
        if (count == 0) return new Forecast(sigmaDiffusive, decay, source);

        double s = sigmaDiffusive.doubleValue();
        double totalVar = s * s * sessions + eventVar;
        BigDecimal withEvent = BigDecimal.valueOf(Math.sqrt(totalVar / sessions))
                .setScale(SCALE, RoundingMode.HALF_UP);
        return new Forecast(withEvent, decay, source, sigmaDiffusive, count,
                BigDecimal.valueOf(eventVar).setScale(SCALE, RoundingMode.HALF_UP));
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
     * Price the touch for an instrument of the given asset class, with no dividend yield resolved.
     *
     * <p>Equivalent to passing {@link Yield#NONE}: the class's full total return is used as the
     * price drift and the result is flagged {@code yieldFallback}. Correct for the zero-drift
     * classes, and an over-drift for anything that pays a dividend — which is why the enriched path
     * always supplies a {@link Yield}.
     */
    public static Touch touch(Inputs in, BigDecimal current, BigDecimal limit,
                              int sessions, Config cfg, AssetClass assetClass) {
        return touch(in, current, limit, sessions, cfg, assetClass, Yield.NONE);
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
                              int sessions, Config cfg, AssetClass assetClass, Yield yield) {
        Config c = cfg == null ? Config.DEFAULT : cfg;
        int n = Math.max(1, sessions);
        Forecast f = resolve(in, n, c);
        if (f.sigmaEff() == null) return Touch.none(n);

        AssetClass ac = assetClass == null ? AssetClass.UNKNOWN : assetClass;
        Yield y = yield == null ? Yield.NONE : yield;
        BigDecimal muTotal = c.useDrift() ? ac.dailyTotalReturn() : BigDecimal.ZERO;
        // Netting only ever happens on the drifted branch. With the flag off muTotal is already
        // ZERO, and subtracting a yield from it would manufacture a negative drift out of a model
        // the reader has switched off — the one way this change could alter a driftless number.
        boolean nets = c.useDrift() && ac.appliesYield();
        BigDecimal mu = nets ? muTotal.subtract(perSessionYield(y.annual())) : muTotal;

        BigDecimal sigmaToLimit = PriceStats.sigmaDistance(current, limit, f.sigmaEff(), n);
        BigDecimal p = probability(current, limit, f.sigmaEff(), n, mu, sigmaToLimit);

        // The range is priced on RV10/RV30, so each end gets the ν its own σ implies — the drag is
        // a function of the volatility being used, not a constant to be shared across the three.
        BigDecimal a = probability(current, limit, positiveOrNull(in.rv10()), n, mu, null);
        BigDecimal b = probability(current, limit, positiveOrNull(in.rv30()), n, mu, null);
        return new Touch(n, f.sigmaEff(), f.decay(), f.source(), sigmaToLimit, p,
                min(a, b), max(a, b), mu, logDrift(mu, f.sigmaEff()),
                muTotal, nets ? y.annual() : BigDecimal.ZERO, nets && y.fallback(),
                f.sigmaEffDiffusive(), f.eventCount(), f.eventVariance());
    }


    /**
     * Price an ordered sequence of barriers over one horizon — {@link #touch} for a round trip.
     *
     * <p>Everything but the first passage itself is the single-barrier path unchanged, and
     * deliberately so: one σ_eff resolution (blended for <em>this</em> horizon, not for a leg of
     * it), one drift netting, one RV10/RV30 bracket. A round trip priced off a second vol model
     * would be a different question wearing the same units as the rungs above it.
     *
     * <p><b>One σ_eff across both legs.</b> σ is forecast for the horizon the reader chose, and the
     * split of that horizon between the two legs is a random variable — there is no per-leg window
     * to forecast for. Blending a second σ for "the rest of the horizon" would be forecasting off a
     * quantity the model itself has not yet drawn.
     */
    public static Sequence sequence(Inputs in, BigDecimal start, List<BigDecimal> barriers,
                                    int sessions, Config cfg, AssetClass assetClass, Yield yield) {
        Config c = cfg == null ? Config.DEFAULT : cfg;
        int n = Math.max(1, sessions);
        int legs = barriers == null ? 0 : barriers.size();
        Forecast f = resolve(in, n, c);
        if (f.sigmaEff() == null) return Sequence.none(n, legs);

        AssetClass ac = assetClass == null ? AssetClass.UNKNOWN : assetClass;
        Yield y = yield == null ? Yield.NONE : yield;
        BigDecimal muTotal = c.useDrift() ? ac.dailyTotalReturn() : BigDecimal.ZERO;
        boolean nets = c.useDrift() && ac.appliesYield();
        BigDecimal mu = nets ? muTotal.subtract(perSessionYield(y.annual())) : muTotal;

        BigDecimal p = PriceStats.sequentialTouchProbability(start, barriers, f.sigmaEff(), n,
                logDrift(mu, f.sigmaEff()));
        BigDecimal lowSigma = positiveOrNull(in == null ? null : in.rv10());
        BigDecimal highSigma = positiveOrNull(in == null ? null : in.rv30());
        BigDecimal a = lowSigma == null ? null : PriceStats.sequentialTouchProbability(
                start, barriers, lowSigma, n, logDrift(mu, lowSigma));
        BigDecimal b = highSigma == null ? null : PriceStats.sequentialTouchProbability(
                start, barriers, highSigma, n, logDrift(mu, highSigma));
        return new Sequence(n, legs, f.sigmaEff(), f.decay(), f.source(), p, min(a, b), max(a, b),
                mu, logDrift(mu, f.sigmaEff()));
    }

    /**
     * The branch itself, and the one place μ is converted to ν.
     *
     * <p>{@code ν = μ − σ²/2}: the μ arriving here — a class total return (8%/yr for equities and
     * equity funds) net of the name's own dividend yield — is an expected <em>arithmetic</em>
     * return, while the first passage is written in log space, and the gap between them — the
     * variance drag — is not decoration. At a 1%/day σ it is 1.26%/yr, a sixth of the gross equity
     * figure and a far larger share of what a high-yielder is left with after netting.
     *
     * <p><b>The branch tests μ, not ν</b>, which matters in both directions. A class with μ = 0
     * (gilts, bullion) takes the driftless path and is left exactly alone rather than being handed
     * a small negative drift it never asked for; and because the conversion happens here rather
     * than inside {@link PriceStats#touchProbabilityDrifted}, that method still reduces to the
     * driftless form exactly at ν = 0, which is what the barrier algebra is tested against.
     */
    private static BigDecimal probability(BigDecimal current, BigDecimal limit, BigDecimal sigma,
                                          int sessions, BigDecimal mu, BigDecimal sigmaDistance) {
        if (sigma == null) return null;
        if (mu != null && mu.signum() != 0) {
            return PriceStats.touchProbabilityDrifted(current, limit, sigma, sessions,
                    logDrift(mu, sigma));
        }
        return PriceStats.touchProbability(sigmaDistance != null ? sigmaDistance
                : PriceStats.sigmaDistance(current, limit, sigma, sessions));
    }

    /**
     * {@code ν = μ − σ²/2}, per session. Zero μ stays zero ν: a class with no expected return is
     * modelled as having no drift at all, not as one dragged below zero by its own variance.
     */
    static BigDecimal logDrift(BigDecimal mu, BigDecimal sigma) {
        if (mu == null || mu.signum() == 0) return BigDecimal.ZERO;
        if (sigma == null) return mu;
        double s = sigma.doubleValue();
        return BigDecimal.valueOf(mu.doubleValue() - s * s / 2.0).setScale(12, RoundingMode.HALF_UP);
    }

    /**
     * An annual dividend yield expressed per trading session — divided by the same
     * {@link AssetClass#SESSIONS_PER_YEAR} the total return and the {@code σ√N} scaling use, so all
     * three terms are on one clock. Null or zero yields exactly zero, so a name with no dividend is
     * left alone rather than nudged by a rounding artefact.
     */
    static BigDecimal perSessionYield(BigDecimal annualYield) {
        if (annualYield == null || annualYield.signum() == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(annualYield.doubleValue() / AssetClass.SESSIONS_PER_YEAR)
                .setScale(10, RoundingMode.HALF_UP);
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
