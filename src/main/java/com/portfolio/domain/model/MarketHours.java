package com.portfolio.domain.model;

import java.time.Instant;

/**
 * One listing's trading windows for the current day, taken verbatim from Yahoo's
 * {@code meta.currentTradingPeriod}. Epoch seconds, so no timezone table is needed here —
 * the exchange's own calendar is the source, which is what makes half-days, holidays and
 * DST transitions come out right without anything to maintain.
 *
 * <p>{@code hasPrePost} is Yahoo's {@code hasPrePostMarketData}: false for LSE/Euronext
 * listings, where the pre/post windows are reported but degenerate. It is the difference
 * between "extended hours exist and nothing has traded in them" and "this venue has none".
 *
 * <p>Boundaries follow the feed's own convention — start inclusive, end exclusive — so the
 * regular close instant reads as {@link MarketState#POST} rather than double-counting into
 * both windows.
 */
public record MarketHours(
        String ticker,
        long preStart, long preEnd,
        long regularStart, long regularEnd,
        long postStart, long postEnd,
        boolean hasPrePost,
        Instant capturedAt) {

    /**
     * Which window {@code at} falls in, or {@link MarketState#CLOSED} when it falls in none.
     * A stale capture (the app was off overnight) therefore degrades to CLOSED rather than
     * asserting an open market — the safe direction, since the caller falls back to quote age
     * when no calendar is held at all.
     */
    public MarketState stateAt(Instant at) {
        long t = at.getEpochSecond();
        if (within(t, regularStart, regularEnd)) return MarketState.OPEN;
        if (hasPrePost && within(t, preStart, preEnd)) return MarketState.PRE;
        if (hasPrePost && within(t, postStart, postEnd)) return MarketState.POST;
        return MarketState.CLOSED;
    }

    private static boolean within(long t, long start, long end) {
        return start > 0 && end > start && t >= start && t < end;
    }
}
