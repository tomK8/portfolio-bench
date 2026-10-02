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
 * <p><b>The broker's word and ours are separate columns.</b> {@code status} is what the export
 * said, stored verbatim and rewritten on every re-parse — it is evidence. {@code resolved_status}
 * is a verdict about an order the broker never accounted for, reached either by matching it to the
 * trade it produced in the cash ledger or by the user saying outright what happened; like
 * {@code note} it belongs to us rather than to the export, so no re-parse touches it. Keeping them
 * apart is what makes the verdict survive tomorrow's download, and what keeps a verdict from ever
 * being mistaken for something a broker said.
 *
 * <p><b>Retention applies to closed rows only.</b> A year of history, because the thing kept
 * longest is a ladder's completed round trip and its two legs can be months apart — and because an
 * order can only be put on a ladder while it is still here, so the window is also how long the
 * user has to notice. An order still open is never pruned however old it is, because pruning it
 * would delete a live annotation.
 */
public class OrderHistoryRepository {

    private static final Logger log = LoggerFactory.getLogger(OrderHistoryRepository.class);

    /**
     * How long a closed order, its annotation and its verdict are kept.
     *
     * <p>A year rather than the month this started as. The month was enough for the original
     * purpose — reading an annotation against the order that replaced it — but not for the two that
     * arrived after it. A ladder's round trip is a sell and the buy that closes it, which can be a
     * season apart, and the order-picker can only offer what is still stored, so retention is also
     * the window in which a user may decide an old fill belongs on a ladder. An order already in a
     * group is exempt from pruning entirely (see {@link #prune}); this governs the rest, which is
     * a few hundred rows a year.
     */
    public static final int RETENTION_DAYS = 365;

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
                closed_at    TEXT,
                resolved_status  TEXT,
                resolved_source  TEXT,
                resolved_at      TEXT,
                resolved_trade   INTEGER,
                matched_through  TEXT
            )""";

    private final JdbcConnectionFactory connections;

    public OrderHistoryRepository(JdbcConnectionFactory connections) {
        this.connections = connections;
        try (Connection conn = connections.open(); Statement ddl = conn.createStatement()) {
            ddl.execute(CREATE_TABLE);
            ddl.execute("CREATE INDEX IF NOT EXISTS idx_order_history_closed "
                    + "ON order_history(closed_at)");
            // A database written before an order could be accounted for by anything but its own
            // export has none of these. All five are nullable and unread by the code that created
            // the table, so they go on in place: no rebuild, no copy, no data migration. A row
            // already there simply has no verdict, which is exactly what it had before.
            for (String[] col : new String[][]{
                    {"resolved_status", "TEXT"},     // our verdict, an OrderStatus name
                    {"resolved_source", "TEXT"},     // MATCHED (cash ledger) or MANUAL (the user)
                    {"resolved_at", "TEXT"},         // the day it was reached
                    {"resolved_trade", "INTEGER"},   // cash_transactions rowid a MATCHED verdict owns
                    {"matched_through", "TEXT"}}) {  // ledger date this row was last checked against
                addColumnIfMissing(conn, col[0], col[1]);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise order_history table", e);
        }
    }

    /**
     * Add a nullable column to an existing table when it is not already there.
     *
     * <p>Deliberately the narrowest migration there is — no rebuild, no copy, no constraint change
     * — because that is the only one this table may need. A column nullable and unread by older
     * code can be added under a running schema; anything past that is a data migration and belongs
     * in its own method with its own reason written down.
     */
    private static void addColumnIfMissing(Connection conn, String column, String type)
            throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(order_history)")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return;
            }
        }
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE order_history ADD COLUMN " + column + " " + type);
            log.info("Added order_history.{} to an existing database", column);
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
                            amount, currency, date_entered, status, note, closed_at,
                            resolved_status, resolved_source, resolved_at
                       FROM order_history WHERE closed_at IS NOT NULL
                      ORDER BY closed_at DESC, broker, symbol""")) {
            while (rs.next()) {
                out.add(new HistoricalOrder(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6),
                        decimal(rs, 7), decimal(rs, 8), decimal(rs, 9),
                        rs.getString(10), rs.getString(11), rs.getString(12),
                        rs.getString(13), rs.getString(14),
                        rs.getString(15), rs.getString(16), rs.getString(17)));
            }
        } catch (Exception e) {
            log.warn("Could not load order history", e);
        }
        return out;
    }

    /**
     * Every stored order, with what is known about how it ended — what the ledger matcher reads.
     *
     * <p>Deliberately <b>every</b> row, not only the ones needing an answer. The orders a broker
     * did account for are what stop an unexplained one claiming a trade that is already spoken for,
     * so leaving them out would turn a modified Roth order's ghost into a second fill. See
     * {@code OrderFills}.
     */
    public List<FillCandidate> fillCandidates() {
        List<FillCandidate> out = new ArrayList<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT order_key, broker, symbol, side, quantity, price, currency,
                            date_entered, closed_at, status, resolved_status, resolved_trade,
                            matched_through
                       FROM order_history""")) {
            while (rs.next()) {
                long trade = rs.getLong(12);
                out.add(new FillCandidate(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        decimal(rs, 5), decimal(rs, 6), rs.getString(7), rs.getString(8),
                        rs.getString(9), rs.getString(10), rs.getString(11),
                        rs.wasNull() ? null : trade, rs.getString(13)));
            }
        } catch (Exception e) {
            log.warn("Could not load orders for fill matching", e);
        }
        return out;
    }

    /**
     * Record what became of an order the broker never accounted for.
     *
     * <p>Writes only where the broker left a gap: the {@code WHERE} clause refuses a row whose
     * stored status this build reads as terminal. The broker's own word is the evidence and nothing
     * here outranks it — a verdict exists to answer the silence, not to argue with a statement. A
     * row that is not closed is likewise untouched: there is nothing to explain about an order
     * still resting.
     *
     * @param status  an {@link OrderStatus} constant, stored by name; null clears the verdict
     * @param source  how it was reached — {@link #SOURCE_MATCHED} or {@link #SOURCE_MANUAL}
     * @param tradeRowid the {@code cash_transactions} row a matched verdict owns, so a later run
     *                   cannot hand the same trade to a second order. Null for a manual verdict,
     *                   which claims nothing: the user saying an order filled is not a statement
     *                   about which ledger row it was.
     * @return true when a row was written
     */
    public boolean saveResolution(String orderKey, OrderStatus status, String source,
                                  Long tradeRowid, LocalDate today) {
        if (orderKey == null || orderKey.isBlank()) return false;
        if (status != null && status.open()) {
            throw new IllegalStateException("A verdict has to say the order ended: "
                    + status.label() + " does not.");
        }
        try (Connection conn = connections.open()) {
            // The guard is read here rather than expressed in the WHERE clause, because what counts
            // as "the broker already said" is OrderStatus's judgement and nowhere else's: it reads
            // "Part-Executed" and "PART EXECUTED" as one terminal word, which no SQL comparison on
            // the stored text would. A second copy of that vocabulary in a query is exactly what
            // the enum exists to prevent.
            String stored;
            boolean closed;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status, closed_at FROM order_history WHERE order_key = ?")) {
                ps.setString(1, orderKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return false;
                    stored = rs.getString(1);
                    closed = rs.getString(2) != null;
                }
            }
            if (!closed) {
                throw new IllegalStateException(
                        "That order is still resting at the broker — there is nothing to explain yet.");
            }
            if (OrderStatus.of(stored).terminal()) {
                throw new IllegalStateException("The broker already said this order was "
                        + OrderStatus.of(stored).label().toLowerCase() + ".");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE order_history SET resolved_status = ?, resolved_source = ?, "
                            + "resolved_at = ?, resolved_trade = ? WHERE order_key = ?")) {
                ps.setString(1, status == null ? null : status.name());
                ps.setString(2, status == null ? null : source);
                ps.setString(3, status == null ? null : today.toString());
                if (status == null || tradeRowid == null) ps.setNull(4, Types.INTEGER);
                else ps.setLong(4, tradeRowid);
                ps.setString(5, orderKey);
                return ps.executeUpdate() > 0;
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not save the order status: " + e.getMessage(), e);
        }
    }

    /**
     * Note how far down the ledger each of these orders has been looked for.
     *
     * <p>This is what keeps the matcher from being re-run on every page load. An order the ledger
     * could not explain is not a failure to retry — it is a question already asked of the data that
     * exists, and the answer cannot change until the data does. Storing the ledger date it was
     * asked against lets the next run skip it until the statement has actually moved past that day.
     */
    public void markChecked(Map<String, String> throughByKey) {
        if (throughByKey == null || throughByKey.isEmpty()) return;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE order_history SET matched_through = ? WHERE order_key = ?")) {
            for (Map.Entry<String, String> e : throughByKey.entrySet()) {
                ps.setString(1, e.getValue());
                ps.setString(2, e.getKey());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception e) {
            log.warn("Could not record how far the fill matcher has read", e);
        }
    }

    /** What reached a verdict: the cash ledger, or the user. */
    public static final String SOURCE_MATCHED = "MATCHED";
    public static final String SOURCE_MANUAL = "MANUAL";

    /**
     * One stored order as the fill matcher needs it. Raw strings and the stored status, because
     * deciding what any of it means is {@code OrderFills}'s job and not a repository's.
     */
    public record FillCandidate(String orderKey, String broker, String symbol, String side,
                                BigDecimal quantity, BigDecimal price, String currency,
                                String dateEntered, String closedAt, String status,
                                String resolvedStatus, Long resolvedTrade, String matchedThrough) {
    }

    /**
     * Drop closed orders that went more than {@link #RETENTION_DAYS} ago. Open orders are never
     * touched however long they have been resting — pruning one would delete a live annotation.
     *
     * @param keep order keys that must survive whatever their age. The caller passes the rungs of
     *             the user's ladders: the thing a ladder is kept for is the completed round trip,
     *             and the second leg can fill months after the first — a sell in August and the buy
     *             that closes it in October are one trip, and a month's retention would delete half
     *             of it before the half that completes it arrives. Passed in rather than read from
     *             the group table here, so this class keeps knowing nothing about ladders and the
     *             rule is visible at the call site that has the reason for it.
     * @return how many rows were removed
     */
    public int prune(LocalDate today, Set<String> keep) {
        String cutoff = today.minusDays(RETENTION_DAYS).toString();
        List<String> protectedKeys = keep == null ? List.of() : List.copyOf(keep);
        String sql = "DELETE FROM order_history WHERE closed_at IS NOT NULL AND closed_at < ?"
                + (protectedKeys.isEmpty() ? ""
                : " AND order_key NOT IN (" + "?,".repeat(protectedKeys.size() - 1) + "?)");
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cutoff);
            for (int i = 0; i < protectedKeys.size(); i++) ps.setString(i + 2, protectedKeys.get(i));
            int removed = ps.executeUpdate();
            if (removed > 0) log.info("Order history: pruned {} order(s) closed before {}", removed, cutoff);
            return removed;
        } catch (Exception e) {
            log.warn("Could not prune order history", e);
            return 0;
        }
    }

    /** Prune with nothing protected. */
    public int prune(LocalDate today) {
        return prune(today, Set.of());
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
                                  String closedAt, String resolvedStatus, String resolvedSource,
                                  String resolvedAt) {

        /**
         * What the panel prints when nobody has said what happened — see {@link #state()}.
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
         * <p>There are three sources for it and they rank, highest first:
         * <ol>
         *   <li><b>The broker's own word</b>, whenever it is terminal. It is the evidence and
         *       nothing outranks it.</li>
         *   <li><b>A verdict</b> — {@link #resolvedStatus} — reached by matching the order to the
         *       trade it produced in the cash ledger, or typed in by the user. This only ever
         *       speaks where the broker was silent.</li>
         *   <li><b>Absence</b>, which is the state no broker writes. A row that got here still
         *       carrying an open status ("Open", "PENDING", "In progress") is one the broker
         *       stopped listing without ever restating it — what every modified Roth order looks
         *       like, the broker issuing a new reference for the replacement. It is <b>not</b>
         *       reported as filled or cancelled: which of the two it was is exactly what the export
         *       declined to say.</li>
         * </ol>
         *
         * <p>{@link #stateInferred()} and {@link #stateResolved()} say which of the three this is,
         * so the panel can mark a figure that is ours rather than the broker's.
         */
        public String state() {
            OrderStatus broker = OrderStatus.of(status);
            if (broker.terminal()) return broker.label();
            OrderStatus verdict = resolved();
            return verdict == null ? NO_LONGER_LISTED : verdict.label();
        }

        /** The verdict recorded about this order, or null when none was — or when it was a state this build does not know. */
        public OrderStatus resolved() {
            if (OrderStatus.of(status).terminal()) return null;
            OrderStatus s = OrderStatus.named(resolvedStatus);
            return s == null || s.open() ? null : s;
        }

        /** True when nobody — neither the broker nor us — has said what became of this order. */
        public boolean stateInferred() {
            return OrderStatus.of(status).open() && resolved() == null;
        }

        /** True when {@link #state()} is a verdict of ours rather than the broker's word. */
        public boolean stateResolved() {
            return resolved() != null;
        }

        /**
         * The verdict's {@link OrderStatus} name, or {@code ""} when there is none — what the
         * status dropdown tests each option against. A name rather than the constant because a
         * template comparing enums is a template that breaks silently when one is renamed.
         */
        public String resolvedName() {
            OrderStatus s = resolved();
            return s == null ? "" : s.name();
        }

        /** True when that verdict came from the user rather than from the cash ledger. */
        public boolean resolvedByUser() {
            return stateResolved() && SOURCE_MANUAL.equals(resolvedSource);
        }

        /** Where a verdict came from, in words, for the cell's hover. Null when there is none. */
        public String resolutionNote() {
            if (!stateResolved()) return null;
            String how = resolvedByUser()
                    ? "You recorded this"
                    : "Matched to the trade it produced in the cash ledger";
            return how + " — the broker stopped listing it without restating it"
                    + (resolvedAt == null ? "" : ", read as " + state().toLowerCase()
                       + " on " + resolvedAt)
                    + ". Last seen as: "
                    + (status != null && !status.isBlank() ? status : "no status") + ".";
        }

        /**
         * Whether the user may say what became of this order.
         *
         * <p>Only where the broker was silent. An order it restated terminally is settled by the
         * evidence, and offering to overrule that would be offering to disagree with the account
         * statement.
         */
        public boolean answerable() {
            return OrderStatus.of(status).open();
        }
    }
}
