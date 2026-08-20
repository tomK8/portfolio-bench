package com.portfolio.domain.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One intraday close bar for an instrument, stored against its resolved Yahoo ticker.
 * {@code currency} is verbatim from Yahoo — {@code "GBp"} for {@code .L} listings (pence,
 * not pounds); the dashboard divides by 100 when matching a GBP holding.
 *
 * <p>{@code session} says whether the minute was regular, pre- or post-market, and
 * {@code sessionDate} is the <em>trading day</em> it belongs to in the exchange's own local
 * calendar — not the UTC date of {@code ts}, which splits a US after-hours session across two
 * dates once it passes midnight UTC. The trading day is what "yesterday's close" has to be
 * measured against, so it is carried on the bar rather than re-derived by every reader.
 */
public record IntradayBar(
        String symbol,
        Instant ts,
        double close,
        Long volume,
        String currency,
        MarketSession session,
        LocalDate sessionDate) {

    /**
     * A regular-session bar with no known trading day — the shape every non-Yahoo writer uses
     * (the HL gilt scrape), and what the pre/post-aware columns degrade to for rows stored
     * before they existed.
     */
    public IntradayBar(String symbol, Instant ts, double close, Long volume, String currency) {
        this(symbol, ts, close, volume, currency, MarketSession.REGULAR, null);
    }

    /**
     * The trading day this bar belongs to, falling back to the UTC date when the feed did not
     * supply one. The fallback is only reached for legacy/gilt rows, both of which are
     * regular-session and daylight-hours, so the UTC date is the trading day for them.
     */
    public LocalDate tradingDay() {
        return sessionDate != null ? sessionDate : ts.atZone(java.time.ZoneOffset.UTC).toLocalDate();
    }
}
