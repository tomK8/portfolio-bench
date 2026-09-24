package com.portfolio.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The multi-list contract: which symbols a list holds, which order it holds them in, and — the
 * part that is easy to get wrong — what happens to a symbol's <em>global</em> alert settings and
 * to the KV mirror when it is in more than one list.
 *
 * <p>The mirror assertions matter beyond this class. {@code PriceFetchSupport} and
 * {@code PortfolioFundamentalsService} read {@code watchlist_symbols} to decide what to fetch
 * daily; a symbol left in the mirror but in no list would be fetched for ever with no screen able
 * to stop it, and one dropped from the mirror while still listed would quietly stop updating.
 */
class WatchlistListRepositoryTest {

    private WatchlistRepository repo(Path dir) {
        return new WatchlistRepository(new JdbcConnectionFactory(dir), new KeyValueStore(dir));
    }

    private List<String> symbols(WatchlistRepository repo, long listId) {
        return repo.loadAll(listId).stream().map(WatchlistRepository.Entry::symbol).toList();
    }

    /** Every watched symbol, across every list — the alert job's view. */
    private List<String> allSymbols(WatchlistRepository repo) {
        return repo.loadAll().stream().map(WatchlistRepository.Entry::symbol).toList();
    }

    private List<String> mirror(Path dir) {
        return List.copyOf(new KeyValueStore(dir).getStringSet(WatchlistRepository.WATCHLIST_SYMBOLS_KEY));
    }

    @Test
    void aFreshDatabaseStartsWithOneEmptyList(@TempDir Path dir) {
        WatchlistRepository repo = repo(dir);
        List<WatchlistRepository.WatchList> lists = repo.lists();
        assertEquals(1, lists.size(), "the UI always needs a list to show");
        assertEquals("Watchlist", lists.get(0).name());
        assertEquals(0, lists.get(0).size());
    }

