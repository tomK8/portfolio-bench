package com.portfolio.persistence;

import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CashTransactionRepositoryAjBellTest {

    @TempDir
    Path dbDir;

    /** Parser-shaped AJBell row (GBP, fx=1.0, balance pre-populated). */
    private static CashTransaction row(String date, String type, String symbol,
                                       double amount, double balance, String desc) {
        return new CashTransaction(date, Account.AJBELL, TransactionType.valueOf(type),
                symbol, 0.0, amount, "GBP", 1.0, amount, balance, balance, desc);
    }

    private CashTransactionRepository newRepo() {
        return new CashTransactionRepository(new JdbcConnectionFactory(dbDir), new KeyValueStore(dbDir));
    }

    private long countRows() throws Exception {
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbDir.resolve("portfolio.db"));
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM cash_transactions WHERE account='AJBell'")) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private double storedBalance(String date, String symbol) throws Exception {
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbDir.resolve("portfolio.db"));
             var st = conn.prepareStatement(
                     "SELECT cash_balance_gbp FROM cash_transactions " +
                             "WHERE account='AJBell' AND transaction_date = ? AND symbol = ?")) {
            st.setString(1, date);
            st.setString(2, symbol);
            try (var rs = st.executeQuery()) {
                assertTrue(rs.next(), "row missing: " + date + " / " + symbol);
                return rs.getDouble(1);
            }
        }
    }

    /**
     * AJ Bell can retroactively insert a dividend in the middle of the statement, which shifts
     * every later row's running balance by the inserted amount. The new dividend must be added
     * and the affected later rows' balances updated in place — not re-inserted as duplicates.
     */
    @Test
    void retroactiveDividendInsertUpdatesShiftedBalancesInPlace() throws Exception {
        CashTransactionRepository repo = newRepo();

        // Yesterday's file: AstraZeneca purchase, then two 04/06 dividends.
        repo.saveAjBell(List.of(
                row("2026-05-22", "CHARGE", "GBP", -5.17, 46_617.40, "FX Charge"),
                row("2026-06-03", "TRANSACTION", "AZN", -3903.18, 42_714.22, "Purchase 30 AstraZeneca"),
                row("2026-06-04", "DIVIDEND", "BA.", 79.34, 42_793.56, "Dividend 348 B A E SYSTEMS"),
                row("2026-06-04", "DIVIDEND", "LGEN", 3385.5, 46_179.06, "Dividend 21605 LEGAL & GENERAL")));

        assertEquals(4, countRows());

        // Today's file: SocGen dividend retroactively inserted on 03/06; later balances shift
        // by +64.86; plus a brand-new 05/06 FX charge at the tail.
        int newlyInserted = repo.saveAjBell(List.of(
                row("2026-05-22", "CHARGE", "GBP", -5.17, 46_617.40, "FX Charge"),
                row("2026-06-03", "TRANSACTION", "AZN", -3903.18, 42_714.22, "Purchase 30 AstraZeneca"),
                row("2026-06-03", "DIVIDEND", "GLE", 64.86, 42_779.08, "DIVIDEND 100 SOCIETE GENERALE"),
                row("2026-06-04", "DIVIDEND", "BA.", 79.34, 42_858.42, "Dividend 348 B A E SYSTEMS"),
                row("2026-06-04", "DIVIDEND", "LGEN", 3385.5, 46_243.92, "Dividend 21605 LEGAL & GENERAL"),
                row("2026-06-05", "CHARGE", "GBP", -0.33, 46_243.59, "FX Charge")));

        assertEquals(2, newlyInserted, "SocGen and 05/06 FX charge are the only new identities");
        assertEquals(6, countRows(), "no duplicates created for the shifted-balance rows");

        // The two 04/06 dividends had their stored balances updated to match the new file.
        assertEquals(42_858.42, storedBalance("2026-06-04", "BA."), 1e-6);
        assertEquals(46_243.92, storedBalance("2026-06-04", "LGEN"), 1e-6);
        // Pre-insertion rows are untouched.
        assertEquals(42_714.22, storedBalance("2026-06-03", "AZN"), 1e-6);
        assertEquals(46_617.40, storedBalance("2026-05-22", "GBP"), 1e-6);
        // The inserted row landed with its new balance.
        assertEquals(42_779.08, storedBalance("2026-06-03", "GLE"), 1e-6);
    }

    /**
     * Re-importing the same file is a clean no-op: every row's identity matches, no balances
     * differ, nothing is inserted or updated.
     */
    @Test
    void reimportingIdenticalFileIsNoOp() throws Exception {
        CashTransactionRepository repo = newRepo();
        List<CashTransaction> rows = List.of(
                row("2026-05-22", "CHARGE", "GBP", -5.17, 46_617.40, "FX Charge"),
                row("2026-06-04", "DIVIDEND", "LGEN", 3385.5, 46_179.06, "Dividend LEGAL & GENERAL"));
        repo.saveAjBell(rows);
        assertEquals(2, countRows());

        int reinserted = repo.saveAjBell(rows);
        assertEquals(0, reinserted);
        assertEquals(2, countRows());
    }

    /**
     * Gap detection: if the file's earliest row is newer than every stored row, the user has
     * lost a window of transactions and the import must refuse rather than silently lose them.
     */
    @Test
    void rejectsFileWithGapToStoredHistory() {
        CashTransactionRepository repo = newRepo();
        repo.saveAjBell(List.of(
                row("2026-01-01", "CHARGE", "GBP", -5.17, 100.0, "FX")));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> repo.saveAjBell(List.of(
                row("2026-03-01", "DIVIDEND", "AAA", 50.0, 150.0, "Dividend AAA"))));
        assertTrue(ex.getMessage().contains("missing"),
                "error message names the gap: " + ex.getMessage());
    }

    /**
     * If a previous buggy import left two rows for one identity (the stale-balance survivor +
     * the re-inserted current-balance row), the next import collapses them by keeping the
     * highest-rowid copy and deleting the older one.
     */
    @Test
    void collapsesLegacyDuplicatesOnNextImport() throws Exception {
        CashTransactionRepository repo = newRepo();
        // Seed two stale-balance copies of the same identity directly via SQL.
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbDir.resolve("portfolio.db"));
             var st = conn.createStatement()) {
            st.execute("INSERT INTO cash_transactions VALUES " +
                    "('2026-06-04','AJBell','DIVIDEND','BA.',0,79.34,'GBP',1.0,79.34,42793.56,42793.56,'old')");
            st.execute("INSERT INTO cash_transactions VALUES " +
                    "('2026-06-04','AJBell','DIVIDEND','BA.',0,79.34,'GBP',1.0,79.34,42858.42,42858.42,'new')");
        }
        assertEquals(2, countRows());

        // Re-import the same identity with the newest balance. Loader detects the duplicate,
        // collapses to the highest rowid (42858.42), then the upsert finds balance matches.
        repo.saveAjBell(List.of(
                row("2026-06-04", "DIVIDEND", "BA.", 79.34, 42_858.42, "Dividend BA")));

        assertEquals(1, countRows(), "stale duplicate row was removed");
        assertEquals(42_858.42, storedBalance("2026-06-04", "BA."), 1e-6);
    }
}
