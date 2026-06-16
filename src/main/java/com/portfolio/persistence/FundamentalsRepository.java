package com.portfolio.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.adapter.YahooQuoteSummaryFetcher.QuoteSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistent cache for the per-holding fundamentals snapshot — one row per internal symbol.
 * Reads never block on Yahoo: the dashboard renders whatever is here and the background
 * {@link com.portfolio.application.FundamentalsFetchJob} refreshes rows asynchronously.
 *
 * <p>Keyed on the user-facing internal symbol (e.g. {@code EQQQ}, not {@code EQQQ.L}) since
 * that's how the rest of the app addresses positions and the service strips the Yahoo
 * suffix before saving. {@code extra} and {@code labels} ride along as JSON blobs — they
 * feed the detail modal and stay flat key/value maps, so normalising would just add
 * schema churn for no query benefit.
 */
public class FundamentalsRepository {

    private static final Logger log = LoggerFactory.getLogger(FundamentalsRepository.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS fundamentals_snapshot (
                internal_symbol   TEXT    PRIMARY KEY,
                currency          TEXT,
                price             REAL,
                market_cap        REAL,
                trailing_pe       REAL,
                forward_pe        REAL,
                peg_ratio         REAL,
                beta              REAL,
                week52_high       REAL,
                week52_low        REAL,
                target_mean_price REAL,
                extra_json        TEXT,
                labels_json       TEXT,
                missing           INTEGER NOT NULL DEFAULT 0,
                fetched_at        TEXT    NOT NULL
            )""";

    private static final String UPSERT_SQL = """
            INSERT INTO fundamentals_snapshot
                (internal_symbol, currency, price, market_cap, trailing_pe, forward_pe,
                 peg_ratio, beta, week52_high, week52_low, target_mean_price,
                 extra_json, labels_json, missing, fetched_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(internal_symbol) DO UPDATE SET
                currency          = excluded.currency,
                price             = excluded.price,
                market_cap        = excluded.market_cap,
                trailing_pe       = excluded.trailing_pe,
                forward_pe        = excluded.forward_pe,
                peg_ratio         = excluded.peg_ratio,
                beta              = excluded.beta,
                week52_high       = excluded.week52_high,
                week52_low        = excluded.week52_low,
                target_mean_price = excluded.target_mean_price,
                extra_json        = excluded.extra_json,
                labels_json       = excluded.labels_json,
                missing           = excluded.missing,
                fetched_at        = excluded.fetched_at""";

    private final JdbcConnectionFactory connections;
    private final ObjectMapper mapper = new ObjectMapper();

    public FundamentalsRepository(JdbcConnectionFactory connections) {
        this.connections = connections;
        try (Connection conn = connections.open(); Statement ddl = conn.createStatement()) {
            ddl.execute(CREATE_TABLE);
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise fundamentals_snapshot table", e);
        }
    }

    public void save(String internalSymbol, QuoteSummary q, Instant fetchedAt) {
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {
            ps.setString(1, internalSymbol);
            setNullableString(ps, 2, q.currency());
            setNullableDecimal(ps, 3, q.price());
            setNullableDecimal(ps, 4, q.marketCap());
            setNullableDecimal(ps, 5, q.trailingPe());
            setNullableDecimal(ps, 6, q.forwardPe());
            setNullableDecimal(ps, 7, q.pegRatio());
            setNullableDecimal(ps, 8, q.beta());
            setNullableDecimal(ps, 9, q.week52High());
            setNullableDecimal(ps, 10, q.week52Low());
            setNullableDecimal(ps, 11, q.targetMeanPrice());
            ps.setString(12, mapper.writeValueAsString(q.extra() == null ? Map.of() : q.extra()));
            ps.setString(13, mapper.writeValueAsString(q.labels() == null ? Map.of() : q.labels()));
            ps.setInt(14, q.missing() ? 1 : 0);
            ps.setString(15, fetchedAt.toString());
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("Could not save fundamentals for {}", internalSymbol, e);
        }
    }

    /** Returns all cached rows, keyed by internal symbol. Insertion order = alphabetical by symbol. */
    public Map<String, Cached> loadAll() {
        Map<String, Cached> out = new LinkedHashMap<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT internal_symbol, currency, price, market_cap, trailing_pe, forward_pe, " +
                             "peg_ratio, beta, week52_high, week52_low, target_mean_price, " +
                             "extra_json, labels_json, missing, fetched_at " +
                             "FROM fundamentals_snapshot ORDER BY internal_symbol")) {
            while (rs.next()) {
                String sym = rs.getString(1);
                Map<String, BigDecimal> extra = parseExtra(rs.getString(12));
                Map<String, String> labels = parseLabels(rs.getString(13));
                QuoteSummary q = new QuoteSummary(
                        sym,
                        rs.getString(2),
                        readDecimal(rs, 3),
                        readDecimal(rs, 4),
                        readDecimal(rs, 5),
                        readDecimal(rs, 6),
                        readDecimal(rs, 7),
                        readDecimal(rs, 8),
                        readDecimal(rs, 9),
                        readDecimal(rs, 10),
                        readDecimal(rs, 11),
                        extra,
                        labels,
                        rs.getInt(14) != 0);
                out.put(sym, new Cached(q, Instant.parse(rs.getString(15))));
            }
        } catch (Exception e) {
            log.warn("Could not load fundamentals snapshot", e);
        }
        return out;
    }

    private Map<String, BigDecimal> parseExtra(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, new TypeReference<Map<String, BigDecimal>>() {});
        } catch (Exception e) {
            log.warn("Could not parse fundamentals extra JSON", e);
            return Map.of();
        }
    }

    private Map<String, String> parseLabels(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("Could not parse fundamentals labels JSON", e);
            return Map.of();
        }
    }

    private static void setNullableString(PreparedStatement ps, int idx, String v) throws java.sql.SQLException {
        if (v == null) ps.setNull(idx, Types.VARCHAR);
        else ps.setString(idx, v);
    }

    private static void setNullableDecimal(PreparedStatement ps, int idx, BigDecimal v) throws java.sql.SQLException {
        if (v == null) ps.setNull(idx, Types.REAL);
        else ps.setDouble(idx, v.doubleValue());
    }

    private static BigDecimal readDecimal(ResultSet rs, int idx) throws java.sql.SQLException {
        double d = rs.getDouble(idx);
        return rs.wasNull() ? null : BigDecimal.valueOf(d);
    }

    public record Cached(QuoteSummary quote, Instant fetchedAt) {}
}
