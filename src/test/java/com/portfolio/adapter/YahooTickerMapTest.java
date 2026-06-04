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

}
