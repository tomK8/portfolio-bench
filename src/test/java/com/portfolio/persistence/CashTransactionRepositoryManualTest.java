package com.portfolio.persistence;

import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The hand-entry write path and the CHECK-constraint migration that has to run before it can
 * store anything.
 */
class CashTransactionRepositoryManualTest {

    @TempDir
    Path dbDir;

    private CashTransactionRepository repo() {
        return new CashTransactionRepository(new JdbcConnectionFactory(dbDir), new KeyValueStore(dbDir));
    }

    private static CashTransaction row(String date, TransactionType type, String symbol,
                                       double qty, double amount) {
        return new CashTransaction(date, Account.EQUATE, type, symbol,
                qty, amount, "GBP", 1.0, amount, 0.0, 0.0, "test");
    }

    // ---- the two-row acquisition -------------------------------------------

    @Test
    void savesTheFundingRowAndThePurchaseAndNetsCashToZero() {
        var r = repo();
        double closing = r.saveManual(Account.EQUATE, List.of(
                row("2020-06-01", TransactionType.OPENING_BALANCE, "GBP", 0, 18_500),
                row("2020-06-01", TransactionType.TRANSACTION, "BARC", 7227, -18_500)));

        assertEquals(0.0, closing, 1e-9,
                "a purchase funded by its own opening balance leaves no cash behind");
        assertEquals(2, r.loadManualRows().size());
    }

    @Test
    void theOpeningBalanceIsNotAContributionAndNotATrade() {
        var r = repo();
        r.saveManual(Account.EQUATE, List.of(
                row("2020-06-01", TransactionType.OPENING_BALANCE, "GBP", 0, 18_500),
                row("2020-06-01", TransactionType.TRANSACTION, "BARC", 7227, -18_500)));

        // The Contributions chart and Period diff's contributions figure must not see it.
        assertTrue(r.loadContributions().isEmpty());
        // Nor may the position/dividend reconstruction, which reads TRANSACTION + DIVIDEND.
        assertEquals(1, r.loadDividendTransactions().size());
        assertEquals("BARC", r.loadDividendTransactions().get(0).symbol());
        // But the TWR's external-flow term must.
        assertEquals(1, r.loadOpeningBalances().size());
        assertEquals(18_500.0, r.loadOpeningBalances().get(0).amountGbp(), 1e-9);
        // And the unfiltered replay, which is what moves the account's cash.
        assertEquals(2, r.loadAllTransactions().size());
    }

    @Test
    void theTradedSymbolEntersThePriceFetchUniverse() {
        var r = repo();
        r.saveManual(Account.EQUATE, List.of(
                row("2020-06-01", TransactionType.OPENING_BALANCE, "GBP", 0, 18_500),
                row("2020-06-01", TransactionType.TRANSACTION, "BARC", 7227, -18_500)));
        assertTrue(r.distinctTradedSymbols().contains("BARC"),
                "without this the position would never be priced");
    }

    // ---- balance chain ------------------------------------------------------

    @Test
    void aBackDatedRowRebuildsTheWholeBalanceChainInDateOrder() {
        var r = repo();
        r.saveManual(Account.EQUATE, List.of(
                row("2024-01-01", TransactionType.OPENING_BALANCE, "GBP", 0, 5_000)));
        // Entered later but dated earlier — the ordinary case for a holding you have had for years.
        r.saveManual(Account.EQUATE, List.of(
                row("2020-06-01", TransactionType.OPENING_BALANCE, "GBP", 0, 18_500),
                row("2020-06-01", TransactionType.TRANSACTION, "BARC", 7227, -18_500)));

        List<CashTransactionRepository.LedgerRow> rows = r.loadManualRows();
        assertEquals(3, rows.size());
        assertEquals("2020-06-01", rows.get(0).tx().transactionDate(), "oldest first");
        assertEquals(0.0, rows.get(1).tx().cashBalanceGbp(), 1e-9,
                "the 2020 purchase spends the 2020 opening balance");
        assertEquals(5_000.0, rows.get(2).tx().cashBalanceGbp(), 1e-9,
                "the 2024 row sits on top of a chain that now starts four years earlier");
    }

    @Test
    void deletingARowRecomputesTheChain() {
        var r = repo();
        r.saveManual(Account.EQUATE, List.of(
                row("2020-06-01", TransactionType.OPENING_BALANCE, "GBP", 0, 18_500),
                row("2020-06-01", TransactionType.TRANSACTION, "BARC", 7227, -18_500),
                row("2021-01-01", TransactionType.OPENING_BALANCE, "GBP", 0, 1_000)));

        long rowid = r.loadManualRows().get(2).rowid();
        assertTrue(r.deleteManualRow(Account.EQUATE, rowid));

        List<CashTransactionRepository.LedgerRow> rows = r.loadManualRows();
        assertEquals(2, rows.size());
        assertEquals(0.0, rows.get(1).tx().cashBalanceGbp(), 1e-9);
    }

