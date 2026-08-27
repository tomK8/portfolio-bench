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

    /**
     * Column and key definitions, kept apart from the {@code CREATE TABLE} preamble so the create
     * and the primary-key migration below build from <em>one</em> definition of the schema. Two
     * copies is exactly how the key drifted in the first place.
     *
     * <p><b>{@code cash_balance_gbp} is in the primary key on purpose.</b> A broker can fill one
     * order as two partial fills of the same size at the same price, which produces two rows
     * identical in every other key column — Citi did this with MRNA on 2026-08-21 (two sales of
     * 3 shares at 148.23, $444.69 each). Those are two distinct economic events and both have to
     * be storable. The running balance is what tells them apart, because it differs by
     * construction: the second fill lands on top of the first.
     */
    private static final String TABLE_BODY_TEMPLATE = """
            (
                transaction_date TEXT NOT NULL,
                account          TEXT NOT NULL CHECK (account IN (%ACCOUNTS%)),
                type             TEXT NOT NULL CHECK (type IN (%TYPES%)),
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

    /**
     * The two CHECK lists are generated from the enums rather than written out here, so adding an
     * {@link Account} or a {@link TransactionType} constant needs no SQL edit and cannot silently
     * disagree with the code that writes the column. {@link #migrateConstraints} rebuilds an
     * existing table whenever the stored DDL no longer matches what this produces.
     */
    private static final String TABLE_BODY = TABLE_BODY_TEMPLATE
            .replace("%ACCOUNTS%", sqlLiteralList(java.util.Arrays.stream(Account.values())
                    .map(Account::dbValue).toList()))
            .replace("%TYPES%", sqlLiteralList(java.util.Arrays.stream(TransactionType.values())
                    .map(Enum::name).toList()));

    /** {@code a, b, c} as {@code 'a', 'b', 'c'} — enum-derived, so no quoting hazard. */
    private static String sqlLiteralList(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) sb.append(", ");
            sb.append('\'').append(v).append('\'');
        }
        return sb.toString();
    }

    private static final String CREATE_TABLE =
            "CREATE TABLE IF NOT EXISTS cash_transactions " + TABLE_BODY;

    /**
     * Every column, named. The migration copies by name rather than positionally because a table
     * created before {@code cash_balance} was added carries it appended at the end
     * ({@code … amount_gbp, cash_balance_gbp, description, cash_balance}) rather than in the
     * position {@link #TABLE_BODY} declares — so {@code SELECT *} would silently transpose the two
     * balances into the description column.
     */
    private static final String COLUMNS =
            "transaction_date, account, type, symbol, quantity, amount, currency, " +
                    "fx_to_gbp, amount_gbp, cash_balance, cash_balance_gbp, description";

    private static final String INSERT_SQL =
            "INSERT INTO cash_transactions (" + COLUMNS + ") " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /** Tolerances for matching a re-partitioned fill group: exact share count, half a cent. */
    private static final double FILL_QTY_EPSILON = 1e-6;
    private static final double FILL_AMOUNT_EPSILON = 0.005;

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
            // After the ALTER, so the rebuilds can copy cash_balance by name.
            migratePrimaryKey(conn);
            migrateConstraints(conn);
            GiltSymbolMigration.run(conn, "cash_transactions");
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise cash_transactions table", e);
        }
    }

    /**
     * Bring a pre-existing table's primary key in line with {@link #TABLE_BODY}.
     *
     * <p>{@code cash_balance_gbp} joined the key in June 2026, but the create is
     * {@code CREATE TABLE IF NOT EXISTS} and no migration came with it — so a database created
     * before then kept the old five-column key
     * ({@code transaction_date, account, type, symbol, amount_gbp}) while every test, and every
     * fresh install, ran on the six-column one. The difference is invisible until a broker reports
     * two identical partial fills on one day, at which point the import dies on a primary-key
     * violation with the ledger's own dedup logic having done nothing wrong.
     *
     * <p>Idempotent and cheap: on an already-correct database this is a single {@code PRAGMA}
     * read and nothing else.
     */
    private static void migratePrimaryKey(Connection conn) throws SQLException {
        if (primaryKeyIncludesBalance(conn)) return;
        log.info("Migrating cash_transactions: adding cash_balance_gbp to the primary key so "
                + "identical same-day partial fills can both be stored");
        rebuildTable(conn, "six-column primary key");
    }

    /**
     * Whether {@code cash_balance_gbp} is part of the table's primary key. {@code PRAGMA
     * table_info} reports {@code pk} as the column's 1-based position within the key, or 0 when it
     * is not in it. Returns true when the table or the column is absent — there is then nothing to
     * migrate, and the create statement has already produced the right shape.
     */
    static boolean primaryKeyIncludesBalance(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(cash_transactions)")) {
            while (rs.next()) {
                if ("cash_balance_gbp".equals(rs.getString("name"))) return rs.getInt("pk") > 0;
            }
        }
        return true;
    }

    /**
     * Bring a pre-existing table's CHECK constraints in line with the {@link Account} and
     * {@link TransactionType} enums.
     *
     * <p>The lists are baked into the column definitions, and the create is
     * {@code CREATE TABLE IF NOT EXISTS} — so adding an enum constant changes what a fresh install
     * accepts while an existing database goes on rejecting it, and the first insert of the new
     * value dies on a constraint violation with nothing wrong upstream. That is exactly how the
     * primary key drifted (see {@link #migratePrimaryKey}); this closes the same gap for the one
     * other part of the schema that is derived rather than fixed.
     *
     * <p>There is no {@code PRAGMA} for CHECK expressions, so the stored DDL is read back from
     * {@code sqlite_master} and compared against what {@link #TABLE_BODY} now produces. The
     * comparison is on the constraint lists alone rather than the whole statement: SQLite stores
     * the original text verbatim, including whitespace, so comparing the full body would rebuild
     * the table on every start after any cosmetic edit.
     *
     * <p>Idempotent and cheap: on an already-correct database this is one small {@code SELECT}.
     */
    private static void migrateConstraints(Connection conn) throws SQLException {
        String stored = storedTableSql(conn);
        if (stored == null) return;
        List<String> missing = new ArrayList<>();
        for (Account a : Account.values()) {
            if (!stored.contains("'" + a.dbValue() + "'")) missing.add("account " + a.dbValue());
        }
        for (TransactionType t : TransactionType.values()) {
            if (!stored.contains("'" + t.name() + "'")) missing.add("type " + t.name());
        }
        if (missing.isEmpty()) return;
        log.info("Migrating cash_transactions: CHECK constraints predate {}", missing);
        rebuildTable(conn, "CHECK constraints for " + missing);
    }

    /** The table's stored {@code CREATE} statement, or null when the table does not exist yet. */
    private static String storedTableSql(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'cash_transactions'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /**
     * SQLite cannot alter a primary key or a CHECK constraint in place, so the table is rebuilt:
     * create, copy by name, drop, rename — all inside one transaction, which SQLite applies to DDL
     * as well, so the ledger is never observable in a half-migrated state. Shared by both
     * migrations, since the mechanics are identical and only the trigger differs.
     *
     * <p>The row count is asserted across the copy and any mismatch rolls the whole thing back.
     * The copy cannot collide on the new key: the old five-column key already made those five
     * columns unique, so no two rows can agree on all six. Nor can it violate the new CHECK lists —
     * they only ever gain members, since a constant is added to an enum rather than removed.
     */
    private static void rebuildTable(Connection conn, String reason) throws SQLException {
        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            int before = countRows(st, "cash_transactions");
            st.execute("DROP TABLE IF EXISTS cash_transactions_migrate");
            st.execute("CREATE TABLE cash_transactions_migrate " + TABLE_BODY);
            st.execute("INSERT INTO cash_transactions_migrate (" + COLUMNS + ") "
                    + "SELECT " + COLUMNS + " FROM cash_transactions");
            int after = countRows(st, "cash_transactions_migrate");
            if (after != before) {
                throw new SQLException(String.format(
                        "cash_transactions migration copied %d of %d rows — rolling back", after, before));
            }
            st.execute("DROP TABLE cash_transactions");
            st.execute("ALTER TABLE cash_transactions_migrate RENAME TO cash_transactions");
            conn.commit();
            log.info("Migrated cash_transactions ({}): {} rows preserved", reason, after);
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    private static int countRows(Statement st, String table) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : 0;
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
     * native balances null. Dedups by (date, symbol, qty, type, amount), then by
     * {@link #repartitionedFills} for the fills the broker has re-aggregated between exports —
     * the file's version of such a group supersedes the stored one, so the ledger keeps the row
     * shape the broker will keep sending. Derives the running balance from the latest stored row
     * (or {@code seed} when the account is empty) and persists the seed for traceability on a
     * first-run import.
     */
    public int saveRothIra(List<CashTransaction> rows, BigDecimal seed) {
        if (rows.isEmpty()) return 0;
        Account account = rows.get(0).account();
        try (Connection conn = connections.open()) {
            Map<String, Integer> existingKeys = new HashMap<>();
            Map<String, double[]> storedGroups = new HashMap<>();
            Double lastBalance = null;
            Double chainAnchor = null;   // the balance the ledger starts from, re-derived below
            try (PreparedStatement q = conn.prepareStatement(
                    "SELECT transaction_date, symbol, quantity, type, amount, cash_balance " +
                            "FROM cash_transactions WHERE account = ? ORDER BY transaction_date, rowid")) {
                q.setString(1, account.dbValue());
                try (ResultSet rs = q.executeQuery()) {
                    while (rs.next()) {
                        String date = rs.getString(1), symbol = rs.getString(2), type = rs.getString(4);
                        double qty = rs.getDouble(3), amount = rs.getDouble(5);
                        existingKeys.merge(rothKey(date, symbol, qty, type, amount), 1, Integer::sum);
                        accumulateFills(storedGroups, fillGroupKey(date, symbol, type), qty, amount);
                        double bal = rs.getDouble(6);
                        if (!rs.wasNull()) {
                            // balance = anchor + running sum of amounts, so the first row's own
                            // balance less its amount re-derives the anchor without the KV seed
                            // (which callers pass as zero on every import after the first).
                            if (chainAnchor == null) chainAnchor = bal - amount;
                            lastBalance = bal;
                        }
                    }
                }
            }

            boolean fromSeed = (lastBalance == null);
            double running = fromSeed ? seed.doubleValue() : lastBalance;

            List<CashTransaction> newRows = new ArrayList<>(rows.stream()
                    .filter(t -> !consumeStoredRow(existingKeys, rothKey(
                            t.transactionDate(), t.symbol(), t.quantity(), t.type().name(), t.amount())))
                    .toList());
            Set<String> repartitioned = repartitionedFills(rows, newRows, storedGroups);
            // Adopting the file's version means deleting the stored fills, which needs a usable
            // chain anchor to rebuild balances from. Without one, leave the stored fills alone and
            // simply don't re-insert — same totals, just not the broker's row shape.
            boolean adoptFileVersion = !repartitioned.isEmpty() && chainAnchor != null;
            if (!repartitioned.isEmpty() && !adoptFileVersion) {
                log.warn("Cash transactions [{}]: no usable balance chain to rebuild from; keeping the "
                        + "stored fills for {} rather than adopting the file's", account.dbValue(), repartitioned);
                newRows.removeIf(t -> repartitioned.contains(
                        fillGroupKey(t.transactionDate(), t.symbol(), t.type().name())));
            }

            // A re-partitioned group nets out unchanged by definition, so the ledger must land
            // exactly where it already was plus whatever genuinely new rows this file brought.
            double expectedFinal = adoptFileVersion
                    ? lastBalance + newRows.stream()
                    .filter(t -> !repartitioned.contains(
                            fillGroupKey(t.transactionDate(), t.symbol(), t.type().name())))
                    .mapToDouble(CashTransaction::amount).sum()
                    : 0.0;

            Collections.reverse(newRows);  // source is newest-first → make oldest-first
            newRows.sort(Comparator.comparing(CashTransaction::transactionDate));  // stable

            conn.setAutoCommit(false);
            int inserted = 0;
            int superseded = 0;
            try {
                if (adoptFileVersion) superseded = deleteFillGroups(conn, account, repartitioned);

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

                if (adoptFileVersion) {
                    // The adopted rows were appended at the tail, so they sort after anything else
                    // already stored on their date: every balance from there on has to be redone.
                    double actualFinal = rebuildBalanceChain(conn, account, chainAnchor);
                    if (Math.abs(actualFinal - expectedFinal) > FILL_AMOUNT_EPSILON) {
                        throw new IllegalStateException(String.format(
                                "Rebuilt %s balance chain ends at %.2f, expected %.2f — refusing to "
                                        + "adopt the re-partitioned fills",
                                account.dbValue(), actualFinal, expectedFinal));
                    }
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }

            if (inserted > 0 && fromSeed) {
                settings.putBigDecimal(ROTH_BROUGHT_FORWARD_KEY, seed);
            }
            log.info("Cash transactions [{}]: {} inserted, {} already present (skipped){}",
                    account.dbValue(), inserted, rows.size() - inserted,
                    superseded > 0 ? ", " + superseded + " superseded by re-partitioned fills" : "");
            return inserted;
        } catch (Exception e) {
            throw new IllegalStateException("Could not save RothIRA cash transactions", e);
        }
    }

    /** Drops every stored row in the given {@code date|symbol|type} groups. */
    private static int deleteFillGroups(Connection conn, Account account, Set<String> groups)
            throws SQLException {
        int deleted = 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM cash_transactions WHERE account = ? AND transaction_date = ? " +
                        "AND symbol = ? AND type = ?")) {
            for (String group : groups) {
                String[] parts = group.split("\\|", 3);
                ps.setString(1, account.dbValue());
                ps.setString(2, parts[0]);
                ps.setString(3, parts[1]);
                ps.setString(4, parts[2]);
                deleted += ps.executeUpdate();
            }
        }
        return deleted;
    }

    /**
     * Recomputes the account's derived running balances as {@code anchor} plus the running sum of
     * amounts in chain order, and returns the closing balance. Needed after adopting a
     * re-partitioned fill group, whose rows are appended at the tail and so land out of sequence.
     *
     * <p>{@code TRANSFER} amounts are excluded from the sum. Their magnitude is a cost basis moving
     * with shares, not cash — counting it would step the account's balance by tens of thousands of
     * pounds that never moved, and the error would then propagate to every later row in the chain.
     * {@link #latestCashBalances()} skips them for the same reason, so neither the derived chain
     * nor the reported balance can be set by a row that moves no money.
     */
    private static double rebuildBalanceChain(Connection conn, Account account, double anchor)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "WITH ordered AS (" +
                        "  SELECT rowid AS rid, " +
                        "         ? + SUM(CASE WHEN type = 'TRANSFER' THEN 0 ELSE amount END) " +
                        "               OVER (ORDER BY transaction_date, rowid) AS bal " +
                        "    FROM cash_transactions WHERE account = ?) " +
                        "UPDATE cash_transactions " +
                        "   SET cash_balance = (SELECT bal FROM ordered WHERE rid = cash_transactions.rowid), " +
                        "       cash_balance_gbp = CASE WHEN fx_to_gbp != 0 THEN " +
                        "           (SELECT bal FROM ordered WHERE rid = cash_transactions.rowid) / fx_to_gbp " +
                        "           ELSE 0 END " +
                        " WHERE account = ?")) {
            ps.setDouble(1, anchor);
            ps.setString(2, account.dbValue());
            ps.setString(3, account.dbValue());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT cash_balance FROM cash_transactions WHERE account = ? " +
                        "ORDER BY transaction_date DESC, rowid DESC LIMIT 1")) {
            ps.setString(1, account.dbValue());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : anchor;
            }
        }
    }

    /**
     * II flow. Rows arrive oldest-first with the native running balance from the file's Running
     * Balance column. Dedup key is (date, type, symbol, amount, currency) — the currency component
     * keeps the GBP and USD per-currency ledgers isolated within the shared {@code account='II'}.
     *
     * <p><b>A matched row has its stored balance refreshed rather than being skipped outright</b>,
     * the same rule {@link #saveAjBell} already applies and for the same reason: II back-dates rows
     * into a day that has already been imported, which shifts the Running Balance of everything at
     * or after it. On 2026-08-25 a gilt purchase was imported while that day's interest credit had
     * not yet posted, so the purchase stored a balance of £691.93; the interest arrived in a later
     * export, was inserted <em>after</em> it, and the purchase's balance should have become £718.38
     * but stayed stale. Identity is the row, never its position — so re-importing must carry the
     * file's newer balance onto rows it already holds.
     *
     * <p><b>It also records the file's newest balance per {@code (account, currency)}</b>, which is
     * what {@link #latestCashBalances()} then reports. That is the fix for the half of the problem
     * an UPDATE cannot reach: with the interest credit inserted after the purchase, the newest row
     * <em>by rowid</em> is the older row <em>in ledger order</em>, and no ordering over
     * {@code (transaction_date, rowid)} can tell them apart — rowid records when we imported a row,
     * not where it sits in the day. The file already knows: it is newest-first, so its first data
     * row is the account's balance. Storing that removes the guess. Left unfixed it reported II
     * holding £20,631.74 of cash against a real £718.38, overstating the whole portfolio by the
     * £19,913.36 the gilt had been bought with (2026-08-27).
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
            int refreshed = 0;
            try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL);
                 PreparedStatement upd = conn.prepareStatement(
                         "UPDATE cash_transactions SET cash_balance = ?, cash_balance_gbp = ? " +
                                 "WHERE account = 'II' AND transaction_date = ? AND type = ? " +
                                 "AND symbol = ? AND amount = ? AND currency = ? " +
                                 "AND (cash_balance_gbp IS NOT ?)")) {
                for (CashTransaction t : rows) {
                    String key = iiKey(t.transactionDate(), t.type().name(), t.symbol(),
                            t.amount(), t.currency());
                    if (existingKeys.contains(key)) {
                        if (t.cashBalanceGbp() == null) continue;
                        setNullableDouble(upd, 1, t.cashBalance());
                        setNullableDouble(upd, 2, t.cashBalanceGbp());
                        upd.setString(3, t.transactionDate());
                        upd.setString(4, t.type().name());
                        upd.setString(5, t.symbol());
                        upd.setDouble(6, t.amount());
                        upd.setString(7, t.currency());
                        setNullableDouble(upd, 8, t.cashBalanceGbp());
                        refreshed += upd.executeUpdate();
                        continue;
                    }
                    bindCashRow(ps, t);
                    ps.executeUpdate();
                    inserted++;
                }
            }
            recordLatestBalances(Account.II, rows);
            log.info("Cash transactions [II]: {} inserted, {} balance-refreshed, {} unchanged",
                    inserted, refreshed, rows.size() - inserted - refreshed);
            return inserted;
        } catch (Exception e) {
            throw new IllegalStateException("Could not save II cash transactions", e);
        }
    }

    /**
     * Remembers the closing balance the import's own file asserts, per {@code (account, currency)},
     * so {@link #latestCashBalances()} need not infer which stored row is the day's last.
     *
     * <p>Rows arrive oldest-first, so the last row carrying a balance in each currency is the one
     * the file listed first — the account's current balance. Held in the {@link KeyValueStore}
     * rather than a column because it is a fact about the account, not about any row: exactly one
     * value per {@code (account, currency)}, replaced wholesale by each import.
     */
    void recordLatestBalances(Account account, List<CashTransaction> rows) {
        if (settings == null) return;
        Map<String, CashTransaction> newestByCcy = new LinkedHashMap<>();
        for (CashTransaction t : rows) {
            if (t.cashBalanceGbp() == null) continue;
            newestByCcy.put(t.currency(), t);
        }
        for (Map.Entry<String, CashTransaction> e : newestByCcy.entrySet()) {
            CashTransaction t = e.getValue();
            settings.putBigDecimal(latestBalanceKey(account, e.getKey(), "gbp"),
                    BigDecimal.valueOf(t.cashBalanceGbp()));
            settings.putBigDecimal(latestBalanceKey(account, e.getKey(), "native"),
                    BigDecimal.valueOf(t.cashBalance() == null ? t.cashBalanceGbp() : t.cashBalance()));
        }
    }

    private static String latestBalanceKey(Account account, String currency, String leg) {
        return "cash_balance_" + account.dbValue() + "_" + currency + "_" + leg;
    }

    /**
     * The balance an importer recorded straight from its file, or null when none has been.
     * Preferred over any stored row because it needs no inference about which row is a day's last.
     */
    private CashBalance assertedBalance(String accountDbValue, String currency) {
        if (settings == null) return null;
        Account account;
        try {
            account = Account.fromDbValue(accountDbValue);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
        BigDecimal gbp = settings.getBigDecimal(latestBalanceKey(account, currency, "gbp"), null);
        if (gbp == null) return null;
        BigDecimal nativeAmt = settings.getBigDecimal(
                latestBalanceKey(account, currency, "native"), gbp);
        return new CashBalance(accountDbValue, currency, nativeAmt.doubleValue(), gbp.doubleValue());
    }

    /**
     * Insert hand-entered rows for a manual account — one with no broker export, whose ledger is
     * typed in on the Manual positions screen rather than imported from a file.
     *
     * <p>There is deliberately no dedup rule here, unlike the three broker paths. Those exist
     * because an export re-states rows the ledger already holds, and each broker re-states them
     * differently; a person filling in a form states each row once and can see what is already
     * stored. The primary key is still the backstop: entering the same buy twice on the same day
     * for the same amount is rejected by the database rather than silently doubling a position.
     *
     * <p>Balances are recomputed rather than supplied. A manual row is almost always back-dated —
     * you enter a holding you have had for years — so it lands ahead of rows already stored and
     * every balance from there on would otherwise be out of sequence. The anchor is zero because a
     * manual account's first row is its own opening balance.
     *
     * @return the account's closing cash balance after the insert
     */
    public double saveManual(Account account, List<CashTransaction> rows) {
        requireManual(account);
        if (rows.isEmpty()) return 0;
        for (CashTransaction t : rows) {
            if (t.account() != account) {
                throw new IllegalArgumentException(
                        "Row for " + t.account() + " passed to saveManual(" + account + ")");
            }
        }
        try (Connection conn = connections.open()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
                    for (CashTransaction t : rows) {
                        bindCashRow(ps, t);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                double closing = rebuildBalanceChain(conn, account, 0.0);
                conn.commit();
                log.info("Stored {} manual row(s) for {}; closing balance {}",
                        rows.size(), account.dbValue(), closing);
                return closing;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not save manual rows for " + account.dbValue() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Delete one hand-entered row by its {@code rowid} and recompute the account's balance chain.
     * Restricted to manual accounts: an imported row deleted here would simply return on the next
     * import, and the ledger has no other undo.
     *
     * @return true when a row was deleted
     */
    public boolean deleteManualRow(Account account, long rowid) {
        requireManual(account);
        try (Connection conn = connections.open()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                int deleted;
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM cash_transactions WHERE rowid = ? AND account = ?")) {
                    ps.setLong(1, rowid);
                    ps.setString(2, account.dbValue());
                    deleted = ps.executeUpdate();
                }
                if (deleted > 0) rebuildBalanceChain(conn, account, 0.0);
                conn.commit();
                return deleted > 0;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not delete manual row " + rowid + ": " + e.getMessage(), e);
        }
    }

    /**
     * Write both legs of an in-specie transfer in one transaction — the position leaving one
     * account and arriving at another, with no cash moving anywhere.
     *
     * <p><b>Both legs or neither.</b> Every consumer of a {@code TRANSFER} row assumes its pair
     * exists: the whole-ledger replays skip the pair precisely because it cancels, and a lone leg
     * would make the portfolio gain or lose a position outright. One transaction is what makes
     * that assumption safe to hold.
     *
     * <p><b>No balance-chain rebuild, and none needed.</b> A transfer moves no cash, and both
     * {@link #latestCashBalances()} and the chain rebuild exclude the type, so no stored balance
     * is affected by inserting one — however far back it is dated. That is the payoff for keeping
     * the amount out of the cash model rather than pairing it with offsetting cash rows.
     *
     * <p>The legs carry a zero {@code cash_balance}, which is a placeholder rather than a claim:
     * {@code cash_balance_gbp} is part of the primary key, and a null there would make two
     * identical transfers distinct to SQLite and silently insert the position twice. Nothing reads
     * the value — both queries that could are filtered on the type.
     *
     * @throws IllegalStateException if the pair is already stored, or the write fails
     */
    public void saveTransfer(CashTransaction out, CashTransaction in) {
        if (out.account() == in.account()) {
            throw new IllegalArgumentException("A transfer must move between two different accounts");
        }
        try (Connection conn = connections.open()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
                    bindCashRow(ps, out);
                    ps.addBatch();
                    bindCashRow(ps, in);
                    ps.addBatch();
                    ps.executeBatch();
                }
                conn.commit();
                log.info("Stored transfer of {} {} from {} to {} on {} (cost basis {})",
                        out.quantity(), out.symbol(), out.account().dbValue(),
                        in.account().dbValue(), out.transactionDate(), out.amountGbp());
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not record the transfer: " + e.getMessage(), e);
        }
    }

    /**
     * Every stored {@code TRANSFER} row, newest first, carrying its {@code rowid} so a recorded
     * transfer can be undone from the screen that wrote it.
     */
    public List<LedgerRow> loadTransferRows() {
        List<LedgerRow> rows = new ArrayList<>();
        if (!connections.dbExists()) return rows;
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT rowid, " + COLUMNS + " FROM cash_transactions " +
                             "WHERE type = 'TRANSFER' ORDER BY transaction_date DESC, rowid DESC")) {
            while (rs.next()) {
                CashTransaction t = mapCashRow(rs, 1);
                if (t != null) rows.add(new LedgerRow(rs.getLong(1), t));
            }
        } catch (Exception e) {
            log.warn("Could not load transfer rows", e);
        }
        return rows;
    }

    /**
     * Delete both legs of a recorded transfer, by rowid, in one transaction.
     *
     * <p>Unlike {@link #deleteManualRow}, this is <b>not</b> restricted to manual accounts, and it
     * is safe not to be: a {@code TRANSFER} row exists in no broker export, so no import can
     * re-create it or collide with it — the thing that makes deleting an imported row pointless.
     * The type is asserted in the statement so this can only ever remove rows this tool wrote.
     *
     * @return true when both legs were removed
     */
    public boolean deleteTransfer(long outRowid, long inRowid) {
        try (Connection conn = connections.open()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                int deleted;
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM cash_transactions WHERE rowid IN (?, ?) AND type = 'TRANSFER'")) {
                    ps.setLong(1, outRowid);
                    ps.setLong(2, inRowid);
                    deleted = ps.executeUpdate();
                }
                // Half a transfer is worse than none: it would move a position out of one account
                // without moving it into the other. Roll back rather than leave a lone leg.
                if (deleted != 2) {
                    conn.rollback();
                    log.warn("Transfer delete matched {} row(s), not 2 — rolled back", deleted);
                    return false;
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not delete the transfer: " + e.getMessage(), e);
        }
    }

    /** Every stored row for the manual accounts, oldest first, carrying its {@code rowid}. */
    public List<LedgerRow> loadManualRows() {
        List<LedgerRow> rows = new ArrayList<>();
        List<Account> manual = Account.manualAccounts();
        if (manual.isEmpty() || !connections.dbExists()) return rows;
        String placeholders = String.join(", ", Collections.nCopies(manual.size(), "?"));
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT rowid, " + COLUMNS + " FROM cash_transactions "
                             + "WHERE account IN (" + placeholders + ") "
                             + "ORDER BY transaction_date, rowid")) {
            for (int i = 0; i < manual.size(); i++) ps.setString(i + 1, manual.get(i).dbValue());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // Column 1 is the rowid, so the shared mapper's 1-based indices shift by one.
                    CashTransaction tx = mapCashRow(rs, 1);
                    if (tx != null) rows.add(new LedgerRow(rs.getLong(1), tx));
                }
            }
        } catch (Exception e) {
            log.warn("Could not load manual ledger rows", e);
        }
        return rows;
    }

    /** A stored row with the {@code rowid} needed to delete exactly it. */
    public record LedgerRow(long rowid, CashTransaction tx) {
    }

    private static void requireManual(Account account) {
        if (!account.isManual()) {
            throw new IllegalArgumentException(account.dbValue()
                    + " is an imported account — its rows come from broker exports, not by hand");
        }
    }

    /**
     * The one query-and-map path behind every {@code CashTransaction} read. {@code filter} is an
     * optional {@code WHERE} clause; the column list, the ordering and the failure behaviour are
     * shared so four near-identical copies cannot drift.
     *
     * <p>Reads degrade: a failure logs and returns whatever was mapped so far, per the
     * repository's read/write split.
     */
    private List<CashTransaction> loadRows(String filter, String label) {
        List<CashTransaction> rows = new ArrayList<>();
        if (!connections.dbExists()) return rows;
        String sql = "SELECT " + COLUMNS + " FROM cash_transactions "
                + (filter.isEmpty() ? "" : filter + " ") + "ORDER BY transaction_date, rowid";
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                CashTransaction row = mapCashRow(rs);
                if (row != null) rows.add(row);
            }
        } catch (Exception e) {
            log.warn("Could not load {}", label, e);
        }
        return rows;
    }

    /**
     * One row, or null when its {@code account} is not a known {@link Account}.
     *
     * <p><b>Skipping the row is the point.</b> This used to call {@link Account#fromDbValue}
     * inline, which throws on an unrecognised value — inside a {@code catch} that logs and returns
     * the rows gathered so far. A single unknown account therefore truncated the entire ledger at
     * that row, silently, everywhere it is read: positions vanish, totals shrink, and the only
     * evidence is one WARN. That is a whole-portfolio failure from one bad cell, so an unmappable
     * row is now dropped on its own and named in the log while the rest of the ledger loads.
     */
    private static CashTransaction mapCashRow(ResultSet rs) throws SQLException {
        return mapCashRow(rs, 0);
    }

    /** {@code offset} shifts the 1-based indices for a query that selects extra leading columns. */
    private static CashTransaction mapCashRow(ResultSet rs, int offset) throws SQLException {
        Account account = accountOrNull(rs.getString(2 + offset));
        if (account == null) return null;
        return new CashTransaction(
                rs.getString(1 + offset), account,
                TransactionType.valueOf(rs.getString(3 + offset)), rs.getString(4 + offset),
                rs.getDouble(5 + offset), rs.getDouble(6 + offset), rs.getString(7 + offset),
                rs.getDouble(8 + offset), rs.getDouble(9 + offset),
                getNullableDouble(rs, 10 + offset), getNullableDouble(rs, 11 + offset),
                rs.getString(12 + offset));
    }

    private static Account accountOrNull(String dbValue) {
        try {
            return Account.fromDbValue(dbValue);
        } catch (IllegalArgumentException unknown) {
            log.warn("Skipping cash_transactions row with unknown account '{}' — it is not one of "
                    + "{}. The rest of the ledger still loads.", dbValue, Account.values());
            return null;
        }
    }

    /**
     * The {@code TRANSACTION}, {@code DIVIDEND} and {@code TRANSFER} rows, oldest first — the raw
     * material for dividend attribution and for every per-{@code (account, symbol)} lot engine.
     * Buys/sells are returned alongside dividends so the share timeline can be reconstructed.
     *
     * <p>{@code TRANSFER} belongs here even though it is not a cash flow, because these consumers
     * track lots <em>per account</em>: after an in-specie transfer the receiving account holds the
     * shares, and without the row it would have no lots for them — its next dividend would fail to
     * attribute and the position would still read as the losing account's. Consumers that count
     * cash flows rather than shares are unaffected: {@code PositionIrr} admits only
     * {@code TRANSACTION}/{@code DIVIDEND} as flows, so a transfer correctly contributes none.
     */
    public List<CashTransaction> loadDividendTransactions() {
        return loadRows("WHERE type IN ('TRANSACTION', 'DIVIDEND', 'TRANSFER')",
                "dividend transactions");
    }

    /**
     * Every row in the table, oldest first — used by the historical valuation pass which
     * needs to replay the full ledger end-to-end (positions plus every cash movement).
     */
    public List<CashTransaction> loadAllTransactions() {
        return loadRows("", "all transactions");
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
                Account account = accountOrNull(rs.getString(3));
                if (account == null) continue;
                rows.add(new TradeRow(
                        rs.getLong(1), rs.getString(2), account, rs.getString(4),
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
        return loadRows("WHERE type = 'CONTRIBUTION'", "contribution transactions");
    }

    /**
     * Every {@code OPENING_BALANCE} row, oldest first — an account's balance on the day its
     * recorded history starts. Deliberately <em>not</em> returned by {@link #loadContributions()}:
     * both step the portfolio's value for a non-market reason and both belong in the TWR's
     * external-flow term, but only one of them is money anyone paid in. See
     * {@link TransactionType#OPENING_BALANCE}.
     */
    public List<CashTransaction> loadOpeningBalances() {
        return loadRows("WHERE type = 'OPENING_BALANCE'", "opening balances");
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
                             "WHERE cash_balance_gbp IS NOT NULL AND type != 'TRANSFER' " +
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
        // An importer that read a closing balance out of its own file wins over the row scan
        // above, which can only order by (transaction_date, rowid) and so cannot tell a
        // back-dated row from a later one inside the same day. See saveII.
        for (String key : new ArrayList<>(latestByKey.keySet())) {
            String[] parts = key.split("\\|", 2);
            CashBalance asserted = assertedBalance(parts[0], parts[1]);
            if (asserted != null) latestByKey.put(key, asserted);
        }
        return new ArrayList<>(latestByKey.values());
    }

    public record CashBalance(String accountDbValue, String currency,
                              Double cashNative, double cashGbp) {
    }

    /**
     * Every symbol ever traded, transferred in, or paid a dividend — the price-fetch universe
     * before gilt filtering. Excludes INTEREST/CHARGE/CONTRIBUTION rows, whose symbol is not a
     * real instrument. {@code TRANSFER} is included so a name that only ever arrived in-specie
     * still gets a price feed; today every such name was also bought somewhere, but relying on
     * that would make the fetch universe quietly wrong the first time it stopped being true.
     */
    public List<String> distinctTradedSymbols() {
        List<String> symbols = new ArrayList<>();
        if (!connections.dbExists()) return symbols;
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT DISTINCT symbol FROM cash_transactions " +
                             "WHERE type IN ('TRANSACTION', 'DIVIDEND', 'TRANSFER') " +
                             "ORDER BY symbol")) {
            while (rs.next()) symbols.add(rs.getString(1));
        } catch (Exception e) {
            log.warn("Could not load symbols", e);
        }
        return symbols;
    }

    /**
     * Re-key every ledger row stored under {@code from} to {@code to}, returning the number of rows
     * changed.
     *
     * <p>This is the fix-up for a description that was imported before its symbol mapping existed:
     * the parser's fallback stores the cleaned-up investment name ("PRIMARY HEALTH PROPERTIES PLC")
     * while the holdings file calls the same position "PHP", so the ledger view loses the position
     * and reconciliation reports the whole holding as a diff. It has to happen <em>in place</em>,
     * not by re-importing: the AJBell dedup key is {@code (transaction_date, type, symbol,
     * amount_gbp)}, so leaving the old rows and re-importing the statement would insert a second
     * set under the new symbol and double-count the position.
     *
     * <p>A dashboard-triggered write, so it throws on failure.
     */
    public int renameSymbol(String from, String to) {
        if (from == null || to == null || from.isBlank() || to.isBlank() || from.equals(to)) return 0;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE cash_transactions SET symbol = ? WHERE symbol = ?")) {
            ps.setString(1, to);
            ps.setString(2, from);
            int updated = ps.executeUpdate();
            if (updated > 0) log.info("Re-keyed {} ledger row(s) from \"{}\" to \"{}\"", updated, from, to);
            return updated;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not re-key ledger rows from \"" + from + "\" to \"" + to + "\".", e);
        }
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

    /**
     * Match one file row against the stored rows and <em>consume</em> the match — the dedup is a
     * multiset, not a set. Returns true when a stored row was available to account for this one.
     *
     * <p>Counting matters because identical rows are real: a broker can fill one order as two
     * partial fills of the same size at the same price (Citi, MRNA, 2026-08-21). With plain set
     * membership the <em>n</em>-th identical file row matched the same single key as the first, so
     * a genuinely new repeat of an already-stored fill was silently skipped. That is the case the
     * group-level {@link #repartitionedFills} guard deliberately does not cover — a group whose net
     * quantity and amount have changed is real activity, and is meant to fall through to row-by-row
     * dedup, which then has to actually be right. Two shares and a few hundred dollars disappearing
     * with no warning is the same failure shape as the re-partitioned-fill bug that overstated cash
     * by $7,350 two days earlier.
     */
    private static boolean consumeStoredRow(Map<String, Integer> remaining, String key) {
        Integer count = remaining.get(key);
        if (count == null || count == 0) return false;
        if (count == 1) {
            remaining.remove(key);
        } else {
            remaining.put(key, count - 1);
        }
        return true;
    }

    private static String rothKey(String date, String symbol, double qty, String type, double amount) {
        return date + "|" + symbol + "|" + qty + "|" + type + "|" + amount;
    }

    /**
     * The {@code (date, symbol, type)} groups in which this file merely re-partitions fills the
     * ledger already holds: the broker collapsing two partial fills into one aggregated row, or
     * splitting one back out. The signature is that the rows no longer match one-for-one but the
     * group's net quantity and net amount are unchanged — nothing economic happened, so the
     * unmatched rows must not be inserted.
     *
     * <p>RothIRA did exactly this on 2026-08-19, re-exporting a 37+13 MRNA sale as a single 50.
     * The row-level dedup key (date, symbol, qty, type, amount) matched neither stored fill, so
     * the aggregate went in as a *third* sale: 100 shares disposed of instead of 50, the position
     * zeroed and USD cash overstated by the sale proceeds.
     *
     * <p>A group whose totals genuinely changed — a further trade in the same name later the same
     * day — is deliberately left alone and dedups row by row as before.
     */
    private static Set<String> repartitionedFills(List<CashTransaction> fileRows,
                                                  List<CashTransaction> unmatched,
                                                  Map<String, double[]> storedGroups) {
        Set<String> candidates = new HashSet<>();
        for (CashTransaction t : unmatched) {
            candidates.add(fillGroupKey(t.transactionDate(), t.symbol(), t.type().name()));
        }
        if (candidates.isEmpty()) return Set.of();

        Map<String, double[]> fileGroups = new HashMap<>();
        for (CashTransaction t : fileRows) {
            String key = fillGroupKey(t.transactionDate(), t.symbol(), t.type().name());
            if (candidates.contains(key)) accumulateFills(fileGroups, key, t.quantity(), t.amount());
        }

        Set<String> repartitioned = new HashSet<>();
        for (String key : candidates) {
            double[] stored = storedGroups.get(key);
            if (stored == null) continue;   // nothing held for that day/name — an ordinary new row
            double[] fromFile = fileGroups.get(key);
            if (Math.abs(stored[0] - fromFile[0]) > FILL_QTY_EPSILON) continue;
            if (Math.abs(stored[1] - fromFile[1]) > FILL_AMOUNT_EPSILON) continue;
            repartitioned.add(key);
            log.info("Cash transactions [RothIRA]: broker re-partitioned the fills on {} — {} row(s) "
                            + "held, {} in file, net quantity {} / amount {} unchanged; adopting "
                            + "the file's version",
                    key, (int) stored[2], (int) fromFile[2], fromFile[0], fromFile[1]);
        }
        return repartitioned;
    }

    private static void accumulateFills(Map<String, double[]> groups, String key,
                                        double qty, double amount) {
        double[] acc = groups.computeIfAbsent(key, k -> new double[3]);
        acc[0] += qty;
        acc[1] += amount;
        acc[2]++;
    }

    private static String fillGroupKey(String date, String symbol, String type) {
        return date + "|" + symbol + "|" + type;
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
