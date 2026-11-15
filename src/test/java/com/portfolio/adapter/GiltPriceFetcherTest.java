package com.portfolio.adapter;

import com.portfolio.domain.model.GiltRef;
import com.portfolio.domain.model.IntradayBar;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
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

        GiltPriceFetcher.Snapshot snapshot = new GiltPriceFetcher().parse(html, TS);
        List<IntradayBar> bars = snapshot.bars();

        Map<String, IntradayBar> bySym = bars.stream().collect(Collectors.toMap(IntradayBar::symbol, b -> b));
        assertEquals(6, bars.size(), () -> "parsed: " + bySym.keySet());

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

        // The SEDOL rides along out of the same name cell. It is the only identifier a broker
        // that names a gilt by a code of its own (II's "T30") shares with this page.
        assertEquals(6, snapshot.sedolToSymbol().size());
        assertEquals("GILT 0.875% 2033", snapshot.sedolToSymbol().get("TEST033"));
        assertEquals("GILT 3.25% 2044", snapshot.sedolToSymbol().get("TEST044"));
    }

    @Test
    void carriesTheIdentifiersTheGiltIsPublishedUnder() throws IOException {
        // The id is the one name for a gilt nobody outside this app uses, so the row's other
        // identifiers come back with it: they are what the Watchlist's picker is searched by.
        String html = Files.readString(
                Path.of("src/test/resources/hl-gilts-sample.html"), StandardCharsets.UTF_8);

        Map<String, GiltRef> bySym = new GiltPriceFetcher().parse(html, TS).refs().stream()
                .collect(Collectors.toMap(GiltRef::symbol, r -> r));

        GiltRef ref = bySym.get("GILT 0.875% 2033");
        assertEquals("GB00TEST0330", ref.isin());
        assertEquals("TEST033", ref.sedol());
        // The full date, not the year already in the symbol: two gilts can mature in one year,
        // and the date is what the picker is ordered on.
        assertEquals(LocalDate.of(2033, 7, 31), ref.maturity());
        assertEquals(LocalDate.of(2032, 4, 30), bySym.get("GILT 1% 2032").maturity());
    }

    @Test
    void readsTheSedolOutOfTheIsinSoAStrippedLeadingZeroIsStillRight() throws IOException {
        // HL prints the old numeric SEDOLs a character short — "240419" for the 6% 2028, where
        // the SEDOL is 0240419 — so a 7-character pattern over the printed tail read nothing at
        // all for them, and neither the picker nor an II cash statement naming one by SEDOL
        // could resolve it. The ISIN in the same cell carries the full form: GB00 + SEDOL + check.
        String html = Files.readString(
                Path.of("src/test/resources/hl-gilts-sample.html"), StandardCharsets.UTF_8);

        GiltPriceFetcher.Snapshot snapshot = new GiltPriceFetcher().parse(html, TS);

        GiltRef numeric = snapshot.refs().stream()
                .filter(r -> r.symbol().equals("GILT 6% 2028")).findFirst().orElseThrow();
        assertEquals("0987654", numeric.sedol(), "the printed tail was 987654");
        assertEquals("GB0009876540", numeric.isin());
        assertEquals("GILT 6% 2028", snapshot.sedolToSymbol().get("0987654"));
        assertNull(snapshot.sedolToSymbol().get("987654"),
                "the six-character form the page prints is not a SEDOL and must not be stored as one");
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

        GiltPriceFetcher.Snapshot snapshot = new GiltPriceFetcher().parse(html, TS);

        assertEquals(1, snapshot.bars().size());
        assertEquals("GILT 3.25% 2044", snapshot.bars().get(0).symbol());
        assertEquals(Map.of(), snapshot.sedolToSymbol(),
                "a name cell carrying neither a UK ISIN nor a 7-character SEDOL maps nothing");
    }
}
