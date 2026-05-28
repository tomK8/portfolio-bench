package com.pension.adapter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class YahooTickerMapTest {

    private final YahooTickerMap map = new YahooTickerMap();

    @Test
    void appliesExchangeSuffixes() {
        assertEquals(List.of("EQQQ.L"), map.tickersFor("EQQQ"));
        assertEquals(List.of("BNP.PA"), map.tickersFor("BNP"));
        assertEquals(List.of("ASML.AS"), map.tickersFor("ASML"));
        assertEquals(List.of("HSBA.L"), map.tickersFor("HSBA"));
        assertEquals(List.of("SGBX.L"), map.tickersFor("SGBX"));
    }

    @Test
    void splitsCommaSeparatedValuesForDualListings() {
        assertEquals(List.of("GOOG", "GOOGL"), map.tickersFor("GOOG/GOOGL"));
    }

    @Test
    void usListingsMapToThemselves() {
        assertEquals(List.of("NVDA"), map.tickersFor("NVDA"));
        assertEquals(List.of("MSFT"), map.tickersFor("MSFT"));
    }

    @Test
    void detectsGilts() {
        assertTrue(map.isGilt("GILT 0.875% 2033"));
        assertFalse(map.isGilt("NVDA"));
        assertFalse(map.isGilt(null));
    }
}
