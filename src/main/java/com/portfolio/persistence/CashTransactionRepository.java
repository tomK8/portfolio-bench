package com.portfolio.persistence;

import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The {@code cash_transactions} table: ingestion of broker cash statements and the reads
 * that drive dividend attribution and the price-fetch universe. Per-source save methods
 * encapsulate the dedup and balance-derivation rules specific to each broker.
 */
public class CashTransactionRepository {

    private static final Logger log = LoggerFactory.getLogger(CashTransactionRepository.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS cash_transactions (
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
            )""";

    private static final String INSERT_SQL =
            "INSERT INTO cash_transactions " +
                    "(transaction_date, account, type, symbol, quantity, amount, currency, " +
                    " fx_to_gbp, amount_gbp, cash_balance, cash_balance_gbp, description) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /** KV key for the RothIRA brought-forward balance (single source of truth, file-backed). */
    public static final String ROTH_BROUGHT_FORWARD_KEY = "roth_balance_brought_forward";

    private final JdbcConnectionFactory connections;
    private final KeyValueStore settings;

    public CashTransactionRepository(JdbcConnectionFactory connections, KeyValueStore settings) {
        this.connections = connections;
        this.settings = settings;
        try (Connection conn = connections.open(); Statement ddl = conn.createStatement()) {
            ddl.execute(CREATE_TABLE);
            try {
                ddl.execute("ALTER TABLE cash_transactions ADD COLUMN cash_balance REAL");
                ddl.execute("UPDATE cash_transactions SET cash_balance = cash_balance_gbp " +
                        "WHERE cash_balance IS NULL AND currency = 'GBP'");
            } catch (SQLException alreadyMigrated) {
                // cash_balance column already present — fine
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise cash_transactions table", e);
        }
    }

    /**
     * AJBell flow. Rows are GBP and already carry a running {@code cash_balance_gbp};
     * dedup by (date, balance). A known balance reappearing after new rows aborts the
     * import as a data-integrity gap.
     */
    public int saveAjBell(List<CashTransaction> transactions) {
        if (transactions.isEmpty()) return 0;
        try (Connection conn = connections.open()) {
            Account account = transactions.get(0).account();
            Set<String> existingKeys = loadKnownDateBalanceKeys(conn, account);

            int inserted = 0, skipped = 0;
            boolean seenNewRow = false;
            try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
                for (CashTransaction tx : transactions) {
                    Double bal = tx.cashBalanceGbp();
                    boolean known = bal != null &&
                            existingKeys.contains(tx.transactionDate() + "|" + bal);
                    if (known) {
                        if (seenNewRow) {
                            throw new IllegalStateException(String.format(
                                    "Data integrity error: %s balance %.2f on %s already exists in DB "
                                            + "but appears after new rows — possible gap or corrupt input file. "
                                            + "Aborting import.",
                                    account.dbValue(), bal, tx.transactionDate()));
                        }
                        skipped++;
                    } else {
                        seenNewRow = true;
                        bindCashRow(ps, tx);
                        ps.executeUpdate();
                        inserted++;
                    }
                }
            }
            log.info("Cash transactions [{}]: {} inserted, {} already present (skipped)",
                    account.dbValue(), inserted, skipped);
            return inserted;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not save AJBell cash transactions", e);
        }
    }

    /**
     * RothIRA flow. Rows arrive with {@code fxToGbp}/{@code amountGbp} resolved per-date,
     * native balances null. Dedups by (date, symbol, qty, type, amount); derives the running
     * balance from the latest stored row (or {@code seed} when the account is empty) and
     * persists the seed for traceability on a first-run import.
     */
    public int saveRothIra(List<CashTransaction> rows, BigDecimal seed) {
        if (rows.isEmpty()) return 0;
        Account account = rows.get(0).account();
        try (Connection conn = connections.open()) {
            Set<String> existingKeys = new HashSet<>();
            Double lastBalance = null;
            try (PreparedStatement q = conn.prepareStatement(
                    "SELECT transaction_date, symbol, quantity, type, amount, cash_balance " +
                            "FROM cash_transactions WHERE account = ? ORDER BY transaction_date, rowid")) {
                q.setString(1, account.dbValue());
                try (ResultSet rs = q.executeQuery()) {
                    while (rs.next()) {
                        existingKeys.add(rothKey(rs.getString(1), rs.getString(2),
                                rs.getDouble(3), rs.getString(4), rs.getDouble(5)));
                        double bal = rs.getDouble(6);
                        if (!rs.wasNull()) lastBalance = bal;
                    }
                }
            }

            boolean fromSeed = (lastBalance == null);
            double running = fromSeed ? seed.doubleValue() : lastBalance;

            List<CashTransaction> newRows = new ArrayList<>(rows.stream()
                    .filter(t -> !existingKeys.contains(rothKey(
                            t.transactionDate(), t.symbol(), t.quantity(), t.type().name(), t.amount())))
                    .toList());
            Collections.reverse(newRows);  // source is newest-first → make oldest-first
            newRows.sort(Comparator.comparing(CashTransaction::transactionDate));  // stable

            int inserted = 0;
            try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
                for (CashTransaction t : newRows) {
                    running += t.amount();
                    double balGbp = t.fxToGbp() != 0 ? running / t.fxToGbp() : 0.0;
                    bindCashRow(ps, new CashTransaction(
                            t.transactionDate(), t.account(), t.type(), t.symbol(),
                            t.quantity(), t.amount(), t.currency(), t.fxToGbp(),
                            t.amountGbp(), running, balGbp, t.description()));
                    ps.executeUpdate();
                    inserted++;
                }
            }

            if (inserted > 0 && fromSeed) {
                settings.putBigDecimal(ROTH_BROUGHT_FORWARD_KEY, seed);
            }
            log.info("Cash transactions [{}]: {} inserted, {} already present (skipped)",
                    account.dbValue(), inserted, rows.size() - inserted);
            return inserted;
        } catch (Exception e) {
            throw new IllegalStateException("Could not save RothIRA cash transactions", e);
        }
    }

    /**
     * II flow. Rows arrive with native running balance from the file's Running Balance column.
     * Dedup key is (date, type, symbol, amount, currency) — the currency component keeps the
     * GBP and USD per-currency ledgers isolated within the shared {@code account='II'}.
     */
    public int saveII(List<CashTransaction> rows) {
        if (rows.isEmpty()) return 0;
        try (Connection conn = connections.open()) {
            Set<String> existingKeys = new HashSet<>();
            try (PreparedStatement q = conn.prepareStatement(
                    "SELECT transaction_date, type, symbol, amount, currency " +
                            "FROM cash_transactions WHERE account = 'II'");
                 ResultSet rs = q.executeQuery()) {
                while (rs.next()) {
                    existingKeys.add(iiKey(rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getDouble(4), rs.getString(5)));
                }
            }

            int inserted = 0;
            try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
                for (CashTransaction t : rows) {
                    String key = iiKey(t.transactionDate(), t.type().name(), t.symbol(),
                            t.amount(), t.currency());
                    if (existingKeys.contains(key)) continue;
                    bindCashRow(ps, t);
                    ps.executeUpdate();
                    inserted++;
                }
            }
            log.info("Cash transactions [II]: {} inserted, {} already present (skipped)",
                    inserted, rows.size() - inserted);
            return inserted;
        } catch (Exception e) {
            throw new IllegalStateException("Could not save II cash transactions", e);
        }
    }

    /**
     * The {@code TRANSACTION} and {@code DIVIDEND} rows, oldest first — the raw material
     * for dividend attribution. Buys/sells are returned alongside dividends so the share
     * timeline can be reconstructed.
     */
    public List<CashTransaction> loadDividendTransactions() {
        List<CashTransaction> rows = new ArrayList<>();
        if (!connections.dbExists()) return rows;
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT transaction_date, account, type, symbol, quantity, amount, currency, " +
                             "fx_to_gbp, amount_gbp, cash_balance, cash_balance_gbp, description " +
                             "FROM cash_transactions WHERE type IN ('TRANSACTION', 'DIVIDEND') " +
                             "ORDER BY transaction_date, rowid")) {
            while (rs.next()) {
                rows.add(new CashTransaction(
                        rs.getString(1), Account.fromDbValue(rs.getString(2)),
                        TransactionType.valueOf(rs.getString(3)), rs.getString(4),
                        rs.getDouble(5), rs.getDouble(6), rs.getString(7), rs.getDouble(8),
                        rs.getDouble(9), getNullableDouble(rs, 10), getNullableDouble(rs, 11),
                        rs.getString(12)));
            }
        } catch (Exception e) {
            log.warn("Could not load dividend transactions", e);
        }
        return rows;
    }

    /**
     * Every symbol ever traded (buys/sells/dividends) — the price-fetch universe before
     * gilt filtering. Excludes INTEREST/CHARGE/CONTRIBUTION rows, whose symbol is not a real
     * instrument.
     */
    public List<String> distinctTradedSymbols() {
        List<String> symbols = new ArrayList<>();
        if (!connections.dbExists()) return symbols;
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT DISTINCT symbol FROM cash_transactions " +
                             "WHERE type IN ('TRANSACTION', 'DIVIDEND') ORDER BY symbol")) {
            while (rs.next()) symbols.add(rs.getString(1));
        } catch (Exception e) {
            log.warn("Could not load symbols", e);
        }
        return symbols;
    }

    // ---- helpers ------------------------------------------------------------

    private static Set<String> loadKnownDateBalanceKeys(Connection conn, Account account) throws SQLException {
        Set<String> out = new HashSet<>();
        try (PreparedStatement q = conn.prepareStatement(
                "SELECT transaction_date, cash_balance_gbp FROM cash_transactions WHERE account = ?")) {
            q.setString(1, account.dbValue());
            try (ResultSet rs = q.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1) + "|" + rs.getDouble(2));
            }
        }
        return out;
    }

    private static String rothKey(String date, String symbol, double qty, String type, double amount) {
        return date + "|" + symbol + "|" + qty + "|" + type + "|" + amount;
    }

    private static String iiKey(String date, String type, String symbol, double amount, String currency) {
        return date + "|" + type + "|" + symbol + "|" + amount + "|" + currency;
    }

    private static void bindCashRow(PreparedStatement ps, CashTransaction tx) throws SQLException {
        ps.setString(1, tx.transactionDate());
        ps.setString(2, tx.account().dbValue());
        ps.setString(3, tx.type().name());
        ps.setString(4, tx.symbol());
        ps.setDouble(5, tx.quantity());
        ps.setDouble(6, tx.amount());
        ps.setString(7, tx.currency());
        ps.setDouble(8, tx.fxToGbp());
        ps.setDouble(9, tx.amountGbp());
        setNullableDouble(ps, 10, tx.cashBalance());
        setNullableDouble(ps, 11, tx.cashBalanceGbp());
        ps.setString(12, tx.description());
    }

    private static void setNullableDouble(PreparedStatement ps, int idx, Double v) throws SQLException {
        if (v != null) ps.setDouble(idx, v);
        else ps.setNull(idx, Types.REAL);
    }

    private static Double getNullableDouble(ResultSet rs, int col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }
}
