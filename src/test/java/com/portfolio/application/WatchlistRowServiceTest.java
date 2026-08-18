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

    private static final LocalDate YESTERDAY = LocalDate.now().minusDays(1);

    private static PriceBar bar(String sym, LocalDate date, double close) {
        return new PriceBar(sym, date, null, null, null, close, close, 1.0, null, "GBP");
    }

    /** Service over a temp DB, with {@code AAPL} priced at 100 → 110 (a +10% last session). */
    private WatchlistService service(java.util.function.Consumer<WatchlistRepository> setup) {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var wlRepo = new WatchlistRepository(cf, kv);
        var priceRepo = new PriceHistoryRepository(cf);
        priceRepo.savePriceBars(List.of(bar("AAPL", YESTERDAY, 100.0),
                bar("AAPL", LocalDate.now(), 110.0)));
        setup.accept(wlRepo);
        return new WatchlistService(wlRepo, priceRepo, new IntradayPriceRepository(cf),
                new FundamentalsRepository(cf), new CashTransactionRepository(cf, kv),
                () -> Map.of(), new YahooTickerMap());
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
