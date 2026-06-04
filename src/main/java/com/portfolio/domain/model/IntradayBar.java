package com.portfolio.domain.model;

import java.time.Instant;

/**
 * One intraday close bar for an instrument, stored against its resolved Yahoo ticker.
 * {@code currency} is verbatim from Yahoo — {@code "GBp"} for {@code .L} listings (pence,
 * not pounds); the dashboard divides by 100 when matching a GBP holding.
 */
public record IntradayBar(
        String symbol,
        Instant ts,
        double close,
        Long volume,
        String currency) {
}
