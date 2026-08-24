package com.portfolio.persistence;

import com.portfolio.domain.Instruments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * User-supplied instrument mappings — the two lookups a new instrument needs before it can be
 * priced, held in SQLite so they can be added from the dashboard instead of from a source edit
 * and a redeploy.
 *
 * <p>Two tables, one feature:
 * <ul>
 *   <li><b>{@code ticker_mappings}</b> — internal symbol → Yahoo ticker. An <em>overlay</em> on
 *       the committed {@code yahoo-tickers.properties}: a row here wins, so a wrong committed
 *       mapping can be corrected without a release. Cached in memory by
 *       {@code YahooTickerMap}, which re-reads it after every mutation.</li>
 *   <li><b>{@code instrument_aliases}</b> — a lowercase keyword found in a broker's instrument
 *       description → internal symbol. Deliberately <em>not</em> per-broker: AJ Bell's orders
 *       export and its cash statement describe the same instrument in near-identical words, so
 *       one keyword ("xtrackers msci world health") fixes both the Symbol column and the ledger
 *       row that would otherwise be stored under the long name.</li>
 * </ul>
 *
 * <p>Reads degrade to empty (a mapping table that won't open must not take the dashboard down);
 * the dashboard-triggered writes throw {@link IllegalStateException} so the failure is visible.
 */
public class InstrumentMappingRepository {

    private static final Logger log = LoggerFactory.getLogger(InstrumentMappingRepository.class);

    private static final String CREATE_TICKERS = """
            CREATE TABLE IF NOT EXISTS ticker_mappings (
                symbol       TEXT PRIMARY KEY,
                yahoo_ticker TEXT NOT NULL,
                currency     TEXT,
                verified_at  TEXT,
                added_at     TEXT NOT NULL
            )""";

    private static final String CREATE_ALIASES = """
            CREATE TABLE IF NOT EXISTS instrument_aliases (
                keyword  TEXT PRIMARY KEY,
                symbol   TEXT NOT NULL,
                raw_name TEXT,
                added_at TEXT NOT NULL
            )""";

    private final JdbcConnectionFactory connections;

    public InstrumentMappingRepository(JdbcConnectionFactory connections) {
        this.connections = connections;
        try (Connection conn = connections.open(); Statement ddl = conn.createStatement()) {
            ddl.execute(CREATE_TICKERS);
            ddl.execute(CREATE_ALIASES);
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise instrument mapping tables", e);
        }
    }

    // ---- symbol → Yahoo ticker -------------------------------------------------------------

    /** Symbol → Yahoo ticker overlay, symbols upper-cased. Degrades to empty. */
    public Map<String, String> loadTickerMappings() {
        Map<String, String> out = new LinkedHashMap<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT symbol, yahoo_ticker FROM ticker_mappings")) {
            while (rs.next()) out.put(rs.getString(1), rs.getString(2));
        } catch (Exception e) {
            log.warn("Could not read ticker_mappings — falling back to the committed properties only", e);
        }
        return out;
    }

    /** Full rows for the dashboard's mapping list, newest first. Degrades to empty. */
    public List<TickerMapping> listTickerMappings() {
        List<TickerMapping> out = new ArrayList<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT symbol, yahoo_ticker, currency, verified_at, added_at " +
                             "FROM ticker_mappings ORDER BY added_at DESC, symbol")) {
            while (rs.next()) {
                out.add(new TickerMapping(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)));
            }
        } catch (Exception e) {
            log.warn("Could not list ticker_mappings", e);
        }
        return out;
    }

    /** Insert or replace one symbol → ticker mapping. */
    public void saveTickerMapping(String symbol, String yahooTicker, String currency, boolean verified) {
        String sym = normaliseSymbol(symbol);
        String tk = yahooTicker == null ? "" : yahooTicker.trim();
        if (sym.isEmpty() || tk.isEmpty()) {
            throw new IllegalStateException("Both a symbol and a Yahoo ticker are required.");
        }
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO ticker_mappings (symbol, yahoo_ticker, currency, verified_at, added_at) " +
                             "VALUES (?, ?, ?, ?, ?) " +
                             "ON CONFLICT(symbol) DO UPDATE SET " +
                             "yahoo_ticker = excluded.yahoo_ticker, " +
                             "currency = excluded.currency, " +
                             "verified_at = excluded.verified_at")) {
            String now = Instant.now().toString();
            ps.setString(1, sym);
            ps.setString(2, tk);
            ps.setString(3, currency);
            ps.setString(4, verified ? now : null);
            ps.setString(5, now);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Could not save the ticker mapping for " + sym + ".", e);
        }
    }

    public void deleteTickerMapping(String symbol) {
        String sym = normaliseSymbol(symbol);
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM ticker_mappings WHERE symbol = ?")) {
            ps.setString(1, sym);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Could not delete the ticker mapping for " + sym + ".", e);
        }
    }

    // ---- instrument name keyword → symbol ---------------------------------------------------

    /**
     * Keyword → symbol, keywords lowercase. Longest keyword first: a more specific phrase must win
     * over a shorter one that also matches, since callers take the first hit.
     */
    public Map<String, String> loadAliases() {
        Map<String, String> out = new LinkedHashMap<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT keyword, symbol FROM instrument_aliases")) {
            // Normalised on the way out as well as in, so a row stored before the keyword form was
            // pinned down still matches, and re-sorted longest-first on the *normalised* key: the
            // parsers take the first hit, so a specific phrase has to be offered before a shorter
            // one that also matches.
            List<String[]> rows = new ArrayList<>();
            while (rs.next()) {
                String key = normaliseKeyword(rs.getString(1));
                if (!key.isEmpty()) rows.add(new String[]{key, rs.getString(2)});
            }
            rows.sort((a, b) -> Integer.compare(b[0].length(), a[0].length()));
            for (String[] row : rows) out.putIfAbsent(row[0], row[1]);
        } catch (Exception e) {
            log.warn("Could not read instrument_aliases — broker names will fall back to the built-in rules", e);
        }
        return out;
    }

    /** Full rows for the dashboard's mapping list, newest first. Degrades to empty. */
    public List<InstrumentAlias> listAliases() {
        List<InstrumentAlias> out = new ArrayList<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT keyword, symbol, raw_name, added_at FROM instrument_aliases " +
                             "ORDER BY added_at DESC, keyword")) {
            while (rs.next()) {
                out.add(new InstrumentAlias(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
            }
        } catch (Exception e) {
            log.warn("Could not list instrument_aliases", e);
        }
        return out;
    }

    public void saveAlias(String keyword, String symbol, String rawName) {
        String key = normaliseKeyword(keyword);
        String sym = normaliseSymbol(symbol);
        if (key.isEmpty() || sym.isEmpty()) {
            throw new IllegalStateException("Both a match text and a symbol are required.");
        }
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO instrument_aliases (keyword, symbol, raw_name, added_at) VALUES (?, ?, ?, ?) " +
                             "ON CONFLICT(keyword) DO UPDATE SET " +
                             "symbol = excluded.symbol, raw_name = excluded.raw_name")) {
            ps.setString(1, key);
            ps.setString(2, sym);
            ps.setString(3, rawName);
            ps.setString(4, Instant.now().toString());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Could not save the name mapping for \"" + key + "\".", e);
        }
    }

    public void deleteAlias(String keyword) {
        String key = normaliseKeyword(keyword);
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM instrument_aliases WHERE keyword = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Could not delete the name mapping for \"" + key + "\".", e);
        }
    }

    static String normaliseSymbol(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }

    /**
     * The stored form of a match keyword — {@link Instruments#matchKey}, which is also what the
     * parsers reduce a broker description to before testing it. One definition on both sides is
     * the whole requirement: they diverged over punctuation once, and the mapping saved fine and
     * simply never matched.
     */
    public static String normaliseKeyword(String s) {
        return Instruments.matchKey(s);
    }

    /** One symbol → Yahoo ticker row. {@code verifiedAt} is null when saved without a live check. */
    public record TickerMapping(String symbol, String yahooTicker, String currency,
                                String verifiedAt, String addedAt) {
    }

    /** One instrument-description keyword → symbol row. {@code rawName} is what it was created from. */
    public record InstrumentAlias(String keyword, String symbol, String rawName, String addedAt) {
    }
}