    // ---- guards -------------------------------------------------------------

    @Test
    void anImportedAccountCannotBeWrittenByHand() {
        var r = repo();
        assertThrows(IllegalArgumentException.class, () -> r.saveManual(Account.AJBELL, List.of(
                new CashTransaction("2020-06-01", Account.AJBELL, TransactionType.TRANSACTION,
                        "BARC", 1, -1, "GBP", 1.0, -1, 0.0, 0.0, "x"))),
                "an imported row deleted or added here would simply return on the next import");
        assertThrows(IllegalArgumentException.class, () -> r.deleteManualRow(Account.II, 1L));
    }

    @Test
    void loadManualRowsIgnoresImportedAccounts() {
        var r = repo();
        r.saveAjBell(List.of(new CashTransaction("2020-06-01", Account.AJBELL,
                TransactionType.TRANSACTION, "LGEN", 1, -1, "GBP", 1.0, -1, 0.0, 0.0, "x")));
        r.saveManual(Account.EQUATE, List.of(
                row("2020-06-01", TransactionType.TRANSACTION, "BARC", 7227, -18_500)));

        List<CashTransactionRepository.LedgerRow> rows = r.loadManualRows();
        assertEquals(1, rows.size());
        assertEquals(Account.EQUATE, rows.get(0).tx().account());
    }

    // ---- the migration ------------------------------------------------------

    /**
     * A database created before {@code EQUATE} / {@code OPENING_BALANCE} existed carries the old
     * CHECK lists, and the create is {@code CREATE TABLE IF NOT EXISTS} — so without a migration it
     * would go on rejecting both while a fresh install accepted them. Builds that table by hand,
     * fills it, then opens the repository over it.
     */
    @Test
    void aDatabaseWithTheOldCheckListsIsMigratedAndKeepsItsRows() throws Exception {
        var connections = new JdbcConnectionFactory(dbDir);
        try (Connection conn = connections.open(); Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE cash_transactions (
                        transaction_date TEXT NOT NULL,
                        account          TEXT NOT NULL CHECK (account IN ('RothIRA', 'AJBell', 'II')),
                        type             TEXT NOT NULL CHECK (type IN ('TRANSACTION', 'DIVIDEND', 'INTEREST', 'CHARGE', 'CONTRIBUTION')),
                        symbol           TEXT NOT NULL,
                        quantity         REAL NOT NULL,
                        amount           REAL NOT NULL,
                        currency         TEXT NOT NULL,
                        fx_to_gbp        REAL NOT NULL,
                        amount_gbp       REAL NOT NULL,
                        cash_balance     REAL,
                        cash_balance_gbp REAL,
                        description      TEXT,
                        PRIMARY KEY (transaction_date, account, type, symbol, amount_gbp, cash_balance_gbp)
                    )""");
            st.execute("INSERT INTO cash_transactions VALUES "
                    + "('2023-01-01','AJBell','TRANSACTION','LGEN',10,-100,'GBP',1.0,-100,900,900,'pre-existing')");
        }

        var r = repo();   // constructor runs the migration

        assertEquals(1, r.loadAllTransactions().size(), "the existing row survives the rebuild");
        assertEquals("pre-existing", r.loadAllTransactions().get(0).description());

        // And the values the old constraints rejected now store.
        r.saveManual(Account.EQUATE, List.of(
                row("2020-06-01", TransactionType.OPENING_BALANCE, "GBP", 0, 18_500),
                row("2020-06-01", TransactionType.TRANSACTION, "BARC", 7227, -18_500)));
        assertEquals(2, r.loadManualRows().size());
    }

    @Test
    void theMigrationIsIdempotent() throws Exception {
        repo();
        String first = tableSql();
        repo();
        assertEquals(first, tableSql(), "a second open must not rebuild an already-correct table");
    }

    @Test
    void theCheckListsAreDerivedFromTheEnums() throws Exception {
        repo();
        String sql = tableSql();
        for (Account a : Account.values()) {
            assertTrue(sql.contains("'" + a.dbValue() + "'"), "missing account " + a);
        }
        for (TransactionType t : TransactionType.values()) {
            assertTrue(sql.contains("'" + t.name() + "'"), "missing type " + t);
        }
    }

    private String tableSql() throws Exception {
        try (Connection conn = new JdbcConnectionFactory(dbDir).open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT sql FROM sqlite_master WHERE type='table' AND name='cash_transactions'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
