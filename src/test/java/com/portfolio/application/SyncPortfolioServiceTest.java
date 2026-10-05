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
    private static final FxRateProvider FX = () -> Map.of(
            "GBP", BigDecimal.ONE, "USD", new BigDecimal("1.25"), "EUR", new BigDecimal("1.20"));

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
                new PortfolioGatherer(FX, new HoldingFileLocator(inputDir), manual, cashRepo);
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
        SyncResult result = service(true).sync(IiCash.ofGbp(BigDecimal.ZERO));

        assertTrue(result.holdings().stream().anyMatch(h -> "BARC".equals(h.securityId())),
                "an account with no export still has to appear on the Files tab");
    }

    @Test
    void aHandEnteredPositionIsPublishedToHeldSymbols() {
        seedManualBarc();
        service(true).sync(IiCash.ofGbp(BigDecimal.ZERO));

        // held_symbols is what feeds the fundamentals, earnings and price-fetch universes.
        assertTrue(new KeyValueStore(dbDir).getStringSet(PriceFetchSupport.HELD_SYMBOLS_KEY)
                .contains("BARC"));
    }

    @Test
    void aHandEnteredPositionDoesNotShowAsReconciliationDrift() {
        seedManualBarc();
        SyncResult result = service(true).sync(IiCash.ofGbp(BigDecimal.ZERO));

        // Both sides derive from the same ledger rows and the same quote, so a drift row here
        // would be the panel disagreeing with itself.
        assertTrue(result.assetRecon().stream().noneMatch(r -> r.symbol().equalsIgnoreCase("BARC")),
                "holdings side and ledger side must agree on a position neither has a file for");
    }

    /**
     * Two AJ Bell accounts both hold sterling, and the aggregator groups by security — so both cash
     * rows are the one security CASH and merge into a row whose sources read "AJ Bell SIPP, AJ Bell
     * Dealing". That names no account, so it keyed against none and the panel reported the entire
     * balance as drift twice over: missing from the ledger on one row, missing from the holdings on
     * the other. Cash is reconciled per account, so it has to be read before aggregation.
     */
    @Test
    void twoAccountsHoldingSterlingReconcileSeparately() throws IOException {
        String header = "\"Investment\",\"Quantity\",\"Price\",\"Value (£)\",\"Cost (£)\","
                + "\"Change (£)\",\"Change (%)\",\"Price +/- today (%)\",\"Valuation currency\","
                + "\"Market currency\",\"Exchange rate\",\"Date\",\"Time\",\"Portfolio\","
                + "\"Ticker\"\n";
        Files.writeString(inputDir.resolve("portfolio-A50742S-SIPP.csv"), header
                + "\"AstraZeneca PLC (LSE:AZN)\",\"30\",\"125\",\"3750\",\"3900\",\"-150\",\"-4\","
                + "\"0\",\"GBP\",\"GBP\",\"1\",\"05-Oct-26\",\"13:36\",\"SIPP\",\"AZN\"\n"
                + "\"Cash GBP\",\"500\",\"1\",\"500\",\"500\",\"0\",\"0\",\"0\",\"GBP\",\"GBP\","
                + "\"1\",\"05-Oct-26\",\"13:36\",\"SIPP\"\n");
        Files.writeString(inputDir.resolve("portfolio-A50742D-Dealing account.csv"), header
                + "\"Barclays PLC (LSE:BARC)\",\"100\",\"4\",\"400\",\"350\",\"50\",\"14\",\"0\","
                + "\"GBP\",\"GBP\",\"1\",\"05-Oct-26\",\"13:36\",\"Dealing account\",\"BARC\"\n"
                + "\"Cash GBP\",\"120\",\"1\",\"120\",\"120\",\"0\",\"0\",\"0\",\"GBP\",\"GBP\","
                + "\"1\",\"05-Oct-26\",\"13:36\",\"Dealing account\"\n");

        SyncResult result = service().sync(IiCash.ofGbp(BigDecimal.ZERO));

        SyncResult.CashRecon sipp = result.cashRecon().stream()
                .filter(r -> "AJBell".equals(r.account()) && "GBP".equals(r.currency()))
                .findFirst().orElseThrow();
        SyncResult.CashRecon dealing = result.cashRecon().stream()
                .filter(r -> "AJBellDealing".equals(r.account()) && "GBP".equals(r.currency()))
                .findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("500.00").compareTo(sipp.holdingsGbp()));
        assertEquals(0, new BigDecimal("120.00").compareTo(dealing.holdingsGbp()));
        assertTrue(result.cashRecon().stream().noneMatch(r -> r.account().contains(",")),
                "a cash row that names two accounts reconciles against neither");
    }

    /**
     * AJ Bell states no cost for shares transferred in in specie — it never saw the purchase. Left
     * at that, the position sits in the portfolio total contributing value but no gain, diluting
     * Return on invested by its whole size with nothing saying so. The basis travelled into the
     * ledger with the shares, which is what recording a transfer at cost is for.
     */
    @Test
    void aCostTheBrokerDoesNotKnowIsTakenFromTheLedger() throws IOException {
        JdbcConnectionFactory cf = new JdbcConnectionFactory(dbDir);
        KeyValueStore kv = new KeyValueStore(dbDir);
        CashTransactionRepository cashRepo = new CashTransactionRepository(cf, kv);
        new ManualPositionService(cashRepo, new IntradayPriceRepository(cf),
                new PriceHistoryRepository(cf), new YahooTickerMap())
                .buy(Account.EQUATE, "BARC", new BigDecimal("7133"), new BigDecimal("26477.08"),
                        java.time.LocalDate.parse("2025-07-30"), null);
        new AssetTransferService(cashRepo).transfer(
                java.time.LocalDate.parse("2026-09-24"), Account.EQUATE, Account.AJBELL_DEALING,
                "BARC");

        // The dealing export: a real market value against a zero cost, exactly as AJ Bell writes it.
        Files.writeString(inputDir.resolve("portfolio-A50742D-Dealing account.csv"),
                "\"Investment\",\"Quantity\",\"Price\",\"Value (£)\",\"Cost (£)\",\"Change (£)\","
                        + "\"Change (%)\",\"Price +/- today (%)\",\"Valuation currency\","
                        + "\"Market currency\",\"Exchange rate\",\"Date\",\"Time\",\"Portfolio\","
                        + "\"Ticker\"\n"
                        + "\"Barclays PLC (LSE:BARC)\",\"7133\",\"4.4645\",\"31845.28\",\"0.00\","
                        + "\"31845.28\",\"0.00\",\"0.69\",\"GBP\",\"GBP\",\"1\",\"05-Oct-26\","
                        + "\"13:36\",\"Dealing account\",\"BARC\"\n");

        SyncResult result = service(true).sync(IiCash.ofGbp(BigDecimal.ZERO));

        var barc = result.holdings().stream()
                .filter(h -> "BARC".equals(h.securityId())).findFirst().orElseThrow();
        assertEquals(5368.20, barc.gainGbp().doubleValue(), 0.005,
                "31,845.28 of value against the ledger's 26,477.08 of cost — not the broker's "
                        + "zero (a £31,845 phantom gain) and not unknown (no gain at all)");
    }

    /** A broker that does state a basis keeps it — FIFO and Section 104 pooling genuinely differ. */
    @Test
    void aCostTheBrokerDoesKnowIsNotOverwritten() throws IOException {
        Files.writeString(inputDir.resolve("portfolio-A50742S-SIPP.csv"),
                "\"Investment\",\"Quantity\",\"Price\",\"Value (£)\",\"Cost (£)\",\"Change (£)\","
                        + "\"Change (%)\",\"Price +/- today (%)\",\"Valuation currency\","
                        + "\"Market currency\",\"Exchange rate\",\"Date\",\"Time\",\"Portfolio\","
                        + "\"Ticker\"\n"
                        + "\"AstraZeneca PLC (LSE:AZN)\",\"30\",\"125\",\"3750\",\"3900\",\"-150\","
                        + "\"-4\",\"0\",\"GBP\",\"GBP\",\"1\",\"05-Oct-26\",\"11:04\",\"SIPP\","
                        + "\"AZN\"\n");

        SyncResult result = service(true).sync(IiCash.ofGbp(BigDecimal.ZERO));

        var azn = result.holdings().stream()
                .filter(h -> "AZN".equals(h.securityId())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("-150").compareTo(azn.gainGbp()),
                "3,750 of value against the broker's own 3,900 of cost");
    }

    @Test
    void returnsEmptyWhenNoInputFiles() {
        SyncResult result = service().sync(IiCash.ofGbp(new BigDecimal("1000")));

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

        SyncResult result = service().sync(IiCash.ofGbp(BigDecimal.ZERO));

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

        SyncResult result = service().sync(IiCash.ofGbp(new BigDecimal("500")));

        assertFalse(result.empty());
        assertEquals(1, result.holdings().size());
        assertEquals("AAPL", result.holdings().get(0).securityId());
        // 1500 USD / 1.25 = 1200 GBP market value; + 500 cash = 1700 total.
        assertEquals(0, new BigDecimal("1700").compareTo(result.totals().totalGbp()));
        // cost 1000 USD / 1.25 = 800 GBP; gain = 1200 - 800 = 400.
        assertEquals(0, new BigDecimal("400").compareTo(result.totals().totalGainGbp()));
    }

    /**
     * II holds euro alongside sterling and dollars, and this form is the holdings view's only
     * source of any of them. Each box has to reach the total at its own live rate, and be
     * compared against the ledger's balance in <em>that</em> currency rather than folded into the
     * sterling row — a euro balance buried in the GBP row is a reconciliation that cannot fail.
     */
    @Test
    void everyCurrencyBoxReachesTheTotalAndItsOwnReconciliationRow() throws IOException {
        Files.writeString(inputDir.resolve("11111111-1111-1111-1111-111111111111.csv"),
                "Symbol,Qty,Market Value,Book Cost\nAAPL,10,$1500.00,$1000.00\n");

        SyncResult result = service().sync(new IiCash(Map.of(
                "GBP", new BigDecimal("500"),
                "USD", new BigDecimal("250"),
                "EUR", new BigDecimal("1200"))));

        // equity 1500 USD / 1.25 = 1200; cash 500 + 250/1.25 + 1200/1.20 = 500 + 200 + 1000.
        assertEquals(0, new BigDecimal("1700").compareTo(result.totals().totalCashGbp()));
        assertEquals(0, new BigDecimal("2900").compareTo(result.totals().totalGbp()));

        SyncResult.CashRecon eur = result.cashRecon().stream()
                .filter(r -> "II".equals(r.account()) && "EUR".equals(r.currency()))
                .findFirst().orElseThrow(() -> new AssertionError("no II/EUR reconciliation row"));
        assertEquals(0, new BigDecimal("1000.00").compareTo(eur.holdingsGbp()),
                "the euro box belongs in the II/EUR row at the live rate");
    }
}
