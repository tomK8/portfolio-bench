package com.portfolio.application;

import com.portfolio.domain.model.PreviousClose;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class PreviousCloseCacheTest {

    @Test
    void servesWhatTheFeedLastSaid() {
        var cache = new PreviousCloseCache();
        cache.put(new PreviousClose("NVDA", 145.98, "USD", Instant.now()));

        PreviousClose got = cache.get("NVDA");
        assertNotNull(got);
        assertEquals(145.98, got.close());
        assertEquals("USD", got.currency());
    }

    @Test
    void unknownTickerIsNullRatherThanAnything() {
        assertNull(new PreviousCloseCache().get("NVDA"));
    }

    /**
     * A previous close belongs to one session. An entry that has outlived the gap any healthy
     * fetch pass leaves behind is evidence the pass stopped, not that the market stood still —
     * serving it would measure Monday's move against Thursday's close.
     */
    @Test
    void entryOlderThanTheStalenessBoundReadsAsAbsent() {
        var cache = new PreviousCloseCache();
        cache.put(new PreviousClose("NVDA", 145.98, "USD",
                Instant.now().minus(PreviousCloseCache.STALE_AFTER).minusSeconds(60)));

        assertNull(cache.get("NVDA"), "too old to be this session's previous close");
        assertEquals(1, cache.size(), "still held, just not served");
    }

    @Test
    void aNullCaptureIsIgnoredRatherThanStored() {
        var cache = new PreviousCloseCache();
        cache.put(null);
        assertEquals(0, cache.size());
    }
}
