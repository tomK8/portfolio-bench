package com.portfolio.persistence;

import com.portfolio.persistence.InstrumentMappingRepository.InstrumentAlias;
import com.portfolio.persistence.InstrumentMappingRepository.TickerMapping;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InstrumentMappingRepositoryTest {

    private InstrumentMappingRepository repo(Path dir) {
        return new InstrumentMappingRepository(new JdbcConnectionFactory(dir));
    }

    @Test
    void savesAndReadsBackATickerMapping(@TempDir Path dir) {
        InstrumentMappingRepository repo = repo(dir);
        repo.saveTickerMapping("xwhs", "XWHS.L", "GBp", true);

        assertEquals(Map.of("XWHS", "XWHS.L"), repo.loadTickerMappings(), "symbols are stored upper-cased");

        List<TickerMapping> rows = repo.listTickerMappings();
        assertEquals(1, rows.size());
        assertEquals("GBp", rows.get(0).currency());
        assertNotNull(rows.get(0).verifiedAt());
    }

    @Test
    void resavingASymbolReplacesItRatherThanDuplicating(@TempDir Path dir) {
        InstrumentMappingRepository repo = repo(dir);
        repo.saveTickerMapping("XWHS", "XWHS.DE", null, false);
        repo.saveTickerMapping("XWHS", "XWHS.L", "GBp", true);

        assertEquals(Map.of("XWHS", "XWHS.L"), repo.loadTickerMappings());
        assertEquals(1, repo.listTickerMappings().size());
    }

    @Test
    void deletingRemovesTheOverride(@TempDir Path dir) {
        InstrumentMappingRepository repo = repo(dir);
        repo.saveTickerMapping("XWHS", "XWHS.L", "GBp", true);
        repo.deleteTickerMapping("xwhs");
        assertTrue(repo.loadTickerMappings().isEmpty());
    }

    @Test
    void blankInputIsRejectedRatherThanStored(@TempDir Path dir) {
        InstrumentMappingRepository repo = repo(dir);
        assertThrows(IllegalStateException.class, () -> repo.saveTickerMapping("", "XWHS.L", null, false));
        assertThrows(IllegalStateException.class, () -> repo.saveTickerMapping("XWHS", "  ", null, false));
        assertThrows(IllegalStateException.class, () -> repo.saveAlias("xwhs", "", null));
    }

    /**
     * Callers take the first keyword that matches the description, so a more specific phrase has to
     * come first — otherwise "xtrackers" would shadow "xtrackers msci world health" and send every
     * Xtrackers fund to the same symbol.
     */
    @Test
    void aliasesComeBackLongestKeywordFirst(@TempDir Path dir) {
        InstrumentMappingRepository repo = repo(dir);
        repo.saveAlias("xtrackers", "XDWT", null);
        repo.saveAlias("Xtrackers MSCI World Health", "XWHS", "Xtrackers MSCI World Health Care ETF 1C GBP");

        assertEquals(List.of("xtrackers msci world health", "xtrackers"),
                List.copyOf(repo.loadAliases().keySet()));
    }

    @Test
    void aliasKeywordsAreNormalisedAndCarryTheNameTheyCameFrom(@TempDir Path dir) {
        InstrumentMappingRepository repo = repo(dir);
        repo.saveAlias("  Xtrackers   MSCI  World ", "xwhs", "Xtrackers MSCI World Health Care ETF 1C GBP");

        assertEquals(Map.of("xtrackers msci world", "XWHS"), repo.loadAliases());
        InstrumentAlias row = repo.listAliases().get(0);
        assertEquals("Xtrackers MSCI World Health Care ETF 1C GBP", row.rawName());

        repo.deleteAlias("XTRACKERS MSCI WORLD");
        assertTrue(repo.loadAliases().isEmpty(), "delete normalises the key the same way save does");
    }

    /** A read before anything has been written must not fail — no DB file exists yet. */
    @Test
    void readsDegradeToEmptyOnAFreshDatabase(@TempDir Path dir) {
        InstrumentMappingRepository repo = repo(dir);
        assertTrue(repo.loadTickerMappings().isEmpty());
        assertTrue(repo.loadAliases().isEmpty());
        assertTrue(repo.listTickerMappings().isEmpty());
        assertTrue(repo.listAliases().isEmpty());
    }
}
