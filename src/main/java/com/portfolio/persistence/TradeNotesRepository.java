package com.portfolio.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * Free-text + tag annotations on individual {@code cash_transactions} rows. The point is
 * trading-psychology bookkeeping — capture FOMO/conviction/fear/rebalance per-trade so
 * the user can study their own decision patterns later.
 *
 * <p>Keyed by SQLite {@code rowid} of the underlying trade row. That choice is deliberate:
 * the dedup logic in {@link CashTransactionRepository} preserves rowids across re-imports
 * (AJBell updates matching rows; II / RothIRA skip duplicates), so a note attached today
 * still resolves to the same trade after tomorrow's cash-statement import. The one edge
 * case is the AJBell stale-duplicate sweep — a rare scenario where two rows collided
 * under the old dedup key and one is discarded. We accept the orphan in that path
 * rather than complicating the schema with a natural composite key that differs per broker.
 */
public class TradeNotesRepository {

    private static final Logger log = LoggerFactory.getLogger(TradeNotesRepository.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS trade_notes (
                transaction_rowid INTEGER PRIMARY KEY,
                note              TEXT,
                tags              TEXT,
                updated_at        TEXT NOT NULL
            )""";

    private final JdbcConnectionFactory connections;

    public TradeNotesRepository(JdbcConnectionFactory connections) {
        this.connections = connections;
        try (Connection conn = connections.open(); Statement ddl = conn.createStatement()) {
            ddl.execute(CREATE_TABLE);
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise trade_notes table", e);
        }
    }

    /** rowid → (note, tags). Empty when the DB doesn't exist yet. */
    public Map<Long, TradeNote> loadAll() {
        Map<Long, TradeNote> out = new HashMap<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT transaction_rowid, note, tags, updated_at FROM trade_notes")) {
            while (rs.next()) {
                out.put(rs.getLong(1),
                        new TradeNote(rs.getString(2), rs.getString(3), rs.getString(4)));
            }
        } catch (Exception e) {
            log.warn("Could not load trade notes", e);
        }
        return out;
    }

    /**
     * Upsert one row. Empty note + empty tags deletes — there's no point keeping a
     * blank annotation around. Returns true if the row now exists (was upserted).
     */
    public boolean save(long transactionRowid, String note, String tags, String updatedAt) {
        boolean empty = (note == null || note.isBlank()) && (tags == null || tags.isBlank());
        try (Connection conn = connections.open()) {
            if (empty) {
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM trade_notes WHERE transaction_rowid = ?")) {
                    del.setLong(1, transactionRowid);
                    del.executeUpdate();
                }
                return false;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO trade_notes (transaction_rowid, note, tags, updated_at) " +
                            "VALUES (?, ?, ?, ?) " +
                            "ON CONFLICT(transaction_rowid) DO UPDATE SET " +
                            "note = excluded.note, tags = excluded.tags, updated_at = excluded.updated_at")) {
                ps.setLong(1, transactionRowid);
                ps.setString(2, note == null ? "" : note);
                ps.setString(3, tags == null ? "" : tags);
                ps.setString(4, updatedAt);
                ps.executeUpdate();
            }
            return true;
        } catch (Exception e) {
            throw new IllegalStateException("Could not save trade note for rowid " + transactionRowid, e);
        }
    }

    public record TradeNote(String note, String tags, String updatedAt) {
    }
}
