package com.portfolio.persistence;

import com.portfolio.domain.model.IntradayBar;
import com.portfolio.domain.model.IntradayPrice;
import com.portfolio.domain.model.MarketSession;
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
            // Idempotent migration (see CashTransactionRepository's constructor for the pattern).
            // Left NULL on existing rows deliberately: everything stored before these columns
            // existed was fetched with includePrePost=false, so NULL *means* regular session and
            // MarketSession.fromDb reads it as such. Backfilling a literal would claim knowledge
            // of a trading day we never captured.
            for (String alter : new String[]{
                    "ALTER TABLE price_intraday ADD COLUMN session TEXT",
                    "ALTER TABLE price_intraday ADD COLUMN session_date TEXT"}) {
                try {
                    ddl.execute(alter);
                } catch (SQLException ignored) {
                    // column already present
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise price_intraday table", e);
        }
    }

    /**
     * Commit bulk writes in chunks rather than one transaction for the whole batch: SQLite's
     * single database-wide writer lock, held across a multi-thousand-row batch, starves
     * interactive writes (a watchlist add/remove) until the batch finishes. Chunked commits
     * release it frequently so those small writes interleave. INSERT-OR-IGNORE is idempotent,
     * so earlier chunks surviving a mid-batch failure is harmless.
     */
    private static final int WRITE_CHUNK_ROWS = 500;

    public int saveIntradayBars(List<IntradayBar> bars) {
        if (bars.isEmpty()) return 0;
        String fetchedAt = Instant.now().toString();
        int inserted = 0;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT OR IGNORE INTO price_intraday " +
                             "(symbol, ts, close, volume, currency, fetched_at, session, session_date) " +
                             "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            conn.setAutoCommit(false);
            int inBatch = 0;
            for (IntradayBar b : bars) {
                ps.setString(1, b.symbol());
                ps.setString(2, b.ts().toString());
                ps.setDouble(3, b.close());
                if (b.volume() != null) ps.setLong(4, b.volume());
                else ps.setNull(4, Types.INTEGER);
                ps.setString(5, b.currency());
                ps.setString(6, fetchedAt);
                ps.setString(7, b.session().name());
                if (b.sessionDate() != null) ps.setString(8, b.sessionDate().toString());
                else ps.setNull(8, Types.VARCHAR);
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
            log.warn("Could not save intraday bars", e);
            return inserted;
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

    /**
     * Bulk lookup: latest stored <em>regular-session</em> bar per ticker. One DB hit, not N.
     *
     * <p>Extended-hours rows are excluded here rather than at each call site, so every existing
     * reader — portfolio valuation, reconciliation, the sync views, open orders, watchlist
     * alerts — keeps the semantics it was written against once the fetcher started storing
     * pre/post minutes. A thin 200-share pre-market print is a fine thing to *look* at and a bad
     * thing to value a portfolio or fire an alert on. The Live tab opts in explicitly via
     * {@link #loadLatestQuotes}.
     */
    public Map<String, IntradayPrice> loadLatestIntradayPrices(Collection<String> tickers) {
        return loadLatest(tickers, true);
    }

    /**
     * Latest stored bar per ticker of <em>any</em> session, each tagged with the session it came
     * from. The Live tab's quote source: a US name at 08:00 London has no regular bar newer than
     * last night's close, but may well have a pre-market one, and showing it (labelled) beats
     * showing a stale close.
     */
    public Map<String, IntradayPrice> loadLatestQuotes(Collection<String> tickers) {
        return loadLatest(tickers, false);
    }

    private Map<String, IntradayPrice> loadLatest(Collection<String> tickers, boolean regularOnly) {
        Map<String, IntradayPrice> out = new HashMap<>();
        if (tickers.isEmpty() || !connections.dbExists()) return out;
        // NULL session = a row written before the column existed, which was regular by
        // construction (includePrePost was false), so it must survive the regular-only filter.
        String sessionFilter = regularOnly
                ? " WHERE session IS NULL OR session = 'REGULAR'" : "";
        String sql = "SELECT p.symbol, p.ts, p.close, p.currency, p.session, p.session_date " +
                "FROM price_intraday p " +
                "JOIN (SELECT symbol, MAX(ts) AS max_ts FROM price_intraday" + sessionFilter +
                "      GROUP BY symbol) m " +
                "ON p.symbol = m.symbol AND p.ts = m.max_ts " +
                "WHERE p.symbol IN (" + placeholders(tickers.size()) + ")";
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            for (String t : tickers) ps.setString(i++, t);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String day = rs.getString(6);
                    out.put(rs.getString(1),
                            new IntradayPrice(Instant.parse(rs.getString(2)),
                                    rs.getDouble(3), rs.getString(4),
                                    MarketSession.fromDb(rs.getString(5)),
                                    day == null ? null : java.time.LocalDate.parse(day)));
                }
            }
        } catch (Exception e) {
            log.warn("Could not load latest intraday prices", e);
        }
        return out;
    }

    /**
     * The distinct gilt symbols currently priced in {@code price_intraday} — i.e. the conventional
     * gilts {@link com.portfolio.application.GiltPriceFetchJob} scrapes hourly from HL, stored as
     * {@code "GILT {coupon}% {year}"}. Feeds the Watchlist add-box autocomplete so a gilt buy-limit
     * alert can be entered by picking a valid name rather than typing one (a mistyped coupon/year
     * would silently never join a price and never fire). Read path — degrades to empty on failure.
     */
    public List<String> distinctGiltSymbols() {
        List<String> out = new java.util.ArrayList<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT DISTINCT symbol FROM price_intraday WHERE symbol LIKE 'GILT %' ORDER BY symbol")) {
            while (rs.next()) out.add(rs.getString(1));
        } catch (Exception e) {
            log.warn("Could not load distinct gilt symbols", e);
        }
        return out;
    }

    /**
     * All stored <em>regular-session</em> 1-minute bars for one ticker at or after {@code from},
     * oldest first. Feeds the watchlist popup's fine-grained price chart for the sub-15-day
     * windows and the intraday realized-vol term structure. Retention (see
     * {@link com.portfolio.application.IntradayPriceFetchJob#RETENTION_DAYS}) bounds how far
     * back this can reach.
     *
     * <p>Extended hours are excluded on purpose. Pre/post minutes are thin and wide-spread, so
     * folding them in would inflate {@code PriceStats.annualizedVolIntraday} with spread noise
     * and draw the overnight gap as a run of intraday moves on the popup chart.
     */
    public List<IntradayBar> loadIntradaySeries(String ticker, Instant from) {
        List<IntradayBar> out = new java.util.ArrayList<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT symbol, ts, close, volume, currency FROM price_intraday " +
                             "WHERE symbol = ? AND ts >= ? " +
                             "AND (session IS NULL OR session = 'REGULAR') ORDER BY ts")) {
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
