package com.portfolio.domain;

import java.math.BigDecimal;

/**
 * Pure evaluation of the two watchlist triggers against a symbol's live state. Kept
 * framework-free and free of any notion of colour or email so the trading rules can be
 * unit-tested directly; the UI maps {@link Flags} to row colours and the alert job maps them
 * to emails.
 *
 * <p>Four triggers. The first two are driven by per-symbol thresholds expressed as whole
 * percents (e.g. {@code 10} = 10%); the last two by absolute price levels in the symbol's
 * native price currency:
 * <ul>
 *   <li><b>near 52-week high</b> — current price is within {@code highThresholdPct}% of the
 *       52-week high (a new high, where current ≥ high, always fires);</li>
 *   <li><b>big today move</b> — the absolute intraday move since the prior close is at least
 *       {@code moveThresholdPct}%, in either direction;</li>
 *   <li><b>above level</b> — current price is at or above {@code priceAbove};</li>
 *   <li><b>below level</b> — current price is at or below {@code priceBelow}.</li>
 * </ul>
 * A {@code null} or non-positive threshold/level disables that trigger for the symbol.
 */
public final class WatchlistAlerts {

    private WatchlistAlerts() {
    }

    /**
     * @param currentPrice     latest price, native currency (same currency as {@code week52High})
     * @param week52High       52-week high, native currency; {@code null} disables the high trigger
     * @param todayMoveFrac    today's move as a fraction ({@code 0.1} = +10%); {@code null} disables the move trigger
     * @param highThresholdPct proximity band to the high, in percent
     * @param moveThresholdPct minimum absolute move, in percent
     * @param priceAbove       fire when current ≥ this level (native currency); {@code null}/≤0 disables
     * @param priceBelow       fire when current ≤ this level (native currency); {@code null}/≤0 disables
     */
    public static Flags evaluate(BigDecimal currentPrice, BigDecimal week52High,
                                 BigDecimal todayMoveFrac,
                                 BigDecimal highThresholdPct, BigDecimal moveThresholdPct,
                                 BigDecimal priceAbove, BigDecimal priceBelow) {
        boolean nearHigh = false;
        if (currentPrice != null && week52High != null && week52High.signum() > 0
                && isPositive(highThresholdPct)) {
            // distance below the high, as a fraction: (high − current) / high. Negative when
            // current has pushed past the stored high — that's a fresh high, still a fire.
            BigDecimal distanceFrac = week52High.subtract(currentPrice)
                    .divide(week52High, 8, java.math.RoundingMode.HALF_UP);
            BigDecimal band = highThresholdPct.movePointLeft(2);   // percent → fraction
            nearHigh = distanceFrac.compareTo(band) <= 0;
        }

        boolean bigMove = false;
        int moveDirection = 0;
        if (todayMoveFrac != null && isPositive(moveThresholdPct)) {
            BigDecimal band = moveThresholdPct.movePointLeft(2);
            bigMove = todayMoveFrac.abs().compareTo(band) >= 0;
            moveDirection = todayMoveFrac.signum();
        }

        // Absolute price-level triggers: at-or-through the target counts (compareTo >= / <= 0).
        boolean aboveLevel = currentPrice != null && isPositive(priceAbove)
                && currentPrice.compareTo(priceAbove) >= 0;
        boolean belowLevel = currentPrice != null && isPositive(priceBelow)
                && currentPrice.compareTo(priceBelow) <= 0;

        return new Flags(nearHigh, bigMove, moveDirection, aboveLevel, belowLevel);
    }

    private static boolean isPositive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    /**
     * Which triggers are firing. {@code moveDirection} is {@code +1} up / {@code −1} down /
     * {@code 0} flat, used only to colour a firing move green vs red (direction is read off the
     * daily-change sign, not the threshold). {@code aboveLevel}/{@code belowLevel} are the
     * absolute price-target triggers. {@code any()} and {@code both()} drive the row highlight
     * vs the pulsing both-fired state (still just near-high + big-move).
     */
    public record Flags(boolean nearHigh, boolean bigMove, int moveDirection,
                        boolean aboveLevel, boolean belowLevel) {
        public boolean any() {
            return nearHigh || bigMove || aboveLevel || belowLevel;
        }

        public boolean both() {
            return nearHigh && bigMove;
        }
    }
}
