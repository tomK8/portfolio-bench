package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.application.WatchlistService.GiltOption;
import com.portfolio.domain.model.GiltRef;
import com.portfolio.domain.model.IntradayBar;
import com.portfolio.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Watchlist add box's gilt picker — {@link WatchlistService#giltOptions()} and
 * {@link WatchlistService#resolveSymbol(String)}.
 *
 * <p>Both exist because the id a gilt is priced and alerted under is the one name for it that
 * nobody else uses. A holder arrives from the LSE's own quote page for T36N — 4⅞% Treasury Gilt
 * 31/07/36, ISIN GB00BWBR1N39 — and the entry they are looking for is called
 * {@code GILT 4.875% 2036}. It was in the list all along; searching it for the code they had in
 * hand found nothing, and neither did scanning 70 entries spelled by coupon and year for a
 * maturity date none of them printed. Nothing was missing from the picker, so nothing a
 * hand-add box could have added would have helped — what was missing was a way in.
 *
 * <p>So every option carries the identifiers the price scrape already publishes beside the
 * price, the list is ordered by maturity, and the add box resolves a pasted ISIN or SEDOL to
 * the id rather than storing it as a symbol no feed will ever price.
 */
class WatchlistGiltPickerTest {

    @TempDir
    Path dbDir;

    private static final GiltRef MAR_2036 =
            new GiltRef("GILT 4.25% 2036", "GB0032452392", "3245239", LocalDate.of(2036, 3, 7));
    private static final GiltRef JUL_2036 =
            new GiltRef("GILT 4.875% 2036", "GB00BWBR1N39", "BWBR1N3", LocalDate.of(2036, 7, 31));
    private static final GiltRef MATURED =
            new GiltRef("GILT 1% 2024", "GB00B52WS153", "B52WS15", LocalDate.of(2024, 9, 7));

    /** A service over a temp DB where {@code priced} are the gilts the feed is quoting. */
    private WatchlistService service(List<GiltRef> known, List<GiltRef> priced) {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        Map<String, String> refs = new LinkedHashMap<>();
        for (GiltRef ref : known) refs.put(ref.symbol(), ref.toKvValue());
        kv.putStringMap(GiltPriceFetchJob.REF_MAP_KEY, refs);
        var intraday = new IntradayPriceRepository(cf);
        intraday.saveIntradayBars(priced.stream()
                .map(r -> new IntradayBar(r.symbol(), Instant.now(), 96.54, null, "GBP"))
                .toList());
        return new WatchlistService(new WatchlistRepository(cf, kv), new PriceHistoryRepository(cf),
                intraday, new FundamentalsRepository(cf), new CashTransactionRepository(cf, kv),
                () -> Map.of(), new YahooTickerMap(), null, kv);
    }

    @Test
    void everyOptionCarriesTheIdentifiersItCanBeFoundBy() {
        List<GiltOption> options = service(List.of(MAR_2036, JUL_2036), List.of(MAR_2036, JUL_2036))
                .giltOptions();

        GiltOption jul = options.stream()
                .filter(o -> o.symbol().equals("GILT 4.875% 2036")).findFirst().orElseThrow();
        assertEquals("GB00BWBR1N39", jul.isin());
        assertEquals("BWBR1N3", jul.sedol());
        // The date the LSE's own page prints, which is also what tells the two 2036 gilts apart.
        assertEquals("31 Jul 2036", jul.maturity());
    }

    @Test
    void theListIsOrderedByMaturity() {
        // The axis a gilt is actually chosen along, and the one thing two gilts maturing in the
        // same year differ on. Alphabetical order on the id puts 4.25% before 4.875% by accident
        // and 0.125% 2028 before 4.875% 2036 as a rule.
        List<GiltOption> options = service(List.of(JUL_2036, MAR_2036), List.of(JUL_2036, MAR_2036))
                .giltOptions();

        assertEquals(List.of("GILT 4.25% 2036", "GILT 4.875% 2036"),
                options.stream().map(GiltOption::symbol).toList());
    }

    @Test
    void aPricedGiltWithNoStoredIdentifiersStillLists() {
        // A search handle may be absent. The name it would have found may not: the gilt is
        // priced, so an alert on it works, and leaving it out of the picker would hide the only
        // valid way to enter it.
        List<GiltOption> options = service(List.of(JUL_2036), List.of(JUL_2036, MAR_2036))
                .giltOptions();

        GiltOption bare = options.stream()
                .filter(o -> o.symbol().equals("GILT 4.25% 2036")).findFirst().orElseThrow();
        assertNull(bare.isin());
        assertNull(bare.sedol());
        assertNull(bare.maturity());
        // ...and it sorts after the ones that do have a date, rather than displacing them.
        assertEquals("GILT 4.25% 2036", options.get(options.size() - 1).symbol());
    }

    @Test
    void aGiltNoLongerPricedIsNotOffered() {
        // The universe is what the feed quotes, not what it has ever quoted. A matured gilt
        // keeps its stored identifiers — a cash statement naming it is still importable — but
        // offering it for an alert would offer a trigger that can never fire.
        List<GiltOption> options = service(List.of(JUL_2036, MATURED), List.of(JUL_2036))
                .giltOptions();

        assertEquals(List.of("GILT 4.875% 2036"), options.stream().map(GiltOption::symbol).toList());
    }

    // ---- What the add box does with what was typed ------------------------------

    @Test
    void aPastedIsinOrSedolResolvesToTheIdTheFeedPricesItUnder() {
        WatchlistService svc = service(List.of(JUL_2036), List.of(JUL_2036));

        assertEquals("GILT 4.875% 2036", svc.resolveSymbol("GB00BWBR1N39"));
        assertEquals("GILT 4.875% 2036", svc.resolveSymbol("BWBR1N3"));
        // Pasted out of a web page: whatever case, with whatever whitespace came with it.
        assertEquals("GILT 4.875% 2036", svc.resolveSymbol(" gb00bwbr1n39 "));
        assertEquals("GILT 4.875% 2036", svc.resolveSymbol("GB00 BWBR 1N39"));
    }

    @Test
    void aMaturedGiltsIdentifierStillResolvesToAName() {
        // Resolution is against every gilt ever scraped. Storing GB00B52WS153 as a symbol is no
        // better for a matured gilt than for a live one, and the id is the form its holder would
        // recognise in the row.
        assertEquals("GILT 1% 2024",
                service(List.of(JUL_2036, MATURED), List.of(JUL_2036)).resolveSymbol("B52WS15"));
    }

    @Test
    void anOrdinaryTickerIsLeftAlone() {
        WatchlistService svc = service(List.of(JUL_2036), List.of(JUL_2036));

        assertEquals("NVDA", svc.resolveSymbol("NVDA"));
        assertEquals("BARC.L", svc.resolveSymbol("BARC.L"));
        // Nothing here matches on shape, so a ticker that merely looks like an identifier is
        // still a ticker: only a string this app has actually seen published resolves.
        assertEquals("GB00ZZZZZZZZ", svc.resolveSymbol("GB00ZZZZZZZZ"));
    }

    @Test
    void aGiltIdTypedWithATrailingZeroIsCanonicalisedNotLeftToMissTheFeed() {
        // The 2026-08-24 failure in miniature: GILT 4.50% 2028 and GILT 4.5% 2028 are the same
        // gilt, and only the second one joins a price.
        assertEquals("GILT 4.5% 2028",
                service(List.of(JUL_2036), List.of(JUL_2036)).resolveSymbol("GILT 4.50% 2028"));
    }

    @Test
    void theRefMapIsOptional() {
        // Reads degrade: a service with no key-value store at all (several tests build one that
        // way) must still list the priced gilts and pass a typed symbol through.
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var intraday = new IntradayPriceRepository(cf);
        intraday.saveIntradayBars(List.of(
                new IntradayBar(JUL_2036.symbol(), Instant.now(), 96.54, null, "GBP")));
        WatchlistService svc = new WatchlistService(new WatchlistRepository(cf, kv),
                new PriceHistoryRepository(cf), intraday, new FundamentalsRepository(cf),
                new CashTransactionRepository(cf, kv), () -> Map.of(), new YahooTickerMap());

        assertEquals(List.of("GILT 4.875% 2036"),
                svc.giltOptions().stream().map(GiltOption::symbol).toList());
        assertEquals("NVDA", svc.resolveSymbol("NVDA"));
    }
}
