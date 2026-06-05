package com.portfolio.adapter;

import com.portfolio.domain.model.IntradayBar;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class GiltPriceFetcherTest {

    private static final Instant TS = Instant.parse("2026-06-05T12:00:00Z");

    @Test
    void parsesGiltRowsFromSampleHtml() throws IOException {
        String html = Files.readString(
                Path.of("src/test/resources/dividenddata-gilts-sample.html"),
                StandardCharsets.UTF_8);

        List<IntradayBar> bars = new GiltPriceFetcher().parse(html, TS);

        Map<String, IntradayBar> bySym = bars.stream().collect(Collectors.toMap(IntradayBar::symbol, b -> b));
        assertEquals(4, bars.size(), () -> "parsed: " + bySym.keySet());

        // The TIDM is ignored; the join key is reconstructed from (coupon, year) so it lines up
        // with what AJBellSippParser.extractBondId produces from the holdings CSV.
        assertEquals(99.71, bySym.get("GILT 1.5% 2026").close());
        assertEquals(77.09, bySym.get("GILT 0.875% 2033").close());
        assertEquals(75.01, bySym.get("GILT 3.25% 2044").close());
        assertEquals(96.42, bySym.get("GILT 4.75% 2038").close());

        IntradayBar one = bars.get(0);
        assertEquals("GBP", one.currency());
        assertEquals(TS, one.ts());
        assertNull(one.volume());
    }

    @Test
    void ignoresNonGiltRows() {
        // The page also contains a header row, navigation, summary rows etc. None of them
        // start with a TG/TR TIDM, so they must be silently dropped.
        String html = """
                <table>
                <tr><th>TIDM</th><th>Desc</th><th>Coupon</th><th>Maturity</th><th>Term</th><th>Price</th></tr>
                <tr><td>SUMMARY</td><td>x</td><td>n/a</td><td>n/a</td><td>n/a</td><td>n/a</td></tr>
                <tr><td>TG44</td><td>x</td><td>3.25%</td><td>22-Jan-2044</td><td>x</td><td>&pound;75.01</td></tr>
                </table>""";

        List<IntradayBar> bars = new GiltPriceFetcher().parse(html, TS);

        assertEquals(1, bars.size());
        assertEquals("GILT 3.25% 2044", bars.get(0).symbol());
    }
}
