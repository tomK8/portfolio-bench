package com.portfolio.persistence;

import com.portfolio.domain.model.PriceBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Daily OHLCV bars per symbol. {@code INSERT OR IGNORE} on the {@code (symbol, date)} PK
 * means re-writes are idempotent: historical {@code adj_close} is <em>not</em> refreshed
 * as later dividends/splits accrue — a future full re-pull (keyed off {@code fetched_at})
 * can fix that if needed.
 */
public class PriceHistoryRepository {

    private static final Logger log = LoggerFactory.getLogger(PriceHistoryRepository.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS price_history (
                symbol       TEXT    NOT NULL,
                date         TEXT    NOT NULL,
                open         REAL,
                high         REAL,
                low          REAL,
                close        REAL    NOT NULL,
                adj_close    REAL    NOT NULL,
                split_factor REAL    NOT NULL DEFAULT 1.0,
                volume       INTEGER,
                currency     TEXT    NOT NULL,
                fetched_at   TEXT    NOT NULL,
                PRIMARY KEY (symbol, date)
            )""";
    private static final String CREATE_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_price_symbol_date
                ON price_history(symbol, date DESC)""";

    private final JdbcConnectionFactory connections;

    public PriceHistoryRepository(JdbcConnectionFactory connections) {
        this.connections = connections;
        try (Connection conn = connections.open(); Statement ddl = conn.createStatement()) {
            ddl.execute(CREATE_TABLE);
            ddl.execute(CREATE_INDEX);
            try {
                ddl.execute("ALTER TABLE price_history ADD COLUMN split_factor REAL NOT NULL DEFAULT 1.0");
            } catch (SQLException alreadyMigrated) {
                // split_factor column already present — fine
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise price_history table", e);
        }
    }

    /**
     * Commit bulk writes in chunks of this many rows rather than one transaction for the whole
     * batch. SQLite has a single writer for the entire database file, so a multi-thousand-row
     * backfill committed atomically holds that lock for seconds and starves interactive writes
     * (a watchlist add/remove) until it finishes. Committing every chunk releases the lock
     * frequently so those small writes interleave. Price backfills are idempotent/incremental,
     * so a failure that leaves earlier chunks committed self-heals on the next fetch.
     */
    private static final int WRITE_CHUNK_ROWS = 500;

    public int savePriceBars(List<PriceBar> bars) {
        if (bars.isEmpty()) return 0;
        String fetchedAt = Instant.now().toString();
        int inserted = 0;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT OR IGNORE INTO price_history " +
                             "(symbol, date, open, high, low, close, adj_close, split_factor, volume, currency, fetched_at) " +
                             "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            conn.setAutoCommit(false);
            int inBatch = 0;
            for (PriceBar b : bars) {
                bindBar(ps, b, fetchedAt);
                ps.addBatch();
                if (++inBatch >= WRITE_CHUNK_ROWS) {
                    for (int n : ps.executeBatch()) if (n > 0) inserted += n;
                    conn.commit();
                    inBatch = 0;
                }
            }
            if (inBatch > 0) {
                for (int n : ps.executeBatch()) if (n > 0) inserted += n;
                conn.commit();
            }
            return inserted;
        } catch (Exception e) {
            log.warn("Could not save price bars", e);
            return inserted;
        }
    }

    /**
     * Last-write-wins upsert. Used by the gilt batch import (so re-importing a Tradeweb file
     * refreshes any earlier intraday-derived row) and by the gilt intraday rollup (so the most
     * recent intraday price each day replaces the prior in-day write).
     *
     * <p>Returns total rows touched (inserts + updates). SQLite's {@code ON CONFLICT … DO UPDATE}
     * reports updates as affected rows, so this counts both.
     */
    public int upsertPriceBars(List<PriceBar> bars) {
        if (bars.isEmpty()) return 0;
        String fetchedAt = Instant.now().toString();
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO price_history " +
                             "(symbol, date, open, high, low, close, adj_close, split_factor, volume, currency, fetched_at) " +
                             "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                             "ON CONFLICT(symbol, date) DO UPDATE SET " +
                             "open = excluded.open, high = excluded.high, low = excluded.low, " +
                             "close = excluded.close, adj_close = excluded.adj_close, " +
                             "split_factor = excluded.split_factor, " +
                             "volume = excluded.volume, currency = excluded.currency, " +
                             "fetched_at = excluded.fetched_at")) {
            conn.setAutoCommit(false);
            int touched = 0, inBatch = 0;
            for (PriceBar b : bars) {
                bindBar(ps, b, fetchedAt);
                ps.addBatch();
                if (++inBatch >= WRITE_CHUNK_ROWS) {   // release the DB-wide writer lock between chunks
                    for (int n : ps.executeBatch()) if (n > 0) touched += n;
                    conn.commit();
                    inBatch = 0;
                }
            }
            if (inBatch > 0) {
                for (int n : ps.executeBatch()) if (n > 0) touched += n;
                conn.commit();
            }
            return touched;
        } catch (Exception e) {
            log.warn("Could not upsert price bars", e);
            return 0;
        }
    }

    /** Shared column binding for the two bulk write paths (identical column lists). */
    private static void bindBar(PreparedStatement ps, PriceBar b, String fetchedAt) throws SQLException {
        ps.setString(1, b.symbol());
        ps.setString(2, b.date().toString());
        setNullableDouble(ps, 3, b.open());
        setNullableDouble(ps, 4, b.high());
        setNullableDouble(ps, 5, b.low());
        ps.setDouble(6, b.close());
        ps.setDouble(7, b.adjClose());
        ps.setDouble(8, b.splitFactor());
        if (b.volume() != null) ps.setLong(9, b.volume());
        else ps.setNull(9, Types.INTEGER);
        ps.setString(10, b.currency());
        ps.setString(11, fetchedAt);
    }

    public LocalDate getLatestPriceDate(String symbol) {
        if (!connections.dbExists()) return null;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT MAX(date) FROM price_history WHERE symbol = ?")) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                String d = rs.next() ? rs.getString(1) : null;
                return d == null ? null : LocalDate.parse(d);
            }
        } catch (Exception e) {
            log.warn("Could not read latest price date", e);
            return null;
        }
    }

    public LocalDate getEarliestPriceDate(String symbol) {
        if (!connections.dbExists()) return null;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT MIN(date) FROM price_history WHERE symbol = ?")) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                String d = rs.next() ? rs.getString(1) : null;
                return d == null ? null : LocalDate.parse(d);
            }
        } catch (Exception e) {
            log.warn("Could not read earliest price date", e);
            return null;
        }
    }

    public PriceBar getPriceOn(String symbol, LocalDate date) {
        return queryBars("SELECT symbol, date, open, high, low, close, adj_close, split_factor, volume, currency " +
                        "FROM price_history WHERE symbol = ? AND date <= ? ORDER BY date DESC LIMIT 1",
                symbol, date.toString(), null).stream().findFirst().orElse(null);
    }

    public List<PriceBar> getPriceHistory(String symbol, LocalDate from, LocalDate to) {
        return queryBars("SELECT symbol, date, open, high, low, close, adj_close, split_factor, volume, currency " +
                        "FROM price_history WHERE symbol = ? AND date BETWEEN ? AND ? ORDER BY date",
                symbol, from.toString(), to.toString());
    }

    private List<PriceBar> queryBars(String sql, String symbol, String a, String b) {
        List<PriceBar> out = new ArrayList<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, symbol);
            ps.setString(2, a);
            if (b != null) ps.setString(3, b);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(readBar(rs));
            }
        } catch (Exception e) {
            log.warn("Could not read price history", e);
        }
        return out;
    }

    /**
     * The newest bar at or before {@code asOf} for each of {@code symbols}, in one query.
     *
     * <p>The "as-of" floor lookup boundary valuations need. Bulk, not N round-trips — same shape as
     * {@link IntradayPriceRepository#loadLatestIntradayPrices}. Callers that only need two points
     * per symbol (a window's start and end) must use this rather than pulling whole series:
     * {@code AttributionService} loaded ~245,000 rows per request to read 94 of them.
     *
     * <p>Symbols with no bar at or before {@code asOf} are simply absent from the result; pair with
     * {@link #loadEarliestPrices} when a ceil-fill fallback is wanted.
     */
    public Map<String, PriceBar> loadPricesAsOf(Collection<String> symbols, LocalDate asOf) {
        if (symbols.isEmpty()) return new HashMap<>();
        return queryBarsBySymbol(
                "SELECT p.symbol, p.date, p.open, p.high, p.low, p.close, p.adj_close, " +
                        "p.split_factor, p.volume, p.currency FROM price_history p " +
                        "JOIN (SELECT symbol, MAX(date) AS d FROM price_history " +
                        "      WHERE symbol IN (" + placeholders(symbols.size()) + ") AND date <= ? " +
                        "      GROUP BY symbol) m ON p.symbol = m.symbol AND p.date = m.d",
                symbols, asOf.toString());
    }

    /** The oldest bar held for each of {@code symbols}, in one query — the ceil-fill fallback. */
    public Map<String, PriceBar> loadEarliestPrices(Collection<String> symbols) {
        if (symbols.isEmpty()) return new HashMap<>();
        return queryBarsBySymbol(
                "SELECT p.symbol, p.date, p.open, p.high, p.low, p.close, p.adj_close, " +
                        "p.split_factor, p.volume, p.currency FROM price_history p " +
                        "JOIN (SELECT symbol, MIN(date) AS d FROM price_history " +
                        "      WHERE symbol IN (" + placeholders(symbols.size()) + ") " +
                        "      GROUP BY symbol) m ON p.symbol = m.symbol AND p.date = m.d",
                symbols, null);
    }

    private Map<String, PriceBar> queryBarsBySymbol(String sql, Collection<String> symbols, String asOf) {
        Map<String, PriceBar> out = new HashMap<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            for (String s : symbols) ps.setString(i++, s);
            if (asOf != null) ps.setString(i, asOf);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), readBar(rs));
            }
        } catch (Exception e) {
            log.warn("Could not bulk-read price history", e);
        }
        return out;
    }

    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        return sb.toString();
    }

    /** Reads one row in the canonical column order: symbol, date, OHLC, adj_close, split, volume, currency. */
    private static PriceBar readBar(ResultSet rs) throws SQLException {
        long vol = rs.getLong(9);
        Long volume = rs.wasNull() ? null : vol;
        return new PriceBar(rs.getString(1), LocalDate.parse(rs.getString(2)),
                getNullableDouble(rs, 3), getNullableDouble(rs, 4), getNullableDouble(rs, 5),
                rs.getDouble(6), rs.getDouble(7), rs.getDouble(8), volume, rs.getString(10));
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
