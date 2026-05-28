package com.pension.adapter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class YahooTickerMapTest {

    private final YahooTickerMap map = new YahooTickerMap();

    @Test
    void appliesExchangeSuffixesAndNormalisations() {
        assertEquals("EQQQ.L", map.tickerFor("EQQQ"));
        assertEquals("LGEN.L", map.tickerFor("LGEN"));
        assertEquals("BNP.PA", map.tickerFor("BNP"));
        assertEquals("ASML.AS", map.tickerFor("ASML"));
        assertEquals("GOOG", map.tickerFor("GOOGL"), "GOOGL normalises onto GOOG");
    }

    @Test
    void usListingsMapToThemselves() {
        assertEquals("NVDA", map.tickerFor("NVDA"));
        assertEquals("MSFT", map.tickerFor("MSFT"));
    }

    @Test
    void detectsGilts() {
        assertTrue(map.isGilt("GILT 0.875% 2033"));
        assertFalse(map.isGilt("NVDA"));
        assertFalse(map.isGilt(null));
    }
}
