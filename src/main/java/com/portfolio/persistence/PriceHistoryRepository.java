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
import java.util.List;

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
    private static final String CREATE_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_price_symbol_date
                ON price_history(symbol, date DESC)""";

    private final JdbcConnectionFactory connections;

    public PriceHistoryRepository(JdbcConnectionFactory connections) {
        this.connections = connections;
        try (Connection conn = connections.open(); Statement ddl = conn.createStatement()) {
            ddl.execute(CREATE_TABLE);
            ddl.execute(CREATE_INDEX);
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise price_history table", e);
        }
    }

    public int savePriceBars(List<PriceBar> bars) {
        if (bars.isEmpty()) return 0;
        String fetchedAt = Instant.now().toString();
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT OR IGNORE INTO price_history " +
                             "(symbol, date, open, high, low, close, adj_close, volume, currency, fetched_at) " +
                             "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            int inserted = 0;
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
            return inserted;
        } catch (Exception e) {
            log.warn("Could not save price bars", e);
            return 0;
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
                             "(symbol, date, open, high, low, close, adj_close, volume, currency, fetched_at) " +
                             "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                             "ON CONFLICT(symbol, date) DO UPDATE SET " +
                             "open = excluded.open, high = excluded.high, low = excluded.low, " +
                             "close = excluded.close, adj_close = excluded.adj_close, " +
                             "volume = excluded.volume, currency = excluded.currency, " +
                             "fetched_at = excluded.fetched_at")) {
            int touched = 0;
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
                touched += ps.executeUpdate();
            }
            return touched;
        } catch (Exception e) {
            log.warn("Could not upsert price bars", e);
            return 0;
        }
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
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, symbol);
            ps.setString(2, a);
            if (b != null) ps.setString(3, b);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sym = rs.getString(1);
                    LocalDate d = LocalDate.parse(rs.getString(2));
                    Double open = getNullableDouble(rs, 3);
                    Double high = getNullableDouble(rs, 4);
                    Double low = getNullableDouble(rs, 5);
                    double close = rs.getDouble(6);
                    double adjClose = rs.getDouble(7);
                    long vol = rs.getLong(8);
                    Long volume = rs.wasNull() ? null : vol;
                    out.add(new PriceBar(sym, d, open, high, low, close, adjClose,
                            volume, rs.getString(9)));
                }
            }
        } catch (Exception e) {
            log.warn("Could not read price history", e);
        }
        return out;
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
