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
    void parsesBarsSkippingNullClosesAndUsesCloseAsAdjWhenNoEvents() throws Exception {
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
        // No events block in this fixture → adjClose equals close. Yahoo's bundled adjclose
        // field is no longer trusted; see parser comment for why.
        assertEquals(48.20, first.adjClose());
        assertEquals(411254000L, first.volume());

        PriceBar second = bars.get(1);
        assertEquals(LocalDate.of(2024, 1, 3), second.date());
        assertEquals(47.80, second.adjClose(), "no events → adjClose equals close");
        assertNull(second.volume(), "null volume preserved");
    }

    @Test
    void appliesDividendAdjustmentBackwardFromExDate() throws Exception {
        // Synthetic fixture: 5 trading days. Day 4 (2024-01-05) is a £2 dividend ex-date.
        // The day-before-ex close is 102. So all bars on or before 2024-01-04 get
        // factor = (102 - 2) / 102 ≈ 0.98039. Bars on or after the ex-date are unadjusted.
        List<PriceBar> bars = new YahooPriceFetcher().parse("TEST",
                Files.readString(Path.of(getClass().getResource("/yahoo-dividend-sample.json").toURI())));

        assertEquals(5, bars.size());
        double factor = (102.0 - 2.0) / 102.0;
        assertEquals(100.0 * factor, bars.get(0).adjClose(), 1e-9, "earliest bar, dividend folded in");
        assertEquals(101.0 * factor, bars.get(1).adjClose(), 1e-9);
        assertEquals(102.0 * factor, bars.get(2).adjClose(), 1e-9, "day before ex: still adjusted");
        assertEquals(100.0, bars.get(3).adjClose(), 1e-9, "ex-date: unadjusted (already reflects drop)");
        assertEquals(101.0, bars.get(4).adjClose(), 1e-9, "after ex: unadjusted");
    }

    @Test
    void ignoresSplitsBecauseCloseIsAlreadySplitAdjusted() throws Exception {
        // Synthetic fixture: closes look continuous across the split date because Yahoo's
        // close column is already split-adjusted across the whole series. Verifying we do
        // NOT scale them again — otherwise pre-split bars would be inflated 4× by our walk.
        // Real-world canary: GOOG's pre-2022 close shows as ~$37, NVDA's pre-Jun-2024
        // close shows as ~$110 — applying the split factor on top would double-count.
        List<PriceBar> bars = new YahooPriceFetcher().parse("SPL",
                Files.readString(Path.of(getClass().getResource("/yahoo-split-sample.json").toURI())));

        assertEquals(4, bars.size());
        for (PriceBar b : bars) {
            assertEquals(b.close(), b.adjClose(), 1e-9,
                    "no dividends in fixture, so adjClose must equal close even across split date");
        }
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
