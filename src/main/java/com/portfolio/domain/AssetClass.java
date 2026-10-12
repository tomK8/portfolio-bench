package com.portfolio.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What kind of thing an instrument is, for the one purpose that needs to know: the expected drift
 * fed to the drifted first-passage model behind the open-orders touch probabilities.
 *
 * <p><b>Drift is per asset class, not per ticker.</b> A per-name expected return would be a
 * forecast of that name — the thing nobody can do — dressed up as a parameter, and it would make
 * every touch probability a function of an opinion about the underlying. A class-level figure is a
 * modelling convention instead: equities and equity funds carry a long-run drift, a gilt held to a
 * redemption price and a lump of gold in a vault carry none. Nothing here is a view on a particular
 * instrument, which is what keeps the number honest.
 *
 * <p><b>The figure is a total return, and the model needs a price drift.</b> That distinction is
 * the whole reason this class carries {@link #appliesYield()}. The first passage is over a
 * <em>price</em> path — a resting limit order is touched by price, not by price plus reinvested
 * dividends — so the drift that reaches it must be
 * <pre>
 *   mu_price = annualTotalReturn() − forwardDividendYield
 * </pre>
 * with the yield resolved per symbol by the caller and subtracted in {@link VolForecast#touch}.
 * The two legs then sit on one basis: {@code PriceStats} builds its return series off {@code close}
 * and deliberately not off {@code adj_close}, so the diffusion is price-only and after the
 * subtraction the drift is too. Applying the total return directly — which this class used to do —
 * over-drifts every dividend payer, and by ~7pp of dispersion between an accumulating tracker and a
 * high-yielding REIT it is not a uniform error that cancels.
 *
 * <p>The figures are <b>per year</b> and converted to per-session by dividing by
 * {@link #SESSIONS_PER_YEAR} — the same 252 the volatility scaling assumes, so drift, yield and
 * diffusion are all measured on one clock.
 *
 * <p>The result is consumed as a <b>log</b> drift, and the Itô correction {@code ν = μ − σ²/2} is
 * applied in {@link VolForecast#touch} where μ and σ meet. It is <em>not</em> applied here: σ is
 * horizon-dependent, so ν is too, and this class has no horizon.
 */
public enum AssetClass {

    /** Directly held shares. */
    EQUITY(0.08, true),
    /**
     * Equity funds and ETFs. Carries the same total return as {@link #EQUITY} by construction, so
     * nothing at the resolution point can tell the two apart today.
     *
     * <p>It is a separate constant so a later divergence has somewhere to go, but the classifier
     * deliberately does <b>not</b> try to detect it: separating an equity ETF from a share needs a
     * maintained list of fund tickers whose only possible effect, while the two figures are equal,
     * is the label on a calibration line. A list that can only ever be wrong and never right is not
     * worth keeping. Bond and bullion wrappers <em>are</em> detected, because for those the
     * underlying — not the wrapper — is what changes the number.
     *
     * <p>Nets a yield like {@link #EQUITY}: a distributing fund's yield is a real drag on its price
     * path. An accumulating fund reports no yield and correctly keeps the full figure.
     */
    ETF(0.08, true),
    /** Gilts and gilt funds: a redemption price, not a growth asset. */
    GILT(0.0, false),
    /** Physical-metal ETCs. Bullion has no earnings and no yield; it just sits there. */
    COMMODITY(0.0, false),
    /**
     * Money-market and ultra-short-dated government ETFs — cash in a wrapper. The price path is
     * an overnight rate accruing, not an asset repricing, so there is no drift worth modelling and
     * nothing for a limit order to be touched by. Zero like {@link #GILT}, and for the same
     * reason: what it earns is a carry, not a return on anything.
     */
    CASH_EQUIVALENT(0.0, false),
    /** Nothing said. Zero drift, so an unclassified instrument can never have drift injected. */
    UNKNOWN(0.0, false);

    /** Trading sessions per year — the same clock {@code PriceStats} scales volatility on. */
    public static final double SESSIONS_PER_YEAR = 252.0;

    private final double annualTotalReturn;
    private final boolean appliesYield;

    AssetClass(double annualTotalReturn, boolean appliesYield) {
        this.annualTotalReturn = annualTotalReturn;
        this.appliesYield = appliesYield;
    }

    /**
     * Expected <b>total</b> return per year, as a fraction: {@code 0.08} = 8%/yr. Dividends are
     * still in this figure — it is not the drift the model consumes. See {@link #appliesYield()}.
     */
    public double annualTotalReturn() {
        return annualTotalReturn;
    }

    /** {@link #annualTotalReturn()} per trading session — the gross figure ÷ 252. */
    public BigDecimal dailyTotalReturn() {
        return BigDecimal.valueOf(annualTotalReturn / SESSIONS_PER_YEAR)
                .setScale(10, RoundingMode.HALF_UP);
    }

    /**
     * Whether a dividend yield is netted off this class's total return to reach the price drift.
     *
     * <p><b>False for every zero-return class, and that is load-bearing rather than tidy.</b> A
     * gilt or a bullion ETC starts at zero; subtracting anything would hand it a negative drift it
     * never asked for, and {@code VolForecast.probability} branches on {@code mu.signum() != 0}, so
     * a zero that stayed zero is also what keeps those instruments on the driftless first passage
     * instead of evaluating an {@code exp(2νb/σ²)} term for no reason.
     */
    public boolean appliesYield() {
        return appliesYield;
    }
}
