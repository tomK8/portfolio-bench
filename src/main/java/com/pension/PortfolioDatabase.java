package com.pension;

import com.pension.model.CashTransaction;
import com.pension.model.DividendEntry;

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

    public final Path dbDir;
    public final Path dbPath;
    public final Path lastIiCashFile;

    public PortfolioDatabase() { this(DEFAULT_DIR); }

    public PortfolioDatabase(Path dbDir) {
        this.dbDir         = dbDir;
        this.dbPath        = dbDir.resolve("portfolio.db");
        this.lastIiCashFile = dbDir.resolve("ii_sipp_cash_last.txt");
    }

    // ---- DDL ----------------------------------------------------------------

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

    private static final String CREATE_DIVIDEND_TABLE = """
            CREATE TABLE IF NOT EXISTS dividend_events (
                payment_date     TEXT NOT NULL,
                account          TEXT NOT NULL CHECK (account IN ('AJBELL', 'II', 'ROTH_IRA')),
                symbol           TEXT NOT NULL,
                currency         TEXT NOT NULL CHECK (currency IN ('GBP', 'USD', 'EUR')),
                dividend_amount  REAL NOT NULL,
                fx_to_gbp        REAL NOT NULL,
                dividend_gbp     REAL NOT NULL,
                PRIMARY KEY (payment_date, account, symbol, currency)
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
                cash_balance_gbp REAL,
                description      TEXT,
                PRIMARY KEY (transaction_date, account, type, symbol, amount_gbp, cash_balance_gbp)
            )""";

    // ---- Public API ---------------------------------------------------------

    public void saveSnapshot(BigDecimal totalGbp, BigDecimal totalGainGbp,
                             BigDecimal totalCashGbp, BigDecimal returnPct,
                             BigDecimal totalReturn, Map<String, BigDecimal> gbpRates) {
        long   snapshotDate     = Instant.now().getEpochSecond();
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
                    try { ddl.execute("ALTER TABLE portfolio_snapshots ADD COLUMN " + col); }
                    catch (SQLException ignored) {}
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
                    ps.setLong(1,   snapshotDate);
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

    public Map<String, BigDecimal> loadDividendsBySymbol() {
        Map<String, BigDecimal> result = new HashMap<>();
        if (!Files.exists(dbPath)) return result;
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement st = conn.createStatement()) {
            st.execute(CREATE_DIVIDEND_TABLE);
            try (var rs = st.executeQuery(
                    "SELECT symbol, SUM(dividend_gbp) FROM dividend_events GROUP BY symbol")) {
                while (rs.next())
                    result.put(rs.getString(1).toUpperCase(), BigDecimal.valueOf(rs.getDouble(2)));
            }
        } catch (SQLException e) {
            System.err.println("Warning: could not load dividends — " + e.getMessage());
        }
        return result;
    }

    public void saveDividends(List<DividendEntry> entries) {
        try {
            Files.createDirectories(dbDir);
            try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                 Statement ddl = conn.createStatement()) {

                ddl.execute(CREATE_DIVIDEND_TABLE);

                int saved = 0;
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR REPLACE INTO dividend_events " +
                        "(payment_date, account, symbol, currency, dividend_amount, fx_to_gbp, dividend_gbp) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    for (DividendEntry d : entries) {
                        ps.setString(1, d.paymentDate());
                        ps.setString(2, d.account());
                        ps.setString(3, d.symbol());
                        ps.setString(4, d.currency());
                        ps.setDouble(5, d.amount());
                        ps.setDouble(6, d.fxToGbp());
                        ps.setDouble(7, d.amountGbp());
                        ps.executeUpdate();
                        saved++;
                    }
                }
                System.out.printf("Dividends saved: %d entr%s%n", saved, saved == 1 ? "y" : "ies");
            }
        } catch (IOException | SQLException e) {
            System.err.println("Warning: could not save dividends — " + e.getMessage());
        }
    }

    public int saveCashTransactions(List<CashTransaction> transactions) {
        if (transactions.isEmpty()) return 0;
        try {
            Files.createDirectories(dbDir);
            try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                 Statement ddl = conn.createStatement()) {
                ddl.execute(CREATE_CASH_TABLE);

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

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO cash_transactions " +
                        "(transaction_date, account, type, symbol, quantity, amount, currency, " +
                        " fx_to_gbp, amount_gbp, cash_balance_gbp, description) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
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
                            ps.setString(1, tx.transactionDate());
                            ps.setString(2, tx.account());
                            ps.setString(3, tx.type());
                            ps.setString(4, tx.symbol());
                            ps.setDouble(5, tx.quantity());
                            ps.setDouble(6, tx.amount());
                            ps.setString(7, tx.currency());
                            ps.setDouble(8, tx.fxToGbp());
                            ps.setDouble(9, tx.amountGbp());
                            if (bal != null) ps.setDouble(10, bal);
                            else             ps.setNull(10, java.sql.Types.REAL);
                            ps.setString(11, tx.description());
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
}
