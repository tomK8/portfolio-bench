package com.portfolio.application;

import com.portfolio.persistence.JdbcConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight ops snapshot — row counts per table, freshness markers, JVM uptime.
 *
 * <p>Reads the SQLite tables directly rather than threading through every repository;
 * the queries are read-only one-off counts so the duplication is acceptable for the
 * convenience of a single self-contained class. Each query handles its own
 * {@link SQLException} (logs WARN, leaves the field null) — health endpoints must
 * never fail end-to-end on a single missing column.
 */
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);
    private static final Instant STARTUP_INSTANT = Instant.now();

    private final JdbcConnectionFactory connections;

    public HealthService(JdbcConnectionFactory connections) {
        this.connections = connections;
    }

    public Health status() {
        Map<String, TableStat> tables = new LinkedHashMap<>();
        tables.put("price_history",
                tableStat("price_history", "date", null));
        tables.put("price_intraday",
                tableStat("price_intraday", "ts", null));
        tables.put("cash_transactions",
                tableStat("cash_transactions", "transaction_date", null));
        tables.put("portfolio_snapshots",
                tableStat("portfolio_snapshots", "snapshot_date", null));

        List<String> tickersFresh24h = new ArrayList<>();
        List<String> tickersStale = new ArrayList<>();
        scanFreshness("price_intraday", tickersFresh24h, tickersStale,
                Instant.now().minusSeconds(60L * 60L * 24L).toString());

        return new Health(STARTUP_INSTANT.toString(),
                Instant.now().toString(),
                tables, tickersFresh24h.size(), tickersStale.size());
    }

    /**
     * For a given table, fetch row count and the max value of {@code latestColumn}. Both
     * fields are wrapped in a try/catch so a missing table or column degrades to nulls
     * rather than failing the whole endpoint.
     */
    private TableStat tableStat(String table, String latestColumn, String tickerColumn) {
        Long rows = null;
        String latest = null;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*), MAX(" + latestColumn + ") FROM " + table)) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    rows = rs.getLong(1);
                    latest = rs.getString(2);
                }
            }
        } catch (SQLException | java.io.IOException e) {
            log.warn("Health: could not read {} stats", table, e);
        }
        return new TableStat(rows, latest);
    }

    private void scanFreshness(String table, List<String> fresh, List<String> stale,
                               String cutoffIso) {
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT symbol, MAX(ts) FROM " + table + " GROUP BY symbol")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sym = rs.getString(1);
                    String ts = rs.getString(2);
                    if (ts == null) continue;
                    if (ts.compareTo(cutoffIso) >= 0) fresh.add(sym);
                    else stale.add(sym);
                }
            }
        } catch (SQLException | java.io.IOException e) {
            log.warn("Health: could not scan {} freshness", table, e);
        }
    }

    // ---- DTOs ---------------------------------------------------------------

    /**
     * One table's stats. {@code rows} is the COUNT(*); {@code latest} is the max value of
     * the date / ts column (ISO string, formatted by SQLite verbatim).
     */
    public record TableStat(Long rows, String latest) {
    }

    public record Health(String startupAt,
                         String now,
                         Map<String, TableStat> tables,
                         int intradayTickersFresh24h,
                         int intradayTickersStale) {
    }
}
