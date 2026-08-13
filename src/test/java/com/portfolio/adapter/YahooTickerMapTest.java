package com.portfolio.adapter;

import org.junit.jupiter.api.Test;

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

}
