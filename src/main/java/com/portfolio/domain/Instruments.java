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
     *   <li>skip them when fetching prices (Yahoo doesn't cover gilts),</li>
     *   <li>skip the real-time market-value calculation (broker price stays source of truth).</li>
     * </ul>
     */
    public static boolean isBond(String securityId) {
        return securityId != null
                && (securityId.contains("%") || securityId.toUpperCase().startsWith("GILT"));
    }
}
