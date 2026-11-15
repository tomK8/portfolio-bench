package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.application.WatchlistService.GiltFacts;
import com.portfolio.application.WatchlistService.Row;
import com.portfolio.domain.model.GiltRef;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the popup shows a gilt where an equity's fundamentals go.
 *
 * <p>It used to show "No fundamentals cached for GILT 4.875% 2036 yet — the background refresh
 * may still be running, or Yahoo has no data for this listing", which was wrong in both halves:
 * {@code PortfolioFundamentalsService} skips bonds deliberately, so no refresh is running or
 * ever will, and no amount of waiting produces earnings for a gilt. The same dead end dressed
 * as a wait that the unpriced row's permanent "(fetching…)" was.
 *
 * <p>What a gilt does have is published beside its price on the page the scrape already reads
 * hourly, and the facts below are assembled from it.
 */
class WatchlistGiltFactsTest {

    @TempDir
    Path dbDir;

    private static final String GILT = "GILT 4.875% 2036";
    private static final GiltRef REF =
            new GiltRef(GILT, "GB00BWBR1N39", "BWBR1N3", LocalDate.of(2036, 7, 31));

    /** PriceStats drops weekend-dated bars, so the fixture is anchored on trading days. */
    private static final LocalDate TODAY = weekdayOnOrBefore(LocalDate.now());
    private static final LocalDate YESTERDAY = weekdayOnOrBefore(TODAY.minusDays(1));

    private static LocalDate weekdayOnOrBefore(LocalDate d) {
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    private static PriceBar bar(String sym, LocalDate date, double close) {
        return new PriceBar(sym, date, null, null, null, close, close, 1.0, null, "GBP");
    }

    /** A service whose stored gilt maps hold {@code refs} and {@code yields}. */
    private WatchlistService service(List<GiltRef> refs, Map<String, String> yields,
                                     List<PriceBar> bars) {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        if (!refs.isEmpty()) {
            var encoded = new java.util.LinkedHashMap<String, String>();
            for (GiltRef ref : refs) encoded.put(ref.symbol(), ref.toKvValue());
            kv.putStringMap(GiltPriceFetchJob.REF_MAP_KEY, encoded);
        }
        if (!yields.isEmpty()) kv.putStringMap(GiltPriceFetchJob.YIELD_MAP_KEY, yields);
        var priceRepo = new PriceHistoryRepository(cf);
        if (!bars.isEmpty()) priceRepo.savePriceBars(bars);
        return new WatchlistService(new WatchlistRepository(cf, kv), priceRepo,
                new IntradayPriceRepository(cf), new FundamentalsRepository(cf),
                new CashTransactionRepository(cf, kv), () -> Map.of(), new YahooTickerMap(),
                null, kv);
    }

    private WatchlistService priced() {
        return service(List.of(REF), Map.of(GILT, "5.34"),
                List.of(bar(GILT, YESTERDAY, 97.31), bar(GILT, TODAY, 96.54)));
    }

    @Test
    void aGiltCarriesTheTermsTheFeedPublishesBesideItsPrice() {
        GiltFacts g = priced().row(GILT).gilt();

        assertNotNull(g, "a bond's row must carry what it has instead of fundamentals");
        // The coupon is in the id itself, so it survives whatever the feed is doing.
        assertEquals(0, new BigDecimal("4.875").compareTo(g.couponPct()));
        assertEquals("31 Jul 2036", g.maturity());
        assertEquals("GB00BWBR1N39", g.isin());
        assertEquals("BWBR1N3", g.sedol());
        // Gross, and read rather than computed: a redemption yield cannot be derived from a
        // price without solving for it.
        assertEquals(0, new BigDecimal("5.34").compareTo(g.redemptionYieldPct()));
    }

    @Test
    void theRunningYieldIsTheCouponOverTheSamePriceTheRowIsShowing() {
        // 4.875 ÷ 96.54 = 5.05%, below the 5.34% redemption yield because the gilt is at a
        // discount — the gap is the pull back to £100, which is the point of showing both.
        GiltFacts g = priced().row(GILT).gilt();

        assertEquals(new BigDecimal("5.05"), g.runningYieldPct());
        assertTrue(g.runningYieldPct().compareTo(g.redemptionYieldPct()) < 0);
    }

    @Test
    void yearsToMaturityCountsFromToday() {
        BigDecimal years = priced().row(GILT).gilt().yearsToMaturity();

        long days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.of(2036, 7, 31));
        assertEquals(BigDecimal.valueOf(days).divide(new BigDecimal("365.25"), 1,
                java.math.RoundingMode.HALF_UP), years);
    }

    @Test
    void anEquityCarriesNoGiltBlockAtAll() {
        // The popup branches on this field, so a stray non-null would put a Terms table under
        // an equity that has real fundamentals to show.
        WatchlistService svc = service(List.of(REF), Map.of(GILT, "5.34"),
                List.of(bar("AAPL", YESTERDAY, 100.0), bar("AAPL", TODAY, 110.0)));

        assertNull(svc.row("AAPL").gilt());
    }

    @Test
    void aGiltTheScrapeHasNotIdentifiedStillCarriesWhatItsNameSays() {
        // Nothing stored at all: no maturity, no identifiers, no yield. The coupon is still a
        // fact — it is in the name — and a null is "we don't know", not a dash we invented.
        GiltFacts g = service(List.of(), Map.of(),
                List.of(bar(GILT, YESTERDAY, 97.31), bar(GILT, TODAY, 96.54))).row(GILT).gilt();

        assertEquals(0, new BigDecimal("4.875").compareTo(g.couponPct()));
        assertEquals(new BigDecimal("5.05"), g.runningYieldPct(), "the price is still ours");
        assertNull(g.maturity());
        assertNull(g.yearsToMaturity());
        assertNull(g.redemptionYieldPct());
        assertNull(g.isin());
        assertNull(g.sedol());
    }

    @Test
    void aGiltWithNoPriceKeepsEverythingThatDoesNotDependOnOne() {
        // The missing-data row. A gilt whose quote has not landed here still has a coupon, a
        // maturity and a published yield — only the running yield needs a price of ours.
        Row row = service(List.of(REF), Map.of(GILT, "5.34"), List.of()).row(GILT);

        assertTrue(row.missingData());
        GiltFacts g = row.gilt();
        assertNotNull(g, "the terms do not depend on a quote having arrived");
        assertEquals("31 Jul 2036", g.maturity());
        assertEquals(0, new BigDecimal("5.34").compareTo(g.redemptionYieldPct()));
        assertNull(g.runningYieldPct());
    }
}
