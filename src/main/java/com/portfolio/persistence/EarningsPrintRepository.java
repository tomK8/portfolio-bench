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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What is known about an individual earnings print, beyond the roster line the calendar caches.
 *
 * <p>Three tables, and each exists because its figure is <b>only obtainable at a particular
 * moment</b> — which is the whole reason this is a store and not another read-through cache:
 *
 * <ul>
 *   <li>{@code earnings_print} — one row per {@code (symbol, print date)}: the fiscal quarter, the
 *       revenue reported, and <b>the consensus that print was measured against</b>. The last of
 *       those is perishable. Yahoo publishes one set of "next report" estimates per company and
 *       rolls them to the following quarter some hours after the print, at which point what that
 *       quarter was expected to earn is gone from every free source. Captured while the date is
 *       still ahead, it is a measurement; reconstructed afterwards it would be the next quarter's
 *       number wearing this one's label.</li>
 *   <li>{@code earnings_print_history} — one row per {@code (symbol, reported date)} of a
 *       <i>previous</i> print. The feed hands back four quarters; this keeps every one it has ever
 *       handed back, so the baseline a price reaction is judged against grows a quarter at a time
 *       instead of being permanently four deep.</li>
 *   <li>{@code earnings_consensus_capture} — the forward consensus by named fiscal period, dated.
 *       "How far have estimates moved since the print" is a difference between two readings, and
 *       nobody publishes the earlier one; taking it regularly is the only way to have it.</li>
 * </ul>
 *
 * <p><b>Writes merge rather than replace.</b> The two halves of a print row arrive on opposite
 * sides of the event — the consensus days before it, the revenue days after — so every update
 * coalesces: a later write that knows nothing about a column leaves what is there. A blind
 * {@code INSERT OR REPLACE} would discard the pre-print consensus the moment the revenue arrived,
 * which is the one figure that cannot be fetched again.
 *
 * <p>Package convention: DDL once in the constructor, reads and background writes degrade (log and
 * carry on) rather than throw — none of this may take down the tab or the job that fills it.
 */
public class EarningsPrintRepository {

    private static final Logger log = LoggerFactory.getLogger(EarningsPrintRepository.class);

    private static final String CREATE_PRINT = """
            CREATE TABLE IF NOT EXISTS earnings_print (
                symbol                TEXT NOT NULL,
                date                  TEXT NOT NULL,
                fiscal_quarter        TEXT,
                revenue_actual        REAL,
                revenue_consensus     REAL,
                revenue_currency      TEXT,
                eps_consensus         REAL,
                consensus_captured_at TEXT,
                detail_fetched_at     TEXT,
                attempted_on          TEXT,
                PRIMARY KEY (symbol, date)
            )""";

    private static final String CREATE_HISTORY = """
            CREATE TABLE IF NOT EXISTS earnings_print_history (
                symbol         TEXT NOT NULL,
                reported_date  TEXT NOT NULL,
                fiscal_quarter TEXT,
                eps            REAL,
                consensus      REAL,
                session        TEXT,
                PRIMARY KEY (symbol, reported_date)
            )""";

    private static final String CREATE_CAPTURE = """
            CREATE TABLE IF NOT EXISTS earnings_consensus_capture (
                symbol        TEXT NOT NULL,
                captured_on   TEXT NOT NULL,
                period_kind   TEXT NOT NULL,
                fiscal_end    TEXT NOT NULL,
                consensus     REAL,
                num_estimates INTEGER,
                revisions_up   INTEGER,
                revisions_down INTEGER,
                PRIMARY KEY (symbol, captured_on, period_kind, fiscal_end)
            )""";

    /** The consensus a print was measured against, and what it delivered on the revenue line. */
    public record PrintDetail(String symbol, LocalDate date, String fiscalQuarter,
                              Double revenueActual, Double revenueConsensus, String revenueCurrency,
                              Double epsConsensus, Instant consensusCapturedAt,
                              Instant detailFetchedAt, LocalDate attemptedOn) {

        /**
         * Whether the consensus on this row was read before the day of the print. Provenance, not
         * validity: what makes the figure belong to <i>this</i> quarter is that the feed still
         * named this date when it was taken, which is tested where it is captured.
         */
        public boolean consensusIsPrePrint() {
            return consensusCapturedAt != null && date != null
                    && consensusCapturedAt.atZone(java.time.ZoneOffset.UTC).toLocalDate()
                            .isBefore(date);
        }
    }

