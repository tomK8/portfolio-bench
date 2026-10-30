package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.application.WatchlistService.Extended;
import com.portfolio.application.WatchlistService.Row;
import com.portfolio.domain.model.IntradayBar;
import com.portfolio.domain.model.MarketSession;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.FundamentalsRepository;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import com.portfolio.persistence.WatchlistRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The extended-hours reading: where the price has gone since the regular close, when the newest
 * quote we hold is a pre- or post-market one.
 *
 * <p>What is pinned here is the bargain the column is built on. It is <em>adjacent</em> — the
 * reading appears, and the price, the momentum windows, the two {@code P(N)} outlooks and the
 * alert flags beside it do not move. An earnings print at 21:10 is the single most
 * decision-relevant fact of the day and the tab used to show nothing until the next afternoon;
 * letting it drive the rest of the row instead would price a barrier off a thin book, and would
 * spend the day's one alert email on a session that cannot be dealt in.
 */
class WatchlistExtendedHoursTest {

    @TempDir
    Path dbDir;

    private static final String TICKER = "AAPL";

    private JdbcConnectionFactory cf;
    private KeyValueStore kv;
    private IntradayPriceRepository intradayRepo;

    private static PriceBar bar(LocalDate date, double close) {
        return new PriceBar(TICKER, date, null, null, null, close, close, 1.0, null, "USD");
    }

