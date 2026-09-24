package com.portfolio.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The volatility-trading watchlist: user-entered symbols (owned or not) each carrying its own
 * two alert thresholds, grouped into one or more named lists (chips, banks, …).
 *
 * <p><b>Three tables, and the split is the point.</b> {@code watchlist} is the symbol universe
 * and the <em>only</em> home of the alert settings — one row per symbol, however many lists it
 * appears in. {@code watchlist_list} names the lists; {@code watchlist_member} says which
 * symbols are in which, and carries the per-list manual order. So a name can sit in both
 * {@code chips} and {@code ai} with one set of triggers: editing its Move % in either place
 * edits the one row, and {@link com.portfolio.application.WatchlistAlertJob} — which dedups on
 * {@code SYMBOL|TRIGGER|date} and knows nothing about lists — cannot email it twice.
 *
 * <p><b>Two invariants tie the tables together</b>, both enforced here rather than by a foreign
 * key: every member row names a symbol that has a {@code watchlist} row, and every
 * {@code watchlist} row is a member of at least one list. Removing a symbol from its last list
 * therefore deletes it outright — an orphan would otherwise keep drawing daily prices and a
 * fundamentals refresh for ever, invisible to the screen that could delete it.
 *
 * <p>The symbol set is <em>mirrored</em> into {@link KeyValueStore} under
 * {@link #WATCHLIST_SYMBOLS_KEY} on every mutation. The price-fetch jobs and the fundamentals
 * job already read held symbols from the KV store; mirroring lets them union the watchlist in
 * without threading this repository through their constructors — so a freshly added name gets
 * daily prices, intraday quotes and a fundamentals row on the next tick. Because the mirror is
 * taken from {@code watchlist}, which is the union across lists by construction, those two
 * consumers needed no change when lists arrived.
 *
 * <p>Write paths that the dashboard triggers ({@link #add}, {@link #remove},
 * {@link #updateThresholds}) throw {@link IllegalStateException} on failure so the
 * {@code @ExceptionHandler} can surface it; the {@link #loadAll} read degrades to empty.
 */
public class WatchlistRepository {

    private static final Logger log = LoggerFactory.getLogger(WatchlistRepository.class);

    /** KV key holding the newline-separated watchlist symbol set (mirror of this table). */
    public static final String WATCHLIST_SYMBOLS_KEY = "watchlist_symbols";

    /**
     * Stored threshold meaning "no alert for this trigger". A blank/absent threshold is
     * persisted as this sentinel; {@link com.portfolio.domain.WatchlistAlerts} treats any
     * non-positive threshold as disabled. So a symbol can sit on the screen for monitoring
     * without ever emailing.
     */
    private static final BigDecimal DISABLED = BigDecimal.ZERO;

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS watchlist (
                symbol             TEXT    PRIMARY KEY,
                high_threshold_pct REAL    NOT NULL,
                move_threshold_pct REAL    NOT NULL,
                added_at           TEXT    NOT NULL,
                display_order      INTEGER,
                price_above        REAL,
                price_below        REAL
            )""";

    private static final String CREATE_LIST_TABLE = """
            CREATE TABLE IF NOT EXISTS watchlist_list (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                name          TEXT    NOT NULL,
                display_order INTEGER NOT NULL,
                created_at    TEXT    NOT NULL
            )""";

    /**
     * {@code COLLATE NOCASE} so "Chips" and "chips" collide: the name is the tab label, and two
     * tabs a user cannot tell apart are worse than a rejected rename.
     */
    private static final String CREATE_LIST_NAME_INDEX =
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_watchlist_list_name ON watchlist_list (name COLLATE NOCASE)";

    private static final String CREATE_MEMBER_TABLE = """
            CREATE TABLE IF NOT EXISTS watchlist_member (
                list_id       INTEGER NOT NULL,
                symbol        TEXT    NOT NULL,
                display_order INTEGER,
                PRIMARY KEY (list_id, symbol)
            )""";

    /**
     * The columns one list shows, in order. Deliberately opaque: a {@code col_key} is either a
     * built-in ({@code price}, {@code vol30d}, …) or a fundamentals field prefixed {@code f:},
     * and the client owns that vocabulary. Storing it as text means adding a metric to the
     * picker needs no migration here — the same bargain {@code fundamentals_snapshot.extra_json}
     * already strikes.
     *
     * <p><b>No rows means the default set</b>, not "no columns". That is what makes an existing
     * list and a brand-new one render identically without writing a row for either.
     */
    private static final String CREATE_COLUMN_TABLE = """
            CREATE TABLE IF NOT EXISTS watchlist_column (
                list_id       INTEGER NOT NULL,
                col_key       TEXT    NOT NULL,
                display_order INTEGER NOT NULL,
                PRIMARY KEY (list_id, col_key)
            )""";

    private static final String CREATE_MEMBER_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_watchlist_member_list ON watchlist_member (list_id, display_order)";

    /** The list the pre-lists watchlist becomes on first start. Renameable like any other. */
    static final String DEFAULT_LIST_NAME = "Watchlist";

    private final JdbcConnectionFactory connections;
    private final KeyValueStore keyValueStore;

    public WatchlistRepository(JdbcConnectionFactory connections, KeyValueStore keyValueStore) {
        this.connections = connections;
        this.keyValueStore = keyValueStore;
        try (Connection conn = connections.open(); Statement ddl = conn.createStatement()) {
            ddl.execute(CREATE_TABLE);
            addColumnIfMissing(ddl, "display_order INTEGER");
            addColumnIfMissing(ddl, "price_above REAL");
            addColumnIfMissing(ddl, "price_below REAL");
            // Backfill any rows that predate the column so ordering is stable and non-null.
            ddl.execute("UPDATE watchlist SET display_order = rowid WHERE display_order IS NULL");

            ddl.execute(CREATE_LIST_TABLE);
            ddl.execute(CREATE_LIST_NAME_INDEX);
            ddl.execute(CREATE_MEMBER_TABLE);
            ddl.execute(CREATE_MEMBER_INDEX);
            ddl.execute(CREATE_COLUMN_TABLE);
            migrateToLists(conn, ddl);
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise watchlist table", e);
        }
    }

    /**
     * One-shot promotion of a pre-lists database: the flat watchlist becomes the first list,
     * keeping its manual order.
     *
     * <p>The member backfill is guarded on <em>having just created</em> that list, not on the
     * member table being empty. Those differ exactly once it matters: a user who empties every
     * list would otherwise find their symbols resurrected by the next restart.
     */
    private static void migrateToLists(Connection conn, Statement ddl) throws java.sql.SQLException {
        try (ResultSet rs = ddl.executeQuery("SELECT COUNT(*) FROM watchlist_list")) {
            if (rs.next() && rs.getInt(1) > 0) return;      // already has lists — nothing to do
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO watchlist_list (name, display_order, created_at) VALUES (?, 0, ?)")) {
            ps.setString(1, DEFAULT_LIST_NAME);
            ps.setString(2, Instant.now().toString());
            ps.executeUpdate();
        }
        long listId;
        try (ResultSet rs = ddl.executeQuery("SELECT id FROM watchlist_list ORDER BY id LIMIT 1")) {
            if (!rs.next()) return;
            listId = rs.getLong(1);
        }
        // Existing symbols keep the order they were shown in; a fresh database inserts nothing.
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO watchlist_member (list_id, symbol, display_order) " +
                        "SELECT ?, symbol, display_order FROM watchlist")) {
            ps.setLong(1, listId);
            int moved = ps.executeUpdate();
            if (moved > 0) log.info("Migrated {} watchlist symbol(s) into the '{}' list", moved, DEFAULT_LIST_NAME);
        }
    }

    /** Idempotent ADD COLUMN — swallows the "duplicate column" error on re-run. */
    private static void addColumnIfMissing(Statement ddl, String columnDef) {
        try {
            ddl.execute("ALTER TABLE watchlist ADD COLUMN " + columnDef);
        } catch (java.sql.SQLException alreadyMigrated) {
            // column already present — fine
        }
    }

    /** Convenience overload for callers that set only the percent thresholds (no price levels). */
    public void add(String symbol, BigDecimal highPct, BigDecimal movePct) {
        add(symbol, highPct, movePct, null, null);
    }

    /** Add to the first list — for callers that predate lists or genuinely don't care which. */
    public void add(String symbol, BigDecimal highPct, BigDecimal movePct,
                    BigDecimal priceAbove, BigDecimal priceBelow) {
        add(symbol, highPct, movePct, priceAbove, priceBelow, firstListId());
    }

    /**
     * Insert a symbol into {@code listId}, or update its thresholds/levels if already watched.
     * Re-mirrors the KV set.
     *
     * <p>The thresholds are <b>global to the symbol</b>, so adding a name already held by another
     * list updates the triggers both lists show. Membership is separate and additive: the same
     * name can sit in any number of lists, appended to the end of each.
     */
    public void add(String symbol, BigDecimal highPct, BigDecimal movePct,
                    BigDecimal priceAbove, BigDecimal priceBelow, long listId) {
        String sym = normalise(symbol);
        if (sym.isEmpty()) throw new IllegalStateException("Watchlist symbol must not be blank.");
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO watchlist (symbol, high_threshold_pct, move_threshold_pct, added_at, display_order, price_above, price_below) " +
                             "VALUES (?, ?, ?, ?, (SELECT COALESCE(MAX(display_order), 0) + 1 FROM watchlist), ?, ?) " +
                             "ON CONFLICT(symbol) DO UPDATE SET " +
                             "high_threshold_pct = excluded.high_threshold_pct, " +
                             "move_threshold_pct = excluded.move_threshold_pct, " +
                             "price_above = excluded.price_above, " +
                             "price_below = excluded.price_below")) {
            ps.setString(1, sym);
            ps.setDouble(2, orDisabled(highPct).doubleValue());
            ps.setDouble(3, orDisabled(movePct).doubleValue());
            ps.setString(4, Instant.now().toString());
            ps.setDouble(5, orDisabled(priceAbove).doubleValue());
            ps.setDouble(6, orDisabled(priceBelow).doubleValue());
            ps.executeUpdate();
            addMember(conn, listId, sym);
        } catch (Exception e) {
            throw new IllegalStateException("Could not add " + sym + " to the watchlist.", e);
        }
        mirrorKv();
    }

    /**
     * Append a symbol to a list. {@code DO NOTHING} on conflict so a re-add keeps the name where
     * the user dragged it rather than bouncing it to the bottom — the thresholds still update.
     */
    private static void addMember(Connection conn, long listId, String sym) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO watchlist_member (list_id, symbol, display_order) " +
                        "VALUES (?, ?, (SELECT COALESCE(MAX(display_order), 0) + 1 FROM watchlist_member WHERE list_id = ?)) " +
                        "ON CONFLICT(list_id, symbol) DO NOTHING")) {
            ps.setLong(1, listId);
            ps.setString(2, sym);
            ps.setLong(3, listId);
            ps.executeUpdate();
        }
    }

    public void updateThresholds(String symbol, BigDecimal highPct, BigDecimal movePct,
                                 BigDecimal priceAbove, BigDecimal priceBelow) {
        String sym = normalise(symbol);
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE watchlist SET high_threshold_pct = ?, move_threshold_pct = ?, " +
                             "price_above = ?, price_below = ? WHERE symbol = ?")) {
            ps.setDouble(1, orDisabled(highPct).doubleValue());
            ps.setDouble(2, orDisabled(movePct).doubleValue());
            ps.setDouble(3, orDisabled(priceAbove).doubleValue());
            ps.setDouble(4, orDisabled(priceBelow).doubleValue());
            ps.setString(5, sym);
            if (ps.executeUpdate() == 0) {
                // The symbol was removed between the edit and this save landing — e.g. the user hit ×
                // while an inline edit's blur-triggered save was still in flight. A threshold update
                // for a row that no longer exists is a genuine no-op, not an error: throwing here would
                // send the client down its error path and flap the just-deleted row back on screen.
                log.debug("Threshold update for {} affected no rows (not on the watchlist) — ignored.", sym);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not update thresholds for " + sym + ".", e);
        }
    }

    /** Drop a symbol from every list and forget its alert settings. */
    public void remove(String symbol) {
        String sym = normalise(symbol);
        try (Connection conn = connections.open()) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM watchlist_member WHERE symbol = ?")) {
                ps.setString(1, sym);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM watchlist WHERE symbol = ?")) {
                ps.setString(1, sym);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not remove " + sym + " from the watchlist.", e);
        }
        mirrorKv();
    }

    /**
     * Drop a symbol from one list, and from the watchlist entirely if that was its last.
     *
     * <p>The second delete is what keeps the KV mirror honest. A symbol in no list is invisible
     * to every screen that could remove it, but {@code PriceFetchSupport} and
     * {@code PortfolioFundamentalsService} would go on fetching it daily for ever.
     */
    public void remove(String symbol, long listId) {
        String sym = normalise(symbol);
        try (Connection conn = connections.open()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM watchlist_member WHERE list_id = ? AND symbol = ?")) {
                ps.setLong(1, listId);
                ps.setString(2, sym);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM watchlist WHERE symbol = ? " +
                            "AND NOT EXISTS (SELECT 1 FROM watchlist_member WHERE symbol = ?)")) {
                ps.setString(1, sym);
                ps.setString(2, sym);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not remove " + sym + " from the watchlist.", e);
        }
        mirrorKv();
    }

    /**
     * Every watched symbol across every list, in the legacy flat order. Degrades to empty on read
     * failure.
     *
     * <p>This is the <b>alert job's</b> view and must stay list-agnostic: a trigger fires because
     * of where the price is, not because of which tab happens to be open. The screen uses
     * {@link #loadAll(long)}.
     */
    public List<Entry> loadAll() {
        return readEntries(
                "SELECT symbol, high_threshold_pct, move_threshold_pct, added_at, price_above, price_below " +
                        "FROM watchlist ORDER BY display_order, symbol", null);
    }

    /** One list's symbols, in that list's own manual order. */
    public List<Entry> loadAll(long listId) {
        return readEntries(
                "SELECT w.symbol, w.high_threshold_pct, w.move_threshold_pct, w.added_at, " +
                        "w.price_above, w.price_below " +
                        "FROM watchlist w JOIN watchlist_member m ON m.symbol = w.symbol " +
                        "WHERE m.list_id = ? ORDER BY m.display_order, w.symbol", listId);
    }

    private List<Entry> readEntries(String sql, Long listId) {
        List<Entry> out = new ArrayList<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (listId != null) ps.setLong(1, listId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // price_above/below are NULL on rows predating the columns → getDouble yields 0.0
                    // (disabled), which is exactly the intended "no level" behaviour.
                    out.add(new Entry(rs.getString(1),
                            BigDecimal.valueOf(rs.getDouble(2)),
                            BigDecimal.valueOf(rs.getDouble(3)),
                            Instant.parse(rs.getString(4)),
                            BigDecimal.valueOf(rs.getDouble(5)),
                            BigDecimal.valueOf(rs.getDouble(6))));
                }
            }
        } catch (Exception e) {
            log.warn("Could not load watchlist", e);
        }
        return out;
    }

    /** Reorder within the first list — for callers that predate lists. */
    public void move(String symbol, boolean up) {
        move(symbol, up, firstListId());
    }

    /**
     * Move a symbol one place up or down within one list by swapping its {@code display_order}
     * with its neighbour's. No-op at the ends of the list or if the symbol isn't in it.
     *
     * <p>The order is <b>per list</b>: the same name can sit third in {@code chips} and first in
     * {@code ai}. Membership is unchanged, so no KV re-mirror is needed.
     */
    public void move(String symbol, boolean up, long listId) {
        String sym = normalise(symbol);
        try (Connection conn = connections.open()) {
            List<String> syms = new ArrayList<>();
            List<Long> orders = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT symbol, display_order FROM watchlist_member " +
                            "WHERE list_id = ? ORDER BY display_order, symbol")) {
                ps.setLong(1, listId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        syms.add(rs.getString(1));
                        orders.add(rs.getLong(2));
                    }
                }
            }
            int idx = syms.indexOf(sym);
            if (idx < 0) return;
            int neighbour = up ? idx - 1 : idx + 1;
            if (neighbour < 0 || neighbour >= syms.size()) return;    // already at an end
            setOrder(conn, listId, syms.get(idx), orders.get(neighbour));
            setOrder(conn, listId, syms.get(neighbour), orders.get(idx));
        } catch (Exception e) {
            throw new IllegalStateException("Could not reorder " + sym + " on the watchlist.", e);
        }
    }

    private static void setOrder(Connection conn, long listId, String symbol, long order)
            throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE watchlist_member SET display_order = ? WHERE list_id = ? AND symbol = ?")) {
            ps.setLong(1, order);
            ps.setLong(2, listId);
            ps.setString(3, symbol);
            ps.executeUpdate();
        }
    }

    // ---- lists -----------------------------------------------------------------

    /** Every list, in the user's manual order. Never empty in practice — the migration seeds one. */
    public List<WatchList> lists() {
        List<WatchList> out = new ArrayList<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT l.id, l.name, " +
                             "(SELECT COUNT(*) FROM watchlist_member m WHERE m.list_id = l.id) " +
                             "FROM watchlist_list l ORDER BY l.display_order, l.id")) {
            while (rs.next()) out.add(new WatchList(rs.getLong(1), rs.getString(2), rs.getInt(3)));
        } catch (Exception e) {
            log.warn("Could not load watchlists", e);
        }
        return out;
    }

    /**
     * The list a list-less caller means: the first one. {@code /watchlist} with no {@code list}
     * param lands here, as does the legacy {@link #add(String, BigDecimal, BigDecimal)} overload.
     * Returns 0 only if the table is somehow empty, which every read below treats as "no rows".
     */
    public long firstListId() {
        List<WatchList> all = lists();
        return all.isEmpty() ? 0L : all.get(0).id();
    }

    /** Create a named list and return its id. Rejects a blank or already-taken name. */
    public long createList(String name) {
        String clean = cleanName(name);
        try (Connection conn = connections.open()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO watchlist_list (name, display_order, created_at) " +
                            "VALUES (?, (SELECT COALESCE(MAX(display_order), -1) + 1 FROM watchlist_list), ?)")) {
                ps.setString(1, clean);
                ps.setString(2, Instant.now().toString());
                ps.executeUpdate();
            }
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (Exception e) {
            throw new IllegalStateException(nameClash(e, clean, "create"), e);
        }
    }

    public void renameList(long id, String name) {
        String clean = cleanName(name);
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement("UPDATE watchlist_list SET name = ? WHERE id = ?")) {
            ps.setString(1, clean);
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(nameClash(e, clean, "rename"), e);
        }
    }

    /**
     * Delete a list, its membership rows, and any symbol thereby left in no list at all.
     *
     * <p>Refuses to delete the last one: the tab strip has nowhere to send the user, and an
     * empty list is a click away from the same result without discarding every alert setting.
     */
    public void deleteList(long id) {
        if (lists().size() <= 1) {
            throw new IllegalStateException("This is the only watchlist — rename it or empty it instead of deleting it.");
        }
        try (Connection conn = connections.open(); Statement st = conn.createStatement()) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM watchlist_member WHERE list_id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            st.execute("DELETE FROM watchlist WHERE symbol NOT IN (SELECT symbol FROM watchlist_member)");
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM watchlist_column WHERE list_id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM watchlist_list WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not delete that watchlist.", e);
        }
        mirrorKv();
    }

    /** Move a list one place along the tab strip by swapping display_order with its neighbour. */
    public void moveList(long id, boolean up) {
        try (Connection conn = connections.open()) {
            List<WatchList> all = lists();
            int idx = -1;
            for (int i = 0; i < all.size(); i++) if (all.get(i).id() == id) idx = i;
            if (idx < 0) return;
            int neighbour = up ? idx - 1 : idx + 1;
            if (neighbour < 0 || neighbour >= all.size()) return;      // already at an end
            setListOrder(conn, all.get(idx).id(), neighbour);
            setListOrder(conn, all.get(neighbour).id(), idx);
        } catch (Exception e) {
            throw new IllegalStateException("Could not reorder that watchlist.", e);
        }
    }

    private static void setListOrder(Connection conn, long id, long order) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE watchlist_list SET display_order = ? WHERE id = ?")) {
            ps.setLong(1, order);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    private static String cleanName(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) throw new IllegalStateException("A watchlist needs a name.");
        if (clean.length() > 40) throw new IllegalStateException("That name is too long (40 characters max).");
        return clean;
    }

    /** The unique index is the only way a name collides, so report that rather than the raw SQL error. */
    private static String nameClash(Exception e, String name, String verb) {
        String msg = String.valueOf(e.getMessage());
        return msg.contains("UNIQUE") || msg.contains("constraint")
                ? "There is already a watchlist called \"" + name + "\"."
                : "Could not " + verb + " that watchlist.";
    }

    public record WatchList(long id, String name, int size) {
    }

    // ---- per-list columns ------------------------------------------------------

    /** The list's chosen columns in order, or empty for "use the default set". */
    public List<String> columnsFor(long listId) {
        List<String> out = new ArrayList<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT col_key FROM watchlist_column WHERE list_id = ? ORDER BY display_order")) {
            ps.setLong(1, listId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (Exception e) {
            log.warn("Could not load watchlist columns for list {}", listId, e);
        }
        return out;
    }

    /**
     * Replace a list's columns wholesale. An empty list clears the rows, which restores the
     * default set rather than blanking the table — "reset to default" needs no separate verb.
     */
    public void saveColumns(long listId, List<String> keys) {
        try (Connection conn = connections.open()) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM watchlist_column WHERE list_id = ?")) {
                ps.setLong(1, listId);
                ps.executeUpdate();
            }
            if (keys == null || keys.isEmpty()) return;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO watchlist_column (list_id, col_key, display_order) VALUES (?, ?, ?)")) {
                int order = 0;
                for (String k : keys) {
                    if (k == null || k.isBlank()) continue;
                    ps.setLong(1, listId);
                    ps.setString(2, k.trim());
                    ps.setInt(3, order++);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not save the column layout.", e);
        }
    }

    private void mirrorKv() {
        List<String> symbols = new ArrayList<>();
        for (Entry e : loadAll()) symbols.add(e.symbol());
        keyValueStore.putStringSet(WATCHLIST_SYMBOLS_KEY, symbols);
    }

    private static String normalise(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase();
    }

    /** Blank or negative → disabled (no alert); otherwise the value as given. */
    private static BigDecimal orDisabled(BigDecimal v) {
        return (v == null || v.signum() < 0) ? DISABLED : v;
    }

    public record Entry(String symbol, BigDecimal highThresholdPct, BigDecimal moveThresholdPct,
                        Instant addedAt, BigDecimal priceAbove, BigDecimal priceBelow) {
    }
}
