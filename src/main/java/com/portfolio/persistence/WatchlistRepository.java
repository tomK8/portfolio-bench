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

    /** Sensible starting thresholds when a symbol is added without explicit values. */
    public static final BigDecimal DEFAULT_HIGH_PCT = new BigDecimal("3");
    public static final BigDecimal DEFAULT_MOVE_PCT = new BigDecimal("10");

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS watchlist (
                symbol             TEXT    PRIMARY KEY,
                high_threshold_pct REAL    NOT NULL,
                move_threshold_pct REAL    NOT NULL,
                added_at           TEXT    NOT NULL
            )""";

    private final JdbcConnectionFactory connections;
    private final KeyValueStore keyValueStore;

    public WatchlistRepository(JdbcConnectionFactory connections, KeyValueStore keyValueStore) {
        this.connections = connections;
        this.keyValueStore = keyValueStore;
        try (Connection conn = connections.open(); Statement ddl = conn.createStatement()) {
            ddl.execute(CREATE_TABLE);
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
                     "INSERT INTO watchlist (symbol, high_threshold_pct, move_threshold_pct, added_at) " +
                             "VALUES (?, ?, ?, ?) " +
                             "ON CONFLICT(symbol) DO UPDATE SET " +
                             "high_threshold_pct = excluded.high_threshold_pct, " +
                             "move_threshold_pct = excluded.move_threshold_pct")) {
            ps.setString(1, sym);
            ps.setDouble(2, orDefault(highPct, DEFAULT_HIGH_PCT).doubleValue());
            ps.setDouble(3, orDefault(movePct, DEFAULT_MOVE_PCT).doubleValue());
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
            ps.setDouble(1, orDefault(highPct, DEFAULT_HIGH_PCT).doubleValue());
            ps.setDouble(2, orDefault(movePct, DEFAULT_MOVE_PCT).doubleValue());
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

    /** All watchlist rows, alphabetical by symbol. Degrades to empty on read failure. */
    public List<Entry> loadAll() {
        List<Entry> out = new ArrayList<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT symbol, high_threshold_pct, move_threshold_pct, added_at " +
                             "FROM watchlist ORDER BY symbol")) {
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

    private void mirrorKv() {
        List<String> symbols = new ArrayList<>();
        for (Entry e : loadAll()) symbols.add(e.symbol());
        keyValueStore.putStringSet(WATCHLIST_SYMBOLS_KEY, symbols);
    }

    private static String normalise(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase();
    }

    private static BigDecimal orDefault(BigDecimal v, BigDecimal dflt) {
        return (v == null || v.signum() < 0) ? dflt : v;
    }

    public record Entry(String symbol, BigDecimal highThresholdPct, BigDecimal moveThresholdPct,
                        Instant addedAt) {
    }
}
