package com.portfolio.domain;

/**
 * Pure-domain predicates over the internal {@code securityId} string. Concentrated here so
 * the "what counts as a bond" rule lives in one place — previously split between
 * {@code PortfolioAggregator} and the adapter layer.
 */
public final class Instruments {

    private Instruments() {
    }

    /**
     * A bond, in this codebase, is any holding whose internal id either starts with
     * {@code GILT} (the format produced by both holdings and cash-statement parsers after
     * normalisation) or carries a {@code %} coupon marker (a defensive catch-all for any
     * unnormalised bond row). Used to:
     * <ul>
     *   <li>sort bonds into their own section on the Portfolio sheet,</li>
     *   <li>skip them when fetching from Yahoo (no coverage) — gilt prices come from
     *       {@code GiltPriceFetchJob} instead,</li>
     *   <li>switch {@code PortfolioAggregator.realtime} to the per-£100-nominal value formula
     *       ({@code qty × price / 100}) instead of the per-share one.</li>
     * </ul>
     */
    public static boolean isBond(String securityId) {
        return securityId != null
                && (securityId.contains("%") || securityId.toUpperCase().startsWith("GILT"));
    }
}
