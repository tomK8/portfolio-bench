package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.application.WatchlistService.Row;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link WatchlistService#row(String)} — the single-symbol form that lets the price popup
 * be opened from tables outside the Watchlist tab (the Live positions table). The point of the
 * method is that the symbol need <em>not</em> be on the watchlist, so the two cases that matter
 * are: an unwatched symbol still prices (with every alert trigger disabled, so merely looking at a
 * holding can never fire an alert), and a watched one is assembled from its stored entry so the
 * popup and the watchlist row agree.
 */
class WatchlistRowServiceTest {

    @TempDir
    Path dbDir;

    /**
     * The two most recent trading weekdays. {@code PriceStats} discards weekend-dated bars as
     * phantom gilt-scraper rows, so a fixture anchored on plain {@code now()} / {@code now()-1}
     * loses one of its two closes — and with it the current price — on the days of the week that
     * put either date on a Saturday or Sunday.
     */
    private static final LocalDate TODAY = weekdayOnOrBefore(LocalDate.now());
    private static final LocalDate YESTERDAY = weekdayOnOrBefore(TODAY.minusDays(1));

    private static LocalDate weekdayOnOrBefore(LocalDate d) {
        while (d.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                || d.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    private static PriceBar bar(String sym, LocalDate date, double close) {
        return new PriceBar(sym, date, null, null, null, close, close, 1.0, null, "GBP");
    }

    /** Service over a temp DB, with {@code AAPL} priced at 100 → 110 (a +10% last session). */
    private WatchlistService service(java.util.function.Consumer<WatchlistRepository> setup) {
        return service(setup, u -> { });
    }

    /** As above, plus a chance to record verdicts on symbols Yahoo has rejected. */
    private WatchlistService service(java.util.function.Consumer<WatchlistRepository> setup,
                                     java.util.function.Consumer<UnresolvedSymbols> rejected) {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var wlRepo = new WatchlistRepository(cf, kv);
        var priceRepo = new PriceHistoryRepository(cf);
        priceRepo.savePriceBars(List.of(bar("AAPL", YESTERDAY, 100.0),
                bar("AAPL", TODAY, 110.0)));
        setup.accept(wlRepo);
        var unresolved = new UnresolvedSymbols(kv);
        rejected.accept(unresolved);
        return new WatchlistService(wlRepo, priceRepo, new IntradayPriceRepository(cf),
                new FundamentalsRepository(cf), new CashTransactionRepository(cf, kv),
                () -> Map.of(), new YahooTickerMap(), unresolved);
    }

    /**
     * The EQQU bug: an unpriced row is either backfilling or hopeless, and the two rendered
     * identically as "(fetching…)". A recorded rejection has to reach the row so the UI can stop
     * promising that waiting will help.
     */
    @Test
    void aRejectedSymbolCarriesItsDiagnosisNotJustMissingData() {
        Row r = service(repo -> { },
                u -> u.record("EQQU", "EQQU", List.of(
                        new com.portfolio.adapter.YahooPriceFetcher.Suggestion(
                                "EQQU.L", "Invesco EQQQ NASDAQ-100 UCITS ETF", "LSE", "ETF"))))
                .row("EQQU");

        assertTrue(r.missingData());
        assertNotNull(r.missingReason());
        assertTrue(r.missingReason().contains("EQQU.L"), r.missingReason());
    }

    /** No verdict means the fetch may still be running — the row must keep saying so. */
    @Test
    void anUnpricedSymbolWithNoVerdictStaysUndiagnosed() {
        Row r = service(repo -> { }).row("BRANDNEW");

        assertTrue(r.missingData());
        assertNull(r.missingReason(), "absent verdict must not be reported as a rejection");
    }

    /** A row that prices is never annotated, whatever stale verdicts sit in the store. */
    @Test
    void aPricedSymbolCarriesNoDiagnosis() {
        Row r = service(repo -> { }, u -> u.record("AAPL", "AAPL", List.of())).row("AAPL");

        assertFalse(r.missingData());
        assertNull(r.missingReason());
    }

    @Test
    void unwatchedSymbolPricesWithEveryTriggerDisabled() {
        Row r = service(repo -> { }).row("AAPL");

        assertFalse(r.missingData(), "a priced symbol is not missing data just because it is unwatched");
        assertEquals(0, new BigDecimal("110").compareTo(r.currentPrice()));
        assertEquals(0, new BigDecimal("0.100000").compareTo(r.todayPct()));
        // Stand-in entry: thresholds disabled, so opening the popup on a holding can't fire an alert.
        assertEquals(0, BigDecimal.ZERO.compareTo(r.highThresholdPct()));
        assertEquals(0, BigDecimal.ZERO.compareTo(r.moveThresholdPct()));
        assertNull(r.priceAbove());
        assertNull(r.priceBelow());
        assertFalse(r.bigMove(), "a disabled move threshold must not fire on a +10% session");
        assertFalse(r.nearHigh());
        assertFalse(r.aboveLevel());
        assertFalse(r.belowLevel());
        assertFalse(r.held(), "no ledger rows — not held");
    }

    /**
     * The weekend anchor. {@code closeNSessionsBefore} skips bars that are not strictly older
     * than its anchor, so anchoring on the calendar date rather than the quote's trading day
     * makes Friday's close "one session before" Sunday — {@code current} is compared against
     * itself and every window reads 0.00% until Monday. The other tests here only catch this on
     * the days of the week that happen to expose it; this one pins it whatever day it runs.
     */
    @Test
    void sessionWindowsAnchorOnTheTradingDayNotTheCalendarDate() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var wlRepo = new WatchlistRepository(cf, kv);
        var priceRepo = new PriceHistoryRepository(cf);
        // Two sessions, then a gap: the most recent bar is a week old, as it would be to any
        // caller running on a non-trading day.
        LocalDate lastSession = weekdayOnOrBefore(LocalDate.now().minusDays(7));
        LocalDate priorSession = weekdayOnOrBefore(lastSession.minusDays(1));
        priceRepo.savePriceBars(List.of(bar("AAPL", priorSession, 100.0),
                bar("AAPL", lastSession, 110.0)));
        var svc = new WatchlistService(wlRepo, priceRepo, new IntradayPriceRepository(cf),
                new FundamentalsRepository(cf), new CashTransactionRepository(cf, kv),
                () -> Map.of(), new YahooTickerMap(), new UnresolvedSymbols(kv));

        Row r = svc.row("AAPL");

        assertEquals(0, new BigDecimal("0.100000").compareTo(r.todayPct()),
                "the last session's move, not 0% — the anchor must follow the price, not the clock");
    }

    @Test
    void watchedSymbolUsesItsStoredEntry() {
        Row r = service(repo -> repo.add("AAPL", new BigDecimal("5"), new BigDecimal("3"),
                new BigDecimal("105"), null)).row("AAPL");

        assertEquals(0, new BigDecimal("3").compareTo(r.moveThresholdPct()));
        assertEquals(0, new BigDecimal("105").compareTo(r.priceAbove()));
        assertTrue(r.bigMove(), "+10% clears the stored 3% move threshold");
        assertEquals(1, r.moveDirection());
        assertTrue(r.aboveLevel(), "110 is at or above the stored 105 level");
    }

    @Test
    void symbolIsNormalisedAndUnknownOneComesBackMissingRatherThanThrowing() {
        assertEquals(0, new BigDecimal("110").compareTo(service(repo -> { }).row(" aapl ").currentPrice()));

        Row unknown = service(repo -> { }).row("NOSUCH");
        assertTrue(unknown.missingData());
        assertEquals("NOSUCH", unknown.symbol());
        assertNull(unknown.currentPrice());
    }

    @Test
    void blankSymbolIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service(repo -> { }).row("  "));
        assertThrows(IllegalArgumentException.class, () -> service(repo -> { }).row(null));
    }
}
