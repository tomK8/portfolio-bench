package com.portfolio.persistence;

import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The write path for in-specie transfers, and the three places a row that moves no cash must not
 * be allowed to behave like one that does.
 */
class CashTransactionRepositoryTransferTest {

    @TempDir
    Path dbDir;

    private CashTransactionRepository repo() {
        return new CashTransactionRepository(new JdbcConnectionFactory(dbDir), new KeyValueStore(dbDir));
    }

    private static CashTransaction leg(Account account, String symbol, double qty, double amountGbp) {
        return new CashTransaction("2026-08-20", account, TransactionType.TRANSFER, symbol,
                qty, amountGbp, "GBP", 1.0, amountGbp, 0.0, 0.0, "transfer");
    }

    private static CashTransaction cashRow(Account account, String date, double amount, double balance) {
        return new CashTransaction(date, account, TransactionType.OPENING_BALANCE, "GBP",
                0, amount, "GBP", 1.0, amount, balance, balance, "cash");
    }

    @Test
    void storesBothLegsAndReadsThemBack() {
        var r = repo();
        r.saveTransfer(leg(Account.AJBELL, "GOOG", 300, 37_663.74),
                       leg(Account.II, "GOOG", 300, -37_663.74));

        List<CashTransactionRepository.LedgerRow> rows = r.loadTransferRows();
        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(x -> x.tx().account() == Account.AJBELL && x.tx().amountGbp() > 0));
        assertTrue(rows.stream().anyMatch(x -> x.tx().account() == Account.II && x.tx().amountGbp() < 0));
    }

    /** The type is new, so the CHECK constraint has to have been migrated for any of this to work. */
    @Test
    void theCheckConstraintAcceptsTheNewType() {
        var r = repo();
        r.saveTransfer(leg(Account.AJBELL, "RHM", 3, 4_191.90),
                       leg(Account.II, "RHM", 3, -4_191.90));
        assertEquals(2, r.loadTransferRows().size());
    }

    /**
     * The row's amount is a cost basis, not money. Letting it set an account's reported cash
     * balance would wipe out tens of thousands of pounds that never moved.
     */
    @Test
    void aTransferNeverSetsAnAccountsCashBalance() {
        var r = repo();
        r.saveAjBell(List.of(cashRow(Account.AJBELL, "2026-08-19", 5_000, 5_000)));
        r.saveTransfer(leg(Account.AJBELL, "GOOG", 300, 37_663.74),
                       leg(Account.II, "GOOG", 300, -37_663.74));

        double ajBell = r.latestCashBalances().stream()
                .filter(b -> b.accountDbValue().equals("AJBell")).mapToDouble(
                        CashTransactionRepository.CashBalance::cashGbp).sum();
        assertEquals(5_000.0, ajBell, 1e-9,
                "the transfer is dated after the cash row and must still not become the balance");
        assertTrue(r.latestCashBalances().stream().noneMatch(b -> b.accountDbValue().equals("II")),
                "a transfer leg must not conjure a cash balance for an account that has none");
    }

    /**
     * The derived chain is rebuilt for manual accounts on every write. A transfer amount inside
     * the running sum would step the balance and corrupt every later row.
     */
    @Test
    void aTransferIsExcludedFromTheDerivedBalanceChain() {
        var r = repo();
        r.saveManual(Account.EQUATE, List.of(
                new CashTransaction("2020-06-01", Account.EQUATE, TransactionType.OPENING_BALANCE,
                        "GBP", 0, 18_500, "GBP", 1.0, 18_500, 0.0, 0.0, "seed"),
                new CashTransaction("2020-06-01", Account.EQUATE, TransactionType.TRANSACTION,
                        "BARC", 7227, -18_500, "GBP", 1.0, -18_500, 0.0, 0.0, "buy")));
        r.saveTransfer(leg(Account.EQUATE, "BARC", 7227, 18_500),
                       leg(Account.II, "BARC", 7227, -18_500));

        // Any later manual write rebuilds the chain over the transfer row.
        double closing = r.saveManual(Account.EQUATE, List.of(
                new CashTransaction("2026-08-21", Account.EQUATE, TransactionType.OPENING_BALANCE,
                        "GBP", 0, 250, "GBP", 1.0, 250, 0.0, 0.0, "later")));
        assertEquals(250.0, closing, 1e-9,
                "the rebuilt chain must see 18500 - 18500 + 250, with the transfer contributing nothing");
    }

    /** The primary key is the backstop against entering the same transfer twice. */
    @Test
    void theSameTransferCannotBeRecordedTwice() {
        var r = repo();
        r.saveTransfer(leg(Account.AJBELL, "BAC", 80, 2_996.39),
                       leg(Account.II, "BAC", 80, -2_996.39));

        assertThrows(IllegalStateException.class, () ->
                r.saveTransfer(leg(Account.AJBELL, "BAC", 80, 2_996.39),
                               leg(Account.II, "BAC", 80, -2_996.39)));
        assertEquals(2, r.loadTransferRows().size(), "the failed retry must not have half-written");
    }

    @Test
    void bothAccountsMustDiffer() {
        var r = repo();
        assertThrows(IllegalArgumentException.class, () ->
                r.saveTransfer(leg(Account.AJBELL, "BAC", 80, 2_996.39),
                               leg(Account.AJBELL, "BAC", 80, -2_996.39)));
    }

    @Test
    void deleteRemovesBothLegs() {
        var r = repo();
        r.saveTransfer(leg(Account.AJBELL, "GOOG", 300, 37_663.74),
                       leg(Account.II, "GOOG", 300, -37_663.74));
        List<CashTransactionRepository.LedgerRow> rows = r.loadTransferRows();

        assertTrue(r.deleteTransfer(rows.get(0).rowid(), rows.get(1).rowid()));
        assertTrue(r.loadTransferRows().isEmpty());
    }

    /**
     * Half a transfer is worse than none — a position would leave one account and arrive nowhere.
     * A delete that cannot find both legs must change nothing.
     */
    @Test
    void deleteRollsBackRatherThanLeavingALoneLeg() {
        var r = repo();
        r.saveTransfer(leg(Account.AJBELL, "GOOG", 300, 37_663.74),
                       leg(Account.II, "GOOG", 300, -37_663.74));
        long real = r.loadTransferRows().get(0).rowid();

        assertFalse(r.deleteTransfer(real, 999_999L));
        assertEquals(2, r.loadTransferRows().size(), "nothing may be removed when the pair is incomplete");
    }

    /** Deleting is restricted to rows this tool wrote — it can never reach an imported row. */
    @Test
    void deleteCannotTouchANonTransferRow() {
        var r = repo();
        r.saveAjBell(List.of(cashRow(Account.AJBELL, "2026-08-19", 5_000, 5_000)));
        r.saveTransfer(leg(Account.AJBELL, "GOOG", 300, 37_663.74),
                       leg(Account.II, "GOOG", 300, -37_663.74));
        List<CashTransactionRepository.LedgerRow> transfers = r.loadTransferRows();

        // Aim one end of the delete at the imported cash row's rowid.
        assertFalse(r.deleteTransfer(transfers.get(0).rowid(), 1L));
        assertEquals(2, r.loadTransferRows().size());
        assertEquals(1, r.latestCashBalances().size(), "the imported row must be untouched");
    }

    /** A transferred-in symbol has to reach the price-fetch universe on its own. */
    @Test
    void aTransferredSymbolIsATradedSymbol() {
        var r = repo();
        r.saveTransfer(leg(Account.AJBELL, "RHM", 3, 4_191.90),
                       leg(Account.II, "RHM", 3, -4_191.90));
        assertTrue(r.distinctTradedSymbols().contains("RHM"));
    }
}
