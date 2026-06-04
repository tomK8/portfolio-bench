package com.portfolio.adapter;

import com.portfolio.domain.model.PriceBar;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class YahooPriceFetcherTest {

    private String sample() throws Exception {
        return Files.readString(Path.of(
                getClass().getResource("/yahoo-nvda-sample.json").toURI()));
    }

    @Test
    void parsesBarsSkippingNullClosesAndFallingBackOnAdjClose() throws Exception {
        List<PriceBar> bars = new YahooPriceFetcher().parse("NVDA", sample());

        assertEquals(2, bars.size(), "the third day has a null close and is dropped");

        PriceBar first = bars.get(0);
        assertEquals("NVDA", first.symbol());
        assertEquals(LocalDate.of(2024, 1, 2), first.date());   // ts + gmtoffset, in exchange tz
        assertEquals("USD", first.currency());
        assertEquals(48.10, first.open());
        assertEquals(49.30, first.high());
        assertEquals(47.60, first.low());
        assertEquals(48.20, first.close());
        assertEquals(48.18, first.adjClose());
        assertEquals(411254000L, first.volume());

        PriceBar second = bars.get(1);
        assertEquals(LocalDate.of(2024, 1, 3), second.date());
        assertEquals(47.80, second.adjClose(), "adjclose null → falls back to unadjusted close");
        assertNull(second.volume(), "null volume preserved");
    }

    @Test
    void returnsEmptyForErrorShapedResponse() throws Exception {
        List<PriceBar> bars = new YahooPriceFetcher()
                .parse("BAD", "{\"chart\":{\"result\":null,\"error\":{\"code\":\"Not Found\"}}}");
        assertTrue(bars.isEmpty());
    }
}
