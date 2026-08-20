package com.portfolio.domain.model;

/**
 * Where a listing's market sits right now, as rendered by the Live tab. Distinct from
 * {@link MarketSession}, which describes a stored bar: a PRE bar from this morning is still a
 * PRE bar at midnight, but the market state by then is {@link #CLOSED}.
 */
public enum MarketState {
    /** Regular hours in progress. */
    OPEN,
    /** Inside the pre-market window. */
    PRE,
    /** Inside the post-market window. */
    POST,
    /** Outside every window the exchange publishes — nothing is trading. */
    CLOSED,
    /** No trading calendar known for this listing yet (e.g. gilts, or a cold start). */
    UNKNOWN
}
