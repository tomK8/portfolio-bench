package com.portfolio.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the server-side contract the watchlist inline-edit UI relies on: an updated threshold — in
 * particular a <em>lowered</em> price-below level on a symbol that is already alerting — persists and
 * reads straight back. The "my edit won't save" report was a client-side stale-reload race (the save
 * did persist; a concurrent slow /watchlist read repainted the pre-save value); these tests guard the
 * write path itself so a real persistence regression would surface here rather than be mistaken for
 * the UI bug again.
 */
class WatchlistRepositoryTest {

    private WatchlistRepository repo(Path dir) {
        return new WatchlistRepository(new JdbcConnectionFactory(dir), new KeyValueStore(dir));
    }

    private WatchlistRepository.Entry entry(WatchlistRepository repo, String symbol) {
        return repo.loadAll().stream().filter(e -> e.symbol().equals(symbol)).findFirst().orElseThrow();
    }

    @Test
    void loweringAnAlreadySetBelowLevelPersists(@TempDir Path dir) {
        WatchlistRepository repo = repo(dir);
        String gilt = "GILT 4.375% 2030";                       // '%' + spaces — the real symbol shape
        repo.add(gilt, null, null, null, new BigDecimal("100.01"));
        assertEquals(0, new BigDecimal("100.01").compareTo(entry(repo, gilt).priceBelow()));

        repo.updateThresholds(gilt, null, null, null, new BigDecimal("99.7"));   // downward edit
        assertEquals(0, new BigDecimal("99.7").compareTo(entry(repo, gilt).priceBelow()),
                "a lowered below-level must persist");

        // And upward again, to be sure direction is irrelevant to the write.
        repo.updateThresholds(gilt, null, null, null, new BigDecimal("100.02"));
        assertEquals(0, new BigDecimal("100.02").compareTo(entry(repo, gilt).priceBelow()));
    }

    @Test
    void blankingALevelDisablesIt(@TempDir Path dir) {
        WatchlistRepository repo = repo(dir);
        repo.add("NVDA", null, null, null, new BigDecimal("232"));
        assertEquals(0, new BigDecimal("232").compareTo(entry(repo, "NVDA").priceBelow()));

        repo.updateThresholds("NVDA", null, null, null, null);     // clear the level
        // Disabled is stored as the ZERO sentinel and read back as 0 (the UI renders that as blank).
        assertEquals(0, BigDecimal.ZERO.compareTo(entry(repo, "NVDA").priceBelow()),
                "clearing a level disables it (stored as 0)");
    }

    @Test
    void updatingOneLevelLeavesTheOtherThreeIntact(@TempDir Path dir) {
        WatchlistRepository repo = repo(dir);
        repo.add("AAA", new BigDecimal("3"), new BigDecimal("10"),
                new BigDecimal("250"), new BigDecimal("200"));

        // Edit only the below level; high/move/above must survive the UPDATE untouched.
        repo.updateThresholds("AAA", new BigDecimal("3"), new BigDecimal("10"),
                new BigDecimal("250"), new BigDecimal("190"));

        WatchlistRepository.Entry e = entry(repo, "AAA");
        assertEquals(0, new BigDecimal("3").compareTo(e.highThresholdPct()));
        assertEquals(0, new BigDecimal("10").compareTo(e.moveThresholdPct()));
        assertEquals(0, new BigDecimal("250").compareTo(e.priceAbove()));
        assertEquals(0, new BigDecimal("190").compareTo(e.priceBelow()));
    }

    @Test
    void updatingThresholdsForARemovedSymbolIsABenignNoOp(@TempDir Path dir) {
        WatchlistRepository repo = repo(dir);
        // Reproduces the delete/save race: the user hits × (DELETE lands) while an inline edit's
        // blur-triggered save is still in flight, so the UPDATE hits zero rows. That must NOT throw —
        // a throw drives the client's error path and flaps the just-deleted row back on screen — and
        // must not resurrect the row.
        repo.add("TSLA", null, null, null, new BigDecimal("200"));
        repo.remove("TSLA");
        assertDoesNotThrow(() -> repo.updateThresholds("TSLA", new BigDecimal("5"),
                new BigDecimal("8"), new BigDecimal("300"), new BigDecimal("150")));
        assertFalse(repo.loadAll().stream().anyMatch(e -> e.symbol().equals("TSLA")),
                "a no-op update must not recreate the removed row");
    }

