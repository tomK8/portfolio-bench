package com.portfolio.application;

import com.portfolio.domain.model.PreviousClose;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-ticker previous-session closes, as last reported by Yahoo. Written by
 * {@link IntradayPriceFetchJob} on every pass — the figure rides along on a response the job
 * already makes, so it costs no extra HTTP call — and read by the Live tab as the Day Δ baseline.
 *
 * <p>Sibling to {@link MarketHoursCache} and in memory for the same reason: a previous close
 * belongs to one session, so one that survived a restart would be a day stale, which is exactly
 * the input that makes the Day Δ column lie. Losing it on restart is the safe failure — the whole
 * map is rebuilt within one fetch pass, and until then the column says it does not know.
 *
 * <p>Entries older than {@link #STALE_AFTER} are treated as absent, so a laptop that slept
 * through the weekend cannot wake up and measure Monday's move against Thursday's close.
 */
public class PreviousCloseCache {

    /** Longer than any plausible fetch gap while running, shorter than one trading day. */
    static final Duration STALE_AFTER = Duration.ofHours(6);

    private final Map<String, PreviousClose> byTicker = new ConcurrentHashMap<>();

    public void put(PreviousClose close) {
        if (close != null && close.ticker() != null) byTicker.put(close.ticker(), close);
    }

    /** The listing's previous close, or null when unknown or too old to trust. */
    public PreviousClose get(String ticker) {
        PreviousClose c = byTicker.get(ticker);
        if (c == null) return null;
        return Duration.between(c.capturedAt(), Instant.now()).compareTo(STALE_AFTER) > 0 ? null : c;
    }

    public int size() {
        return byTicker.size();
    }
}