    /**
     * One previous print of the same company. {@code session} is {@code BMO}/{@code AMC} where it
     * could be established and null where it could not — which is a usable row for a surprise
     * history and an unusable one for a price reaction, so the two readers ask different questions
     * of the same table rather than it holding two.
     */
    public record PriorPrint(String symbol, LocalDate reported, String fiscalQuarter,
                             Double eps, Double consensus, String session) {
    }

    /** One reading of the forward consensus for one named fiscal period. */
    public record ConsensusCapture(String symbol, LocalDate capturedOn, String periodKind,
                                   String fiscalEnd, Double consensus, Integer numEstimates,
                                   Integer up, Integer down) {
    }

    private final JdbcConnectionFactory connections;

    public EarningsPrintRepository(JdbcConnectionFactory connections) {
        this.connections = connections;
        try (Connection conn = connections.open(); Statement ddl = conn.createStatement()) {
            ddl.execute(CREATE_PRINT);
            ddl.execute(CREATE_HISTORY);
            ddl.execute(CREATE_CAPTURE);
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise earnings_print tables", e);
        }
    }

    // ---- the print row -----------------------------------------------------------------

    /**
     * Record what the print still ahead is expected to deliver. Written while the date is in front
     * of us, because afterwards it cannot be had: the consensus columns are coalesced on every
     * later write so the figures measured here survive everything that follows.
     */
    public void saveConsensus(String symbol, LocalDate date, Double eps, Double revenue,
                              String currency) {
        if (symbol == null || date == null) return;
        merge(symbol, date, """
                INSERT INTO earnings_print (symbol, date, eps_consensus, revenue_consensus,
                                            revenue_currency, consensus_captured_at)
                VALUES (?,?,?,?,?,?)
                ON CONFLICT(symbol, date) DO UPDATE SET
                    eps_consensus         = COALESCE(excluded.eps_consensus, eps_consensus),
                    revenue_consensus     = COALESCE(excluded.revenue_consensus, revenue_consensus),
                    revenue_currency      = COALESCE(excluded.revenue_currency, revenue_currency),
                    consensus_captured_at = COALESCE(consensus_captured_at, excluded.consensus_captured_at)
                """, ps -> {
            setNullableDouble(ps, 3, eps);
            setNullableDouble(ps, 4, revenue);
            ps.setString(5, currency);
            ps.setString(6, Instant.now().toString());
        }, "consensus");
    }

    /** Record what the print actually delivered on the revenue line, and which quarter it was. */
    public void saveReported(String symbol, LocalDate date, String fiscalQuarter,
                             Double revenueActual, String currency) {
        if (symbol == null || date == null) return;
        merge(symbol, date, """
                INSERT INTO earnings_print (symbol, date, fiscal_quarter, revenue_actual,
                                            revenue_currency, detail_fetched_at)
                VALUES (?,?,?,?,?,?)
                ON CONFLICT(symbol, date) DO UPDATE SET
                    fiscal_quarter    = COALESCE(excluded.fiscal_quarter, fiscal_quarter),
                    revenue_actual    = COALESCE(excluded.revenue_actual, revenue_actual),
                    revenue_currency  = COALESCE(excluded.revenue_currency, revenue_currency),
                    detail_fetched_at = excluded.detail_fetched_at
                """, ps -> {
            ps.setString(3, fiscalQuarter);
            setNullableDouble(ps, 4, revenueActual);
            ps.setString(5, currency);
            ps.setString(6, Instant.now().toString());
        }, "reported detail");
    }

