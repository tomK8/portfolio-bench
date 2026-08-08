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
 * two alert thresholds. One row per symbol.
 *
 * <p>The symbol set is <em>mirrored</em> into {@link KeyValueStore} under
 * {@link #WATCHLIST_SYMBOLS_KEY} on every mutation. The price-fetch jobs and the fundamentals
 * job already read held symbols from the KV store; mirroring lets them union the watchlist in
 * without threading this repository through their constructors — so a freshly added name gets
 * daily prices, intraday quotes and a fundamentals row on the next tick.
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
                display_order      INTEGER
            )""";

    private final JdbcConnectionFactory connections;
    private final KeyValueStore keyValueStore;

    public WatchlistRepository(JdbcConnectionFactory connections, KeyValueStore keyValueStore) {
        this.connections = connections;
        this.keyValueStore = keyValueStore;
        try (Connection conn = connections.open(); Statement ddl = conn.createStatement()) {
            ddl.execute(CREATE_TABLE);
            try {
                ddl.execute("ALTER TABLE watchlist ADD COLUMN display_order INTEGER");
            } catch (java.sql.SQLException alreadyMigrated) {
                // display_order column already present — fine
            }
            // Backfill any rows that predate the column so ordering is stable and non-null.
            ddl.execute("UPDATE watchlist SET display_order = rowid WHERE display_order IS NULL");
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise watchlist table", e);
        }
    }

    /** Insert a new symbol or update its thresholds if already present. Re-mirrors the KV set. */
    public void add(String symbol, BigDecimal highPct, BigDecimal movePct) {
        String sym = normalise(symbol);
        if (sym.isEmpty()) throw new IllegalStateException("Watchlist symbol must not be blank.");
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO watchlist (symbol, high_threshold_pct, move_threshold_pct, added_at, display_order) " +
                             "VALUES (?, ?, ?, ?, (SELECT COALESCE(MAX(display_order), 0) + 1 FROM watchlist)) " +
                             "ON CONFLICT(symbol) DO UPDATE SET " +
                             "high_threshold_pct = excluded.high_threshold_pct, " +
                             "move_threshold_pct = excluded.move_threshold_pct")) {
            ps.setString(1, sym);
            ps.setDouble(2, orDisabled(highPct).doubleValue());
            ps.setDouble(3, orDisabled(movePct).doubleValue());
            ps.setString(4, Instant.now().toString());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Could not add " + sym + " to the watchlist.", e);
        }
        mirrorKv();
    }

    public void updateThresholds(String symbol, BigDecimal highPct, BigDecimal movePct) {
        String sym = normalise(symbol);
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE watchlist SET high_threshold_pct = ?, move_threshold_pct = ? WHERE symbol = ?")) {
            ps.setDouble(1, orDisabled(highPct).doubleValue());
            ps.setDouble(2, orDisabled(movePct).doubleValue());
            ps.setString(3, sym);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException(sym + " is not on the watchlist.");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not update thresholds for " + sym + ".", e);
        }
    }

    public void remove(String symbol) {
        String sym = normalise(symbol);
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM watchlist WHERE symbol = ?")) {
            ps.setString(1, sym);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Could not remove " + sym + " from the watchlist.", e);
        }
        mirrorKv();
    }

    /** All watchlist rows, in the user's manual display order. Degrades to empty on read failure. */
    public List<Entry> loadAll() {
        List<Entry> out = new ArrayList<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT symbol, high_threshold_pct, move_threshold_pct, added_at " +
                             "FROM watchlist ORDER BY display_order, symbol")) {
            while (rs.next()) {
                out.add(new Entry(rs.getString(1),
                        BigDecimal.valueOf(rs.getDouble(2)),
                        BigDecimal.valueOf(rs.getDouble(3)),
                        Instant.parse(rs.getString(4))));
            }
        } catch (Exception e) {
            log.warn("Could not load watchlist", e);
        }
        return out;
    }

    /**
     * Move a symbol one place up or down in the manual display order by swapping its
     * {@code display_order} with its neighbour's. No-op at the ends of the list or if the
     * symbol isn't present. The symbol set is unchanged, so no KV re-mirror is needed.
     */
    public void move(String symbol, boolean up) {
        String sym = normalise(symbol);
        try (Connection conn = connections.open()) {
            List<String> syms = new ArrayList<>();
            List<Long> orders = new ArrayList<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT symbol, display_order FROM watchlist ORDER BY display_order, symbol")) {
                while (rs.next()) {
                    syms.add(rs.getString(1));
                    orders.add(rs.getLong(2));
                }
            }
            int idx = syms.indexOf(sym);
            if (idx < 0) return;
            int neighbour = up ? idx - 1 : idx + 1;
            if (neighbour < 0 || neighbour >= syms.size()) return;    // already at an end
            setOrder(conn, syms.get(idx), orders.get(neighbour));
            setOrder(conn, syms.get(neighbour), orders.get(idx));
        } catch (Exception e) {
            throw new IllegalStateException("Could not reorder " + sym + " on the watchlist.", e);
        }
    }

    private static void setOrder(Connection conn, String symbol, long order) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE watchlist SET display_order = ? WHERE symbol = ?")) {
            ps.setLong(1, order);
            ps.setString(2, symbol);
            ps.executeUpdate();
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
                        Instant addedAt) {
    }
}
