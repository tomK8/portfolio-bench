package com.portfolio.persistence;

import com.portfolio.domain.model.GroupType;
import com.portfolio.domain.model.IntendedRung;
import com.portfolio.domain.model.OrderGroup;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The groups of orders the user has decided belong together, the orders assigned to them, and the
 * rungs recorded but never placed.
 *
 * <p>Three tables rather than one, because they answer to three different owners. {@code
 * order_group} is the user's intention and nothing else touches it. {@code order_group_member} is a
 * pointer into {@code order_history}, which the broker feed owns - so membership survives the order
 * filling, being cancelled or vanishing, and carries no copy of the order that could drift from it.
 * {@code order_group_intent} is an order that exists nowhere else at all; it has to carry its own
 * side, quantity and price because no feed will ever supply them.
 *
 * <p><b>Membership points at an order key, and that is the load-bearing choice.</b> The key is
 * {@code OpenOrder.identity()} - the broker's own reference where there is one - which is the same
 * key the annotations use. So a rung keeps its ladder when it fills, and a modified order (a new
 * reference, by every broker's rules) does not silently inherit the old one's place in the ladder:
 * it is a different order and the user assigns it themselves, which is the whole point of not
 * inferring membership.
 *
 * <p><b>One order belongs to at most one group</b> ({@code order_key} is the member table's primary
 * key, not a pair). A rung counted in two ladders would be counted twice in the cash each needs,
 * which is the figure the panel exists to get right. The key is unique across <em>types</em> too,
 * so an order cannot be a ladder rung and a switch leg at once - it is one order and it was placed
 * for one reason.
 *
 * <p><b>The table is type-agnostic and stays that way.</b> It holds ladders and share-class
 * switches side by side and knows the difference only as a string in {@code type}. A switch's second
 * listing is one more nullable column; which types may use it, which orders may be assigned to it
 * and how its legs are read are all decisions of the per-type assemblers in {@code application}.
 *
 * <p>Write paths throw {@link IllegalStateException} (the app-wide convention - the caller renders
 * the error fragment); reads log and degrade to empty, since a panel that cannot list ladders
 * should still list orders.
 */
public class OrderGroupRepository {

    private static final Logger log = LoggerFactory.getLogger(OrderGroupRepository.class);

    private static final String CREATE_GROUP = """
            CREATE TABLE IF NOT EXISTS order_group (
                id          INTEGER PRIMARY KEY,
                type        TEXT NOT NULL,
                name        TEXT NOT NULL,
                symbol      TEXT,
                symbol_to   TEXT,
                note        TEXT,
                created_at  TEXT NOT NULL
            )""";

    private static final String CREATE_MEMBER = """
            CREATE TABLE IF NOT EXISTS order_group_member (
                order_key   TEXT PRIMARY KEY,
                group_id    INTEGER NOT NULL,
                added_at    TEXT NOT NULL
            )""";

    private static final String CREATE_INTENT = """
            CREATE TABLE IF NOT EXISTS order_group_intent (
                id          INTEGER PRIMARY KEY,
                group_id    INTEGER NOT NULL,
                account     TEXT NOT NULL,
                symbol      TEXT,
                side        TEXT NOT NULL,
                quantity    REAL,
                price       REAL,
                note        TEXT,
                created_at  TEXT NOT NULL
            )""";

    private final JdbcConnectionFactory connections;

    public OrderGroupRepository(JdbcConnectionFactory connections) {
        this.connections = connections;
        try (Connection conn = connections.open(); Statement ddl = conn.createStatement()) {
            ddl.execute(CREATE_GROUP);
            ddl.execute(CREATE_MEMBER);
            ddl.execute(CREATE_INTENT);
            ddl.execute("CREATE INDEX IF NOT EXISTS idx_group_member_group "
                    + "ON order_group_member(group_id)");
            ddl.execute("CREATE INDEX IF NOT EXISTS idx_group_intent_group "
                    + "ON order_group_intent(group_id)");
            // A database created before share-class switches existed has no second symbol column.
            // Additive and nullable, so it needs no table rebuild and no data migration: every row
            // already there is a ladder, and a ladder's second symbol is null by definition.
            addColumnIfMissing(conn, "order_group", "symbol_to", "TEXT");
            // A database created before a switch's two sides could each span both listings has no
            // symbol on its intents. Additive and nullable for the same reason: a ladder's intent
            // never needed one, and a switch's older rows are read under the rule they were
            // recorded under — the side named the listing.
            addColumnIfMissing(conn, "order_group_intent", "symbol", "TEXT");
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise the order-group tables", e);
        }
    }

    /**
     * Add a column to an existing table when it is not already there.
     *
     * <p>Kept deliberately narrow — no rebuild, no copy, no constraint change — because that is the
     * only migration this table is allowed to need. A column that is nullable and unread by older
     * code can be added under a running schema; anything beyond that (a rename, a new NOT NULL)
     * would be a data migration and belongs in its own method with its own reason written down.
     */
    private static void addColumnIfMissing(Connection conn, String table, String column,
                                           String type) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return;
            }
        }
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            log.info("Added {}.{} to an existing database", table, column);
        }
    }

    // ---- reads -------------------------------------------------------------

    /**
     * Every group, newest first. A row whose {@code type} this build does not know is dropped with
     * a warning rather than read as the only type that exists - see {@link GroupType#fromDb}.
     */
    public List<OrderGroup> groups() {
        List<OrderGroup> out = new ArrayList<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, type, name, symbol, note, created_at, symbol_to FROM order_group "
                             + "ORDER BY created_at DESC, id DESC")) {
            while (rs.next()) {
                GroupType type = GroupType.fromDb(rs.getString(2));
                if (type == null) {
                    log.warn("Order group {} has unknown type '{}' - not shown", rs.getLong(1), rs.getString(2));
                    continue;
                }
                out.add(new OrderGroup(rs.getLong(1), type, rs.getString(3), rs.getString(4),
                        rs.getString(7), rs.getString(5), LocalDate.parse(rs.getString(6))));
            }
        } catch (Exception e) {
            log.warn("Could not load order groups", e);
        }
        return out;
    }

    /** Order keys per group, in the order they were added. */
    public Map<Long, List<String>> members() {
        Map<Long, List<String>> out = new LinkedHashMap<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT group_id, order_key FROM order_group_member "
                     + "ORDER BY added_at, rowid")) {
            while (rs.next()) {
                out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2));
            }
        } catch (Exception e) {
            log.warn("Could not load order group members", e);
        }
        return out;
    }

    /** Every assigned order key - what the "add rungs" picker filters out. */
    public Set<String> assignedKeys() {
        Set<String> out = new HashSet<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT order_key FROM order_group_member")) {
            while (rs.next()) out.add(rs.getString(1));
        } catch (Exception e) {
            log.warn("Could not load assigned order keys", e);
        }
        return out;
    }

    /** Intended rungs per group, oldest first. */
    public Map<Long, List<IntendedRung>> intents() {
        Map<Long, List<IntendedRung>> out = new LinkedHashMap<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT id, group_id, account, side, quantity, price, note, created_at,
                            symbol
                       FROM order_group_intent ORDER BY created_at, id""")) {
            while (rs.next()) {
                out.computeIfAbsent(rs.getLong(2), k -> new ArrayList<>()).add(readIntent(rs));
            }
        } catch (Exception e) {
            log.warn("Could not load intended rungs", e);
        }
        return out;
    }

    /** One intended rung, or null. Used when the user says an intent has been placed. */
    public IntendedRung intent(long id) {
        if (!connections.dbExists()) return null;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, group_id, account, side, quantity, price, note, created_at,
                            symbol
                       FROM order_group_intent WHERE id = ?""")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readIntent(rs) : null;
            }
        } catch (Exception e) {
            log.warn("Could not load intended rung {}", id, e);
            return null;
        }
    }

    private static IntendedRung readIntent(ResultSet rs) throws Exception {
        return new IntendedRung(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(9),
                rs.getString(4), decimal(rs, 5), decimal(rs, 6), rs.getString(7),
                LocalDate.parse(rs.getString(8)));
    }

    // ---- writes ------------------------------------------------------------

    /**
     * Create a group. Returns its id.
     *
     * @param symbolTo the second listing, for a share-class switch; null for a ladder. The storage
     *                 does not police which types use it — that is the assembler's rule, and this
     *                 table has stayed type-agnostic on purpose.
     */
    public long create(GroupType type, String name, String symbol, String symbolTo, String note,
                       LocalDate today) {
        if (type == null) throw new IllegalStateException("A group needs a type.");
        if (name == null || name.isBlank()) throw new IllegalStateException("Give the group a name.");
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO order_group (type, name, symbol, symbol_to, note, created_at) "
                             + "VALUES (?,?,?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, type.name());
            ps.setString(2, name.trim());
            ps.setString(3, symbol == null || symbol.isBlank() ? null : symbol.trim().toUpperCase());
            ps.setString(4, symbolTo == null || symbolTo.isBlank() ? null : symbolTo.trim().toUpperCase());
            ps.setString(5, note == null || note.isBlank() ? null : note.trim());
            ps.setString(6, today.toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not create the group: " + e.getMessage(), e);
        }
    }

    /**
     * Delete a group and everything hanging off it. The member rows go, but nothing in {@code
     * order_history} does: those orders are the broker's record and were only ever pointed at.
     * Intended rungs exist nowhere else, so deleting the group is the only thing that can remove
     * them and they go with it.
     */
    public void delete(long groupId) {
        try (Connection conn = connections.open()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                exec(conn, "DELETE FROM order_group_member WHERE group_id = ?", groupId);
                exec(conn, "DELETE FROM order_group_intent WHERE group_id = ?", groupId);
                exec(conn, "DELETE FROM order_group WHERE id = ?", groupId);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not delete the group: " + e.getMessage(), e);
        }
    }

    /**
     * Assign orders to a group. An order already in another group moves rather than being
     * duplicated - {@code order_key} is the primary key, so the upsert is the enforcement.
     */
    public void addMembers(long groupId, Collection<String> orderKeys, LocalDate today) {
        if (orderKeys == null || orderKeys.isEmpty()) return;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO order_group_member (order_key, group_id, added_at) VALUES (?,?,?)
                     ON CONFLICT(order_key) DO UPDATE SET group_id = excluded.group_id""")) {
            for (String key : orderKeys) {
                if (key == null || key.isBlank()) continue;
                ps.setString(1, key);
                ps.setLong(2, groupId);
                ps.setString(3, today.toString());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception e) {
            throw new IllegalStateException("Could not add the order(s): " + e.getMessage(), e);
        }
    }

    /** Take one order out of a group. The order itself is untouched. */
    public void removeMember(String orderKey) {
        try (Connection conn = connections.open()) {
            exec(conn, "DELETE FROM order_group_member WHERE order_key = ?", orderKey);
        } catch (Exception e) {
            throw new IllegalStateException("Could not remove the order: " + e.getMessage(), e);
        }
    }

    /**
     * Record a rung the user has decided on but not placed. Returns its id.
     *
     * @param symbol the listing the rung trades, or null to leave it unsaid. A ladder passes null —
     *               its rungs are the group's one security. A switch passes the listing, because
     *               either of its two lines can carry either side.
     */
    public long addIntent(long groupId, String account, String symbol, String side,
                          BigDecimal quantity, BigDecimal price, String note, LocalDate today) {
        if (account == null || account.isBlank()) throw new IllegalStateException("Pick an account.");
        if (side == null || side.isBlank()) throw new IllegalStateException("Pick a side.");
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalStateException("Give the rung a quantity above zero.");
        }
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO order_group_intent
                            (group_id, account, symbol, side, quantity, price, note, created_at)
                     VALUES (?,?,?,?,?,?,?,?)""", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, groupId);
            ps.setString(2, account.trim());
            ps.setString(3, symbol == null || symbol.isBlank() ? null : symbol.trim().toUpperCase());
            ps.setString(4, "sell".equalsIgnoreCase(side.trim()) ? "Sell" : "Buy");
            setDecimal(ps, 5, quantity);
            setDecimal(ps, 6, price);
            ps.setString(7, note == null || note.isBlank() ? null : note.trim());
            ps.setString(8, today.toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not record the intended rung: " + e.getMessage(), e);
        }
    }

    /**
     * Re-price or re-size a rung the user recorded here. Both figures are written together — the
     * caller has already decided which of them actually moved and passes the stored one back for
     * the other, so this stays one statement rather than a conditional SQL builder.
     *
     * <p>Quantity is guarded exactly as {@link #addIntent} guards it: a rung is a commitment against
     * cash, and one with no size is not a commitment. Price may be null — a rung decided on before
     * its level is is a real state, and the panel prices it at nothing rather than at zero.
     */
    public void updateIntent(long intentId, BigDecimal quantity, BigDecimal price) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalStateException("Give the rung a quantity above zero.");
        }
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE order_group_intent SET quantity = ?, price = ? WHERE id = ?")) {
            setDecimal(ps, 1, quantity);
            setDecimal(ps, 2, price);
            ps.setLong(3, intentId);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException("That intended rung is no longer on file.");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not update the intended rung: " + e.getMessage(), e);
        }
    }

    public void deleteIntent(long intentId) {
        try (Connection conn = connections.open()) {
            exec(conn, "DELETE FROM order_group_intent WHERE id = ?", intentId);
        } catch (Exception e) {
            throw new IllegalStateException("Could not delete the intended rung: " + e.getMessage(), e);
        }
    }

    private static void exec(Connection conn, String sql, Object arg) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (arg instanceof Long l) ps.setLong(1, l);
            else ps.setString(1, String.valueOf(arg));
            ps.executeUpdate();
        }
    }

    private static void setDecimal(PreparedStatement ps, int idx, BigDecimal v) throws Exception {
        if (v == null) ps.setNull(idx, Types.REAL);
        else ps.setDouble(idx, v.doubleValue());
    }

    private static BigDecimal decimal(ResultSet rs, int col) throws Exception {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : BigDecimal.valueOf(v);
    }
}
