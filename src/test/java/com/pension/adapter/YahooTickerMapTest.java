package com.pension.adapter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class YahooTickerMapTest {

    private final YahooTickerMap map = new YahooTickerMap();

    @Test
    void appliesExchangeSuffixes() {
        assertEquals("EQQQ.L", map.tickerFor("EQQQ"));
        assertEquals("BNP.PA", map.tickerFor("BNP"));
        assertEquals("ASML.AS", map.tickerFor("ASML"));
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
    void detectsGilts() {
        assertTrue(map.isGilt("GILT 0.875% 2033"));
        assertFalse(map.isGilt("NVDA"));
        assertFalse(map.isGilt(null));
    }
}
