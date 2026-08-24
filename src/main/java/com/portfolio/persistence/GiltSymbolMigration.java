package com.portfolio.persistence;

import com.portfolio.domain.Instruments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Re-keys gilt symbols stored before {@link Instruments#giltId} was the only way to build one.
 *
 * <p>The id is a join key, and until 2026-08-24 each writer took the coupon verbatim from
 * whatever text it was reading. AJ Bell's cash statement writes {@code "GILT 4.50%"} while HL
 * publishes the same bond as {@code "4.500"} and trimmed it to {@code "4.5"}, so a £50,597
 * purchase sat in {@code cash_transactions} under a string that matched neither its intraday
 * quote nor its daily close: the cash-ledger view reported the position unpriced and dropped it
 * from the totals. Fixing the writers stops new rows diverging; only this closes the gap for rows
 * already stored, and there is no other way to reach them — the ledger has no re-import path that
 * would rewrite a symbol in place.
 *
 * <p>{@code UPDATE OR REPLACE} rather than a plain {@code UPDATE}: a bond may already hold rows
 * under both spellings (the live database had {@code GILT 1.00% 2024} beside {@code GILT 1% 2024}),
 * and on a primary-key collision the row being re-keyed wins. That is the same last-write-wins rule
 * the two gilt price feeds already share, and it keeps a collision from aborting the migration and
 * with it the repository's whole constructor.
 *
 * <p>Idempotent and cheap: once every stored gilt symbol is canonical this is one
 * {@code SELECT DISTINCT} over an indexed column and no writes.
 */
final class GiltSymbolMigration {

    private static final Logger log = LoggerFactory.getLogger(GiltSymbolMigration.class);

    private GiltSymbolMigration() {
    }

    /** Canonicalises every {@code GILT …} symbol in {@code table}. Returns the rows re-keyed. */
    static int run(Connection conn, String table) throws SQLException {
        Map<String, String> renames = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT DISTINCT symbol FROM " + table + " WHERE symbol LIKE 'GILT %'")) {
            while (rs.next()) {
                String stored = rs.getString(1);
                String canonical = Instruments.canonicalGiltId(stored);
                if (!stored.equals(canonical)) renames.put(stored, canonical);
            }
        }
        if (renames.isEmpty()) return 0;

        int rows = 0;
        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE OR REPLACE " + table + " SET symbol = ? WHERE symbol = ?")) {
            for (Map.Entry<String, String> e : renames.entrySet()) {
                ps.setString(1, e.getValue());
                ps.setString(2, e.getKey());
                rows += ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(autoCommit);
        }
        log.info("Migrated {} {} rows to canonical gilt symbols: {}", rows, table, renames);
        return rows;
    }
}
