package com.portfolio.application;

import com.portfolio.adapter.HoldingFileLocator;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.PriceHistoryRepository;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.SnapshotRepository;
import com.portfolio.port.FxRateProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SyncPortfolioServiceTest {

    /**
     * Fake FX provider — the reason FxRateProvider is a port: no network in tests.
     */
    private static final FxRateProvider FX =
            () -> Map.of("GBP", BigDecimal.ONE, "USD", new BigDecimal("1.25"));

    @TempDir
    Path inputDir;
    @TempDir
    Path dbDir;

    private SyncPortfolioService service() {
        return service(false);
    }

    private SyncPortfolioService service(boolean withManualPositions) {
        JdbcConnectionFactory cf = new JdbcConnectionFactory(dbDir);
        KeyValueStore kv = new KeyValueStore(dbDir);
        CashTransactionRepository cashRepo = new CashTransactionRepository(cf, kv);
        SnapshotRepository snapshots = new SnapshotRepository(cf);
        IntradayPriceRepository intraday = new IntradayPriceRepository(cf);
        PriceHistoryRepository priceHistory = new PriceHistoryRepository(cf);
        ManualPositionService manual = withManualPositions
                ? new ManualPositionService(cashRepo, intraday, priceHistory, new YahooTickerMap())
                : null;
        PortfolioGatherer gatherer =
                new PortfolioGatherer(FX, new HoldingFileLocator(inputDir), manual);
        return new SyncPortfolioService(gatherer, snapshots, intraday,
                new DividendService(cashRepo), new YahooTickerMap(), cashRepo, priceHistory, kv);
    }

    /** Enter a BARC position at the hand-entered account and give it a live quote. */
    private void seedManualBarc() {
        JdbcConnectionFactory cf = new JdbcConnectionFactory(dbDir);
        KeyValueStore kv = new KeyValueStore(dbDir);
        new ManualPositionService(new CashTransactionRepository(cf, kv),
                new IntradayPriceRepository(cf), new PriceHistoryRepository(cf), new YahooTickerMap())
                .buy(com.portfolio.domain.model.Account.EQUATE, "BARC",
                        new BigDecimal("7227"), new BigDecimal("18500"),
                        java.time.LocalDate.parse("2020-06-01"), null);
        new IntradayPriceRepository(cf).saveIntradayBars(List.of(
                new com.portfolio.domain.model.IntradayBar("BARC.L", java.time.Instant.now(),
                        320.5, null, "GBp")));
    }

    @Test
    void aHandEnteredPositionReachesTheHoldingsPipeline() {
        seedManualBarc();
        SyncResult result = service(true).sync(BigDecimal.ZERO, BigDecimal.ZERO);

        assertTrue(result.holdings().stream().anyMatch(h -> "BARC".equals(h.securityId())),
                "an account with no export still has to appear on the Files tab");
    }

    @Test
    void aHandEnteredPositionIsPublishedToHeldSymbols() {
        seedManualBarc();
        service(true).sync(BigDecimal.ZERO, BigDecimal.ZERO);

        // held_symbols is what feeds the fundamentals, earnings and price-fetch universes.
        assertTrue(new KeyValueStore(dbDir).getStringSet(PriceFetchSupport.HELD_SYMBOLS_KEY)
                .contains("BARC"));
    }

    @Test
    void aHandEnteredPositionDoesNotShowAsReconciliationDrift() {
        seedManualBarc();
        SyncResult result = service(true).sync(BigDecimal.ZERO, BigDecimal.ZERO);

        // Both sides derive from the same ledger rows and the same quote, so a drift row here
        // would be the panel disagreeing with itself.
        assertTrue(result.assetRecon().stream().noneMatch(r -> r.symbol().equalsIgnoreCase("BARC")),
                "holdings side and ledger side must agree on a position neither has a file for");
    }

    @Test
    void returnsEmptyWhenNoInputFiles() {
        SyncResult result = service().sync(new BigDecimal("1000"), BigDecimal.ZERO);

        assertTrue(result.empty());
        assertTrue(result.holdings().isEmpty());
        assertEquals(0, new BigDecimal("1.25").compareTo(result.rates().get("USD")));
    }

    @Test
    void cashReconUsesLiveFxForLedgerNativeBalance() throws IOException {
        // Pre-insert a RothIRA ledger row with a "stored" FX baked in (1.40 USD/GBP).
        // Today's live rate is 1.25 — if the recon used the stored cash_balance_gbp,
        // the ledger side would read $1000 / 1.40 = £714.29; with the fix it must
        // re-convert at the live rate: $1000 / 1.25 = £800.
        JdbcConnectionFactory cf = new JdbcConnectionFactory(dbDir);
        KeyValueStore kv = new KeyValueStore(dbDir);
        CashTransactionRepository cashRepo = new CashTransactionRepository(cf, kv);
        cashRepo.saveRothIra(List.of(new CashTransaction(
                "2025-01-01", Account.ROTH_IRA, TransactionType.CONTRIBUTION, "USD",
                0.0, 1000.0, "USD", 1.40, 1000.0 / 1.40,
                null, null, "seed")), BigDecimal.ZERO);

        // Need a non-empty portfolio so sync() proceeds past the empty-input early return.
        Files.writeString(inputDir.resolve("11111111-1111-1111-1111-111111111111.csv"),
                "Symbol,Qty,Market Value,Book Cost\nAAPL,1,$100.00,$100.00\n");

        SyncResult result = service().sync(BigDecimal.ZERO, BigDecimal.ZERO);

        SyncResult.CashRecon row = result.cashRecon().stream()
                .filter(r -> "RothIRA".equals(r.account()) && "USD".equals(r.currency()))
                .findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("800.00").compareTo(row.ledgerGbp()),
                "ledger GBP should use live FX (1.25), not stored FX (1.40)");
    }

    @Test
    void aggregatesParsedHoldingsAndComputesTotals() throws IOException {
        // Minimal II SIPP-style export (UUID filename, USD-priced row).
        Files.writeString(inputDir.resolve("11111111-1111-1111-1111-111111111111.csv"),
                "Symbol,Qty,Market Value,Book Cost\nAAPL,10,$1500.00,$1000.00\n");

        SyncResult result = service().sync(new BigDecimal("500"), BigDecimal.ZERO);

        assertFalse(result.empty());
        assertEquals(1, result.holdings().size());
        assertEquals("AAPL", result.holdings().get(0).securityId());
        // 1500 USD / 1.25 = 1200 GBP market value; + 500 cash = 1700 total.
        assertEquals(0, new BigDecimal("1700").compareTo(result.totals().totalGbp()));
        // cost 1000 USD / 1.25 = 800 GBP; gain = 1200 - 800 = 400.
        assertEquals(0, new BigDecimal("400").compareTo(result.totals().totalGainGbp()));
    }
}
