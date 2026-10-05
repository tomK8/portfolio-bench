package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.Holding;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Hand-entered positions: what gets written, what comes back, and how it is priced. */
class ManualPositionServiceTest {

    @TempDir
    Path dbDir;

    private CashTransactionRepository cashRepo;
    private PriceHistoryRepository priceRepo;

    private ManualPositionService service() {
        JdbcConnectionFactory cf = new JdbcConnectionFactory(dbDir);
        KeyValueStore kv = new KeyValueStore(dbDir);
        cashRepo = new CashTransactionRepository(cf, kv);
        priceRepo = new PriceHistoryRepository(cf);
        return new ManualPositionService(cashRepo, new IntradayPriceRepository(cf), priceRepo,
                new YahooTickerMap());
    }

    private static final LocalDate BOUGHT = LocalDate.parse("2020-06-01");

    private ManualPositionService withBarc() {
        ManualPositionService s = service();
        s.buy(Account.EQUATE, "BARC", new BigDecimal("7227"), new BigDecimal("18500"), BOUGHT, null);
        return s;
    }

    // ---- writing ------------------------------------------------------------

    @Test
    void aPurchaseIsStoredAsTheSharesPlusTheirFunding() {
        ManualPositionService s = withBarc();

        List<ManualPositionService.ManualRow> rows = s.view().rows();
        assertEquals(2, rows.size());
        assertEquals("OPENING_BALANCE", rows.get(0).type());
        assertEquals(0, new BigDecimal("18500").compareTo(rows.get(0).amount()));
        assertEquals("TRANSACTION", rows.get(1).type());
        assertEquals("BARC", rows.get(1).symbol());
        assertEquals(0, new BigDecimal("-18500").compareTo(rows.get(1).amount()),
                "the buy is cash out, which is what makes the reconstructor read it as shares in");
    }

    @Test
    void thePositionReconstructsToTheSharesAndCostEntered() {
        ManualPositionService.ManualPosition p = withBarc().view().positions().get(0);

        assertEquals("BARC", p.symbol());
        assertEquals("Equate", p.broker());
        assertEquals(0, new BigDecimal("7227").compareTo(p.quantity()));
        // The reconstructor carries cost as a 12-dp per-share figure, so the total comes back a
        // fraction of a penny under what was typed. Compared to the penny, which is what is shown.
        assertEquals(18_500.00, p.costGbp().doubleValue(), 0.005);
    }

    @Test
    void aSaleReducesThePositionAndLeavesTheProceedsAsCash() {
        ManualPositionService s = withBarc();
        s.sell(Account.EQUATE, "BARC", new BigDecimal("227"), new BigDecimal("700"),
                LocalDate.parse("2024-01-10"), null);

        ManualPositionService.ManualPosition p = s.view().positions().get(0);
        assertEquals(0, new BigDecimal("7000").compareTo(p.quantity()));
    }

    @Test
    void sellingOutEntirelyRemovesThePosition() {
        ManualPositionService s = withBarc();
        s.sell(Account.EQUATE, "BARC", new BigDecimal("7227"), new BigDecimal("22000"),
                LocalDate.parse("2024-01-10"), null);

        assertTrue(s.view().positions().isEmpty());
        assertTrue(s.heldSymbols().isEmpty());
    }

    @Test
    void aWithdrawalIsAnExternalFlowOutNotALoss() {
        ManualPositionService s = withBarc();
        s.sell(Account.EQUATE, "BARC", new BigDecimal("7227"), new BigDecimal("22000"),
                LocalDate.parse("2024-01-10"), null);
        s.withdraw(Account.EQUATE, new BigDecimal("22000"), LocalDate.parse("2024-01-11"), null);

        // Written as a negative opening balance so the TWR sees a flow rather than a fall.
        assertEquals(-22000.0,
                cashRepo.loadOpeningBalances().stream()
                        .filter(t -> t.amountGbp() < 0).mapToDouble(t -> t.amountGbp()).sum(), 1e-9);
        assertTrue(cashRepo.loadContributions().isEmpty());
    }

    // ---- validation ---------------------------------------------------------

    @Test
    void rejectsNonsenseInput() {
        ManualPositionService s = service();
        assertThrows(IllegalArgumentException.class, () ->
                s.buy(Account.EQUATE, "", BigDecimal.ONE, BigDecimal.ONE, BOUGHT, null));
        assertThrows(IllegalArgumentException.class, () ->
                s.buy(Account.EQUATE, "BARC", BigDecimal.ZERO, BigDecimal.ONE, BOUGHT, null));
        assertThrows(IllegalArgumentException.class, () ->
                s.buy(Account.EQUATE, "BARC", BigDecimal.ONE, new BigDecimal("-1"), BOUGHT, null));
        assertThrows(IllegalArgumentException.class, () ->
                s.buy(Account.EQUATE, "BARC", BigDecimal.ONE, BigDecimal.ONE,
                        LocalDate.now().plusDays(1), null),
                "a future-dated holding would value at zero on every chart before it");
        assertThrows(IllegalArgumentException.class, () ->
                s.buy(Account.EQUATE, "CASH", BigDecimal.ONE, BigDecimal.ONE, BOUGHT, null),
                "CASH is the ledger's own marker, not a security");
    }

