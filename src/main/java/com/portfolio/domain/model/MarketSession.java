package com.portfolio.domain.model;

/**
 * Which trading session a 1-minute bar belongs to. Yahoo's chart endpoint returns pre- and
 * post-market minutes only when asked ({@code includePrePost=true}) and reports the exact
 * window boundaries per day in {@code meta.tradingPeriods}, so the classification is read off
 * the feed rather than guessed from a clock — half-days and holidays come out right for free.
 *
 * <p>Stored verbatim in {@code price_intraday.session}. Rows written before the column existed
 * are NULL and read back as {@link #REGULAR}: everything fetched up to then was regular-session
 * only, so that is the truthful reading, not a default.
 */
public enum MarketSession {
    PRE, REGULAR, POST;

    /** NULL/blank/unrecognised → {@link #REGULAR} (see the class doc on legacy rows). */
    public static MarketSession fromDb(String s) {
        if (s == null || s.isBlank()) return REGULAR;
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return REGULAR;
        }
    }

    public boolean isExtendedHours() {
        return this != REGULAR;
    }
}