    @Test
    void loadAllReflectsTheLatestWriteImmediately(@TempDir Path dir) {
        WatchlistRepository repo = repo(dir);
        repo.add("MSFT", null, null, null, new BigDecimal("400"));
        // Read-after-write: every update is visible on the very next read (no caching layer to skew
        // the UI's reload). Twenty quick descending edits, each asserted straight back.
        for (int i = 0; i < 20; i++) {
            BigDecimal below = new BigDecimal("400").subtract(new BigDecimal(i));
            repo.updateThresholds("MSFT", null, null, null, below);
            assertEquals(0, below.compareTo(entry(repo, "MSFT").priceBelow()),
                    "iteration " + i + " must read back the value just written");
        }
        List<WatchlistRepository.Entry> all = repo.loadAll();
        assertTrue(all.stream().anyMatch(e -> e.symbol().equals("MSFT")));
    }

    // ---- the per-symbol note ---------------------------------------------------

    @Test
    void aNoteRoundTripsAndBlankMeansNoNote(@TempDir Path dir) {
        WatchlistRepository repo = repo(dir);
        repo.add("AAPL", null, null, new BigDecimal("250"), null);
        assertNull(entry(repo, "AAPL").note(), "a symbol starts with no note");

        repo.updateNote("AAPL", "  Trim 25% in the SIPP, keep the Dealing lot  ");
        assertEquals("Trim 25% in the SIPP, keep the Dealing lot", entry(repo, "AAPL").note(),
                "stored trimmed, so the row and the alert email read the same");

        // Blank is an absence, not an empty string: the alert job asks whether there IS a note.
        repo.updateNote("AAPL", "   ");
        assertNull(entry(repo, "AAPL").note());
        repo.updateNote("AAPL", null);
        assertNull(entry(repo, "AAPL").note());
    }

    @Test
    void addingAWatchedSymbolAgainKeepsItsNote(@TempDir Path dir) {
        WatchlistRepository repo = repo(dir);
        repo.add("NVDA", new BigDecimal("3"), null, null, null);
        repo.updateNote("NVDA", "Sell 10 shares into any 5% pop");

        // How a name joins a second list: the add box re-adds it, carrying thresholds but no note.
        // The upsert must leave the note alone — erasing a written plan is not what adding a tab does.
        long second = repo.createList("Chips");
        repo.add("NVDA", new BigDecimal("4"), new BigDecimal("6"), null, null, second);

        assertEquals("Sell 10 shares into any 5% pop", entry(repo, "NVDA").note());
        assertEquals(0, new BigDecimal("4").compareTo(entry(repo, "NVDA").highThresholdPct()),
                "the thresholds, unlike the note, are what an add restates");
    }

    @Test
    void notingARemovedSymbolIsABenignNoOp(@TempDir Path dir) {
        WatchlistRepository repo = repo(dir);
        repo.add("TSLA", null, null, null, new BigDecimal("200"));
        repo.remove("TSLA");
        // Same delete/save race as the thresholds: × lands while the note input's blur-save is in
        // flight. A throw would drive the client's error path and flap the deleted row back on screen.
        assertDoesNotThrow(() -> repo.updateNote("TSLA", "buy the dip"));
        assertFalse(repo.loadAll().stream().anyMatch(e -> e.symbol().equals("TSLA")),
                "a no-op note update must not recreate the removed row");
    }

    @Test
    void theNoteColumnIsAddedToSavedLayoutsOnceAndStaysRemovedWhenRemoved(@TempDir Path dir) {
        JdbcConnectionFactory cf = new JdbcConnectionFactory(dir);
        KeyValueStore kv = new KeyValueStore(dir);
        WatchlistRepository repo = new WatchlistRepository(cf, kv);
        long list = repo.firstListId();
        // A layout saved before the note existed: it names its own keys, so a column added later
        // would never reach it (an uncustomised list renders WlColumns.DEFAULTS and gets it free).
        repo.saveColumns(list, List.of("price", "todayPct", "thrAbove"));
        kv.putBoolean(WatchlistRepository.NOTE_COLUMN_BACKFILLED_KEY, false);

        new WatchlistRepository(cf, kv);                       // restart

        assertEquals(List.of("price", "todayPct", "thrAbove", "note"), repo.columnsFor(list),
                "the note joins at the end of a layout that predates it");

        // Now the user takes it out again. The marker is why this sticks: guarding on "the layout
        // doesn't list note" would put it back on every restart.
        repo.saveColumns(list, List.of("price", "todayPct", "thrAbove"));
        new WatchlistRepository(cf, kv);

        assertEquals(List.of("price", "todayPct", "thrAbove"), repo.columnsFor(list),
                "a column the user removed must not be resurrected by the next start");
    }
}
