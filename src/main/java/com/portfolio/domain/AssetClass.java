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
 * modelling convention instead: equities and equity funds carry a long-run real-ish drift, a gilt
 * held to a redemption price and a lump of gold in a vault carry none. Nothing here is a view on a
 * particular instrument, which is what keeps the number honest.
 *
 * <p>The figures are <b>per year</b> and converted to per-session by dividing by
 * {@link #SESSIONS_PER_YEAR} — the same 252 the volatility scaling assumes, so drift and diffusion
 * are measured on one clock.
 *
 * <p>The drift is treated as a <b>log-space</b> drift, because that is what the first-passage
 * formula consumes. A caller who means 6% as an expected <em>total return</em> would want
 * {@code μ − σ²/2}, which at a 1%/day σ is about 1.3%/yr of the 6% — not nothing. It is left
 * unapplied deliberately: the correction is only right if the input was arithmetic, and the input
 * here is a convention, not a measurement. Stated rather than silently adjusted.
 */
public enum AssetClass {

    /** Directly held shares. */
    EQUITY(0.06),
    /**
     * Equity funds and ETFs. Carries the same drift as {@link #EQUITY} by construction, so nothing
     * at the resolution point can tell the two apart today.
     *
     * <p>It is a separate constant so a later divergence has somewhere to go, but the classifier
     * deliberately does <b>not</b> try to detect it: separating an equity ETF from a share needs a
     * maintained list of fund tickers whose only possible effect, while the two drifts are equal,
     * is the label on a calibration line. A list that can only ever be wrong and never right is not
     * worth keeping. Bond and bullion wrappers <em>are</em> detected, because for those the
     * underlying — not the wrapper — is what changes the number.
     */
    ETF(0.06),
    /** Gilts and gilt funds: a redemption price, not a growth asset. */
    GILT(0.0),
    /** Physical-metal ETCs. Bullion has no earnings and no yield; it just sits there. */
    COMMODITY(0.0),
    /** Nothing said. Zero drift, so an unclassified instrument can never have drift injected. */
    UNKNOWN(0.0);

    /** Trading sessions per year — the same clock {@code PriceStats} scales volatility on. */
    public static final double SESSIONS_PER_YEAR = 252.0;

    private final double annualDrift;

    AssetClass(double annualDrift) {
        this.annualDrift = annualDrift;
    }

    /** Expected log drift per year, as a fraction: {@code 0.06} = 6%/yr. */
    public double annualDrift() {
        return annualDrift;
    }

    /** Expected log drift per trading session — {@link #annualDrift()} ÷ 252. */
    public BigDecimal dailyDrift() {
        return BigDecimal.valueOf(annualDrift / SESSIONS_PER_YEAR).setScale(10, RoundingMode.HALF_UP);
    }
}