    /**
     * Note that this print was asked about today, whatever came back.
     *
     * <p>Without it a company the feeds have nothing to say about is asked again on every pass,
     * forever, which is the one way a job that is otherwise self-limiting by content stops being
     * so. Dated rather than a flag, so a feed that catches up tomorrow is still tried tomorrow:
     * "we asked and got nothing" is a fact about today, not about the company.
     */
    public void markAttempted(String symbol, LocalDate date, LocalDate today) {
        if (symbol == null || date == null || today == null) return;
        merge(symbol, date, """
                INSERT INTO earnings_print (symbol, date, attempted_on) VALUES (?,?,?)
                ON CONFLICT(symbol, date) DO UPDATE SET attempted_on = excluded.attempted_on
                """, ps -> ps.setString(3, today.toString()), "attempt marker");
    }

    /** Every print row for the given symbols, keyed {@code SYMBOL|yyyy-MM-dd}. */
    public Map<String, PrintDetail> details(List<String> symbols, LocalDate from, LocalDate to) {
        Map<String, PrintDetail> out = new LinkedHashMap<>();
        if (symbols == null || symbols.isEmpty() || from == null || to == null) return out;
        String sql = "SELECT symbol, date, fiscal_quarter, revenue_actual, revenue_consensus, "
                + "revenue_currency, eps_consensus, consensus_captured_at, detail_fetched_at, "
                + "attempted_on FROM earnings_print WHERE date BETWEEN ? AND ? AND symbol IN ("
                + placeholders(symbols.size()) + ")";
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            for (int i = 0; i < symbols.size(); i++) ps.setString(3 + i, symbols.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PrintDetail d = new PrintDetail(
                            rs.getString("symbol"), LocalDate.parse(rs.getString("date")),
                            rs.getString("fiscal_quarter"),
                            nullableDouble(rs, "revenue_actual"),
                            nullableDouble(rs, "revenue_consensus"),
                            rs.getString("revenue_currency"),
                            nullableDouble(rs, "eps_consensus"),
                            instant(rs.getString("consensus_captured_at")),
                            instant(rs.getString("detail_fetched_at")),
                            day(rs.getString("attempted_on")));
                    out.put(d.symbol() + "|" + d.date(), d);
                }
            }
        } catch (Exception e) {
            log.warn("Could not read earnings print details", e);
        }
        return out;
    }

    // ---- previous prints ---------------------------------------------------------------

    /**
     * Add or update previous prints. The session is coalesced, never overwritten with null: a date
     * whose session the calendar once stated must not lose it to a later read that could only
     * infer, and a later read that <i>can</i> state one fills a gap the earlier left.
     */
    public void savePriorPrints(List<PriorPrint> prints) {
        if (prints == null || prints.isEmpty()) return;
        String sql = """
                INSERT INTO earnings_print_history (symbol, reported_date, fiscal_quarter, eps,
                                                    consensus, session)
                VALUES (?,?,?,?,?,?)
                ON CONFLICT(symbol, reported_date) DO UPDATE SET
                    fiscal_quarter = COALESCE(excluded.fiscal_quarter, fiscal_quarter),
                    eps            = COALESCE(excluded.eps, eps),
                    consensus      = COALESCE(excluded.consensus, consensus),
                    session        = COALESCE(excluded.session, session)
                """;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (PriorPrint p : prints) {
                if (p.symbol() == null || p.reported() == null) continue;
                ps.setString(1, p.symbol());
                ps.setString(2, p.reported().toString());
                ps.setString(3, p.fiscalQuarter());
                setNullableDouble(ps, 4, p.eps());
                setNullableDouble(ps, 5, p.consensus());
                ps.setString(6, p.session());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception e) {
            log.warn("Could not store previous earnings prints", e);
        }
    }

    /** Every previous print known for these symbols, newest first within each symbol. */
    public Map<String, List<PriorPrint>> priorPrints(List<String> symbols) {
        Map<String, List<PriorPrint>> out = new LinkedHashMap<>();
        if (symbols == null || symbols.isEmpty()) return out;
        String sql = "SELECT symbol, reported_date, fiscal_quarter, eps, consensus, session "
                + "FROM earnings_print_history WHERE symbol IN (" + placeholders(symbols.size())
                + ") ORDER BY symbol, reported_date DESC";
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < symbols.size(); i++) ps.setString(1 + i, symbols.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PriorPrint p = new PriorPrint(rs.getString("symbol"),
                            LocalDate.parse(rs.getString("reported_date")),
                            rs.getString("fiscal_quarter"),
                            nullableDouble(rs, "eps"), nullableDouble(rs, "consensus"),
                            rs.getString("session"));
                    out.computeIfAbsent(p.symbol(), k -> new ArrayList<>()).add(p);
                }
            }
        } catch (Exception e) {
            log.warn("Could not read previous earnings prints", e);
        }
        return out;
    }

    // ---- consensus readings over time ----------------------------------------------------

    /** Store one day's reading of the forward consensus. Same day, same period = same row. */
    public void saveCaptures(List<ConsensusCapture> rows) {
        if (rows == null || rows.isEmpty()) return;
        String sql = """
                INSERT OR REPLACE INTO earnings_consensus_capture
                    (symbol, captured_on, period_kind, fiscal_end, consensus, num_estimates,
                     revisions_up, revisions_down)
                VALUES (?,?,?,?,?,?,?,?)
                """;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (ConsensusCapture c : rows) {
                if (c.symbol() == null || c.capturedOn() == null || c.fiscalEnd() == null) continue;
                ps.setString(1, c.symbol());
                ps.setString(2, c.capturedOn().toString());
                ps.setString(3, c.periodKind());
                ps.setString(4, c.fiscalEnd());
                setNullableDouble(ps, 5, c.consensus());
                setNullableInt(ps, 6, c.numEstimates());
                setNullableInt(ps, 7, c.up());
                setNullableInt(ps, 8, c.down());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception e) {
            log.warn("Could not store consensus captures", e);
        }
    }

    /** Every reading held for these symbols, oldest first — the caller picks its two endpoints. */
    public Map<String, List<ConsensusCapture>> captures(List<String> symbols) {
        Map<String, List<ConsensusCapture>> out = new LinkedHashMap<>();
        if (symbols == null || symbols.isEmpty()) return out;
        String sql = "SELECT symbol, captured_on, period_kind, fiscal_end, consensus, "
                + "num_estimates, revisions_up, revisions_down FROM earnings_consensus_capture "
                + "WHERE symbol IN (" + placeholders(symbols.size())
                + ") ORDER BY symbol, captured_on";
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < symbols.size(); i++) ps.setString(1 + i, symbols.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ConsensusCapture c = new ConsensusCapture(rs.getString("symbol"),
                            LocalDate.parse(rs.getString("captured_on")),
                            rs.getString("period_kind"), rs.getString("fiscal_end"),
                            nullableDouble(rs, "consensus"), nullableInt(rs, "num_estimates"),
                            nullableInt(rs, "revisions_up"), nullableInt(rs, "revisions_down"));
                    out.computeIfAbsent(c.symbol(), k -> new ArrayList<>()).add(c);
                }
            }
        } catch (Exception e) {
            log.warn("Could not read consensus captures", e);
        }
        return out;
    }

    // ---- plumbing --------------------------------------------------------------------------

    private interface Binder {
        void bind(PreparedStatement ps) throws Exception;
    }

    private void merge(String symbol, LocalDate date, String sql, Binder binder, String what) {
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, symbol);
            ps.setString(2, date.toString());
            binder.bind(ps);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("Could not store earnings print {} for {} {}", what, symbol, date, e);
        }
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    private static LocalDate day(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Instant instant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Double nullableDouble(ResultSet rs, String col) throws java.sql.SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private static Integer nullableInt(ResultSet rs, String col) throws java.sql.SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static void setNullableDouble(PreparedStatement ps, int i, Double v)
            throws java.sql.SQLException {
        if (v == null) ps.setNull(i, java.sql.Types.REAL);
        else ps.setDouble(i, v);
    }

    private static void setNullableInt(PreparedStatement ps, int i, Integer v)
            throws java.sql.SQLException {
        if (v == null) ps.setNull(i, java.sql.Types.INTEGER);
        else ps.setInt(i, v);
    }
}
