package com.portfolio.domain.model;

import java.time.Instant;

/**
 * One listing's previous-session close, taken verbatim from Yahoo's {@code meta.previousClose}
 * on the intraday response — the feed's own word for the number every Day Δ is measured against.
 *
 * <p>It exists because the close cannot be inferred from what we store. {@code price_history}
 * holds "the newest bar at or before date D", which silently degrades to a bar from last week
 * when the daily job has not run, and no calendar distance tells the two apart: a Monday quote
 * is three days after Friday's close whether the history is complete or four sessions behind.
 * The feed states the number outright, so nothing has to be deduced.
 *
 * <p>{@code close} is in {@code currency}, which is the listing currency of the same response's
 * bars — {@code GBp} (pence) for a London listing, exactly as {@link IntradayPrice#currency()}
 * reports it, so the two cancel when divided.
 *
 * <p>{@code capturedAt} is when the response was read, not a market timestamp: the previous close
 * belongs to whichever session the feed considered current at that moment, so an entry that has
 * outlived its session is worse than none — see {@code PreviousCloseCache}.
 */
public record PreviousClose(String ticker, double close, String currency, Instant capturedAt) {
}
