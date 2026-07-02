package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.application.TargetAllocationService.DriftRow;
import com.portfolio.application.TargetAllocationService.TargetReport;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.IntradayBar;
import com.portfolio.domain.model.TransactionType;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import com.portfolio.port.HistoricalFxRateProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class TargetAllocationServiceTest {

    @TempDir
    Path dbDir;

    private static final HistoricalFxRateProvider NO_FX = (ccy, from, to) -> new TreeMap<>();

    private static CashTransaction buy(String date, String symbol, double qty, double amountGbp) {
        return new CashTransaction(date, Account.AJBELL, TransactionType.TRANSACTION,
                symbol, qty, -amountGbp, "GBP", 1.0, -amountGbp, null, null, "buy");
    }

    private void seedPosition(String symbol, double qty, double costGbp, double livePrice) {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var repo = new CashTransactionRepository(cf, kv);
        repo.saveAjBell(List.of(buy("2023-01-01", symbol, qty, costGbp)));
        new IntradayPriceRepository(cf).saveIntradayBars(List.of(
                new IntradayBar(symbol, Instant.now(), livePrice, null, "GBP")));
    }

    /** Builds a fully-wired ConcentrationService for the shared dbDir. */
    private ConcentrationService conc() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var cashRepo = new CashTransactionRepository(cf, kv);
        var intraRepo = new IntradayPriceRepository(cf);
        var priceRepo = new PriceHistoryRepository(cf);
        var allocSvc = new AllocationService(cashRepo, priceRepo, NO_FX, new YahooTickerMap(), kv);
        return new ConcentrationService(allocSvc, cashRepo, intraRepo, () -> Map.of(), new YahooTickerMap());
    }

    private TargetAllocationService service() {
        return new TargetAllocationService(conc(), new KeyValueStore(dbDir));
    }

    // ---- loadTargets / saveTargets -----------------------------------------

    @Test
    void roundTripTargetsPersistViaKv() {
        var svc = service();
        svc.saveTargets(Map.of(
                "AAPL", new BigDecimal("0.40"),
                "MSFT", new BigDecimal("0.30")));

        Map<String, BigDecimal> loaded = svc.loadTargets();
        assertEquals(2, loaded.size());
        assertEquals(0, new BigDecimal("0.400000").compareTo(loaded.get("AAPL")));
        assertEquals(0, new BigDecimal("0.300000").compareTo(loaded.get("MSFT")));
    }

    @Test
    void emptyTargetsRoundTripToEmptyMap() {
        var svc = service();
        svc.saveTargets(Map.of());
        assertTrue(svc.loadTargets().isEmpty());
    }

    // ---- report() ----------------------------------------------------------

    @Test
    void emptyPortfolioYieldsEmptyReport() {
        assertTrue(service().report().rows().isEmpty());
    }

    @Test
    void onTargetPositionIsHold() {
        seedPosition("AAPL", 10.0, 1000.0, 100.0); // value = 10 × 100 = £1000
        var svc = service();
        // Target = 100% AAPL → no drift
        svc.saveTargets(Map.of("AAPL", new BigDecimal("1.0")));
        TargetReport r = svc.report();

        DriftRow row = r.rows().stream().filter(x -> x.symbol().equals("AAPL")).findFirst().orElseThrow();
        assertEquals("hold", row.suggestedAction());
        assertEquals(0, BigDecimal.ZERO.compareTo(row.driftGbp()));
    }

    @Test
    void largeShortfallSuggestsAdd() {
        seedPosition("AAPL", 10.0, 1000.0, 100.0); // actual £1000
        var svc = service();
        // Target 100% in MSFT — MSFT has £0 → shortfall £1000
        svc.saveTargets(Map.of("MSFT", new BigDecimal("1.0")));
        TargetReport r = svc.report();

        DriftRow msft = r.rows().stream().filter(x -> x.symbol().equals("MSFT")).findFirst().orElseThrow();
        assertEquals("add", msft.suggestedAction());
    }

    @Test
    void overweightByLargeAmountSuggestsTrim() {
        // AAPL at £2000, target only 10% of £2000 = £200 → overweight by £1800 → trim
        seedPosition("AAPL", 20.0, 2000.0, 100.0); // value 20×100=£2000
        var svc = service();
        svc.saveTargets(Map.of("AAPL", new BigDecimal("0.10")));
        TargetReport r = svc.report();

        DriftRow row = r.rows().stream().filter(x -> x.symbol().equals("AAPL")).findFirst().orElseThrow();
        assertEquals("trim", row.suggestedAction());
    }

    @Test
    void untargetedHoldingAppearsWithNullTargetAndNullAction() {
        seedPosition("GOOG", 5.0, 500.0, 100.0);
        // No targets saved — GOOG has no target
        TargetReport r = service().report();

        DriftRow row = r.rows().stream().filter(x -> x.symbol().equals("GOOG")).findFirst().orElseThrow();
        assertNull(row.targetWeight());
        assertNull(row.suggestedAction());
        assertFalse(row.targeted());
    }
}
