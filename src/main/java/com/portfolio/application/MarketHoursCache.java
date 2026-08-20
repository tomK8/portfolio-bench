package com.portfolio.application;

import com.portfolio.domain.model.MarketHours;
import com.portfolio.domain.model.MarketState;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-ticker trading calendars, as last reported by Yahoo. Written by
 * {@link IntradayPriceFetchJob} on every pass (the calendar rides along on a response the job
 * already makes, so this costs no extra HTTP call) and read by the Live tab to decide whether a
 * listing's market is open, in extended hours, or shut.
 *
 * <p>In memory rather than a table on purpose: the whole map is rebuilt within one fetch pass of
 * startup, and a calendar that survived a restart would be a day stale — precisely the input that
 * makes a closed-market badge lie. Losing it on restart is the safe failure.
 *
 * <p>Entries older than {@link #STALE_AFTER} are treated as absent. That is what stops a laptop
 * that slept through the weekend from waking up and asserting Friday's windows against Monday's
 * clock; the caller falls back to judging state by quote age instead.
 */
public class MarketHoursCache {

    /** Longer than any plausible fetch gap while running, shorter than one trading day. */
    static final Duration STALE_AFTER = Duration.ofHours(6);

    private final Map<String, MarketHours> byTicker = new ConcurrentHashMap<>();

    public void put(MarketHours hours) {
        if (hours != null && hours.ticker() != null) byTicker.put(hours.ticker(), hours);
    }

    /** The listing's calendar, or null when unknown or too old to trust. */
    public MarketHours get(String ticker) {
        MarketHours h = byTicker.get(ticker);
        if (h == null) return null;
        return Duration.between(h.capturedAt(), Instant.now()).compareTo(STALE_AFTER) > 0 ? null : h;
    }

    /**
     * Market state for {@code ticker} at {@code at}, or {@link MarketState#UNKNOWN} when no
     * usable calendar is held — the caller decides what to do with that rather than being handed
     * a confident CLOSED it cannot distinguish from a real one.
     */
    public MarketState stateAt(String ticker, Instant at) {
        MarketHours h = get(ticker);
        return h == null ? MarketState.UNKNOWN : h.stateAt(at);
    }

    public int size() {
        return byTicker.size();
    }
}
