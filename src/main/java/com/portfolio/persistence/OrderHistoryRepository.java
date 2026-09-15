package com.portfolio.persistence;

import com.portfolio.domain.model.OpenOrder;
import com.portfolio.domain.model.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every order this tool has seen in a broker's order export, with the free-text annotation hung on
 * it — the Open-orders counterpart to {@code trade_notes}.
 *
 * <p><b>Live orders are in here too, and that is the point.</b> The table is not "orders that are
 * gone"; it is "orders we have seen", with {@code closed_at} marking the ones that no longer
 * appear as open. An annotation has to be attachable the moment an order is placed, and it has to
 * still be there after the order fills — so the row must predate the order closing. Writing
 * history only at the moment an order disappears would mean the note had nowhere to live until
 * then, and nowhere to come from afterwards.
 *
 * <p><b>An order closes by absence as much as by status.</b> A broker either restates the order
 * with a terminal status (Dealt, Cancelled, Expired) or simply stops listing it; both mean the
 * same thing to a reader, so {@link #record} treats them the same. Absence is only meaningful when
 * the file actually parsed, which is why the caller passes the rows for one broker at a time and
 * skips the call entirely for a broker whose export failed — otherwise one bad file would close
 * every order it should have listed.
 *
 * <p><b>Retention applies to closed rows only.</b> A month of history is what the user asked for
 * and is enough to compare an annotation against the order that replaced it; an order still open
 * is never pruned however old it is, because pruning it would delete a live annotation.
 */
public class OrderHistoryRepository {

    private static final Logger log = LoggerFactory.getLogger(OrderHistoryRepository.class);

    /** How long a closed order and its annotation are kept. */
    public static final int RETENTION_DAYS = 31;

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS order_history (
                order_key    TEXT PRIMARY KEY,
                broker       TEXT NOT NULL,
                symbol       TEXT,
                name         TEXT,
                side         TEXT,
                order_type   TEXT,
                quantity     REAL,
                price        REAL,
                amount       REAL,
                currency     TEXT,
                date_entered TEXT,
                expiry       TEXT,
                status       TEXT,
                reference    TEXT,
                note         TEXT,
                first_seen   TEXT NOT NULL,
                last_seen    TEXT NOT NULL,
                closed_at    TEXT
            )""";

    private final JdbcConnectionFactory connections;

    public OrderHistoryRepository(JdbcConnectionFactory connections) {
        this.connections = connections;
        try (Connection conn = connections.open(); Statement ddl = conn.createStatement()) {
            ddl.execute(CREATE_TABLE);
            ddl.execute("CREATE INDEX IF NOT EXISTS idx_order_history_closed "
                    + "ON order_history(closed_at)");
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise order_history table", e);
        }
    }

    /**
     * Reconcile one broker's export against what is stored: upsert every row it lists, and close
     * anything of that broker's the file no longer lists as open.
     *
     * @param broker    the parser's source label — scopes the close sweep, so one broker's export
     *                  can never close another's orders
     * @param allParsed <b>every</b> row the file yielded, terminal ones included. The service
     *                  filters to open rows for display; this needs the unfiltered set, or an
     *                  order restated as "Dealt" would look like an absence and lose its status.
     * @param openKeys  identities of the rows that are currently open
     */
    public void record(String broker, List<OpenOrder> allParsed, Set<String> openKeys,
                       LocalDate today) {
        if (broker == null) return;
        String day = today.toString();
        try (Connection conn = connections.open()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                Set<String> seen = new HashSet<>();
                for (OpenOrder o : allParsed) {
                    String key = o.identity();
                    seen.add(key);
                    upsert(conn, key, broker, o, openKeys.contains(key), day);
                }
                closeMissing(conn, broker, seen, openKeys, day);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (Exception e) {
            // Never fail the panel over its own bookkeeping: the orders themselves parsed fine.
            log.warn("Could not record order history for {}", broker, e);
        }
    }

    /**
     * Upsert one row. The annotation is deliberately absent from the UPDATE — it belongs to the
     * user, not the export, and a re-parse must never overwrite it. {@code first_seen} is likewise
     * only set on insert.
     */
    private void upsert(Connection conn, String key, String broker, OpenOrder o,
                        boolean open, String day) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO order_history (order_key, broker, symbol, name, side, order_type,
                        quantity, price, amount, currency, date_entered, expiry, status, reference,
                        first_seen, last_seen, closed_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(order_key) DO UPDATE SET
                    status = excluded.status,
                    price = excluded.price,
                    quantity = excluded.quantity,
                    amount = excluded.amount,
                    last_seen = excluded.last_seen,
                    closed_at = excluded.closed_at""")) {
            ps.setString(1, key);
            ps.setString(2, broker);
            ps.setString(3, o.symbol());
            ps.setString(4, o.name());
            ps.setString(5, o.side());
            ps.setString(6, o.orderType());
            setDecimal(ps, 7, o.quantity());
            setDecimal(ps, 8, o.price());
            setDecimal(ps, 9, o.amount());
            ps.setString(10, o.currency());
            ps.setString(11, o.dateEntered() == null ? null : o.dateEntered().toString());
            ps.setString(12, o.expiry() == null ? null : o.expiry().toString());
            ps.setString(13, o.status());
            ps.setString(14, o.reference());
            ps.setString(15, day);
            ps.setString(16, day);
            // An order that reappears as open — a broker restating one it had dropped — clears its
            // closing date rather than staying filed under history with a live twin on screen.
            ps.setString(17, open ? null : day);
            ps.executeUpdate();
        }
    }

    /**
     * Close this broker's stored orders that the export no longer lists at all. Rows already
     * closed keep their original date, so a month's retention runs from when an order actually
     * went rather than from the last time anything was imported.
     */
    private void closeMissing(Connection conn, String broker, Set<String> seen,
                              Set<String> openKeys, String day) throws Exception {
        List<String> stale = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT order_key FROM order_history WHERE broker = ? AND closed_at IS NULL")) {
            ps.setString(1, broker);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString(1);
                    if (!seen.contains(key) && !openKeys.contains(key)) stale.add(key);
                }
            }
        }
        if (stale.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE order_history SET closed_at = ? WHERE order_key = ? AND closed_at IS NULL")) {
            for (String key : stale) {
                ps.setString(1, day);
                ps.setString(2, key);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        log.info("Order history [{}]: {} order(s) no longer listed — filed as closed", broker, stale.size());
    }

    /** Annotations by order key. Reads degrade: a missing note is not worth failing the panel. */
    public Map<String, String> notes() {
        Map<String, String> out = new HashMap<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT order_key, note FROM order_history WHERE note IS NOT NULL AND note <> ''")) {
            while (rs.next()) out.put(rs.getString(1), rs.getString(2));
        } catch (Exception e) {
            log.warn("Could not load order notes", e);
        }
        return out;
    }

    /**
     * Save (or clear) one order's annotation.
     *
     * <p>Writes only to a row that already exists — an annotation is <em>about</em> an order the
     * tool has seen, and inserting a bare row for an unknown key would create an order with no
     * broker, no price and no history behind it. Returns false in that case rather than throwing:
     * the realistic cause is a stale page whose order has since closed and been pruned.
     */
    public boolean saveNote(String orderKey, String note) {
        if (orderKey == null || orderKey.isBlank()) return false;
        String value = note == null || note.isBlank() ? null : note.trim();
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE order_history SET note = ? WHERE order_key = ?")) {
            ps.setString(1, value);
            ps.setString(2, orderKey);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            throw new IllegalStateException("Could not save the order note: " + e.getMessage(), e);
        }
    }

    /** Closed orders, newest first — what the history panel lists. */
    public List<HistoricalOrder> closed() {
        List<HistoricalOrder> out = new ArrayList<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT order_key, broker, symbol, name, side, order_type, quantity, price,
                            amount, currency, date_entered, status, note, closed_at
                       FROM order_history WHERE closed_at IS NOT NULL
                      ORDER BY closed_at DESC, broker, symbol""")) {
            while (rs.next()) {
                out.add(new HistoricalOrder(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6),
                        decimal(rs, 7), decimal(rs, 8), decimal(rs, 9),
                        rs.getString(10), rs.getString(11), rs.getString(12),
                        rs.getString(13), rs.getString(14)));
            }
        } catch (Exception e) {
            log.warn("Could not load order history", e);
        }
        return out;
    }

    /**
     * Drop closed orders that went more than {@link #RETENTION_DAYS} ago. Open orders are never
     * touched however long they have been resting — pruning one would delete a live annotation.
     *
     * @return how many rows were removed
     */
    public int prune(LocalDate today) {
        String cutoff = today.minusDays(RETENTION_DAYS).toString();
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM order_history WHERE closed_at IS NOT NULL AND closed_at < ?")) {
            ps.setString(1, cutoff);
            int removed = ps.executeUpdate();
            if (removed > 0) log.info("Order history: pruned {} order(s) closed before {}", removed, cutoff);
            return removed;
        } catch (Exception e) {
            log.warn("Could not prune order history", e);
            return 0;
        }
    }

    /** Visible for tests — every stored row regardless of state. */
    Collection<String> allKeys() {
        List<String> out = new ArrayList<>();
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT order_key FROM order_history")) {
            while (rs.next()) out.add(rs.getString(1));
        } catch (Exception e) {
            log.warn("Could not list order keys", e);
        }
        return out;
    }

    private static void setDecimal(PreparedStatement ps, int idx, BigDecimal v) throws Exception {
        if (v == null) ps.setNull(idx, Types.REAL);
        else ps.setDouble(idx, v.doubleValue());
    }

    private static BigDecimal decimal(ResultSet rs, int col) throws Exception {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : BigDecimal.valueOf(v);
    }

    /**
     * One closed order as the history panel shows it.
     *
     * <p>{@link #status} is the broker's own word, stored verbatim and never rewritten — it is the
     * evidence, and the three brokers between them write six spellings of it. {@link #state()} is
     * what the panel prints: one vocabulary, from {@link OrderStatus}.
     */
    public record HistoricalOrder(String orderKey, String broker, String symbol, String name,
                                  String side, String orderType, BigDecimal quantity,
                                  BigDecimal price, BigDecimal amount, String currency,
                                  String dateEntered, String status, String note,
                                  String closedAt) {

        /**
         * What the panel prints when the broker never said what happened — see {@link #state()}.
         */
        public static final String NO_LONGER_LISTED = "No longer listed";

        /** What to show in the Security column — the ticker, falling back to the exported name. */
        public String display() {
            return symbol != null && !symbol.isBlank() ? symbol : (name == null ? "—" : name);
        }

        /**
         * The row's state in one vocabulary — the point of which is that "Dealt", "Executed" and
         * "EXECUTED" are one outcome and should read as one.
         *
         * <p>The third state is the one no broker writes. An order closes by absence as much as by
         * status, and a row that got here still carrying an open status ("Open", "PENDING", "In
         * progress") is one the broker stopped listing without ever restating it — which is what
         * every modified Roth order looks like, the broker issuing a new reference for the
         * replacement. Printing that row's last-seen "Open" in a table of closed orders said the
         * opposite of the truth. It is <b>not</b> reported as filled or cancelled either: which of
         * the two it was is exactly what the export declined to say, and
         * {@link #stateInferred()} marks it as our inference rather than the broker's word.
         */
        public String state() {
            OrderStatus s = OrderStatus.of(status);
            return s.open() ? NO_LONGER_LISTED : s.label();
        }

        /** True when {@link #state()} is read off the order's absence, not off the broker's word. */
        public boolean stateInferred() {
            return OrderStatus.of(status).open();
        }
    }
}
