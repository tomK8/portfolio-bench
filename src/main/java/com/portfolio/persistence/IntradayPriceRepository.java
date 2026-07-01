package com.portfolio.persistence;

import com.portfolio.domain.model.IntradayBar;
import com.portfolio.domain.model.IntradayPrice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 1-minute intraday close prices per ticker. {@code INSERT OR IGNORE} on
 * {@code (symbol, ts)} keeps re-fetches idempotent; old rows are pruned at the end of each
 * intraday fetch tick.
 */
public class IntradayPriceRepository {

    private static final Logger log = LoggerFactory.getLogger(IntradayPriceRepository.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS price_intraday (
                symbol     TEXT    NOT NULL,
                ts         TEXT    NOT NULL,
                close      REAL    NOT NULL,
                volume     INTEGER,
                currency   TEXT    NOT NULL,
                fetched_at TEXT    NOT NULL,
                PRIMARY KEY (symbol, ts)
            )""";
    private static final String CREATE_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_intraday_symbol_ts
                ON price_intraday(symbol, ts DESC)""";

    private final JdbcConnectionFactory connections;

    public IntradayPriceRepository(JdbcConnectionFactory connections) {
        this.connections = connections;
        try (Connection conn = connections.open(); Statement ddl = conn.createStatement()) {
            ddl.execute(CREATE_TABLE);
            ddl.execute(CREATE_INDEX);
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise price_intraday table", e);
        }
    }

    public int saveIntradayBars(List<IntradayBar> bars) {
        if (bars.isEmpty()) return 0;
        String fetchedAt = Instant.now().toString();
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT OR IGNORE INTO price_intraday " +
                             "(symbol, ts, close, volume, currency, fetched_at) " +
                             "VALUES (?, ?, ?, ?, ?, ?)")) {
            int inserted = 0;
            for (IntradayBar b : bars) {
                ps.setString(1, b.symbol());
                ps.setString(2, b.ts().toString());
                ps.setDouble(3, b.close());
                if (b.volume() != null) ps.setLong(4, b.volume());
                else ps.setNull(4, Types.INTEGER);
                ps.setString(5, b.currency());
                ps.setString(6, fetchedAt);
                inserted += ps.executeUpdate();
            }
            return inserted;
        } catch (Exception e) {
            log.warn("Could not save intraday bars", e);
            return 0;
        }
    }

    public Instant getLatestIntradayTs(String ticker) {
        if (!connections.dbExists()) return null;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT MAX(ts) FROM price_intraday WHERE symbol = ?")) {
            ps.setString(1, ticker);
            try (ResultSet rs = ps.executeQuery()) {
                String t = rs.next() ? rs.getString(1) : null;
                return t == null ? null : Instant.parse(t);
            }
        } catch (Exception e) {
            log.warn("Could not read latest intraday ts", e);
            return null;
        }
    }

    /** Bulk lookup: latest stored bar per ticker. One DB hit, not N. */
    public Map<String, IntradayPrice> loadLatestIntradayPrices(Collection<String> tickers) {
        Map<String, IntradayPrice> out = new HashMap<>();
        if (tickers.isEmpty() || !connections.dbExists()) return out;
        String sql = "SELECT p.symbol, p.ts, p.close, p.currency FROM price_intraday p " +
                "JOIN (SELECT symbol, MAX(ts) AS max_ts FROM price_intraday GROUP BY symbol) m " +
                "ON p.symbol = m.symbol AND p.ts = m.max_ts " +
                "WHERE p.symbol IN (" + placeholders(tickers.size()) + ")";
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            for (String t : tickers) ps.setString(i++, t);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1),
                            new IntradayPrice(Instant.parse(rs.getString(2)),
                                    rs.getDouble(3), rs.getString(4)));
                }
            }
        } catch (Exception e) {
            log.warn("Could not load latest intraday prices", e);
        }
        return out;
    }

    /**
     * All stored 1-minute bars for one ticker at or after {@code from}, oldest first. Feeds the
     * watchlist popup's fine-grained price chart for the sub-15-day windows. Retention (see
     * {@link com.portfolio.application.IntradayPriceFetchJob#RETENTION_DAYS}) bounds how far
     * back this can reach.
     */
    public List<IntradayBar> loadIntradaySeries(String ticker, Instant from) {
        List<IntradayBar> out = new java.util.ArrayList<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT symbol, ts, close, volume, currency FROM price_intraday " +
                             "WHERE symbol = ? AND ts >= ? ORDER BY ts")) {
            ps.setString(1, ticker);
            ps.setString(2, from.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long vol = rs.getLong(4);
                    Long volume = rs.wasNull() ? null : vol;
                    out.add(new IntradayBar(rs.getString(1), Instant.parse(rs.getString(2)),
                            rs.getDouble(3), volume, rs.getString(5)));
                }
            }
        } catch (Exception e) {
            log.warn("Could not load intraday series for {}", ticker, e);
        }
        return out;
    }

    public int pruneIntradayBefore(Instant cutoff) {
        if (!connections.dbExists()) return 0;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM price_intraday WHERE ts < ?")) {
            ps.setString(1, cutoff.toString());
            return ps.executeUpdate();
        } catch (Exception e) {
            log.warn("Could not prune intraday rows", e);
            return 0;
        }
    }

    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        return sb.toString();
    }
}
