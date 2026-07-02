package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.model.*;
import com.portfolio.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReconciliationServiceTest {

    @TempDir
    Path dbDir;

    private ReconciliationService service() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        return new ReconciliationService(
                new CashTransactionRepository(cf, kv),
                new PriceHistoryRepository(cf),
                new IntradayPriceRepository(cf),
                new YahooTickerMap());
    }

    private static CashTransaction buy(String symbol) {
        return new CashTransaction("2023-01-01", Account.AJBELL, TransactionType.TRANSACTION,
                symbol, 10.0, -1000.0, "GBP", 1.0, -1000.0, null, null, "buy");
    }

    @Test
    void emptyLedgerYieldsZeroPositions() {
        ReconciliationService.Report r = service().report();
        assertEquals(0, r.positionsChecked());
        assertTrue(r.issues().isEmpty());
    }

    @Test
    void missingPriceHistoryIsAnError() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var cashRepo = new CashTransactionRepository(cf, kv);
        cashRepo.saveAjBell(List.of(buy("AAPL")));
        // No rows in price_history
        var svc = new ReconciliationService(cashRepo,
                new PriceHistoryRepository(cf), new IntradayPriceRepository(cf), new YahooTickerMap());
        ReconciliationService.Report r = svc.report();

        assertEquals(1, r.positionsChecked());
        assertTrue(r.issues().stream().anyMatch(i -> "no_price_history".equals(i.code())));
        assertTrue(r.issues().stream().anyMatch(i -> "error".equals(i.severity())));
    }

    @Test
    void staleIntradayIsAWarning() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var cashRepo = new CashTransactionRepository(cf, kv);
        cashRepo.saveAjBell(List.of(buy("AAPL")));

        // Fresh price history
        var priceRepo = new PriceHistoryRepository(cf);
        priceRepo.savePriceBars(List.of(new PriceBar("AAPL", LocalDate.now().minusDays(1),
                null, null, null, 100.0, 100.0, 1.0, null, "GBP")));

        // Stale intraday (5 days old — older than INTRADAY_STALE_HOURS = 72h)
        var intraRepo = new IntradayPriceRepository(cf);
        intraRepo.saveIntradayBars(List.of(
                new IntradayBar("AAPL", Instant.now().minusSeconds(5L * 86400), 100.0, null, "GBP")));

        var svc = new ReconciliationService(cashRepo, priceRepo, intraRepo, new YahooTickerMap());
        ReconciliationService.Report r = svc.report();

        assertTrue(r.issues().stream().anyMatch(i -> "stale_intraday".equals(i.code())));
        assertTrue(r.issues().stream().anyMatch(i -> "warning".equals(i.severity())));
    }

    @Test
    void cleanPositionYieldsNoIssues() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var cashRepo = new CashTransactionRepository(cf, kv);
        cashRepo.saveAjBell(List.of(buy("AAPL")));

        var priceRepo = new PriceHistoryRepository(cf);
        priceRepo.savePriceBars(List.of(new PriceBar("AAPL", LocalDate.now().minusDays(1),
                null, null, null, 100.0, 100.0, 1.0, null, "GBP")));

        var intraRepo = new IntradayPriceRepository(cf);
        // Fresh intraday (within 72h)
        intraRepo.saveIntradayBars(List.of(
                new IntradayBar("AAPL", Instant.now().minusSeconds(3600), 100.0, null, "GBP")));

        var svc = new ReconciliationService(cashRepo, priceRepo, intraRepo, new YahooTickerMap());
        ReconciliationService.Report r = svc.report();

        assertEquals(1, r.positionsChecked());
        assertTrue(r.issues().isEmpty());
    }
}
