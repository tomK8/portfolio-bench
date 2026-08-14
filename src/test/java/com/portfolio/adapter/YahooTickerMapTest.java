package com.portfolio.adapter;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class YahooTickerMapTest {

    private final YahooTickerMap map = new YahooTickerMap();

    @Test
    void appliesExchangeSuffixes() {
        assertEquals("EQQQ.L", map.tickerFor("EQQQ"));
        assertEquals("BNP.PA", map.tickerFor("BNP"));
        assertEquals("XAIX.L", map.tickerFor("XAIX"));
        assertEquals("HSBA.L", map.tickerFor("HSBA"));
        assertEquals("SGBX.L", map.tickerFor("SGBX"));
        assertEquals("RHM.DE", map.tickerFor("RHM"));
        assertEquals("GLE.PA", map.tickerFor("GLE"));
    }

    @Test
    void usListingsMapToThemselves() {
        assertEquals("NVDA", map.tickerFor("NVDA"));
        assertEquals("GOOG", map.tickerFor("GOOG"));
        assertEquals("GOOGL", map.tickerFor("GOOGL"));
    }

    @Test
    void rewritesShareClassPunctuationAndLseSuffixes() {
        assertEquals("BRK-B", map.tickerFor("BRK.B"));
        assertEquals("RBTX.L", map.tickerFor("RBTX"));
        assertEquals("MCTS.L", map.tickerFor("MCTS"));
    }

    /**
     * The UK REITs are the case that motivated the mapping warnings: unmapped, {@code PHP} resolves
     * on Yahoo to a US mutual fund delisted in 2019 (no data at all) and {@code BLND} to an
     * unrelated NYSE listing near $1.50 — silently wrong rather than silently absent.
     */
    @Test
    void mapsUkReitsThatCollideWithUsTickers() {
        assertEquals("PHP.L", map.tickerFor("PHP"));
        assertEquals("BLND.L", map.tickerFor("BLND"));
        assertEquals("LMP.L", map.tickerFor("LMP"));
    }

    @Test
    void reportsWhetherASymbolIsExplicitlyMapped() {
        assertTrue(map.hasMapping("PHP"));
        assertFalse(map.hasMapping("NVDA"));
    }

    @Test
    void identityResolutionIsDistinguishedFromAMappedTicker() {
        assertTrue(map.resolvedByIdentity("NVDA"));      // no property maps to it
        assertFalse(map.resolvedByIdentity("PHP.L"));    // right-hand side of PHP=PHP.L
        assertFalse(map.resolvedByIdentity("EQQQ.L"));
    }

    // ---- user overrides (the ticker_mappings overlay) ---------------------------------------

    @Test
    void userOverrideAddsAMappingTheFileDoesNotHave() {
        YahooTickerMap m = new YahooTickerMap(() -> Map.of("XWHS", "XWHS.L"));
        assertEquals("XWHS.L", m.tickerFor("XWHS"));
        assertTrue(m.hasMapping("XWHS"));
        assertFalse(m.resolvedByIdentity("XWHS.L"));
    }

    /**
     * A committed mapping that turns out to be wrong has to be correctable without a release —
     * that is the whole reason the overlay wins rather than merely filling gaps.
     */
    @Test
    void userOverrideBeatsTheCommittedFile() {
        YahooTickerMap m = new YahooTickerMap(() -> Map.of("PHP", "PHP.XX"));
        assertEquals("PHP.XX", m.tickerFor("PHP"));
    }

    @Test
    void reloadPicksUpAMappingAddedAfterConstruction() {
        Map<String, String> live = new HashMap<>();
        YahooTickerMap m = new YahooTickerMap(() -> Map.copyOf(live));
        assertEquals("XWHS", m.tickerFor("XWHS"), "identity until mapped");

        live.put("XWHS", "XWHS.L");
        assertEquals("XWHS", m.tickerFor("XWHS"), "cached — no per-lookup read");

        m.reload();
        assertEquals("XWHS.L", m.tickerFor("XWHS"));
    }

    @Test
    void overrideKeysAreCaseInsensitiveAndBlanksAreIgnored() {
        Map<String, String> raw = new HashMap<>();
        raw.put("xwhs", " XWHS.L ");
        raw.put("EMPTY", "");
        YahooTickerMap m = new YahooTickerMap(() -> raw);

        assertEquals("XWHS.L", m.tickerFor("XWHS"));
        assertEquals("EMPTY", m.tickerFor("EMPTY"), "a blank ticker is not a mapping");
    }

    @Test
    void builtInMappingsListTheCommittedFileOnly() {
        YahooTickerMap m = new YahooTickerMap(() -> Map.of("XWHS", "XWHS.L"));
        assertEquals("EQQQ.L", m.builtInMappings().get("EQQQ"));
        assertFalse(m.builtInMappings().containsKey("XWHS"));
    }
}
