package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.model.*;
import com.portfolio.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LivePricesServiceTest {

    @TempDir
    Path dbDir;

    private LivePricesService service() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        return new LivePricesService(
                new CashTransactionRepository(cf, kv),
                new IntradayPriceRepository(cf),
                new PriceHistoryRepository(cf),
                () -> Map.of(),
                new YahooTickerMap());
    }

    private static PriceBar bar(String symbol, LocalDate date, double close) {
        return new PriceBar(symbol, date, null, null, null, close, close, 1.0, null, "GBP");
    }

    private static CashTransaction buy(String date, String symbol, double qty) {
        return new CashTransaction(date, Account.AJBELL, TransactionType.TRANSACTION,
                symbol, qty, -qty * 100, "GBP", 1.0, -qty * 100, null, null, "buy");
    }

    @Test
    void emptyLedgerYieldsEmptySnapshot() {
        LivePricesService.Snapshot snap = service().snapshot();
        assertTrue(snap.rows().isEmpty());
        assertEquals(0, BigDecimal.ZERO.compareTo(snap.totals().positionsGbp()));
    }

    @Test
    void gbpPencePriceDividedByHundred() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var cashRepo = new CashTransactionRepository(cf, kv);
        cashRepo.saveAjBell(List.of(buy("2023-01-01", "AZN", 10.0)));

        var intraRepo = new IntradayPriceRepository(cf);
        // AZN → AZN.L in yahoo-tickers.properties; intraday fetcher stores by Yahoo ticker.
        // Real LSE price is in pence, so 11000p = £110.
        intraRepo.saveIntradayBars(List.of(
                new IntradayBar("AZN.L", Instant.now(), 11000.0, null, "GBp")));

        var svc = new LivePricesService(cashRepo, intraRepo,
                new PriceHistoryRepository(cf), () -> Map.of(), new YahooTickerMap());
        LivePricesService.Snapshot snap = svc.snapshot();

        assertFalse(snap.rows().isEmpty());
        LivePricesService.Row row = snap.rows().get(0);
        // £110 per share × 10 shares = £1100
        assertEquals(0, new BigDecimal("1100.00").compareTo(row.positionGbp().setScale(2, java.math.RoundingMode.HALF_UP)));
    }

    @Test
    void dayChangePctComputedFromPreviousClose() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var cashRepo = new CashTransactionRepository(cf, kv);
        cashRepo.saveAjBell(List.of(buy("2023-01-01", "AAPL", 1.0)));

        var priceRepo = new PriceHistoryRepository(cf);
        LocalDate yesterday = LocalDate.now().minusDays(1);
        // Previous close = 100; current intraday = 110
        priceRepo.savePriceBars(List.of(
                new PriceBar("AAPL", yesterday, null, null, null, 100.0, 100.0, 1.0, null, "GBP")));

        var intraRepo = new IntradayPriceRepository(cf);
        intraRepo.saveIntradayBars(List.of(
                new IntradayBar("AAPL", Instant.now(), 110.0, null, "GBP")));

        var svc = new LivePricesService(cashRepo, intraRepo, priceRepo, () -> Map.of(), new YahooTickerMap());
        LivePricesService.Snapshot snap = svc.snapshot();
        assertFalse(snap.rows().isEmpty());
        LivePricesService.Row row = snap.rows().get(0);

        assertNotNull(row.dayChangePct());
        assertTrue(row.dayChangePct().compareTo(BigDecimal.ZERO) > 0, "positive day move");
        assertNotNull(row.dayChangeGbp());
        assertEquals(0, new BigDecimal("10.00").compareTo(row.dayChangeGbp().setScale(2, java.math.RoundingMode.HALF_UP)));
    }

    @Test
    void trailingReturnsSpanFiveDaysToSixMonths() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var cashRepo = new CashTransactionRepository(cf, kv);
        cashRepo.saveAjBell(List.of(buy("2020-01-01", "AAPL", 1.0)));

        // One close per boundary the snapshot reads, each a round step off today's 100.
        var priceRepo = new PriceHistoryRepository(cf);
        LocalDate today = LocalDate.now();
        priceRepo.savePriceBars(List.of(
                bar("AAPL", today, 100.0),
                bar("AAPL", today.minusDays(5), 50.0),
                bar("AAPL", today.minusDays(10), 40.0),
                bar("AAPL", today.minusDays(30), 25.0),
                bar("AAPL", today.minusMonths(3), 20.0),
                bar("AAPL", today.minusMonths(6), 10.0)));

        var intraRepo = new IntradayPriceRepository(cf);
        intraRepo.saveIntradayBars(List.of(
                new IntradayBar("AAPL", Instant.now(), 100.0, null, "GBP")));

        var svc = new LivePricesService(cashRepo, intraRepo, priceRepo, () -> Map.of(), new YahooTickerMap());
        LivePricesService.Row row = svc.snapshot().rows().get(0);

        // Trailing changes come off the daily closes, not the intraday quote.
        assertEquals(0, new BigDecimal("1.0").compareTo(row.change5dPct()));
        assertEquals(0, new BigDecimal("1.5").compareTo(row.change10dPct()));
        assertEquals(0, new BigDecimal("3.0").compareTo(row.monthChangePct()));
        assertEquals(0, new BigDecimal("4.0").compareTo(row.change3mPct()));
        assertEquals(0, new BigDecimal("9.0").compareTo(row.change6mPct()));
    }

    @Test
    void trailingReturnIsNullWhenHistoryIsShorterThanTheWindow() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var cashRepo = new CashTransactionRepository(cf, kv);
        cashRepo.saveAjBell(List.of(buy("2020-01-01", "AAPL", 1.0)));

        // Only a week of history: the 5d base exists, everything longer has no bar at all.
        var priceRepo = new PriceHistoryRepository(cf);
        LocalDate today = LocalDate.now();
        priceRepo.savePriceBars(List.of(
                bar("AAPL", today, 100.0),
                bar("AAPL", today.minusDays(5), 50.0)));

        var intraRepo = new IntradayPriceRepository(cf);
        intraRepo.saveIntradayBars(List.of(
                new IntradayBar("AAPL", Instant.now(), 100.0, null, "GBP")));

        var svc = new LivePricesService(cashRepo, intraRepo, priceRepo, () -> Map.of(), new YahooTickerMap());
        LivePricesService.Row row = svc.snapshot().rows().get(0);

        assertNotNull(row.change5dPct());
        assertNull(row.change3mPct(), "no bar 3 months back → blank, not a fabricated 0%");
        assertNull(row.change6mPct());
    }

    @Test
    void positionDropsWhenIntradayMissing() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var cashRepo = new CashTransactionRepository(cf, kv);
        cashRepo.saveAjBell(List.of(buy("2023-01-01", "AAPL", 5.0)));
        // No intraday bar saved
        var svc = new LivePricesService(cashRepo, new IntradayPriceRepository(cf),
                new PriceHistoryRepository(cf), () -> Map.of(), new YahooTickerMap());

        LivePricesService.Snapshot snap = svc.snapshot();
        assertTrue(snap.rows().isEmpty(), "position without intraday price is excluded");
    }
}
