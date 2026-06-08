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
    void emitsCumulativeSplitFactorAndLeavesCloseUntouched() throws Exception {
        // Fixture: 4:1 split between bar 2 (2024-02-02) and bar 3 (2024-02-05). Closes are
        // shown split-adjusted to today's basis (~$100 across the whole series, like real
        // Yahoo data — pre-split GOOG shows as ~$39, not the ~$780 it actually traded at).
        // For each bar we must emit:
        //   close: untouched (we never re-scale Yahoo's already-adjusted close)
        //   adjClose: equal to close (no dividends in fixture)
        //   splitFactor: 4.0 for bars dated strictly before the split, 1.0 from the split day onward
        List<PriceBar> bars = new YahooPriceFetcher().parse("SPL",
                Files.readString(Path.of(getClass().getResource("/yahoo-split-sample.json").toURI())));

        assertEquals(4, bars.size());
        for (PriceBar b : bars) {
            assertEquals(b.close(), b.adjClose(), 1e-9,
                    "no dividends → adjClose equals close even across the split date");
        }
        assertEquals(4.0, bars.get(0).splitFactor(), 1e-9, "pre-split bar inherits the full 4× factor");
        assertEquals(4.0, bars.get(1).splitFactor(), 1e-9, "day before split: still pre-split basis");
        assertEquals(1.0, bars.get(2).splitFactor(), 1e-9, "split day: already in current basis");
        assertEquals(1.0, bars.get(3).splitFactor(), 1e-9, "after split: current basis");
        // close × splitFactor recovers the raw basis-at-date: $400 pre-split → $101 post-split.
        assertEquals(400.0, bars.get(0).close() * bars.get(0).splitFactor(), 1e-9);
        assertEquals(101.0, bars.get(2).close() * bars.get(2).splitFactor(), 1e-9);
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
