package com.portfolio.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * On-disk cache of historical GBP FX rates — one row per {@code (currency, date)}, rate in the
 * app-wide convention of <em>units of foreign currency per 1 GBP</em>.
 *
 * <p>Exists because every historical view (attribution, value-over-time, allocation, scenarios,
 * currency exposure, …) re-fetched its own multi-year series from Frankfurter on every request.
 * A single timeout then silently zeroed every non-GBP position on one boundary — see
 * {@link com.portfolio.application.CachedHistoricalFxRateProvider}, which owns the refresh policy
 * on top of this table. Published rates for past dates never change, so the cache is
 * indefinitely valid; only the tail is ever refreshed.
 *
 * <p>Small by construction: the full 2000→today history for USD and EUR is ~13,600 rows.
 *
 * <p>Follows the package convention for a derived cache: DDL runs once in the constructor, and
 * both reads and writes degrade (log + empty/no-op) rather than throwing — losing the cache must
 * never take down a chart.
 */
public class FxRateRepository {

    private static final Logger log = LoggerFactory.getLogger(FxRateRepository.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS fx_rates (
                currency TEXT NOT NULL,
                date     TEXT NOT NULL,
                rate     REAL NOT NULL,
                PRIMARY KEY (currency, date)
            )""";

    private final JdbcConnectionFactory connections;

    public FxRateRepository(JdbcConnectionFactory connections) {
        this.connections = connections;
        try (Connection conn = connections.open(); Statement ddl = conn.createStatement()) {
            ddl.execute(CREATE_TABLE);
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise fx_rates table", e);
        }
    }

    /** Cached rates for {@code currency} over the inclusive {@code [from, to]} range. */
    public NavigableMap<LocalDate, BigDecimal> load(String currency, LocalDate from, LocalDate to) {
        NavigableMap<LocalDate, BigDecimal> out = new TreeMap<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT date, rate FROM fx_rates WHERE currency = ? AND date BETWEEN ? AND ? ORDER BY date")) {
            ps.setString(1, currency);
            ps.setString(2, from.toString());
            ps.setString(3, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(LocalDate.parse(rs.getString(1)), BigDecimal.valueOf(rs.getDouble(2)));
                }
            }
        } catch (Exception e) {
            log.warn("Could not load cached FX rates for {}", currency, e);
        }
        return out;
    }

    /** Upsert — a re-fetched tail overwrites whatever was stored for the same dates. */
    public int save(String currency, Map<LocalDate, BigDecimal> rates) {
        if (rates == null || rates.isEmpty()) return 0;
        int written = 0;
        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO fx_rates (currency, date, rate) VALUES (?, ?, ?)")) {
                for (Map.Entry<LocalDate, BigDecimal> e : rates.entrySet()) {
                    ps.setString(1, currency);
                    ps.setString(2, e.getKey().toString());
                    ps.setDouble(3, e.getValue().doubleValue());
                    ps.addBatch();
                }
                for (int r : ps.executeBatch()) if (r >= 0 || r == Statement.SUCCESS_NO_INFO) written++;
            }
            conn.commit();
        } catch (Exception e) {
            log.warn("Could not cache {} FX rates for {}", rates.size(), currency, e);
            return 0;
        }
        return written;
    }

    /** Oldest cached date for {@code currency}, or null when nothing is stored. */
    public LocalDate earliest(String currency) {
        return boundary(currency, "MIN");
    }

    /** Newest cached date for {@code currency}, or null when nothing is stored. */
    public LocalDate latest(String currency) {
        return boundary(currency, "MAX");
    }

    private LocalDate boundary(String currency, String fn) {
        if (!connections.dbExists()) return null;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT " + fn + "(date) FROM fx_rates WHERE currency = ?")) {
            ps.setString(1, currency);
            try (ResultSet rs = ps.executeQuery()) {
                String d = rs.next() ? rs.getString(1) : null;
                return d == null ? null : LocalDate.parse(d);
            }
        } catch (Exception e) {
            log.warn("Could not read cached FX {} date for {}", fn, currency, e);
            return null;
        }
    }
}
