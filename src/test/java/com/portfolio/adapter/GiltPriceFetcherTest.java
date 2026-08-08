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
                Path.of("src/test/resources/hl-gilts-sample.html"),
                StandardCharsets.UTF_8);

        List<IntradayBar> bars = new GiltPriceFetcher().parse(html, TS);

        Map<String, IntradayBar> bySym = bars.stream().collect(Collectors.toMap(IntradayBar::symbol, b -> b));
        assertEquals(5, bars.size(), () -> "parsed: " + bySym.keySet());

        // The join key is reconstructed from (coupon, year) so it lines up with what
        // AJBellSippParser.extractBondId produces from the holdings CSV. HL pads coupons
        // with trailing zeros ("1.000", "4.750") which must trim to the broker form.
        assertEquals(99.71, bySym.get("GILT 1.5% 2026").close());
        assertEquals(77.09, bySym.get("GILT 0.875% 2033").close());
        assertEquals(83.50, bySym.get("GILT 1% 2032").close());
        assertEquals(75.01, bySym.get("GILT 3.25% 2044").close());
        assertEquals(96.42, bySym.get("GILT 4.75% 2038").close());

        IntradayBar one = bars.get(0);
        assertEquals("GBP", one.currency());
        assertEquals(TS, one.ts());
        assertNull(one.volume());
    }

    @Test
    void ignoresNonGiltRows() {
        // The page also contains a header row, navigation tables, promo rows etc. None of
        // them carry a Treasury name plus decimal coupon/maturity/price cells, so they must
        // be silently dropped.
        String html = """
                <table>
                <tr><th>Bond name</th><th>Coupon (%)</th><th>Maturity</th><th>Price (£)</th></tr>
                <tr><td>Treasury Bills explained</td><td>read</td><td>more</td><td>here</td></tr>
                <tr><td>Corporate PLC 5% 2030 GBP | GB00X | X</td><td>5.000</td><td>1 June 2030</td><td>98.000</td></tr>
                <tr><td>Treasury 3.25% 22/01/2044 GBP | GB00Y | Y</td><td>3.250</td><td>22 January 2044</td><td>75.010</td></tr>
                </table>""";

        List<IntradayBar> bars = new GiltPriceFetcher().parse(html, TS);

        assertEquals(1, bars.size());
        assertEquals("GILT 3.25% 2044", bars.get(0).symbol());
    }
}
