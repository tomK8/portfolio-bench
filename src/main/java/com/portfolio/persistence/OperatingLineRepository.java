package com.portfolio.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.domain.model.OperatingLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cache for the EDGAR-sourced operating line — one row per internal symbol, refreshed on its
 * own cadence and merged into the fundamentals response at read time.
 *
 * <p><b>Why a separate table rather than the fundamentals blob.</b> {@code extra_json} is
 * written whole on every Yahoo refresh (every four hours), which is what makes a removed Yahoo
 * field migrate itself. Filings move quarterly, so folding EDGAR figures into that blob would
 * either re-fetch SEC every four hours for data that cannot have changed, or make the blob
 * depend on its own previous value. Two sources with two cadences get two tables, and the
 * merge happens where they are read together.
 *
 * <p>A withheld line is stored too — figures null, reason set. The panel needs to tell
 * "the filer never tagged operating income" from "we did not trust what it tagged", and a row
 * that is simply absent cannot say which.
 */
public class OperatingLineRepository {

    private static final Logger log = LoggerFactory.getLogger(OperatingLineRepository.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS edgar_operating_line (
                internal_symbol     TEXT PRIMARY KEY,
                operating_income    REAL,
                revenue             REAL,
                operating_margin    REAL,
                period_end          TEXT,
                unit                TEXT,
                revenue_concept     TEXT,
                derived_quarter_end TEXT,
                quarters_json       TEXT,
                withheld_reason     TEXT,
                fetched_at          TEXT NOT NULL
            )""";

    private static final String UPSERT_SQL = """
            INSERT INTO edgar_operating_line
                (internal_symbol, operating_income, revenue, operating_margin, period_end,
                 unit, revenue_concept, derived_quarter_end, quarters_json, withheld_reason,
                 fetched_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(internal_symbol) DO UPDATE SET
                operating_income    = excluded.operating_income,
                revenue             = excluded.revenue,
                operating_margin    = excluded.operating_margin,
                period_end          = excluded.period_end,
                unit                = excluded.unit,
                revenue_concept     = excluded.revenue_concept,
                derived_quarter_end = excluded.derived_quarter_end,
                quarters_json       = excluded.quarters_json,
                withheld_reason     = excluded.withheld_reason,
                fetched_at          = excluded.fetched_at""";

    private final JdbcConnectionFactory connections;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    public OperatingLineRepository(JdbcConnectionFactory connections) {
        this.connections = connections;
        try (Connection conn = connections.open(); Statement ddl = conn.createStatement()) {
            ddl.execute(CREATE_TABLE);
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise edgar_operating_line table", e);
        }
    }

    public void save(OperatingLine line) {
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {
            ps.setString(1, line.symbol());
            setNullableDecimal(ps, 2, line.operatingIncomeTtm());
            setNullableDecimal(ps, 3, line.revenueTtm());
            setNullableDecimal(ps, 4, line.operatingMarginTtm());
            setNullableString(ps, 5, line.periodEnd() == null ? null : line.periodEnd().toString());
            setNullableString(ps, 6, line.unit());
            setNullableString(ps, 7, line.revenueConcept());
            setNullableString(ps, 8, line.derivedQuarterEnd() == null
                    ? null : line.derivedQuarterEnd().toString());
            ps.setString(9, mapper.writeValueAsString(
                    line.quarters() == null ? List.of() : line.quarters()));
            setNullableString(ps, 10, line.withheldReason());
            ps.setString(11, line.fetchedAt().toString());
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("Could not save operating line for {}", line.symbol(), e);
        }
    }

    /** Every cached line, keyed by internal symbol. Empty on any read failure. */
    public Map<String, OperatingLine> loadAll() {
        Map<String, OperatingLine> out = new LinkedHashMap<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT internal_symbol, operating_income, revenue, operating_margin,
                            period_end, unit, revenue_concept, derived_quarter_end,
                            quarters_json, withheld_reason, fetched_at
                     FROM edgar_operating_line ORDER BY internal_symbol""")) {
            while (rs.next()) {
                String sym = rs.getString(1);
                out.put(sym, new OperatingLine(sym,
                        readDecimal(rs, 2), readDecimal(rs, 3), readDecimal(rs, 4),
                        readDate(rs.getString(5)), rs.getString(6), rs.getString(7),
                        readDate(rs.getString(8)), parseQuarters(rs.getString(9)),
                        rs.getString(10), Instant.parse(rs.getString(11))));
            }
        } catch (Exception e) {
            log.warn("Could not load operating lines", e);
        }
        return out;
    }

    private List<OperatingLine.Quarter> parseQuarters(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<List<OperatingLine.Quarter>>() {});
        } catch (Exception e) {
            log.warn("Could not parse operating-line quarters JSON", e);
            return List.of();
        }
    }

    private static LocalDate readDate(String s) {
        return (s == null || s.isBlank()) ? null : LocalDate.parse(s);
    }

    private static void setNullableString(PreparedStatement ps, int idx, String v)
            throws java.sql.SQLException {
        if (v == null) ps.setNull(idx, Types.VARCHAR);
        else ps.setString(idx, v);
    }

    private static void setNullableDecimal(PreparedStatement ps, int idx, BigDecimal v)
            throws java.sql.SQLException {
        if (v == null) ps.setNull(idx, Types.REAL);
        else ps.setDouble(idx, v.doubleValue());
    }

    private static BigDecimal readDecimal(ResultSet rs, int idx) throws java.sql.SQLException {
        double d = rs.getDouble(idx);
        return rs.wasNull() ? null : BigDecimal.valueOf(d);
    }
}
