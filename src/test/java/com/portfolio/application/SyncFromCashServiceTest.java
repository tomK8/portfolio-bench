package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.model.*;
import com.portfolio.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SyncFromCashServiceTest {

    @TempDir
    Path dbDir;

    private SyncFromCashService service() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        return new SyncFromCashService(
                new CashTransactionRepository(cf, kv),
                new IntradayPriceRepository(cf),
                () -> Map.of(),
                new YahooTickerMap(),
                new PriceHistoryRepository(cf));
    }

    private static CashTransaction buy(String date, String symbol, double qty, double amountGbp) {
        return new CashTransaction(date, Account.AJBELL, TransactionType.TRANSACTION,
                symbol, qty, -amountGbp, "GBP", 1.0, -amountGbp, null, null, "buy");
    }

    @Test
    void emptyLedgerYieldsEmptySyncResult() {
        SyncResult r = service().sync();
        assertTrue(r.empty());
        assertTrue(r.holdings().isEmpty());
    }

    @Test
    void positionWithIntradayPriceAppearsInHoldings() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var cashRepo = new CashTransactionRepository(cf, kv);
        cashRepo.saveAjBell(List.of(buy("2023-01-01", "AAPL", 10.0, 1000.0)));

        var intraRepo = new IntradayPriceRepository(cf);
        intraRepo.saveIntradayBars(List.of(
                new IntradayBar("AAPL", Instant.now(), 120.0, null, "GBP")));

        var svc = new SyncFromCashService(cashRepo, intraRepo, () -> Map.of(),
                new YahooTickerMap(), new PriceHistoryRepository(cf));
        SyncResult r = svc.sync();

        assertFalse(r.empty());
        assertFalse(r.holdings().isEmpty());
        assertEquals("AAPL", r.holdings().get(0).securityId());
        // 10 × 120 = £1200
        assertNotNull(r.totals());
        assertTrue(r.totals().rtTotalGbp().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void positionWithoutIntradayIsDroppedAndListed() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var cashRepo = new CashTransactionRepository(cf, kv);
        cashRepo.saveAjBell(List.of(buy("2023-01-01", "AAPL", 5.0, 500.0)));
        // No intraday bar

        var svc = new SyncFromCashService(cashRepo, new IntradayPriceRepository(cf),
                () -> Map.of(), new YahooTickerMap(), new PriceHistoryRepository(cf));
        SyncResult r = svc.sync();

        assertTrue(r.holdings().isEmpty());
        assertTrue(r.missingIntradayPrices().contains("AAPL"));
    }

    @Test
    void everyPricedHoldingCarriesAnIrrKeyedByItsSecurityId() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var cashRepo = new CashTransactionRepository(cf, kv);
        // £1,000 four years ago, now worth £2,000 on the live quote below.
        cashRepo.saveAjBell(List.of(buy("2022-08-22", "AAPL", 10.0, 1000.0)));
        var intraRepo = new IntradayPriceRepository(cf);
        intraRepo.saveIntradayBars(List.of(
                new IntradayBar("AAPL", Instant.now(), 200.0, null, "GBP")));

        var svc = new SyncFromCashService(cashRepo, intraRepo, () -> Map.of(),
                new YahooTickerMap(), new PriceHistoryRepository(cf));
        SyncResult r = svc.sync();

        var irr = r.irrBySymbol().get("AAPL");
        assertNotNull(irr, "keyed by the same securityId the row renders");
        assertTrue(irr.annualised());
        assertNotNull(irr.annualisedReturn());
        // Doubling over ~4 years is ≈19%/yr; the point is that it is a plausible rate solved
        // against the row's own market value, not the exact figure (which moves with the clock).
        assertTrue(irr.annualisedReturn().doubleValue() > 0.15
                        && irr.annualisedReturn().doubleValue() < 0.25,
                "got " + irr.annualisedReturn());
        assertNotNull(irr.explain());
    }

    @Test
    void theTerminalFlowIsTheRowsOwnMarketValue() {
        // The rate must never describe a different valuation from the Market Value beside it.
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var cashRepo = new CashTransactionRepository(cf, kv);
        cashRepo.saveAjBell(List.of(buy("2020-01-06", "MSFT", 10.0, 1000.0)));
        var intraRepo = new IntradayPriceRepository(cf);
        intraRepo.saveIntradayBars(List.of(
                new IntradayBar("MSFT", Instant.now(), 315.0, null, "GBP")));

        var svc = new SyncFromCashService(cashRepo, intraRepo, () -> Map.of(),
                new YahooTickerMap(), new PriceHistoryRepository(cf));
        SyncResult r = svc.sync();

        var h = r.holdings().get(0);
        var irr = r.irrBySymbol().get("MSFT");
        // Rebuild the rate from the rendered market value: they must agree.
        var expected = com.portfolio.domain.Irr.solve(List.of(
                new com.portfolio.domain.Irr.Flow(java.time.LocalDate.parse("2020-01-06"), -1000.0),
                new com.portfolio.domain.Irr.Flow(java.time.LocalDate.now(
                        java.time.ZoneId.of("Europe/London")), h.marketValueGbp().doubleValue())));
        assertNotNull(expected);
        assertEquals(expected, irr.annualisedReturn().doubleValue(), 1e-6);
    }

    @Test
    void cashRowsHaveNoIrr() {
        // Cash has no purchase date and no return to annualise; the column shows "—".
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var cashRepo = new CashTransactionRepository(cf, kv);
        cashRepo.saveAjBell(List.of(buy("2023-01-01", "AAPL", 10.0, 1000.0)));
        var intraRepo = new IntradayPriceRepository(cf);
        intraRepo.saveIntradayBars(List.of(
                new IntradayBar("AAPL", Instant.now(), 120.0, null, "GBP")));

        var svc = new SyncFromCashService(cashRepo, intraRepo, () -> Map.of(),
                new YahooTickerMap(), new PriceHistoryRepository(cf));
        SyncResult r = svc.sync();

        assertNull(r.irrBySymbol().get("CASH"));
    }

    @Test
    void bondWithoutIntradayFallsBackToDailyClose() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var cashRepo = new CashTransactionRepository(cf, kv);
        cashRepo.saveAjBell(List.of(buy("2023-01-01", "GILT 0.875% 2033", 2000.0, 1500.0)));
        // No intraday bar, but a stored daily close (the gilt feed being down)
        var histRepo = new PriceHistoryRepository(cf);
        histRepo.savePriceBars(List.of(new PriceBar("GILT 0.875% 2033",
                java.time.LocalDate.of(2026, 7, 15), null, null, null, 77.22, 77.22, 1.0, null, "GBP")));

        var svc = new SyncFromCashService(cashRepo, new IntradayPriceRepository(cf),
                () -> Map.of(), new YahooTickerMap(), histRepo);
        SyncResult r = svc.sync();

        assertEquals(1, r.holdings().size());
        // per-£100-nominal: 2000 × 77.22 / 100 = £1544.40
        assertEquals(0, r.holdings().get(0).marketValueGbp().compareTo(new BigDecimal("1544.40")));
        assertTrue(r.missingIntradayPrices().isEmpty());
        assertEquals(List.of("GILT 0.875% 2033 (close from 2026-07-15)"), r.dailyCloseFallbacks());
    }

    @Test
    void bondWithNeitherIntradayNorDailyIsDroppedAndListed() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var cashRepo = new CashTransactionRepository(cf, kv);
        cashRepo.saveAjBell(List.of(buy("2023-01-01", "GILT 4.75% 2038", 1000.0, 900.0)));

        var svc = new SyncFromCashService(cashRepo, new IntradayPriceRepository(cf),
                () -> Map.of(), new YahooTickerMap(), new PriceHistoryRepository(cf));
        SyncResult r = svc.sync();

        assertTrue(r.holdings().isEmpty());
        assertTrue(r.missingIntradayPrices().contains("GILT 4.75% 2038"));
        assertTrue(r.dailyCloseFallbacks().isEmpty());
        // Gilts are priced by their own scraper and have no Yahoo listing, so a missing gilt price
        // must never be reported as a ticker-mapping problem.
        assertTrue(r.unpricedSymbols().isEmpty());
    }

    @Test
    void symbolWithNoIntradayAndNoDailyHistoryIsFlaggedAsNeedingATickerMapping() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var cashRepo = new CashTransactionRepository(cf, kv);
        cashRepo.saveAjBell(List.of(buy("2026-08-01", "PHP", 10000.0, 10000.0)));

        var svc = new SyncFromCashService(cashRepo, new IntradayPriceRepository(cf),
                () -> Map.of(), new YahooTickerMap(), new PriceHistoryRepository(cf));
        SyncResult r = svc.sync();

        // Names the ticker actually tried — kept as its own field so the dashboard can pre-fill
        // the inline mapping editor with it, not just print it.
        assertEquals(List.of(new SyncResult.UnpricedSymbol("PHP", "PHP.L")), r.unpricedSymbols());
        assertEquals("PHP (ticker PHP.L)", r.unpricedSymbols().get(0).display());
    }

    @Test
    void symbolWithDailyHistoryButNoIntradayIsNotATickerMappingProblem() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var cashRepo = new CashTransactionRepository(cf, kv);
        cashRepo.saveAjBell(List.of(buy("2023-01-01", "AAPL", 5.0, 500.0)));
        var histRepo = new PriceHistoryRepository(cf);
        histRepo.savePriceBars(List.of(new PriceBar("AAPL",
                java.time.LocalDate.of(2026, 8, 12), null, null, null, 190.0, 190.0, 1.0, null, "USD")));

        var svc = new SyncFromCashService(cashRepo, new IntradayPriceRepository(cf),
                () -> Map.of(), new YahooTickerMap(), histRepo);
        SyncResult r = svc.sync();

        // A stale intraday feed is ordinary; the mapping resolves fine.
        assertTrue(r.missingIntradayPrices().contains("AAPL"));
        assertTrue(r.unpricedSymbols().isEmpty());
    }
}
