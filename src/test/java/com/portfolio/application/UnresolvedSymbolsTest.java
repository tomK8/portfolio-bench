package com.portfolio.application;

import com.portfolio.adapter.YahooPriceFetcher.Suggestion;
import com.portfolio.persistence.KeyValueStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The third state: "Yahoo rejected this" as something a reader can see, not only something the log
 * knows. The bug this replaces is the silence — {@code EQQU} 404'd on every fetch while the
 * watchlist row said "(fetching…)" and the limit probe said "reload in a few seconds", forever,
 * because nothing recorded that the question had already been answered.
 */
class UnresolvedSymbolsTest {

    @TempDir
    Path dbDir;

    private UnresolvedSymbols store() {
        return new UnresolvedSymbols(new KeyValueStore(dbDir));
    }

    private static Suggestion lse(String symbol, String name) {
        return new Suggestion(symbol, name, "LSE", "ETF");
    }

    @Test
    void aSymbolNeverRejectedHasNoVerdict() {
        assertNull(store().get("NVDA"));
    }

    /** The distinction the whole class exists for: absent ≠ rejected. */
    @Test
    void aRecordedVerdictSurvivesAReadThroughANewInstance() {
        store().record("EQQU", "EQQU", List.of(lse("EQQU.L", "Invesco EQQQ NASDAQ-100 UCITS ETF")));

        UnresolvedSymbols.Unresolved v = store().get("EQQU");
        assertNotNull(v);
        assertEquals("EQQU", v.symbol());
        assertEquals("EQQU", v.ticker());
        assertEquals(LocalDate.now(), v.checkedOn());
        assertEquals(1, v.suggestions().size());
        assertTrue(v.suggestions().get(0).startsWith("EQQU.L (LSE"));
    }

    @Test
    void lookupIsCaseAndWhitespaceInsensitive() {
        store().record("eqqu ", "EQQU", List.of());
        assertNotNull(store().get(" EqQu"));
    }

    /** The candidate is the actionable half — the message must name the ticker that would work. */
    @Test
    void theMessageNamesTheSuggestedTickerAndWhereToMapIt() {
        store().record("EQQU", "EQQU", List.of(lse("EQQU.L", "Invesco EQQQ NASDAQ-100 UCITS ETF")));

        String msg = store().get("EQQU").message();
        assertTrue(msg.contains("no listing for EQQU"), msg);
        assertTrue(msg.contains("EQQU.L"), msg);
        assertTrue(msg.contains("EQQU → EQQU.L"), msg);
        assertTrue(msg.contains("Open orders"), msg);
    }

    @Test
    void bestSuggestionIsTheBareTickerNotTheWholeDisplayLine() {
        store().record("EQQU", "EQQU", List.of(lse("EQQU.L", "Invesco EQQQ NASDAQ-100 UCITS ETF")));
        assertEquals("EQQU.L", store().get("EQQU").bestSuggestion());
    }

    /** No candidates reads as a typo, and must not claim Yahoo suggested something. */
    @Test
    void noCandidatesYieldsASpellingMessageAndANullBestSuggestion() {
        store().record("ZQZQZQ", "ZQZQZQ", List.of());

        UnresolvedSymbols.Unresolved v = store().get("ZQZQZQ");
        assertTrue(v.suggestions().isEmpty());
        assertNull(v.bestSuggestion());
        String msg = v.message();
        assertTrue(msg.contains("knows nothing by that name"), msg);
        assertFalse(msg.contains("does know:"), msg);
    }

    /** A mapped symbol failed as its ticker, and the message has to say which one was tried. */
    @Test
    void aMappedTickerIsNamedSeparatelyFromTheSymbol() {
        store().record("BLND", "BLND.WRONG", List.of());
        assertTrue(store().get("BLND").message().contains("BLND's mapped ticker BLND.WRONG"));
    }

    @Test
    void clearingRemovesTheVerdictAndLeavesOthersAlone() {
        UnresolvedSymbols s = store();
        s.record("EQQU", "EQQU", List.of());
        s.record("FOO", "FOO", List.of());

        s.clear("eqqu");
        assertNull(s.get("EQQU"));
        assertNotNull(s.get("FOO"));
    }

    @Test
    void recordingTwiceOverwritesRatherThanAccumulating() {
        UnresolvedSymbols s = store();
        s.record("EQQU", "EQQU", List.of(lse("EQQU.L", "first")));
        s.record("EQQU", "EQQU.X", List.of(lse("EQQU.PA", "second")));

        UnresolvedSymbols.Unresolved v = s.get("EQQU");
        assertEquals("EQQU.X", v.ticker());
        assertEquals(1, v.suggestions().size());
        assertEquals("EQQU.PA", v.bestSuggestion());
    }

    /**
     * The store is line-per-symbol, so a listing name carrying the separators or a newline would
     * corrupt every entry after it. Names come from Yahoo, so this is not hypothetical.
     */
    @Test
    void aListingNameWithSeparatorsDoesNotCorruptTheStore() {
        UnresolvedSymbols s = store();
        s.record("WEIRD", "WEIRD",
                List.of(new Suggestion("W.L", "Line\none\u001Ftwo\u001Ethree", "LSE", "ETF")));
        s.record("AFTER", "AFTER", List.of());

        assertNotNull(s.get("AFTER"));
        UnresolvedSymbols.Unresolved v = s.get("WEIRD");
        assertEquals(1, v.suggestions().size());
        assertEquals("W.L", v.bestSuggestion());
    }

    @Test
    void onlyTheFirstFewCandidatesAreKept() {
        store().record("MANY", "MANY", List.of(
                lse("A.L", "a"), lse("B.L", "b"), lse("C.L", "c"), lse("D.L", "d"),
                lse("E.L", "e"), lse("F.L", "f")));
        assertEquals(4, store().get("MANY").suggestions().size());
    }

    @Test
    void aBlankSymbolIsNeitherRecordedNorLookedUp() {
        UnresolvedSymbols s = store();
        s.record("  ", "X", List.of());
        assertNull(s.get("  "));
        assertNull(s.get(null));
        s.clear(null);   // must not throw
    }
}
