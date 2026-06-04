package com.portfolio;

import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.PriceBar;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

public class PortfolioDatabase {

    private static final Path DEFAULT_DIR =
            Path.of(System.getProperty("user.home"), "Documents", "Investing");
    private static final String CREATE_SNAPSHOTS_TABLE = """
            CREATE TABLE IF NOT EXISTS portfolio_snapshots (
                snapshot_date      INTEGER PRIMARY KEY,
                snapshot_date_text TEXT    NOT NULL,
                total_value_gbp    REAL    NOT NULL,
                total_gain_gbp     REAL,
                total_cash_gbp     REAL,
                return_pct         REAL,
                total_return       REAL,
                gbpusd             REAL,
                gbpeur             REAL
            )""";
    private static final String CREATE_CASH_TABLE = """
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
    private static final String CREATE_PRICE_TABLE = """
            CREATE TABLE IF NOT EXISTS price_history (
                symbol     TEXT    NOT NULL,
                date       TEXT    NOT NULL,
                open       REAL,
                high       REAL,
                low        REAL,
                close      REAL    NOT NULL,
                adj_close  REAL    NOT NULL,
                volume     INTEGER,
                currency   TEXT    NOT NULL,
                fetched_at TEXT    NOT NULL,
                PRIMARY KEY (symbol, date)
            )""";
    private static final String CREATE_PRICE_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_price_symbol_date
                ON price_history(symbol, date DESC)""";
    private static final String INSERT_CASH_SQL =
            "INSERT INTO cash_transactions " +
                    "(transaction_date, account, type, symbol, quantity, amount, currency, " +
                    " fx_to_gbp, amount_gbp, cash_balance, cash_balance_gbp, description) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    public final Path dbDir;
    public final Path dbPath;

    // ---- DDL ----------------------------------------------------------------
    public final Path lastIiCashFile;
    public final Path rothBroughtForwardFile;

    public PortfolioDatabase() {
        this(DEFAULT_DIR);
    }

    // ---- Public API ---------------------------------------------------------

    public PortfolioDatabase(Path dbDir) {
        this.dbDir = dbDir;
        this.dbPath = dbDir.resolve("portfolio.db");
        this.lastIiCashFile = dbDir.resolve("ii_sipp_cash_last.txt");
        this.rothBroughtForwardFile = dbDir.resolve("roth_balance_brought_forward.txt");
    }

    private static String rothKey(String date, String symbol, double qty, String type, double amount) {
        return date + "|" + symbol + "|" + qty + "|" + type + "|" + amount;
    }

