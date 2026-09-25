package com.portfolio.persistence;

import com.portfolio.domain.model.EarningsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cache of the Nasdaq earnings calendar so the Earnings tab is fast and resilient — a repeat view or
 * a source outage serves last-known rows instead of re-hitting (or waiting on) Nasdaq. Two tables:
 *
 * <ul>
 *   <li>{@code earnings_calendar} — one row per {@code (date, symbol)} report.
 *   <li>{@code earnings_calendar_fetch} — one row per fetched {@code date} recording {@code fetched_at}
 *       and the event count. This bookkeeping is what lets a <b>zero-report day</b> be cached: without
 *       it, an empty roster would look identical to "never fetched" and be re-fetched every view.
 * </ul>
 *
 * <p>Package convention: DDL runs once in the constructor; reads and the cache-fill write degrade
 * (log + return) rather than throwing — the calendar cache must never take down the tab.
 */
public class EarningsCalendarRepository {

    private static final Logger log = LoggerFactory.getLogger(EarningsCalendarRepository.class);

    private static final String CREATE_ROWS = """
            CREATE TABLE IF NOT EXISTS earnings_calendar (
                date          TEXT NOT NULL,
                symbol        TEXT NOT NULL,
                company       TEXT,
                time          TEXT,
                market_cap    INTEGER,
                eps_forecast  REAL,
                num_estimates INTEGER,
                last_year_eps REAL,
                PRIMARY KEY (date, symbol)
            )""";

    private static final String CREATE_FETCH = """
            CREATE TABLE IF NOT EXISTS earnings_calendar_fetch (
                date        TEXT PRIMARY KEY,
                fetched_at  TEXT NOT NULL,
                event_count INTEGER NOT NULL
            )""";

    private final JdbcConnectionFactory connections;

    public EarningsCalendarRepository(JdbcConnectionFactory connections) {
        this.connections = connections;
        try (Connection conn = connections.open(); Statement ddl = conn.createStatement()) {
            ddl.execute(CREATE_ROWS);
            ddl.execute(CREATE_FETCH);
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise earnings_calendar tables", e);
        }
    }

