package com.portfolio.domain.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Latest cached intraday quote for one ticker — what the dashboard renders as
 * "Price" / "RT Value" without going back to Yahoo on every request.
 *
 * <p>{@code session} / {@code sessionDate} carry the same meaning as on {@link IntradayBar}.
 * Most callers ask for regular-session quotes only and can ignore both; the Live tab asks for
 * the newest quote of any session, so it needs to know which one it got.
 */
public record IntradayPrice(Instant ts, double close, String currency,
                            MarketSession session, LocalDate sessionDate) {

    /** A regular-session quote with no known trading day. */
    public IntradayPrice(Instant ts, double close, String currency) {
        this(ts, close, currency, MarketSession.REGULAR, null);
    }

    /** @see IntradayBar#tradingDay() */
    public LocalDate tradingDay() {
        return sessionDate != null ? sessionDate : ts.atZone(java.time.ZoneOffset.UTC).toLocalDate();
    }
}
