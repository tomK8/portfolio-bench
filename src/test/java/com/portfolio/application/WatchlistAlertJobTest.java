package com.portfolio.application;

import com.portfolio.persistence.KeyValueStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link WatchlistAlertJob#clearFiredFor(String)} — the re-arm hook called when a symbol's
 * triggers change so a corrected target can fire fresh the same day (and a stale firing for a
 * now-disabled trigger stops lingering). {@code clearFiredFor} only touches the KV set, so the
 * service/notifier deps are left null.
 */
class WatchlistAlertJobTest {

    private static WatchlistAlertJob job(KeyValueStore kv) {
        return new WatchlistAlertJob(null, null, kv);
    }

    @Test
    void clearFiredFor_dropsOnlyThatSymbolsKeys(@TempDir Path dir) {
        KeyValueStore kv = new KeyValueStore(dir);
        kv.putStringSet(WatchlistAlertJob.FIRED_KEY, List.of(
                "GILT 4.5% 2028|ABOVE|2026-08-05",
                "GILT 4.5% 2028|BELOW|2026-08-05",
                "NVDA|MOVE|2026-08-05"));

        job(kv).clearFiredFor("GILT 4.5% 2028");

        Set<String> left = kv.getStringSet(WatchlistAlertJob.FIRED_KEY);
        assertEquals(Set.of("NVDA|MOVE|2026-08-05"), left,
                "only the mutated symbol's fired flags should be dropped");
    }

    @Test
    void clearFiredFor_isCaseAndWhitespaceInsensitive(@TempDir Path dir) {
        KeyValueStore kv = new KeyValueStore(dir);
        kv.putStringSet(WatchlistAlertJob.FIRED_KEY, List.of("NVDA|ABOVE|2026-08-05"));

        job(kv).clearFiredFor("  nvda ");   // normalised to NVDA before matching

        assertTrue(kv.getStringSet(WatchlistAlertJob.FIRED_KEY).isEmpty());
    }

    @Test
    void clearFiredFor_noMatch_isQuietNoop(@TempDir Path dir) {
        KeyValueStore kv = new KeyValueStore(dir);
        kv.putStringSet(WatchlistAlertJob.FIRED_KEY, List.of("NVDA|ABOVE|2026-08-05"));

        assertDoesNotThrow(() -> job(kv).clearFiredFor("TSLA"));
        assertDoesNotThrow(() -> job(kv).clearFiredFor(null));
        assertEquals(Set.of("NVDA|ABOVE|2026-08-05"), kv.getStringSet(WatchlistAlertJob.FIRED_KEY));
    }
}
