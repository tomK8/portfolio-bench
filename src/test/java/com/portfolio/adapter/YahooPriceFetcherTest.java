package com.portfolio.adapter;

import com.portfolio.domain.model.IntradayBar;
import com.portfolio.domain.model.PriceBar;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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

    @Test
    void parsesIntradayBarsSkippingNullClosesWithUtcInstants() throws Exception {
        String json = Files.readString(Path.of(
                getClass().getResource("/yahoo-aapl-intraday-sample.json").toURI()));

        List<IntradayBar> bars = new YahooPriceFetcher().parseIntraday("AAPL", json);

        assertEquals(3, bars.size(), "the third minute has a null close and is dropped");

        IntradayBar first = bars.get(0);
        assertEquals("AAPL", first.symbol());
        assertEquals(Instant.ofEpochSecond(1717074000), first.ts(),
                "intraday ts is raw epoch seconds → UTC Instant, no gmtoffset shift");
        assertEquals(190.10, first.close());
        assertEquals(12000L, first.volume());
        assertEquals("USD", first.currency());

        assertEquals(190.50, bars.get(2).close());
        assertEquals(8800L, bars.get(2).volume());
    }

    @Test
    void parseIntradayReturnsEmptyForErrorShape() throws Exception {
        List<IntradayBar> bars = new YahooPriceFetcher()
                .parseIntraday("BAD", "{\"chart\":{\"result\":null,\"error\":{\"code\":\"Not Found\"}}}");
        assertTrue(bars.isEmpty());
    }
}
