package com.portfolio.adapter;

import com.portfolio.port.FxRateProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * TTL-caching, fail-soft wrapper around a live {@link FxRateProvider}.
 *
 * <p>Every read-side service (correlations, concentration, currency exposure, watchlist,
 * position detail, dividends, cash-ledger sync, …) fetches live GBP FX rates once per
 * request. Frankfurter is a free public API and occasionally times out; without a fallback
 * a single blip silently drops every non-GBP position from whatever view was open (an empty
 * rates map means {@code valueGbp} returns null for USD/EUR names). This decorator makes
 * that failure mode rare and non-destructive:
 *
 * <ul>
 *   <li>Within {@code ttl} of a successful fetch, serve the cached rates without hitting the
 *       network at all — GBP/USD does not move enough intra-quarter-hour to matter for
 *       portfolio weights.</li>
 *   <li>On a fetch failure, serve the last good rates <b>regardless of age</b> — stale FX is
 *       far better than dropping half the book — logging the staleness at WARN.</li>
 *   <li>Only propagate the exception when there is no cached value at all (cold start while
 *       the API is down). Callers that already tolerate a thrown fetch keep that behaviour.</li>
 * </ul>
 *
 * <p>Thread-safe: {@link #fetchRates()} is synchronized so concurrent tab clicks share one
 * in-flight fetch rather than stampeding the API.
 */
public class CachingFxRateProvider implements FxRateProvider {

    private static final Logger log = LoggerFactory.getLogger(CachingFxRateProvider.class);

    private final FxRateProvider delegate;
    private final Duration ttl;

    private Map<String, BigDecimal> cached;
    private Instant cachedAt = Instant.EPOCH;

    public CachingFxRateProvider(FxRateProvider delegate, Duration ttl) {
        this.delegate = delegate;
        this.ttl = ttl;
    }

    @Override
    public synchronized Map<String, BigDecimal> fetchRates() throws Exception {
        if (cached != null && Duration.between(cachedAt, Instant.now()).compareTo(ttl) < 0) {
            return cached;
        }
        try {
            Map<String, BigDecimal> fresh = delegate.fetchRates();
            cached = fresh;
            cachedAt = Instant.now();
            return fresh;
        } catch (Exception e) {
            if (cached != null) {
                log.warn("FX fetch failed; serving cached rates from {} ({} old)",
                        cachedAt, Duration.between(cachedAt, Instant.now()), e);
                return cached;
            }
            throw e; // cold start, no cache to fall back on — let the caller decide
        }
    }
}
