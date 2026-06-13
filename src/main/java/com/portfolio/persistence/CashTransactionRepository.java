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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     * AJBell flow. Rows are GBP and carry a running {@code cash_balance_gbp} that AJ Bell may
     * retroactively shift when they insert a dividend mid-file. Identity is therefore
     * {@code (date, type, symbol, amount_gbp)} — the row itself, not its position. Matching
     * rows have their stored balance/description updated; new rows are inserted. Integrity
     * check: if the file's earliest row is newer than every stored AJBell row, refuse the
     * import (a window has been lost between exports).
     */
    public int saveAjBell(List<CashTransaction> transactions) {
        if (transactions.isEmpty()) return 0;
        try (Connection conn = connections.open()) {
            Account account = transactions.get(0).account();
            Map<String, StoredRow> existing = loadStoredAjBellRows(conn, account);
            String maxStoredDate = existing.values().stream()
                    .map(StoredRow::date).max(Comparator.naturalOrder()).orElse(null);

            String fileEarliest = transactions.get(0).transactionDate();
            if (maxStoredDate != null && fileEarliest.compareTo(maxStoredDate) > 0) {
                throw new IllegalStateException(String.format(
                        "Data integrity error: %s file's earliest row %s is newer than the "
                                + "latest stored row %s — a window of transactions is missing. "
                                + "Aborting import.",
                        account.dbValue(), fileEarliest, maxStoredDate));
            }

            int inserted = 0, updated = 0, unchanged = 0;
            try (PreparedStatement insert = conn.prepareStatement(INSERT_SQL);
                 PreparedStatement update = conn.prepareStatement(
                         "UPDATE cash_transactions SET cash_balance = ?, cash_balance_gbp = ?, "
                                 + "description = ? WHERE rowid = ?")) {
                for (CashTransaction tx : transactions) {
                    String key = ajBellIdentityKey(
                            tx.transactionDate(), tx.type().name(), tx.symbol(), tx.amountGbp());
                    StoredRow prior = existing.get(key);
                    if (prior == null) {
                        bindCashRow(insert, tx);
                        insert.executeUpdate();
                        inserted++;
                    } else if (balancesMatch(prior.cashBalanceGbp(), tx.cashBalanceGbp())) {
                        unchanged++;
                    } else {
                        setNullableDouble(update, 1, tx.cashBalance());
                        setNullableDouble(update, 2, tx.cashBalanceGbp());
                        update.setString(3, tx.description());
                        update.setLong(4, prior.rowid());
                        update.executeUpdate();
                        updated++;
                    }
                }
            }
            log.info("Cash transactions [{}]: {} inserted, {} balance-updated, {} unchanged",
                    account.dbValue(), inserted, updated, unchanged);
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
     * Every row in the table, oldest first — used by the historical valuation pass which
     * needs to replay the full ledger end-to-end (positions plus every cash movement).
     */
    public List<CashTransaction> loadAllTransactions() {
        List<CashTransaction> rows = new ArrayList<>();
        if (!connections.dbExists()) return rows;
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT transaction_date, account, type, symbol, quantity, amount, currency, " +
                             "fx_to_gbp, amount_gbp, cash_balance, cash_balance_gbp, description " +
                             "FROM cash_transactions ORDER BY transaction_date, rowid")) {
            while (rs.next()) {
                rows.add(new CashTransaction(
                        rs.getString(1), Account.fromDbValue(rs.getString(2)),
                        TransactionType.valueOf(rs.getString(3)), rs.getString(4),
                        rs.getDouble(5), rs.getDouble(6), rs.getString(7), rs.getDouble(8),
                        rs.getDouble(9), getNullableDouble(rs, 10), getNullableDouble(rs, 11),
                        rs.getString(12)));
            }
        } catch (Exception e) {
            log.warn("Could not load all transactions", e);
        }
        return rows;
    }

    /**
     * Every {@code TRANSACTION} (buy/sell) row, newest first, carrying its SQLite
     * {@code rowid}. The rowid is the join key into {@code trade_notes} — it survives
     * re-imports because the matching cash-import paths either UPDATE the existing row
     * (AJBell) or skip it (II / RothIRA dedup). Dividends, interest, charges and
     * contributions are excluded; they aren't decisions to annotate.
     */
    public List<TradeRow> loadTrades() {
        List<TradeRow> rows = new ArrayList<>();
        if (!connections.dbExists()) return rows;
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT rowid, transaction_date, account, symbol, quantity, amount, " +
                             "currency, amount_gbp, description " +
                             "FROM cash_transactions WHERE type = 'TRANSACTION' " +
                             "ORDER BY transaction_date DESC, rowid DESC")) {
            while (rs.next()) {
                rows.add(new TradeRow(
                        rs.getLong(1), rs.getString(2),
                        Account.fromDbValue(rs.getString(3)), rs.getString(4),
                        rs.getDouble(5), rs.getDouble(6), rs.getString(7),
                        rs.getDouble(8), rs.getString(9)));
            }
        } catch (Exception e) {
            log.warn("Could not load trades", e);
        }
        return rows;
    }

    public record TradeRow(long rowid, String transactionDate, Account account, String symbol,
                           double quantity, double amount, String currency,
                           double amountGbp, String description) {
    }

    /**
     * Every {@code CONTRIBUTION} row, oldest first — used to chart cash inflows over time.
     * Other types (TRANSACTION, DIVIDEND, INTEREST, CHARGE) are not external contributions.
     */
    public List<CashTransaction> loadContributions() {
        List<CashTransaction> rows = new ArrayList<>();
        if (!connections.dbExists()) return rows;
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT transaction_date, account, type, symbol, quantity, amount, currency, " +
                             "fx_to_gbp, amount_gbp, cash_balance, cash_balance_gbp, description " +
                             "FROM cash_transactions WHERE type = 'CONTRIBUTION' " +
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
            log.warn("Could not load contribution transactions", e);
        }
        return rows;
    }

    /**
     * Earliest stored transaction date for the given account, or null when the account is
     * empty. Used to anchor the Roth IRA's brought-forward seed on the contributions chart:
     * Roth has no CONTRIBUTION rows, but the seed entered the portfolio when the broker
     * started feeding us cash history.
     */
    public String earliestTransactionDate(Account account) {
        if (!connections.dbExists()) return null;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT MIN(transaction_date) FROM cash_transactions WHERE account = ?")) {
            ps.setString(1, account.dbValue());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            log.warn("Could not query earliest transaction date for {}", account, e);
            return null;
        }
    }

    /**
     * Latest stored cash balance per {@code (account, currency)} — the live cash position
     * derived from the running-balance column on the most recent row of each per-currency
     * ledger. AJBell has one entry (GBP); RothIRA has one (USD); II has two (GBP, USD).
     * Rows whose {@code cash_balance_gbp} is null (e.g. II's intermediate TRANSACTION rows)
     * are skipped — the matching CHARGE row carries the post-trade balance.
     */
    public List<CashBalance> latestCashBalances() {
        Map<String, CashBalance> latestByKey = new LinkedHashMap<>();
        if (!connections.dbExists()) return List.of();
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT account, currency, cash_balance, cash_balance_gbp " +
                             "FROM cash_transactions " +
                             "WHERE cash_balance_gbp IS NOT NULL " +
                             "ORDER BY transaction_date, rowid")) {
            while (rs.next()) {
                String account = rs.getString(1);
                String currency = rs.getString(2);
                Double cashNative = getNullableDouble(rs, 3);
                double cashGbp = rs.getDouble(4);
                latestByKey.put(account + "|" + currency,
                        new CashBalance(account, currency, cashNative, cashGbp));
            }
        } catch (Exception e) {
            log.warn("Could not load latest cash balances", e);
        }
        return new ArrayList<>(latestByKey.values());
    }

    public record CashBalance(String accountDbValue, String currency,
                              Double cashNative, double cashGbp) {
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

    /**
     * Load AJBell rows keyed by identity. If a prior import (under the old date/balance dedup)
     * left two rows sharing one identity, keep the most recently inserted one (highest rowid)
     * and delete the older copies — they are the stale-balance casualties of an AJ Bell
     * retroactive insertion.
     */
    private Map<String, StoredRow> loadStoredAjBellRows(Connection conn, Account account) throws SQLException {
        Map<String, StoredRow> out = new HashMap<>();
        List<Long> toDelete = new ArrayList<>();
        try (PreparedStatement q = conn.prepareStatement(
                "SELECT rowid, transaction_date, type, symbol, amount_gbp, cash_balance_gbp "
                        + "FROM cash_transactions WHERE account = ? ORDER BY rowid")) {
            q.setString(1, account.dbValue());
            try (ResultSet rs = q.executeQuery()) {
                while (rs.next()) {
                    long rowid = rs.getLong(1);
                    String date = rs.getString(2);
                    String type = rs.getString(3);
                    String symbol = rs.getString(4);
                    double amountGbp = rs.getDouble(5);
                    Double balGbp = getNullableDouble(rs, 6);
                    String key = ajBellIdentityKey(date, type, symbol, amountGbp);
                    StoredRow prior = out.put(key, new StoredRow(rowid, date, balGbp));
                    if (prior != null) {
                        log.warn("Discarding stale duplicate AJBell row (rowid={}, date={}, " +
                                "symbol={}, amount_gbp={}) — superseded by rowid={}",
                                prior.rowid(), date, symbol, amountGbp, rowid);
                        toDelete.add(prior.rowid());
                    }
                }
            }
        }
        if (!toDelete.isEmpty()) {
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM cash_transactions WHERE rowid = ?")) {
                for (long rowid : toDelete) {
                    del.setLong(1, rowid);
                    del.executeUpdate();
                }
            }
        }
        return out;
    }

    private static String ajBellIdentityKey(String date, String type, String symbol, double amountGbp) {
        return date + "|" + type + "|" + symbol + "|" + amountGbp;
    }

    private static boolean balancesMatch(Double a, Double b) {
        if (a == null || b == null) return a == b;
        return Double.compare(a, b) == 0;
    }

    private record StoredRow(long rowid, String date, Double cashBalanceGbp) {
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