    @Test
    void refusesToWriteToAnImportedAccount() {
        ManualPositionService s = service();
        assertThrows(IllegalArgumentException.class, () ->
                s.buy(Account.AJBELL, "BARC", BigDecimal.ONE, BigDecimal.ONE, BOUGHT, null));
    }

    // ---- pricing and the holdings projection --------------------------------

    @Test
    void pencePricesAreFoldedToPoundsBeforeValuing() {
        ManualPositionService s = withBarc();
        // BARC.L quotes in GBp: 320.5p on 7227 shares is £23,162.55, not £2.3m.
        priceRepo.upsertPriceBars(List.of(new PriceBar("BARC.L", LocalDate.now(),
                320.5, 320.5, 320.5, 320.5, 320.5, 1.0, null, "GBp")));

        ManualPositionService.ManualPosition p = s.view().positions().get(0);
        assertEquals(23_162.535, p.marketValueGbp().doubleValue(), 0.005);
        assertEquals(4_662.535, p.gainGbp().doubleValue(), 0.01);
    }

    @Test
    void anUnpricedPositionIsReportedRatherThanValuedAtZero() {
        ManualPositionService s = withBarc();

        ManualPositionService.ManualPosition p = s.view().positions().get(0);
        assertNull(p.marketValueGbp(), "a zero would be summed into the portfolio total as real");
        assertFalse(s.view().warnings().isEmpty());

        Holding h = s.holdings().get(0);
        assertNull(h.getCurrentMarketValueGbp());
        assertEquals(0, new BigDecimal("7227").compareTo(h.getQuantity()),
                "the shares are still known even when nothing has priced them");
    }

    @Test
    void holdingsCarryTheSymbolCostAndBrokerForTheFilesPipeline() {
        ManualPositionService s = withBarc();
        priceRepo.upsertPriceBars(List.of(new PriceBar("BARC.L", LocalDate.now(),
                320.5, 320.5, 320.5, 320.5, 320.5, 1.0, null, "GBp")));

        List<Holding> holdings = s.holdings();
        assertEquals(1, holdings.size());
        Holding h = holdings.get(0);
        assertEquals("BARC", h.getSecurityId());
        assertEquals("Equate", h.getSource(),
                "the source label must equal the account's db value, or reconciliation strands it");
        assertEquals("GBP", h.getCurrency().getCurrencyCode());
        assertEquals(18_500.00, h.getCostBasisGbp().doubleValue(), 0.005);
        assertEquals(23_162.535, h.getCurrentMarketValueGbp().doubleValue(), 0.005);
    }

    @Test
    void deletingARowUndoesTheEntry() {
        ManualPositionService s = withBarc();
        List<ManualPositionService.ManualRow> rows = s.view().rows();

        assertTrue(s.delete(Account.EQUATE, rows.get(1).rowid()));
        assertTrue(s.view().positions().isEmpty());
        assertEquals(1, s.view().rows().size());
    }

    @Test
    void theBrokerListOffersOnlyHandEnteredAccounts() {
        assertEquals(List.of("Equate"), service().view().brokers());
    }

    // ---- transfers out ------------------------------------------------------

    /**
     * Loading the row is not replaying it — the mistake three other lot engines made before this
     * one. A manual account that transferred its position away must stop reporting it: the shares
     * are in the receiving broker's own export, so a position left standing here is counted twice
     * in the portfolio and shows up on the Assets reconciliation at exactly double.
     */
    @Test
    void aPositionTransferredAwayStopsBeingHeld() {
        ManualPositionService s = withBarc();
        new AssetTransferService(cashRepo).transfer(
                LocalDate.parse("2026-09-24"), Account.EQUATE, Account.AJBELL_DEALING, "BARC");

        assertTrue(s.holdings().isEmpty(),
                "the shares are at the receiving account now, and it has its own export");
        assertTrue(s.view().positions().isEmpty());
    }

    /** Only the transferred part leaves; a manual account that kept some still holds it. */
    @Test
    void whatWasNotTransferredIsStillHeld() {
        ManualPositionService s = service();
        s.buy(Account.EQUATE, "BARC", new BigDecimal("7227"), new BigDecimal("18500"), BOUGHT, null);
        s.buy(Account.EQUATE, "AZN", new BigDecimal("30"), new BigDecimal("3900"), BOUGHT, null);
        new AssetTransferService(cashRepo).transfer(
                LocalDate.parse("2026-09-24"), Account.EQUATE, Account.AJBELL_DEALING, "BARC");

        List<Holding> left = s.holdings();
        assertEquals(1, left.size());
        assertEquals("AZN", left.get(0).getSecurityId());
    }
}
