package com.portfolio.persistence;

import com.portfolio.domain.DividendYield;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cash dividend history per symbol, harvested from the chart response the daily price job already
 * makes. Backs the derived yield behind the income overlay ({@link DividendYield}) and the
 * open-orders drift netting, for instruments whose {@code summaryDetail} publishes no yield at all.
 *
 * <p><b>Two tables, and the second is the load-bearing one.</b> {@code dividend_history} holds the
 * payments; {@code dividend_scan} records that a symbol's <em>full</em> window has been read.
 * Without that bookkeeping an absent symbol is ambiguous in exactly the way that matters — an
 * accumulating ETF that genuinely distributes nothing looks identical to a name nobody has fetched
 * yet, and booking either as a zero yield understates portfolio income with nothing on screen to
 * say why. Same three-state discipline as {@code earnings_calendar_fetch}, which exists so a
 * zero-report day caches distinctly from a day never fetched, and as the sector block's
 * {@code AWAITING_DATA} / {@code NO_SECTOR} split.
 *
 * <p><b>Only a full-window fetch may mark a scan.</b> The nightly incremental run asks Yahoo for
 * the gap since the last stored bar, so it legitimately sees no dividends most nights; letting it
 * write a marker would assert "this name pays nothing" on the strength of a three-day window. It
 * still upserts what it finds, so a newly-declared dividend lands without waiting for a rebuild.
 *
 * <p>Reads and writes both degrade — this rides on a background job and must never crash it, and a
 * missing yield is a warning in the UI rather than a failure.
 */
public class DividendHistoryRepository {

    private static final Logger log = LoggerFactory.getLogger(DividendHistoryRepository.class);

    private static final String CREATE_HISTORY = """
            CREATE TABLE IF NOT EXISTS dividend_history (
                symbol     TEXT NOT NULL,
                ex_date    TEXT NOT NULL,
                amount     REAL NOT NULL,
                currency   TEXT,
                fetched_at TEXT NOT NULL,
                PRIMARY KEY (symbol, ex_date)
            )""";
    private static final String CREATE_SCAN = """
            CREATE TABLE IF NOT EXISTS dividend_scan (
                symbol       TEXT NOT NULL PRIMARY KEY,
                window_start TEXT NOT NULL,
                scanned_at   TEXT NOT NULL,
                payments     INTEGER NOT NULL
            )""";

    private final JdbcConnectionFactory connections;

    public DividendHistoryRepository(JdbcConnectionFactory connections) {
        this.connections = connections;
        try (Connection conn = connections.open(); Statement ddl = conn.createStatement()) {
            ddl.execute(CREATE_HISTORY);
            ddl.execute(CREATE_SCAN);
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise dividend_history tables", e);
        }
    }

    /**
     * Upserts payments for one symbol. Last write wins per {@code (symbol, ex_date)} — Yahoo
     * occasionally restates an amount, and the newer read is the better one.
     *
     * @return rows written
     */
    public int saveDividends(String symbol, List<DividendYield.Payment> payments) {
        return saveDividends(symbol, payments, null);
    }

    /** Same, carrying the listing currency Yahoo reported alongside the amounts. */
    public int saveDividends(String symbol, List<DividendYield.Payment> payments, String currency) {
        if (symbol == null || payments == null || payments.isEmpty() || !connections.dbExists()) {
            return 0;
        }
        String now = Instant.now().toString();
        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            int n = 0;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO dividend_history(symbol, ex_date, amount, currency, fetched_at) "
                            + "VALUES(?,?,?,?,?) ON CONFLICT(symbol, ex_date) DO UPDATE SET "
                            + "amount = excluded.amount, currency = excluded.currency, "
                            + "fetched_at = excluded.fetched_at")) {
                for (DividendYield.Payment p : payments) {
                    if (p == null || p.exDate() == null || p.amount() == null) continue;
                    ps.setString(1, symbol);
                    ps.setString(2, p.exDate().toString());
                    ps.setBigDecimal(3, p.amount());
                    ps.setString(4, currency);
                    ps.setString(5, now);
                    ps.addBatch();
                    n++;
                }
                ps.executeBatch();
            }
            conn.commit();
            return n;
        } catch (Exception e) {
            log.warn("Could not save dividend history for {}", symbol, e);
            return 0;
        }
    }

    /**
     * Records that {@code symbol}'s full history back to {@code windowStart} has been read, so an
     * empty payment list for it can be believed rather than treated as an unfetched gap. Callers
     * that fetched only an incremental gap must not call this — see the class doc.
     */
    public void markFullScan(String symbol, LocalDate windowStart, int payments) {
        if (symbol == null || windowStart == null || !connections.dbExists()) return;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO dividend_scan(symbol, window_start, scanned_at, payments) "
                             + "VALUES(?,?,?,?) ON CONFLICT(symbol) DO UPDATE SET "
                             + "window_start = excluded.window_start, "
                             + "scanned_at = excluded.scanned_at, payments = excluded.payments")) {
            ps.setString(1, symbol);
            ps.setString(2, windowStart.toString());
            ps.setString(3, Instant.now().toString());
            ps.setInt(4, payments);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("Could not mark dividend scan for {}", symbol, e);
        }
    }

    /** Symbols whose full history has been read — the set for which "no payments" means zero. */
    public Set<String> scannedSymbols() {
        Set<String> out = new HashSet<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT symbol FROM dividend_scan")) {
            while (rs.next()) out.add(rs.getString(1));
        } catch (Exception e) {
            log.warn("Could not read dividend scan markers", e);
        }
        return out;
    }

    /**
     * Payments for the requested symbols, keyed by symbol. A symbol with no rows is absent from
     * the map rather than present as an empty list — the caller pairs this with
     * {@link #scannedSymbols()} to tell a genuine non-payer from an unfetched one.
     */
    public Map<String, List<DividendYield.Payment>> loadPayments(Collection<String> symbols) {
        Map<String, List<DividendYield.Payment>> out = new HashMap<>();
        if (symbols == null || symbols.isEmpty() || !connections.dbExists()) return out;
        String placeholders = String.join(",", java.util.Collections.nCopies(symbols.size(), "?"));
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT symbol, ex_date, amount FROM dividend_history "
                             + "WHERE symbol IN (" + placeholders + ") ORDER BY symbol, ex_date")) {
            int i = 1;
            for (String s : symbols) ps.setString(i++, s);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BigDecimal amt = rs.getBigDecimal(3);
                    if (amt == null) continue;
                    out.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                            .add(new DividendYield.Payment(LocalDate.parse(rs.getString(2)), amt));
                }
            }
        } catch (Exception e) {
            log.warn("Could not load dividend history", e);
        }
        return out;
    }
}
