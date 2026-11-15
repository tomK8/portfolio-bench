package com.portfolio.application;

import com.portfolio.adapter.GiltPriceFetcher;
import com.portfolio.domain.model.GiltRef;
import com.portfolio.domain.model.IntradayBar;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GiltPriceFetchJobTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final String SYMBOL = "GILT 4.25% 2032";

    @TempDir
    Path dbDir;

    private static final GiltRef REF =
            new GiltRef(SYMBOL, "GB00BSQNRD01", "BSQNRD0", LocalDate.parse("2032-06-07"));

    private static final BigDecimal YTM = new BigDecimal("4.17");

    /** Returns one canned bar stamped at whatever instant the test asks for. */
    private static class FakeFetcher extends GiltPriceFetcher {
        private final Instant ts;
        private final List<GiltRef> refs;
        private final Map<String, BigDecimal> yields;

        FakeFetcher(Instant ts) {
            this(ts, List.of(REF));
        }

        FakeFetcher(Instant ts, List<GiltRef> refs) {
            this(ts, refs, Map.of(SYMBOL, YTM));
        }

        FakeFetcher(Instant ts, List<GiltRef> refs, Map<String, BigDecimal> yields) {
            this.ts = ts;
            this.refs = refs;
            this.yields = yields;
        }

        @Override
        public Snapshot fetch() {
            return new Snapshot(List.of(new IntradayBar(SYMBOL, ts, 97.21, null, "GBP")), refs,
                    yields);
        }
    }

    private List<PriceBar> runAt(Instant ts) {
        return runAt(new FakeFetcher(ts));
    }

    private List<PriceBar> runAt(FakeFetcher fetcher) {
        JdbcConnectionFactory cf = new JdbcConnectionFactory(dbDir);
        PriceHistoryRepository prices = new PriceHistoryRepository(cf);
        new GiltPriceFetchJob(fetcher, new IntradayPriceRepository(cf), prices,
                new KeyValueStore(dbDir)).run();
        return prices.getPriceHistory(SYMBOL, LocalDate.parse("2000-01-01"), LocalDate.parse("2100-01-01"));
    }

    private static Instant londonNoon(String isoDate) {
        return LocalDateTime.of(LocalDate.parse(isoDate), java.time.LocalTime.NOON)
                .atZone(LONDON).toInstant();
    }

    @Test
    void weekdayScrapeIsRolledIntoTheDailySeries() {
        // 2026-07-03 is a Friday.
        List<PriceBar> bars = runAt(londonNoon("2026-07-03"));
        assertEquals(1, bars.size());
        assertEquals(LocalDate.parse("2026-07-03"), bars.get(0).date());
    }

    @Test
    void saturdayScrapeIsNotRolledIntoTheDailySeries() {
        // HL serves Friday's prices all weekend, but a gilt has no Saturday close — storing one
        // put weekend columns across the whole Returns grid, since its date axis is the union of
        // every symbol's stored dates.
        assertTrue(runAt(londonNoon("2026-07-04")).isEmpty());
    }

    @Test
    void sundayScrapeIsNotRolledIntoTheDailySeries() {
        assertTrue(runAt(londonNoon("2026-07-05")).isEmpty());
    }

    @Test
    void theWeekendGuardJudgesTheBarsOwnDateNotTheClock() {
        // A tick just before midnight on a Sunday belongs to Sunday, not to the Monday it is
        // about to become.
        Instant lateSunday = LocalDateTime.of(LocalDate.parse("2026-07-05"), java.time.LocalTime.of(23, 55))
                .atZone(LONDON).toInstant();
        assertTrue(runAt(lateSunday).isEmpty());
    }

    @Test
    void isTradingDayCoversTheWholeWeek() {
        assertTrue(GiltPriceFetchJob.isTradingDay(LocalDate.parse("2026-07-06")));   // Mon
        assertTrue(GiltPriceFetchJob.isTradingDay(LocalDate.parse("2026-07-07")));   // Tue
        assertTrue(GiltPriceFetchJob.isTradingDay(LocalDate.parse("2026-07-08")));   // Wed
        assertTrue(GiltPriceFetchJob.isTradingDay(LocalDate.parse("2026-07-09")));   // Thu
        assertTrue(GiltPriceFetchJob.isTradingDay(LocalDate.parse("2026-07-10")));   // Fri
        assertFalse(GiltPriceFetchJob.isTradingDay(LocalDate.parse("2026-07-11")));  // Sat
        assertFalse(GiltPriceFetchJob.isTradingDay(LocalDate.parse("2026-07-12")));  // Sun
    }

    @Test
    void theIntradayWriteStillHappensAtWeekends() {
        // Deliberately unchanged: the RT lookup wants the newest gilt price whatever day it is,
        // and those rows are pruned after 15 days regardless.
        JdbcConnectionFactory cf = new JdbcConnectionFactory(dbDir);
        IntradayPriceRepository intraday = new IntradayPriceRepository(cf);
        new GiltPriceFetchJob(new FakeFetcher(londonNoon("2026-07-04")), intraday,
                new PriceHistoryRepository(cf)).run();
        assertFalse(intraday.loadLatestIntradayPrices(List.of(SYMBOL)).isEmpty());
    }

    // ---- SEDOL map -------------------------------------------------------------

    @Test
    void publishesTheSedolMappingTheCashParserNeeds() {
        runAt(londonNoon("2026-07-03"));

        assertEquals(Map.of("BSQNRD0", SYMBOL),
                new KeyValueStore(dbDir).getStringMap(GiltPriceFetchJob.SEDOL_MAP_KEY));
    }

    @Test
    void aMaturedGiltKeepsItsMappingAfterItDropsOffThePage() {
        // A gilt disappears from HL's table the day it matures. Replacing the stored map would
        // silently un-resolve a cash statement that is still perfectly importable, so the merge
        // only ever adds.
        GiltRef old = new GiltRef("GILT 1% 2024", "GB00OLDGILT9", "OLDGILT", LocalDate.parse("2024-01-22"));
        runAt(new FakeFetcher(londonNoon("2026-07-03"), List.of(old)));
        runAt(new FakeFetcher(londonNoon("2026-07-06"), List.of(REF)));

        assertEquals(Map.of("OLDGILT", "GILT 1% 2024", "BSQNRD0", SYMBOL),
                new KeyValueStore(dbDir).getStringMap(GiltPriceFetchJob.SEDOL_MAP_KEY));
        assertEquals(2, new KeyValueStore(dbDir).getStringMap(GiltPriceFetchJob.REF_MAP_KEY).size(),
                "the identifier map merges on the same terms, for the same reason");
    }

    // ---- Identifier map --------------------------------------------------------

    @Test
    void publishesTheIdentifiersThePickerIsSearchedBy() {
        // The id is the one name for a gilt nobody outside this app uses, so the other
        // identifiers HL prints beside the price are stored with it — that is what lets the
        // Watchlist's picker be found by a maturity date or a pasted ISIN.
        runAt(londonNoon("2026-07-03"));

        Map<String, String> stored = new KeyValueStore(dbDir).getStringMap(GiltPriceFetchJob.REF_MAP_KEY);
        assertEquals(Map.of(SYMBOL, "GB00BSQNRD01|BSQNRD0|2032-06-07"), stored);
        assertEquals(REF, GiltRef.fromKv(SYMBOL, stored.get(SYMBOL)),
                "what is stored must decode back to what was scraped");
    }

    // ---- Redemption yields -----------------------------------------------------

    @Test
    void publishesTheRedemptionYieldThePopupShowsInPlaceOfFundamentals() {
        runAt(londonNoon("2026-07-03"));

        assertEquals(Map.of(SYMBOL, "4.17"),
                new KeyValueStore(dbDir).getStringMap(GiltPriceFetchJob.YIELD_MAP_KEY));
    }

    @Test
    void aYieldIsReplacedNotMerged() {
        // The opposite contract to the identifier maps, and deliberately so: a yield is a quote.
        // A gilt that has dropped off the page must not keep a yield with no date on it, sitting
        // beside a live price as though it were current.
        runAt(new FakeFetcher(londonNoon("2026-07-03"), List.of(REF),
                Map.of("GILT 1% 2024", new BigDecimal("3.90"))));
        runAt(new FakeFetcher(londonNoon("2026-07-06"), List.of(REF), Map.of(SYMBOL, YTM)));

        assertEquals(Map.of(SYMBOL, "4.17"),
                new KeyValueStore(dbDir).getStringMap(GiltPriceFetchJob.YIELD_MAP_KEY),
                "the matured gilt's yield is gone, while its identifiers are kept");
        assertTrue(new KeyValueStore(dbDir).getStringMap(GiltPriceFetchJob.REF_MAP_KEY)
                .containsKey(SYMBOL));
    }

    @Test
    void aScrapeThatYieldedNothingLeavesTheLastGoodSetAlone() {
        // A failed or changed page degrades to the previous yields rather than to a panel of
        // dashes: the figures are hours old at worst, and silence is not a correction.
        runAt(londonNoon("2026-07-03"));
        runAt(new FakeFetcher(londonNoon("2026-07-06"), List.of(REF), Map.of()));

        assertEquals(Map.of(SYMBOL, "4.17"),
                new KeyValueStore(dbDir).getStringMap(GiltPriceFetchJob.YIELD_MAP_KEY));
    }

    @Test
    void aGiltWithNoIdentifiersStillGetsAnEntry() {
        // A page that stops printing an ISIN costs the picker a search handle. It must not cost
        // the gilt its entry, nor leave a half-written value that fails to decode.
        GiltRef bare = new GiltRef(SYMBOL, null, null, null);
        runAt(new FakeFetcher(londonNoon("2026-07-03"), List.of(bare)));

        Map<String, String> stored = new KeyValueStore(dbDir).getStringMap(GiltPriceFetchJob.REF_MAP_KEY);
        assertEquals(Map.of(SYMBOL, "||"), stored);
        assertEquals(bare, GiltRef.fromKv(SYMBOL, stored.get(SYMBOL)));
    }
}
