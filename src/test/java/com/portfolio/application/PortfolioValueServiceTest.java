package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.model.*;
import com.portfolio.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class PortfolioValueServiceTest {

    @TempDir
    Path dbDir;

    private static final com.portfolio.port.HistoricalFxRateProvider NO_FX = (ccy, from, to) -> new TreeMap<>();

    private PortfolioValueService service() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        return new PortfolioValueService(
                new CashTransactionRepository(cf, kv),
                new PriceHistoryRepository(cf),
                NO_FX,
                new YahooTickerMap(),
                kv);
    }

    @Test
    void emptyLedgerYieldsEmptyTimeline() {
        PortfolioValueService.ValueTimeline t = service().timeline();
        assertTrue(t.points().isEmpty());
        assertTrue(t.missingPrices().isEmpty());
    }

    @Test
    void symbolWithNoPriceHistoryAppearsInMissingList() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var cashRepo = new CashTransactionRepository(cf, kv);
        cashRepo.saveAjBell(List.of(new CashTransaction(
                "2024-01-01", Account.AJBELL, TransactionType.TRANSACTION,
                "AAPL", 10.0, -1000.0, "GBP", 1.0, -1000.0, null, null, "buy")));
        // No rows in price_history for AAPL

        var svc = new PortfolioValueService(cashRepo, new PriceHistoryRepository(cf), NO_FX,
                new YahooTickerMap(), kv);
        PortfolioValueService.ValueTimeline t = svc.timeline();

        assertTrue(t.missingPrices().stream().anyMatch(m -> "AAPL".equals(m.symbol())));
    }

    @Test
    void singleBuyWithPriceHistoryProducesTimelineAndNoMissingPrices() {
        LocalDate buyDate = LocalDate.of(2024, 1, 2);

        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var cashRepo = new CashTransactionRepository(cf, kv);
        // Contribute £1200 then buy AAPL at £100 × 10 = £1000
        cashRepo.saveAjBell(List.of(
                new CashTransaction(buyDate.toString(), Account.AJBELL, TransactionType.CONTRIBUTION,
                        "GBP", 0.0, 1200.0, "GBP", 1.0, 1200.0, 1200.0, 1200.0, "deposit"),
                new CashTransaction(buyDate.toString(), Account.AJBELL, TransactionType.TRANSACTION,
                        "AAPL", 10.0, -1000.0, "GBP", 1.0, -1000.0, 200.0, 200.0, "buy AAPL")));

        var priceRepo = new PriceHistoryRepository(cf);
        priceRepo.savePriceBars(List.of(
                new PriceBar("AAPL", buyDate, null, null, null, 100.0, 100.0, 1.0, null, "GBP")));

        var svc = new PortfolioValueService(cashRepo, priceRepo, NO_FX, new YahooTickerMap(), kv);
        PortfolioValueService.ValueTimeline t = svc.timeline();

        assertFalse(t.points().isEmpty());
        assertTrue(t.missingPrices().isEmpty(), "AAPL has price history; no missing-price warning expected");
        // First sample: equity (10×100=£1000) + remaining cash (£200) = £1200
        PortfolioValueService.DataPoint first = t.points().get(0);
        assertNotNull(first.valueGbp());
        assertEquals(0, new BigDecimal("1200.00").compareTo(first.valueGbp().setScale(2, java.math.RoundingMode.HALF_UP)));
    }
}
