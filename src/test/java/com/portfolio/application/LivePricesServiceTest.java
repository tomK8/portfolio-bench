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

    /**
     * A real sector-key provider over the temp database: {@code loadAll} on a schema with no
     * fundamentals rows returns empty, so every position groups as Unclassified — enough to
     * exercise the grouping path in these tests without any of them being about sectors.
     */
    private static SectorGroupKeyProvider sectorKeys(JdbcConnectionFactory cf) {
        return new SectorGroupKeyProvider(new com.portfolio.domain.SectorClassifier(),
                new com.portfolio.persistence.FundamentalsRepository(cf));
    }

    private LivePricesService service() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        return new LivePricesService(
                new CashTransactionRepository(cf, kv),
                new IntradayPriceRepository(cf),
                new PriceHistoryRepository(cf),
                () -> Map.of(),
                new YahooTickerMap(),
                new MarketHoursCache(), sectorKeys(cf));
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
                new PriceHistoryRepository(cf), () -> Map.of(), new YahooTickerMap(),
                new MarketHoursCache(), sectorKeys(cf));
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

        var svc = new LivePricesService(cashRepo, intraRepo, priceRepo, () -> Map.of(), new YahooTickerMap(), new MarketHoursCache(), sectorKeys(cf));
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

        // One close per window base, each a round step off the live 100. No bar for today —
        // the daily job doesn't write one until 22:00, which is the normal intraday state.
        var priceRepo = new PriceHistoryRepository(cf);
        LocalDate today = LocalDate.now();
        priceRepo.savePriceBars(List.of(
                bar("AAPL", today.minusDays(5), 50.0),
                bar("AAPL", today.minusDays(10), 40.0),
                bar("AAPL", today.minusDays(30), 25.0),
                bar("AAPL", today.minusMonths(3), 20.0),
                bar("AAPL", today.minusMonths(6), 10.0)));

        var intraRepo = new IntradayPriceRepository(cf);
        intraRepo.saveIntradayBars(List.of(
                new IntradayBar("AAPL", Instant.now(), 100.0, null, "GBP")));

        var svc = new LivePricesService(cashRepo, intraRepo, priceRepo, () -> Map.of(), new YahooTickerMap(), new MarketHoursCache(), sectorKeys(cf));
        LivePricesService.Row row = svc.snapshot().rows().get(0);

        // Every window ends on the live quote, so today's move is inside all of them.
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
        priceRepo.savePriceBars(List.of(bar("AAPL", today.minusDays(5), 50.0)));

        var intraRepo = new IntradayPriceRepository(cf);
        intraRepo.saveIntradayBars(List.of(
                new IntradayBar("AAPL", Instant.now(), 100.0, null, "GBP")));

        var svc = new LivePricesService(cashRepo, intraRepo, priceRepo, () -> Map.of(), new YahooTickerMap(), new MarketHoursCache(), sectorKeys(cf));
        LivePricesService.Row row = svc.snapshot().rows().get(0);

        assertNotNull(row.change5dPct());
        assertNull(row.change3mPct(), "no bar 3 months back → blank, not a fabricated 0%");
        assertNull(row.change6mPct());
    }

    @Test
    void trailingWindowsIncludeTodaysMoveBeforeTheDailyBarIsWritten() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var cashRepo = new CashTransactionRepository(cf, kv);
        cashRepo.saveAjBell(List.of(buy("2020-01-01", "AAPL", 1.0)));

        // The MRNA shape (2026-08-19): flat for a week, then a huge intraday move on a day
        // whose daily bar doesn't exist yet. A stored-close numerator read this as -0.6%
        // while the Day Δ column beside it read +144%.
        var priceRepo = new PriceHistoryRepository(cf);
        LocalDate today = LocalDate.now();
        priceRepo.savePriceBars(List.of(
                bar("AAPL", today.minusDays(1), 63.0),
                bar("AAPL", today.minusDays(5), 63.3),
                bar("AAPL", today.minusDays(10), 59.2)));

        var intraRepo = new IntradayPriceRepository(cf);
        intraRepo.saveIntradayBars(List.of(
                new IntradayBar("AAPL", Instant.now(), 153.4, null, "GBP")));

        var svc = new LivePricesService(cashRepo, intraRepo, priceRepo, () -> Map.of(), new YahooTickerMap(), new MarketHoursCache(), sectorKeys(cf));
        LivePricesService.Row row = svc.snapshot().rows().get(0);

        assertTrue(row.dayChangePct().doubleValue() > 1.0, "day move is +144%");
        assertTrue(row.change5dPct().doubleValue() > 1.0,
                "5d must carry today's move, not end at yesterday's close");
        assertTrue(row.change10dPct().doubleValue() > 1.0);
    }

    @Test
    void positionDropsWhenIntradayMissing() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var cashRepo = new CashTransactionRepository(cf, kv);
        cashRepo.saveAjBell(List.of(buy("2023-01-01", "AAPL", 5.0)));
        // No intraday bar saved
        var svc = new LivePricesService(cashRepo, new IntradayPriceRepository(cf),
                new PriceHistoryRepository(cf), () -> Map.of(), new YahooTickerMap(),
                new MarketHoursCache(), sectorKeys(cf));

        LivePricesService.Snapshot snap = svc.snapshot();
        assertTrue(snap.rows().isEmpty(), "position without intraday price is excluded");
    }

    /**
     * The 2026-08-20 MRNA regression. Overnight in London, a US name's newest quote is the
     * previous US session's close, and the daily table can also hold a *partial* bar for that
     * same session (the price job's startup run snapshots an in-progress day). Anchoring the
     * baseline on "the close before calendar yesterday" therefore compared the session's real
     * close against a mid-session snapshot of itself: MRNA, up 177% that session, printed
     * +12.13%.
     */
    @Test
    void dayChangeIgnoresAPartialBarForTheQuotesOwnSession() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var cashRepo = new CashTransactionRepository(cf, kv);
        cashRepo.saveAjBell(List.of(buy("2023-01-01", "AAPL", 10.0)));

        LocalDate session = LocalDate.now().minusDays(1);
        var priceRepo = new PriceHistoryRepository(cf);
        priceRepo.savePriceBars(List.of(
                bar("AAPL", session.minusDays(1), 62.96),      // the real prior close
                bar("AAPL", session, 155.51)));                // mid-session snapshot, not a close

        var intraRepo = new IntradayPriceRepository(cf);
        intraRepo.saveIntradayBars(List.of(new IntradayBar(
                "AAPL", Instant.now().minusSeconds(3600), 174.38, null, "GBP",
                MarketSession.REGULAR, session)));

        var svc = new LivePricesService(cashRepo, intraRepo, priceRepo,
                () -> Map.of(), new YahooTickerMap(), new MarketHoursCache(), sectorKeys(cf));
        LivePricesService.Row row = svc.snapshot().rows().get(0);

        // 174.38 / 62.96 - 1 = +176.97%, not 174.38 / 155.51 - 1 = +12.13%.
        assertEquals(1.769695, row.dayChangePct().doubleValue(), 1e-5);
    }

    @Test
    void preMarketQuoteIsMeasuredAgainstThePreviousRegularClose() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var cashRepo = new CashTransactionRepository(cf, kv);
        cashRepo.saveAjBell(List.of(buy("2023-01-01", "AAPL", 10.0)));

        LocalDate today = LocalDate.now();
        var priceRepo = new PriceHistoryRepository(cf);
        priceRepo.savePriceBars(List.of(bar("AAPL", today.minusDays(1), 200.0)));

        var intraRepo = new IntradayPriceRepository(cf);
        intraRepo.saveIntradayBars(List.of(new IntradayBar(
                "AAPL", Instant.now(), 180.0, null, "GBP", MarketSession.PRE, today)));

        var svc = new LivePricesService(cashRepo, intraRepo, priceRepo,
                () -> Map.of(), new YahooTickerMap(), new MarketHoursCache(), sectorKeys(cf));
        LivePricesService.Row row = svc.snapshot().rows().get(0);

        assertEquals("PRE", row.session(), "the UI has to be able to label the price");
        assertEquals(-0.10, row.dayChangePct().doubleValue(), 1e-9);
    }

    @Test
    void extendedHoursQuoteIsUsedForTheLivePriceButNotForRegularHoursReaders() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var cashRepo = new CashTransactionRepository(cf, kv);
        cashRepo.saveAjBell(List.of(buy("2023-01-01", "AAPL", 10.0)));

        var intraRepo = new IntradayPriceRepository(cf);
        Instant closeTs = Instant.now().minusSeconds(7200);
        intraRepo.saveIntradayBars(List.of(
                new IntradayBar("AAPL", closeTs, 174.38, null, "GBP",
                        MarketSession.REGULAR, LocalDate.now().minusDays(1)),
                new IntradayBar("AAPL", Instant.now(), 153.62, null, "GBP",
                        MarketSession.PRE, LocalDate.now())));

        // The Live tab takes the newest quote of any session…
        assertEquals(153.62,
                intraRepo.loadLatestQuotes(List.of("AAPL")).get("AAPL").close());
        // …while every regular-hours reader (valuation, reconciliation, alerts) still sees the
        // last regular close, so a thin pre-market print can never move the portfolio's value.
        assertEquals(174.38,
                intraRepo.loadLatestIntradayPrices(List.of("AAPL")).get("AAPL").close());
    }

    @Test
    void marketStateFallsBackToQuoteAgeWhenNoCalendarIsHeld() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var cashRepo = new CashTransactionRepository(cf, kv);
        cashRepo.saveAjBell(List.of(buy("2023-01-01", "AAPL", 10.0)));

        var intraRepo = new IntradayPriceRepository(cf);
        intraRepo.saveIntradayBars(List.of(new IntradayBar(
                "AAPL", Instant.now().minusSeconds(6 * 3600), 174.38, null, "GBP",
                MarketSession.REGULAR, LocalDate.now().minusDays(1))));

        var svc = new LivePricesService(cashRepo, intraRepo, new PriceHistoryRepository(cf),
                () -> Map.of(), new YahooTickerMap(), new MarketHoursCache(), sectorKeys(cf));

        assertEquals("CLOSED", svc.snapshot().rows().get(0).marketState(),
                "a six-hour-old quote with no published calendar reads as a shut market");
    }
}
