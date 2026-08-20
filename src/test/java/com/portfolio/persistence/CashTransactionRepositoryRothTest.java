package com.portfolio.persistence;

import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CashTransactionRepositoryRothTest {

    @TempDir
    Path dbDir;

    /**
     * Parser-shaped row: native USD amount, fx resolved, balances still null.
     */
    private static CashTransaction row(String date, String symbol, String type,
                                       double qty, double amount, double fx) {
        return new CashTransaction(date, Account.ROTH_IRA, TransactionType.valueOf(type),
                symbol, qty, amount, "USD", fx, amount / fx, null, null, date);
    }

    private CashTransactionRepository newRepo(KeyValueStore kv) {
        return new CashTransactionRepository(new JdbcConnectionFactory(dbDir), kv);
    }

    private double finalBalance() throws Exception {
        Path dbPath = dbDir.resolve("portfolio.db");
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             var st = conn.createStatement();
             var rs = st.executeQuery(
                     "SELECT cash_balance FROM cash_transactions WHERE account='RothIRA' " +
                             "ORDER BY transaction_date DESC, rowid DESC LIMIT 1")) {
            assertTrue(rs.next());
            return rs.getDouble(1);
        }
    }

    @Test
    void seedsFromBroughtForwardThenContinuesOnReimport() throws Exception {
        KeyValueStore kv = new KeyValueStore(dbDir);
        CashTransactionRepository repo = newRepo(kv);

        // First import (rows arrive newest-first like the file). Seed = 1000.
        List<CashTransaction> first = List.of(
                row("2026-01-02", "AAA", "DIVIDEND", 0, 100, 1.25),
                row("2026-01-01", "BBB", "TRANSACTION", 10, -50, 1.25));
        int inserted = repo.saveRothIra(first, new BigDecimal("1000"));

        assertEquals(2, inserted);
        // chronological: 1000 - 50 = 950, then 950 + 100 = 1050
        assertEquals(1050.0, finalBalance(), 1e-6);
        assertEquals(new BigDecimal("1000"),
                kv.getBigDecimal(CashTransactionRepository.ROTH_BROUGHT_FORWARD_KEY, BigDecimal.ZERO),
                "seed persisted for traceability");

        // Re-import overlaps the two existing rows and adds one new, newer row.
        List<CashTransaction> second = List.of(
                row("2026-01-03", "CCC", "TRANSACTION", -5, 200, 1.30),
                row("2026-01-02", "AAA", "DIVIDEND", 0, 100, 1.25),
                row("2026-01-01", "BBB", "TRANSACTION", 10, -50, 1.25));
        int insertedAgain = repo.saveRothIra(second, new BigDecimal("1000"));

        assertEquals(1, insertedAgain, "only the new row is inserted");
        // continues from the stored 1050: 1050 + 200 = 1250
        assertEquals(1250.0, finalBalance(), 1e-6);
    }

    @Test
    void gbpBalanceUsesRowFxRate() throws Exception {
        CashTransactionRepository repo = newRepo(new KeyValueStore(dbDir));
        repo.saveRothIra(
                List.of(row("2026-02-01", "AAA", "DIVIDEND", 0, 250, 1.25)),
                new BigDecimal("1000"));

        Path dbPath = dbDir.resolve("portfolio.db");
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             var st = conn.createStatement();
             var rs = st.executeQuery(
                     "SELECT cash_balance, cash_balance_gbp FROM cash_transactions WHERE account='RothIRA'")) {
            assertTrue(rs.next());
            assertEquals(1250.0, rs.getDouble(1), 1e-6);           // native USD
            assertEquals(1250.0 / 1.25, rs.getDouble(2), 1e-6);    // GBP at the row's rate
        }
    }

    @Test
    void backfillsNativeBalanceForExistingGbpRows() throws Exception {
        // Simulate an older DB created before the cash_balance column existed.
        Path dbPath = dbDir.resolve("portfolio.db");
        Files.createDirectories(dbDir);
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             var st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE cash_transactions (
                        transaction_date TEXT NOT NULL, account TEXT NOT NULL, type TEXT NOT NULL,
                        symbol TEXT NOT NULL, quantity REAL NOT NULL, amount REAL NOT NULL,
                        currency TEXT NOT NULL, fx_to_gbp REAL NOT NULL, amount_gbp REAL NOT NULL,
                        cash_balance_gbp REAL, description TEXT)""");
            st.execute("INSERT INTO cash_transactions VALUES " +
                    "('2026-01-01','AJBell','INTEREST','GBP',0,5,'GBP',1.0,5,105.0,'interest')");
        }

        // Any save triggers the migration + backfill via the repo's constructor.
        CashTransactionRepository repo = newRepo(new KeyValueStore(dbDir));
        repo.saveRothIra(
                List.of(row("2026-03-01", "AAA", "DIVIDEND", 0, 10, 1.25)),
                new BigDecimal("1000"));

        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             var st = conn.createStatement();
             var rs = st.executeQuery(
                     "SELECT cash_balance FROM cash_transactions WHERE account='AJBell'")) {
            assertTrue(rs.next());
            assertEquals(105.0, rs.getDouble(1), 1e-6, "existing GBP row backfilled from cash_balance_gbp");
        }
    }

    private int rothRowCount() throws Exception {
        Path dbPath = dbDir.resolve("portfolio.db");
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             var st = conn.createStatement();
             var rs = st.executeQuery(
                     "SELECT COUNT(*) FROM cash_transactions WHERE account='RothIRA'")) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private List<double[]> rothFills(String symbol) throws Exception {
        Path dbPath = dbDir.resolve("portfolio.db");
        List<double[]> out = new java.util.ArrayList<>();
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             var st = conn.createStatement();
             var rs = st.executeQuery(
                     "SELECT quantity, amount, cash_balance FROM cash_transactions " +
                             "WHERE account='RothIRA' AND symbol='" + symbol + "' " +
                             "ORDER BY transaction_date, rowid")) {
            while (rs.next()) out.add(new double[]{rs.getDouble(1), rs.getDouble(2), rs.getDouble(3)});
        }
        return out;
    }

    /**
     * The MRNA case of 2026-08-19: two partial fills imported, then the broker re-exported the
     * same sale as one aggregated row. Row-level dedup matches neither fill, so without the group
     * guard the aggregate inserts as a third sale — 100 shares disposed of instead of 50.
     *
     * <p>The file's version wins, so the ledger ends up holding the row shape every later export
     * will carry and the plain row key matches it from then on.
     */
    @Test
    void aggregatedRefillSupersedesTheStoredPartialFills() throws Exception {
        CashTransactionRepository repo = newRepo(new KeyValueStore(dbDir));
        repo.saveRothIra(List.of(
                row("2026-08-19", "MRNA", "TRANSACTION", -13, 1911, 1.36),
                row("2026-08-19", "MRNA", "TRANSACTION", -37, 5439, 1.36)),
                new BigDecimal("10000"));
        assertEquals(2, rothRowCount());
        assertEquals(17350.0, finalBalance(), 1e-6);

        repo.saveRothIra(List.of(
                row("2026-08-19", "MRNA", "TRANSACTION", -50, 7350, 1.36)),
                new BigDecimal("10000"));

        List<double[]> fills = rothFills("MRNA");
        assertEquals(1, fills.size(), "the two partials are superseded by the aggregate");
        assertEquals(-50.0, fills.get(0)[0], 1e-9);
        assertEquals(7350.0, fills.get(0)[1], 1e-9);
        assertEquals(17350.0, finalBalance(), 1e-6, "cash unchanged — no second sale happened");
    }

    /** A third import of the same aggregate is now an ordinary row-level match: a plain no-op. */
    @Test
    void reimportingTheAggregateAgainIsAPlainNoOp() throws Exception {
        CashTransactionRepository repo = newRepo(new KeyValueStore(dbDir));
        repo.saveRothIra(List.of(
                row("2026-08-19", "MRNA", "TRANSACTION", -13, 1911, 1.36),
                row("2026-08-19", "MRNA", "TRANSACTION", -37, 5439, 1.36)),
                new BigDecimal("10000"));
        repo.saveRothIra(List.of(
                row("2026-08-19", "MRNA", "TRANSACTION", -50, 7350, 1.36)),
                new BigDecimal("10000"));

        int inserted = repo.saveRothIra(List.of(
                row("2026-08-19", "MRNA", "TRANSACTION", -50, 7350, 1.36)),
                new BigDecimal("10000"));

        assertEquals(0, inserted);
        assertEquals(1, rothFills("MRNA").size());
        assertEquals(17350.0, finalBalance(), 1e-6);
    }

    /** The mirror case: the aggregate landed first and a later export splits it back out. */
    @Test
    void splitSupersedesTheStoredAggregate() throws Exception {
        CashTransactionRepository repo = newRepo(new KeyValueStore(dbDir));
        repo.saveRothIra(List.of(
                row("2026-08-19", "MRNA", "TRANSACTION", -50, 7350, 1.36)),
                new BigDecimal("10000"));

        repo.saveRothIra(List.of(
                row("2026-08-19", "MRNA", "TRANSACTION", -13, 1911, 1.36),
                row("2026-08-19", "MRNA", "TRANSACTION", -37, 5439, 1.36)),
                new BigDecimal("10000"));

        assertEquals(2, rothFills("MRNA").size());
        assertEquals(17350.0, finalBalance(), 1e-6);
    }

    /**
     * Adopted rows are appended at the tail, so they sort after anything else already stored on
     * their date — every balance from there on has to be rebuilt, not just the group's own.
     */
    @Test
    void balancesOfLaterRowsSurviveTheSupersede() throws Exception {
        CashTransactionRepository repo = newRepo(new KeyValueStore(dbDir));
        repo.saveRothIra(List.of(
                row("2026-08-19", "WMT", "TRANSACTION", 50, -5743.50, 1.36),
                row("2026-08-19", "MRNA", "TRANSACTION", -13, 1911, 1.36),
                row("2026-08-19", "MRNA", "TRANSACTION", -37, 5439, 1.36)),
                new BigDecimal("10000"));
        repo.saveRothIra(List.of(
                row("2026-08-20", "ISRG", "TRANSACTION", 20, -7877.40, 1.36)),
                new BigDecimal("10000"));
        double before = finalBalance();

        repo.saveRothIra(List.of(
                row("2026-08-19", "MRNA", "TRANSACTION", -50, 7350, 1.36)),
                new BigDecimal("10000"));

        assertEquals(before, finalBalance(), 1e-6, "the day's closing balance is unchanged");
        assertEquals(10000.0 - 5743.50 + 7350 - 7877.40, finalBalance(), 1e-6);
        // WMT keeps a balance consistent with its own place in the rebuilt chain.
        assertEquals(10000.0 - 5743.50, rothFills("WMT").get(0)[2], 1e-6);
    }

    /**
     * The guard must not swallow real activity: a genuine further trade in the same name on the
     * same day changes the group's net totals, so it still dedups row by row and inserts.
     */
    @Test
    void furtherTradeInTheSameNameOnTheSameDayStillInserts() throws Exception {
        CashTransactionRepository repo = newRepo(new KeyValueStore(dbDir));
        repo.saveRothIra(List.of(
                row("2026-08-19", "MRNA", "TRANSACTION", -13, 1911, 1.36),
                row("2026-08-19", "MRNA", "TRANSACTION", -37, 5439, 1.36)),
                new BigDecimal("10000"));

        // Same two fills re-exported unchanged, plus a real third sale later that day.
        int inserted = repo.saveRothIra(List.of(
                row("2026-08-19", "MRNA", "TRANSACTION", -20, 3000, 1.36),
                row("2026-08-19", "MRNA", "TRANSACTION", -13, 1911, 1.36),
                row("2026-08-19", "MRNA", "TRANSACTION", -37, 5439, 1.36)),
                new BigDecimal("10000"));

        assertEquals(1, inserted);
        assertEquals(3, rothRowCount());
        assertEquals(20350.0, finalBalance(), 1e-6);
    }

    /** Two genuine same-day buys arriving in one file are both kept — no stored group to match. */
    @Test
    void twoGenuineSameDayFillsInOneFileAreBothKept() throws Exception {
        CashTransactionRepository repo = newRepo(new KeyValueStore(dbDir));
        int inserted = repo.saveRothIra(List.of(
                row("2025-10-03", "NVDA", "TRANSACTION", 43, -8084, 1.34),
                row("2025-10-03", "NVDA", "TRANSACTION", 106, -19928, 1.34)),
                new BigDecimal("50000"));

        assertEquals(2, inserted);
        assertEquals(50000.0 - 8084 - 19928, finalBalance(), 1e-6);
    }
}