    private static void bindCashRow(PreparedStatement ps, CashTransaction tx) throws SQLException {
        ps.setString(1, tx.transactionDate());
        ps.setString(2, tx.account());
        ps.setString(3, tx.type());
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

    /**
     * Creates the cash table if absent and migrates pre-existing tables by adding the
     * native-currency {@code cash_balance} column, backfilling it from the GBP balance
     * for rows already stored in GBP (AJBell). The ALTER fails harmlessly once the
     * column exists, which is also when the one-time backfill has already run.
     */
    private static void ensureCashTable(Statement ddl) throws SQLException {
        ddl.execute(CREATE_CASH_TABLE);
        try {
            ddl.execute("ALTER TABLE cash_transactions ADD COLUMN cash_balance REAL");
            ddl.execute("UPDATE cash_transactions SET cash_balance = cash_balance_gbp " +
                    "WHERE cash_balance IS NULL AND currency = 'GBP'");
        } catch (SQLException alreadyMigrated) {
            // cash_balance column already present — nothing to do
        }
    }

    /**
     * Last II SIPP cash balance entered by the user, or zero if none saved yet.
     */
    public BigDecimal loadLastIiSippCash() {
        try {
            if (Files.exists(lastIiCashFile)) {
                String saved = Files.readString(lastIiCashFile).trim();
                if (!saved.isEmpty()) return new BigDecimal(saved);
            }
        } catch (IOException | NumberFormatException ignored) {
        }
        return BigDecimal.ZERO;
    }

    public void saveLastIiSippCash(BigDecimal value) {
        try {
            Files.createDirectories(dbDir);
            Files.writeString(lastIiCashFile, value.toPlainString());
        } catch (IOException ignored) {
        }
    }

    public void saveSnapshot(BigDecimal totalGbp, BigDecimal totalGainGbp,
                             BigDecimal totalCashGbp, BigDecimal returnPct,
                             BigDecimal totalReturn, Map<String, BigDecimal> gbpRates) {
        long snapshotDate = Instant.now().getEpochSecond();
        String snapshotDateText = LocalDate.now().toString();

        double gbpusd = gbpRates.getOrDefault("USD", BigDecimal.ZERO).doubleValue();
        double gbpeur = gbpRates.getOrDefault("EUR", BigDecimal.ZERO).doubleValue();

        try {
            Files.createDirectories(dbDir);
            try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                 Statement ddl = conn.createStatement()) {

                ddl.execute(CREATE_SNAPSHOTS_TABLE);

                for (String col : List.of(
                        "gbpusd REAL", "gbpeur REAL", "total_gain_gbp REAL",
                        "total_cash_gbp REAL", "return_pct REAL", "total_return REAL")) {
                    try {
                        ddl.execute("ALTER TABLE portfolio_snapshots ADD COLUMN " + col);
                    } catch (SQLException ignored) {
                    }
                }

                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM portfolio_snapshots WHERE snapshot_date_text = ?")) {
                    del.setString(1, snapshotDateText);
                    del.executeUpdate();
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO portfolio_snapshots " +
                                "(snapshot_date, snapshot_date_text, total_value_gbp, " +
                                " total_gain_gbp, total_cash_gbp, return_pct, total_return, gbpusd, gbpeur) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setLong(1, snapshotDate);
                    ps.setString(2, snapshotDateText);
                    ps.setDouble(3, totalGbp.setScale(2, RoundingMode.HALF_UP).doubleValue());
                    ps.setDouble(4, totalGainGbp.setScale(2, RoundingMode.HALF_UP).doubleValue());
                    ps.setDouble(5, totalCashGbp.setScale(2, RoundingMode.HALF_UP).doubleValue());
                    ps.setDouble(6, returnPct.setScale(6, RoundingMode.HALF_UP).doubleValue());
                    ps.setDouble(7, totalReturn.setScale(6, RoundingMode.HALF_UP).doubleValue());
                    ps.setDouble(8, gbpusd);
                    ps.setDouble(9, gbpeur);
                    ps.executeUpdate();
                }
            }
            System.out.printf("Snapshot saved to DB: %s → £%,.2f  gain £%,.2f  return %.2f%%%n",
                    snapshotDateText, totalGbp.doubleValue(),
                    totalGainGbp.doubleValue(), returnPct.multiply(BigDecimal.valueOf(100)).doubleValue());

        } catch (IOException | SQLException e) {
            System.err.println("Warning: could not save DB snapshot — " + e.getMessage());
        }
    }

    /**
     * The {@code TRANSACTION} and {@code DIVIDEND} rows of {@code cash_transactions}, oldest
     * first — the raw material for {@link com.portfolio.domain.DividendAttributor}, which works
     * out how much of each symbol's dividends belongs to the shares still held. Buys/sells are
     * needed alongside dividends so the share timeline can be reconstructed.
     */
    public List<CashTransaction> loadDividendTransactions() {
        List<CashTransaction> rows = new ArrayList<>();
        if (!Files.exists(dbPath)) return rows;
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement st = conn.createStatement()) {
            st.execute(CREATE_CASH_TABLE);
            try (var rs = st.executeQuery(
                    "SELECT transaction_date, account, type, symbol, quantity, amount, currency, " +
                            "fx_to_gbp, amount_gbp, cash_balance, cash_balance_gbp, description " +
                            "FROM cash_transactions WHERE type IN ('TRANSACTION', 'DIVIDEND') " +
                            "ORDER BY transaction_date, rowid")) {
                while (rs.next()) {
                    rows.add(new CashTransaction(
                            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getDouble(5), rs.getDouble(6), rs.getString(7), rs.getDouble(8),
                            rs.getDouble(9), getNullableDouble(rs, 10), getNullableDouble(rs, 11),
                            rs.getString(12)));
                }
            }
        } catch (SQLException e) {
            System.err.println("Warning: could not load dividend transactions — " + e.getMessage());
        }
        return rows;
    }

    private static Double getNullableDouble(ResultSet rs, int col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    // ---- Shared cash-table helpers ------------------------------------------

    public int saveCashTransactions(List<CashTransaction> transactions) {
        if (transactions.isEmpty()) return 0;
        try {
            Files.createDirectories(dbDir);
            try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                 Statement ddl = conn.createStatement()) {
                ensureCashTable(ddl);

                String account = transactions.get(0).account();

                Set<String> existingKeys = new HashSet<>();
                try (PreparedStatement q = conn.prepareStatement(
                        "SELECT transaction_date, cash_balance_gbp FROM cash_transactions WHERE account = ?")) {
                    q.setString(1, account);
                    try (var rs = q.executeQuery()) {
                        while (rs.next())
                            existingKeys.add(rs.getString(1) + "|" + rs.getDouble(2));
                    }
                }

                int inserted = 0, skipped = 0;
                boolean seenNewRow = false;

                try (PreparedStatement ps = conn.prepareStatement(INSERT_CASH_SQL)) {
                    for (CashTransaction tx : transactions) {
                        Double bal = tx.cashBalanceGbp();
                        boolean known = bal != null &&
                                existingKeys.contains(tx.transactionDate() + "|" + bal);

                        if (known) {
                            if (seenNewRow) {
                                System.err.printf(
                                        "%n!!! DATA INTEGRITY ERROR: %s balance %.2f on %s already exists in DB " +
                                                "but appears after new rows — possible gap or corrupt input file. Aborting import.%n",
                                        account, bal, tx.transactionDate());
                                return 0;
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
                System.out.printf("Cash transactions [%s]: %d inserted, %d already present (skipped)%n",
                        account, inserted, skipped);
                return inserted;
            }
        } catch (IOException | SQLException e) {
            System.err.println("Warning: could not save cash transactions — " + e.getMessage());
        }
        return 0;
    }

    /**
     * RothIRA opening balance (before the earliest transaction), or zero if none saved.
     */
    public BigDecimal loadRothBroughtForward() {
        try {
            if (Files.exists(rothBroughtForwardFile)) {
                String saved = Files.readString(rothBroughtForwardFile).trim();
                if (!saved.isEmpty()) return new BigDecimal(saved);
            }
        } catch (IOException | NumberFormatException ignored) {
        }
        return BigDecimal.ZERO;
    }

    public void saveRothBroughtForward(BigDecimal value) {
        try {
            Files.createDirectories(dbDir);
            Files.writeString(rothBroughtForwardFile, value.toPlainString());
        } catch (IOException ignored) {
        }
    }

    /**
     * Saves RothIRA cash rows (native USD). Rows arrive with {@code fxToGbp} and
     * {@code amountGbp} already resolved from the historical rate for their date; this
     * method dedups on (date, symbol, quantity, type, amount), derives the running native
     * balance — continuing from the latest stored row, or from {@code seed} (the opening
     * balance before the earliest transaction) when the account is still empty — converts
     * each balance to GBP, and inserts the new rows in chronological order.
     */
    public int saveRothIraCashTransactions(List<CashTransaction> rows, BigDecimal seed) {
        if (rows.isEmpty()) return 0;
        String account = rows.get(0).account();
        try {
            Files.createDirectories(dbDir);
            try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                 Statement ddl = conn.createStatement()) {
                ensureCashTable(ddl);

                Set<String> existingKeys = new HashSet<>();
                Double lastBalance = null;
                try (PreparedStatement q = conn.prepareStatement(
                        "SELECT transaction_date, symbol, quantity, type, amount, cash_balance " +
                                "FROM cash_transactions WHERE account = ? ORDER BY transaction_date, rowid")) {
                    q.setString(1, account);
                    try (var rs = q.executeQuery()) {
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
                                t.transactionDate(), t.symbol(), t.quantity(), t.type(), t.amount())))
                        .toList());
                Collections.reverse(newRows);  // source is newest-first → make oldest-first
                newRows.sort(Comparator.comparing(CashTransaction::transactionDate));  // stable

                int inserted = 0;
                try (PreparedStatement ps = conn.prepareStatement(INSERT_CASH_SQL)) {
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

                if (inserted > 0 && fromSeed) saveRothBroughtForward(seed);
                System.out.printf("Cash transactions [%s]: %d inserted, %d already present (skipped)%n",
                        account, inserted, rows.size() - inserted);
                return inserted;
            }
        } catch (IOException | SQLException e) {
            System.err.println("Warning: could not save RothIRA cash transactions — " + e.getMessage());
        }
        return 0;
    }

    /**
     * Saves II cash rows. The parser already populated {@code cashBalance}/{@code cashBalanceGbp}
     * from the file's per-currency Running Balance column, so this method only dedups and inserts.
     * Dedup key: {@code (transaction_date, type, symbol, amount, currency)} within
     * {@code account='II'} — the currency component keeps the GBP and USD ledgers isolated even
     * though they share an account.
     */
    public int saveIiCashTransactions(List<CashTransaction> rows) {
        if (rows.isEmpty()) return 0;
        try {
            Files.createDirectories(dbDir);
            try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                 Statement ddl = conn.createStatement()) {
                ensureCashTable(ddl);

                Set<String> existingKeys = new HashSet<>();
                try (PreparedStatement q = conn.prepareStatement(
                        "SELECT transaction_date, type, symbol, amount, currency " +
                                "FROM cash_transactions WHERE account = 'II'")) {
                    try (var rs = q.executeQuery()) {
                        while (rs.next()) {
                            existingKeys.add(iiKey(rs.getString(1), rs.getString(2),
                                    rs.getString(3), rs.getDouble(4), rs.getString(5)));
                        }
                    }
                }

                int inserted = 0;
                try (PreparedStatement ps = conn.prepareStatement(INSERT_CASH_SQL)) {
                    for (CashTransaction t : rows) {
                        String key = iiKey(t.transactionDate(), t.type(), t.symbol(),
                                t.amount(), t.currency());
                        if (existingKeys.contains(key)) continue;
                        bindCashRow(ps, t);
                        ps.executeUpdate();
                        inserted++;
                    }
                }
                System.out.printf("Cash transactions [II]: %d inserted, %d already present (skipped)%n",
                        inserted, rows.size() - inserted);
                return inserted;
            }
        } catch (IOException | SQLException e) {
            System.err.println("Warning: could not save II cash transactions — " + e.getMessage());
        }
        return 0;
    }

    private static String iiKey(String date, String type, String symbol, double amount, String currency) {
        return date + "|" + type + "|" + symbol + "|" + amount + "|" + currency;
    }

    // ---- Price history ------------------------------------------------------

    private static void ensurePriceTable(Statement ddl) throws SQLException {
        ddl.execute(CREATE_PRICE_TABLE);
        ddl.execute(CREATE_PRICE_INDEX);
    }

    /**
     * Every security ever traded (buys/sells/dividends) — the fetch universe before gilt filtering.
     * Excludes INTEREST/CHARGE/CONTRIBUTION rows, whose {@code symbol} is not a real instrument.
     */
    public List<String> distinctTradedSymbols() {
        List<String> symbols = new ArrayList<>();
        if (!Files.exists(dbPath)) return symbols;
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement st = conn.createStatement()) {
            st.execute(CREATE_CASH_TABLE);
            try (var rs = st.executeQuery(
                    "SELECT DISTINCT symbol FROM cash_transactions " +
                            "WHERE type IN ('TRANSACTION', 'DIVIDEND') ORDER BY symbol")) {
                while (rs.next()) symbols.add(rs.getString(1));
            }
        } catch (SQLException e) {
            System.err.println("Warning: could not load symbols — " + e.getMessage());
        }
        return symbols;
    }

    /** Idempotent insert (INSERT OR IGNORE on the (symbol, date) PK). Returns rows actually written. */
    public int savePriceBars(List<PriceBar> bars) {
        if (bars.isEmpty()) return 0;
        String fetchedAt = Instant.now().toString();
        try {
            Files.createDirectories(dbDir);
            try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                 Statement ddl = conn.createStatement()) {
                ensurePriceTable(ddl);
                int inserted = 0;
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR IGNORE INTO price_history " +
                                "(symbol, date, open, high, low, close, adj_close, volume, currency, fetched_at) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    for (PriceBar b : bars) {
                        ps.setString(1, b.symbol());
                        ps.setString(2, b.date().toString());
                        setNullableDouble(ps, 3, b.open());
                        setNullableDouble(ps, 4, b.high());
                        setNullableDouble(ps, 5, b.low());
                        ps.setDouble(6, b.close());
                        ps.setDouble(7, b.adjClose());
                        if (b.volume() != null) ps.setLong(8, b.volume());
                        else ps.setNull(8, Types.INTEGER);
                        ps.setString(9, b.currency());
                        ps.setString(10, fetchedAt);
                        inserted += ps.executeUpdate();
                    }
                }
                return inserted;
            }
        } catch (IOException | SQLException e) {
            System.err.println("Warning: could not save price bars — " + e.getMessage());
        }
        return 0;
    }

    /** Most recent stored date for a symbol, or null if none. */
    public LocalDate getLatestPriceDate(String symbol) {
        if (!Files.exists(dbPath)) return null;
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement ddl = conn.createStatement()) {
            ensurePriceTable(ddl);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT MAX(date) FROM price_history WHERE symbol = ?")) {  // ISO text → lexical = chrono
                ps.setString(1, symbol);
                try (var rs = ps.executeQuery()) {
                    String d = rs.next() ? rs.getString(1) : null;
                    return d == null ? null : LocalDate.parse(d);
                }
            }
        } catch (SQLException e) {
            System.err.println("Warning: could not read latest price date — " + e.getMessage());
        }
        return null;
    }

    /** Closest bar on or before {@code date}, or null. */
    public PriceBar getPriceOn(String symbol, LocalDate date) {
        return queryBars("SELECT symbol, date, open, high, low, close, adj_close, volume, currency " +
                "FROM price_history WHERE symbol = ? AND date <= ? ORDER BY date DESC LIMIT 1",
                symbol, date.toString(), null).stream().findFirst().orElse(null);
    }

    public List<PriceBar> getPriceHistory(String symbol, LocalDate from, LocalDate to) {
        return queryBars("SELECT symbol, date, open, high, low, close, adj_close, volume, currency " +
                "FROM price_history WHERE symbol = ? AND date BETWEEN ? AND ? ORDER BY date",
                symbol, from.toString(), to.toString());
    }

    private List<PriceBar> queryBars(String sql, String symbol, String a, String b) {
        List<PriceBar> out = new ArrayList<>();
        if (!Files.exists(dbPath)) return out;
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement ddl = conn.createStatement()) {
            ensurePriceTable(ddl);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, symbol);
                ps.setString(2, a);
                if (b != null) ps.setString(3, b);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String sym = rs.getString(1);
                        LocalDate d = LocalDate.parse(rs.getString(2));
                        Double open = getNullableDouble(rs, 3);
                        Double high = getNullableDouble(rs, 4);
                        Double low = getNullableDouble(rs, 5);
                        double close = rs.getDouble(6);
                        double adjClose = rs.getDouble(7);
                        long vol = rs.getLong(8);
                        Long volume = rs.wasNull() ? null : vol;   // wasNull reflects the column just read
                        out.add(new PriceBar(sym, d, open, high, low, close, adjClose,
                                volume, rs.getString(9)));
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Warning: could not read price history — " + e.getMessage());
        }
        return out;
    }
}