    /**
     * Replace the cached roster for {@code date} with {@code events} and record the fetch. Runs in one
     * transaction so a reader never sees a half-swapped day. Replace-per-date (not upsert-per-row) so a
     * company dropped from the roster since the last fetch disappears from the cache too.
     */
    public void save(LocalDate date, List<EarningsEvent> events) {
        if (date == null || events == null) return;
        String d = date.toString();
        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM earnings_calendar WHERE date = ?")) {
                    del.setString(1, d);
                    del.executeUpdate();
                }
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO earnings_calendar (date, symbol, company, time, market_cap, "
                                + "eps_forecast, num_estimates, last_year_eps) VALUES (?,?,?,?,?,?,?,?)")) {
                    for (EarningsEvent e : events) {
                        ins.setString(1, d);
                        ins.setString(2, e.symbol());
                        ins.setString(3, e.companyName());
                        ins.setString(4, e.time());
                        setNullableLong(ins, 5, e.marketCap());
                        setNullableDouble(ins, 6, e.epsForecast());
                        setNullableInt(ins, 7, e.numEstimates());
                        setNullableDouble(ins, 8, e.lastYearEps());
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
                try (PreparedStatement meta = conn.prepareStatement(
                        "INSERT OR REPLACE INTO earnings_calendar_fetch (date, fetched_at, event_count) "
                                + "VALUES (?,?,?)")) {
                    meta.setString(1, d);
                    meta.setString(2, Instant.now().toString());
                    meta.setInt(3, events.size());
                    meta.executeUpdate();
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (Exception e) {
            log.warn("Could not cache earnings calendar for {}", date, e);
        }
    }

    /** When {@code date} was last fetched, or {@code null} if never (so the caller fetches). */
    public Instant lastFetched(LocalDate date) {
        if (!connections.dbExists()) return null;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT fetched_at FROM earnings_calendar_fetch WHERE date = ?")) {
            ps.setString(1, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Instant.parse(rs.getString(1));
            }
        } catch (Exception e) {
            log.warn("Could not read earnings-calendar fetch time for {}", date, e);
        }
        return null;
    }

    /**
     * The dates on which {@code symbol} is scheduled to report inside {@code [from, to]}, ascending
     * — the by-symbol view of a cache that is otherwise keyed by date.
     *
     * <p>Reads only what has already been fetched, and says nothing about coverage: an empty list
     * means "no print cached in that window", which is a different thing from "no print due" and a
     * caller that treats them the same will silently under-adjust. {@link #coveredThrough} is how
     * the two are told apart.
     */
    public List<LocalDate> datesForSymbol(String symbol, LocalDate from, LocalDate to) {
        List<LocalDate> out = new ArrayList<>();
        if (symbol == null || symbol.isBlank() || from == null || to == null
                || to.isBefore(from) || !connections.dbExists()) {
            return out;
        }
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT date FROM earnings_calendar WHERE symbol = ? AND date >= ? AND date <= ? "
                             + "ORDER BY date")) {
            ps.setString(1, symbol.trim().toUpperCase());
            ps.setString(2, from.toString());
            ps.setString(3, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(LocalDate.parse(rs.getString("date")));
            }
        } catch (Exception e) {
            log.warn("Could not read earnings dates for {}", symbol, e);
        }
        return out;
    }

    /**
     * The soonest cached report per symbol inside {@code [from, to]} — the whole window in one
     * query, because the reminder job asks about every symbol you hold, watch or have an order
     * resting on and a query each would be a hundred round trips for one screen.
     *
     * <p>Reads only what has been fetched, exactly like {@link #datesForSymbol}: a symbol missing
     * from the result has no <em>cached</em> print in the window, which is not the same as having
     * none due. The caller pairs this with the fundamentals snapshot rather than reading the
     * silence as an answer.
     */
    public Map<String, EarningsEvent> earliestPerSymbol(LocalDate from, LocalDate to) {
        Map<String, EarningsEvent> out = new LinkedHashMap<>();
        if (from == null || to == null || to.isBefore(from) || !connections.dbExists()) return out;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT date, symbol, company, time, market_cap, eps_forecast, num_estimates, "
                             + "last_year_eps FROM earnings_calendar "
                             + "WHERE date >= ? AND date <= ? ORDER BY date")) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String symbol = rs.getString("symbol");
                    if (symbol == null || symbol.isBlank()) continue;
                    // Ordered by date, so the first row seen for a symbol is its soonest print.
                    out.putIfAbsent(symbol.toUpperCase(), new EarningsEvent(
                            LocalDate.parse(rs.getString("date")),
                            symbol,
                            rs.getString("company"),
                            rs.getString("time"),
                            nullableLong(rs, "market_cap"),
                            nullableDouble(rs, "eps_forecast"),
                            nullableInt(rs, "num_estimates"),
                            nullableDouble(rs, "last_year_eps")));
                }
            }
        } catch (Exception e) {
            log.warn("Could not read the earnings calendar for {}..{}", from, to, e);
        }
        return out;
    }

    /**
     * The last date at or after {@code from} for which a roster has actually been fetched, with no
     * gap before it — i.e. how far forward the calendar can be trusted to be complete.
     *
     * <p>A gap matters more than it looks: the roster is market-wide, so an unfetched date is a date
     * on which <em>every</em> symbol appears to have nothing scheduled. Walking from {@code from}
     * and stopping at the first missing day is what lets a caller say "unadjusted beyond here"
     * instead of quietly reporting no events.
     */
    public LocalDate coveredThrough(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || !connections.dbExists()) return null;
        LocalDate covered = null;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            // Weekends never carry a roster and are never fetched; they can't break the run.
            if (d.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                    || d.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
                continue;
            }
            if (lastFetched(d) == null) return covered;
            covered = d;
        }
        return covered;
    }

    /** Cached roster for {@code date}, biggest market cap first (nulls last); empty when none stored. */
    public List<EarningsEvent> load(LocalDate date) {
        List<EarningsEvent> out = new ArrayList<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT symbol, company, time, market_cap, eps_forecast, num_estimates, last_year_eps "
                             + "FROM earnings_calendar WHERE date = ? "
                             + "ORDER BY market_cap IS NULL, market_cap DESC, symbol")) {
            ps.setString(1, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new EarningsEvent(
                            date,
                            rs.getString("symbol"),
                            rs.getString("company"),
                            rs.getString("time"),
                            nullableLong(rs, "market_cap"),
                            nullableDouble(rs, "eps_forecast"),
                            nullableInt(rs, "num_estimates"),
                            nullableDouble(rs, "last_year_eps")));
                }
            }
        } catch (Exception e) {
            log.warn("Could not read cached earnings calendar for {}", date, e);
        }
        return out;
    }

    private static void setNullableLong(PreparedStatement ps, int i, Long v) throws java.sql.SQLException {
        if (v == null) ps.setNull(i, java.sql.Types.INTEGER);
        else ps.setLong(i, v);
    }

    private static void setNullableInt(PreparedStatement ps, int i, Integer v) throws java.sql.SQLException {
        if (v == null) ps.setNull(i, java.sql.Types.INTEGER);
        else ps.setInt(i, v);
    }

    private static void setNullableDouble(PreparedStatement ps, int i, Double v) throws java.sql.SQLException {
        if (v == null) ps.setNull(i, java.sql.Types.REAL);
        else ps.setDouble(i, v);
    }

    private static Long nullableLong(ResultSet rs, String col) throws java.sql.SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Integer nullableInt(ResultSet rs, String col) throws java.sql.SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static Double nullableDouble(ResultSet rs, String col) throws java.sql.SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }
}
