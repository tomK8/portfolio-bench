package com.portfolio.domain.model;

import java.time.Instant;

/**
 * Latest cached intraday quote for one ticker — what the dashboard renders as
 * "Price" / "RT Value" without going back to Yahoo on every request.
 */
public record IntradayPrice(Instant ts, double close, String currency) {
}