    @Test
    void aPreListsWatchlistIsMigratedIntoTheFirstListKeepingItsOrder(@TempDir Path dir) {
        // Write the pre-lists shape by hand: the table, three rows, an explicit display_order,
        // and no list tables at all — exactly what an existing database looks like.
        JdbcConnectionFactory conns = new JdbcConnectionFactory(dir);
        try (java.sql.Connection c = conns.open(); java.sql.Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE watchlist (
                        symbol TEXT PRIMARY KEY, high_threshold_pct REAL NOT NULL,
                        move_threshold_pct REAL NOT NULL, added_at TEXT NOT NULL,
                        display_order INTEGER, price_above REAL, price_below REAL)""");
            st.execute("INSERT INTO watchlist VALUES ('AMD', 3, 5, '2026-01-01T00:00:00Z', 20, 0, 0)");
            st.execute("INSERT INTO watchlist VALUES ('MU',  3, 5, '2026-01-01T00:00:00Z', 10, 0, 0)");
            st.execute("INSERT INTO watchlist VALUES ('TSM', 3, 5, '2026-01-01T00:00:00Z', 30, 0, 0)");
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        WatchlistRepository repo = repo(dir);                       // constructor runs the migration
        long first = repo.firstListId();
        assertEquals(List.of("MU", "AMD", "TSM"), symbols(repo, first),
                "the manual order the user had must survive the promotion");
        assertEquals(3, repo.lists().get(0).size());
    }

    @Test
    void theMigrationDoesNotResurrectSymbolsFromAnEmptiedList(@TempDir Path dir) {
        WatchlistRepository repo = repo(dir);
        long first = repo.firstListId();
        repo.add("AMD", null, null, null, null, first);
        repo.remove("AMD", first);
        assertEquals(List.of(), symbols(repo, first));

        repo(dir);                                                   // restart
        assertEquals(List.of(), symbols(repo(dir), repo.firstListId()),
                "a deliberately emptied list must stay empty across a restart");
    }

    @Test
    void oneSymbolInTwoListsHasOneSetOfTriggers(@TempDir Path dir) {
        WatchlistRepository repo = repo(dir);
        long chips = repo.createList("chips");
        long ai = repo.createList("ai");

        repo.add("AMD", new BigDecimal("3"), new BigDecimal("5"), null, null, chips);
        repo.add("AMD", null, null, null, null, ai);

        assertTrue(symbols(repo, chips).contains("AMD"));
        assertTrue(symbols(repo, ai).contains("AMD"));
        assertEquals(List.of("AMD"), allSymbols(repo), "the alert job must see AMD exactly once");

        repo.updateThresholds("AMD", new BigDecimal("9"), null, null, null);
        for (long list : List.of(chips, ai)) {
            assertEquals(0, new BigDecimal("9").compareTo(repo.loadAll(list).get(0).highThresholdPct()),
                    "a threshold edit reaches every list showing the symbol");
        }
    }

    @Test
    void removingFromOneListLeavesItInTheOther_andTheLastRemovalDropsItEntirely(@TempDir Path dir) {
        WatchlistRepository repo = repo(dir);
        long chips = repo.createList("chips");
        long ai = repo.createList("ai");
        repo.add("AMD", null, null, null, null, chips);
        repo.add("AMD", null, null, null, null, ai);

        repo.remove("AMD", chips);
        assertEquals(List.of(), symbols(repo, chips));
        assertEquals(List.of("AMD"), symbols(repo, ai), "still watched by the other list");
        assertTrue(mirror(dir).contains("AMD"), "still fetched — a list still shows it");

        repo.remove("AMD", ai);
        assertEquals(List.of(), symbols(repo, ai));
        assertEquals(List.of(), allSymbols(repo), "in no list means no longer watched");
        assertTrue(mirror(dir).isEmpty(), "and no longer fetched daily for ever");
    }

    @Test
    void eachListKeepsItsOwnRowOrder(@TempDir Path dir) {
        WatchlistRepository repo = repo(dir);
        long chips = repo.createList("chips");
        long ai = repo.createList("ai");
        for (long list : List.of(chips, ai)) {
            repo.add("AMD", null, null, null, null, list);
            repo.add("MU", null, null, null, null, list);
        }
        assertEquals(List.of("AMD", "MU"), symbols(repo, chips));

        repo.move("MU", true, chips);
        assertEquals(List.of("MU", "AMD"), symbols(repo, chips));
        assertEquals(List.of("AMD", "MU"), symbols(repo, ai),
                "reordering one list must not disturb another showing the same names");
    }

    @Test
    void reAddingASymbolLeavesItWhereTheUserPutIt(@TempDir Path dir) {
        WatchlistRepository repo = repo(dir);
        long first = repo.firstListId();
        repo.add("AMD", null, null, null, null, first);
        repo.add("MU", null, null, null, null, first);
        repo.move("MU", true, first);
        assertEquals(List.of("MU", "AMD"), symbols(repo, first));

        // Re-adding is how the add box edits an existing row's thresholds.
        repo.add("MU", new BigDecimal("7"), null, null, null, first);
        assertEquals(List.of("MU", "AMD"), symbols(repo, first), "a re-add must not bounce it to the bottom");
        assertEquals(0, new BigDecimal("7").compareTo(repo.loadAll(first).get(0).highThresholdPct()));
    }

    @Test
    void deletingAListDropsOnlyTheSymbolsNoOtherListHolds(@TempDir Path dir) {
        WatchlistRepository repo = repo(dir);
        long chips = repo.createList("chips");
        long ai = repo.createList("ai");
        repo.add("AMD", null, null, null, null, chips);   // shared
        repo.add("AMD", null, null, null, null, ai);
        repo.add("MU", null, null, null, null, chips);    // chips only

        repo.deleteList(chips);
        assertEquals(List.of("AMD"), symbols(repo, ai));
        assertEquals(List.of("AMD"), allSymbols(repo), "MU was in no other list, so it is gone");
        assertEquals(List.of("AMD"), mirror(dir));
    }

    @Test
    void theLastListCannotBeDeleted(@TempDir Path dir) {
        WatchlistRepository repo = repo(dir);
        long only = repo.firstListId();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> repo.deleteList(only));
        assertTrue(e.getMessage().contains("only watchlist"), "the message must say why, not just fail");
        assertEquals(1, repo.lists().size());
    }

    @Test
    void aDuplicateNameIsRejectedRegardlessOfCase(@TempDir Path dir) {
        WatchlistRepository repo = repo(dir);
        repo.createList("chips");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> repo.createList("CHIPS"));
        assertTrue(e.getMessage().contains("already a watchlist"),
                "two tabs the user cannot tell apart are worse than a rejected name");
    }

    @Test
    void aBlankNameIsRejected(@TempDir Path dir) {
        WatchlistRepository repo = repo(dir);
        assertThrows(IllegalStateException.class, () -> repo.createList("   "));
    }

    @Test
    void listsReorderBySwappingWithTheirNeighbour(@TempDir Path dir) {
        WatchlistRepository repo = repo(dir);
        repo.createList("chips");
        repo.createList("banks");
        assertEquals(List.of("Watchlist", "chips", "banks"),
                repo.lists().stream().map(WatchlistRepository.WatchList::name).toList());

        repo.moveList(repo.lists().get(2).id(), true);
        assertEquals(List.of("Watchlist", "banks", "chips"),
                repo.lists().stream().map(WatchlistRepository.WatchList::name).toList());

        repo.moveList(repo.lists().get(0).id(), true);   // already first
        assertEquals(List.of("Watchlist", "banks", "chips"),
                repo.lists().stream().map(WatchlistRepository.WatchList::name).toList(),
                "moving past an end is a no-op, not a wrap-around");
    }

    @Test
    void anUnknownListReadsAsEmptyRatherThanThrowing(@TempDir Path dir) {
        WatchlistRepository repo = repo(dir);
        repo.add("AMD", null, null, null, null, repo.firstListId());
        assertEquals(List.of(), symbols(repo, 9999L));
    }
}
