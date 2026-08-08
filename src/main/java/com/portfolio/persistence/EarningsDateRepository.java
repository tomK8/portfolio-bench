package com.portfolio.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Cache of earnings-proxy dates (§D). Populated once per symbol from the EDGAR filing-date proxy so
 * the swing report never re-hits SEC on every request. One row per {@code (symbol, date)};
 * {@code source} records where the date came from (e.g. "EDGAR 10-Q/10-K filing dates").
 *
 * <p>Follows the package convention: DDL runs once in the constructor; reads and the cache-fill write
 * degrade (log + return) rather than throwing — a missing earnings cache must never take down the
 * report.
 */
public class EarningsDateRepository {

    private static final Logger log = LoggerFactory.getLogger(EarningsDateRepository.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS earnings_dates (
                symbol     TEXT NOT NULL,
                date       TEXT NOT NULL,
                source     TEXT NOT NULL,
                fetched_at TEXT NOT NULL,
                PRIMARY KEY (symbol, date)
            )""";

    private final JdbcConnectionFactory connections;

    public EarningsDateRepository(JdbcConnectionFactory connections) {
        this.connections = connections;
        try (Connection conn = connections.open(); Statement ddl = conn.createStatement()) {
            ddl.execute(CREATE_TABLE);
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise earnings_dates table", e);
        }
    }

    /** Replace the cached dates for {@code symbol}. No-op on an empty list. */
    public void saveDates(String symbol, List<LocalDate> dates, String source) {
        if (dates == null || dates.isEmpty()) return;
        String fetchedAt = Instant.now().toString();
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT OR REPLACE INTO earnings_dates (symbol, date, source, fetched_at) VALUES (?, ?, ?, ?)")) {
            for (LocalDate d : dates) {
                ps.setString(1, symbol);
                ps.setString(2, d.toString());
                ps.setString(3, source);
                ps.setString(4, fetchedAt);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            log.warn("Could not save earnings dates for {}", symbol, e);
        }
    }

    /** Cached dates for {@code symbol}, ascending; empty when none stored. */
    public List<LocalDate> datesFor(String symbol) {
        List<LocalDate> out = new ArrayList<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT date FROM earnings_dates WHERE symbol = ? ORDER BY date")) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(LocalDate.parse(rs.getString(1)));
            }
        } catch (Exception e) {
            log.warn("Could not read earnings dates for {}", symbol, e);
        }
        return out;
    }

    /** Whether any dates are cached for {@code symbol}. */
    public boolean hasAny(String symbol) {
        if (!connections.dbExists()) return false;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM earnings_dates WHERE symbol = ? LIMIT 1")) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            log.warn("Could not check earnings cache for {}", symbol, e);
            return false;
        }
    }
}