    /** 40 sessions of flat-ish daily closes ending at 100.00 — enough for a vol estimate. */
    private static List<PriceBar> dailySeries() {
        List<PriceBar> bars = new ArrayList<>();
        LocalDate d = LocalDate.now();
        for (int i = 0; i < 40; i++) {
            while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                d = d.minusDays(1);
            }
            bars.add(0, bar(d, 100.0 + (i % 3) * 0.5));
            d = d.minusDays(1);
        }
        bars.set(bars.size() - 1, bar(bars.get(bars.size() - 1).date(), 100.0));
        return bars;
    }

    private static IntradayBar intraday(Instant ts, double close, MarketSession session) {
        return new IntradayBar(TICKER, ts, close, 1L, "USD", session, LocalDate.now());
    }

    /**
     * A row for AAPL with the given intraday bars stored. There is always a regular bar at 100.00
     * so the row has a live price to measure an extended print against; extra bars are layered on
     * top of it.
     */
    private Row row(List<IntradayBar> extra) {
        cf = new JdbcConnectionFactory(dbDir);
        kv = new KeyValueStore(dbDir);
        var wlRepo = new WatchlistRepository(cf, kv);
        var priceRepo = new PriceHistoryRepository(cf);
        priceRepo.savePriceBars(dailySeries());
        intradayRepo = new IntradayPriceRepository(cf);

        List<IntradayBar> bars = new ArrayList<>();
        bars.add(intraday(regularClose(), 100.0, MarketSession.REGULAR));
        bars.addAll(extra);
        intradayRepo.saveIntradayBars(bars);

        wlRepo.add(TICKER, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("110"), new BigDecimal("90"));
        var service = new WatchlistService(wlRepo, priceRepo, intradayRepo,
                new FundamentalsRepository(cf), new CashTransactionRepository(cf, kv),
                () -> Map.of(), new YahooTickerMap(), new UnresolvedSymbols(kv), kv);
        return service.row(TICKER);
    }

    private static Instant regularClose() {
        return Instant.now().minus(Duration.ofHours(2));
    }

    private static Instant afterTheClose() {
        return Instant.now().minus(Duration.ofMinutes(30));
    }

    // ---- when there is a reading at all -------------------------------------

    /** The ordinary case: the market is open, or shut with nothing since. No reading, not a 0%. */
    @Test
    void aRegularQuoteIsTheNewestThingKnownSoThereIsNoReading() {
        assertNull(row(List.of()).extended(),
                "absent, not a zero move — there is nothing to say, which is not the same as nothing happening");
    }

    /** A post-market print below the close — the earnings case the column exists for. */
    @Test
    void anAfterHoursPrintIsMeasuredAgainstTheRegularCloseBesideIt() {
        Row r = row(List.of(intraday(afterTheClose(), 92.0, MarketSession.POST)));

        Extended x = r.extended();
        assertNotNull(x);
        assertEquals(MarketSession.POST, x.session());
        assertEquals(0, new BigDecimal("92.0000").compareTo(x.price()));
        assertEquals(0, new BigDecimal("-0.080000").compareTo(x.movePct()),
                "92 against the 100.00 in the Price column is -8%");
        assertEquals(0, new BigDecimal("100.0000").compareTo(r.currentPrice()),
                "and the Price column is still the regular close");
    }

    /** A pre-market print, against the same reference: the newest regular bar is the prior close. */
    @Test
    void aPreMarketPrintReadsTheSameWay() {
        Row r = row(List.of(intraday(afterTheClose(), 104.0, MarketSession.PRE)));

        Extended x = r.extended();
        assertNotNull(x);
        assertEquals(MarketSession.PRE, x.session());
        assertEquals(0, new BigDecimal("0.040000").compareTo(x.movePct()));
    }

    /** Once the market reopens, the newest bar is regular again and the reading goes away. */
    @Test
    void aRegularPrintAfterAnExtendedOneClearsTheReading() {
        Row r = row(List.of(
                intraday(Instant.now().minus(Duration.ofHours(12)), 92.0, MarketSession.POST),
                intraday(afterTheClose(), 97.0, MarketSession.REGULAR)));

        assertNull(r.extended(), "an extended print is not the newest thing known any more");
        assertEquals(0, new BigDecimal("97.0000").compareTo(r.currentPrice()),
                "and that regular print is now the price");
    }

    /** A stored 0.0 is absent, not a price — the same reading the thresholds get. */
    @Test
    void aZeroCloseIsNotAnExtendedPrint() {
        assertNull(row(List.of(intraday(afterTheClose(), 0.0, MarketSession.POST))).extended());
    }

    // ---- what the reading must NOT touch ------------------------------------

    /**
     * The whole bargain, in one assertion set. An 8% after-hours drop changes the reading and
     * nothing else: the price, every momentum window and both outlooks are identical to the row
     * that has no extended bar at all.
     */
    @Test
    void nothingElseOnTheRowIsMeasuredFromIt() {
        Row without = row(List.of());
        Row with = row(List.of(intraday(afterTheClose(), 92.0, MarketSession.POST)));

        assertNotNull(with.extended());
        assertNull(without.extended());

        assertEquals(without.currentPrice(), with.currentPrice(), "the Price column");
        assertEquals(without.todayPct(), with.todayPct(), "Today %");
        assertEquals(without.pct2d(), with.pct2d());
        assertEquals(without.pct5d(), with.pct5d());
        assertEquals(without.pct30d(), with.pct30d());
        assertEquals(without.pctFromHigh(), with.pctFromHigh());
        assertEquals(without.pctFromLow(), with.pctFromLow());
        assertEquals(without.vol30d(), with.vol30d());
    }

    /**
     * The barrier is priced off the regular close, so a Below level the after-hours book has gone
     * straight through is still a distance and a probability — not a certainty, and not a fired
     * alert. The dedup in {@code WatchlistAlertJob} keys on the day, and a US post-market session
     * runs past midnight in London: firing here would write tomorrow's key and leave the trigger
     * silent through the session that can actually be traded.
     */
    @Test
    void anAfterHoursPrintThroughALevelNeitherFiresItNorPricesItAsCertain() {
        // Below trigger at 90, regular close 100, after-hours print 85 — through it on the night.
        Row r = row(List.of(intraday(afterTheClose(), 85.0, MarketSession.POST)));

        assertNotNull(r.extended());
        assertFalse(r.belowLevel(), "the alert flag stays on the regular close");
        assertFalse(r.belowOutlook().through(),
                "and so does the barrier — 90 is still below the price an order would fill at");
        assertEquals(0, new BigDecimal("-0.100000").compareTo(r.belowOutlook().movePct()));
        assertTrue(r.belowOutlook().probability().compareTo(BigDecimal.ONE) < 0);
    }

    /** The poll patches the same cells the screen built, so it carries the same reading. */
    @Test
    void theQuotePollCarriesIt() {
        row(List.of(intraday(afterTheClose(), 92.0, MarketSession.POST)));
        var wlRepo = new WatchlistRepository(cf, kv);
        var priceRepo = new PriceHistoryRepository(cf);
        var service = new WatchlistService(wlRepo, priceRepo, intradayRepo,
                new FundamentalsRepository(cf), new CashTransactionRepository(cf, kv),
                () -> Map.of(), new YahooTickerMap(), new UnresolvedSymbols(kv), kv);

        var quotes = service.quotes();
        assertEquals(1, quotes.rows().size());
        Extended x = quotes.rows().get(0).extended();
        assertNotNull(x, "the poll owns the intraday-varying cells, and this is one of them");
        assertEquals(0, new BigDecimal("-0.080000").compareTo(x.movePct()));
    }
}
