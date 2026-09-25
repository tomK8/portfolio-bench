package com.portfolio.persistence;

import com.portfolio.domain.model.Reminder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The reminders the user typed, and the earnings reminders they switched off.
 *
 * <p><b>Two tables, because only one of the two kinds is data.</b> A hand-entered reminder exists
 * nowhere else — no feed will ever supply "check the gilt ladder on the 14th" — so every field of it
 * is stored. An earnings reminder is <em>derived</em>: its symbol comes from what you hold, watch or
 * have resting, and its date comes from the earnings feeds, both of which move on their own. Storing
 * a row per earnings reminder would be a hand-maintained copy of two live sources and could only
 * ever drift out of date with them, so {@code reminder_mute} stores the one bit that <em>is</em> the
 * user's: which symbols they have told to be quiet. Everything else is recomputed each time.
 *
 * <p>That is also why a mute is keyed by symbol and not by an occurrence. "Stop reminding me about
 * BARC earnings" is a standing instruction about the name, and it survives the print it was given
 * before — the quarterly rhythm would otherwise re-arm it every ninety days behind the user's back.
 * The screen lists muted symbols rather than hiding them, so a standing instruction stays visible
 * and is one click to lift.
 *
 * <p>Column names are {@code rule_text} / {@code action_text}: {@code ACTION} is a SQLite keyword,
 * and a quoted identifier that only works while it stays quoted is a trap for the next query.
 *
 * <p>Package convention: DDL runs once in the constructor; writes throw {@link IllegalStateException}
 * (the caller renders the error fragment), reads log and degrade to empty — a screen that cannot
 * list reminders must not take the dashboard down with it.
 */
public class ReminderRepository {

    private static final Logger log = LoggerFactory.getLogger(ReminderRepository.class);

    private static final String CREATE_REMINDER = """
            CREATE TABLE IF NOT EXISTS reminder (
                id          INTEGER PRIMARY KEY,
                symbol      TEXT,
                due_date    TEXT NOT NULL,
                rule_text   TEXT,
                action_text TEXT,
                enabled     INTEGER NOT NULL DEFAULT 1,
                created_at  TEXT NOT NULL,
                updated_at  TEXT NOT NULL
            )""";

    private static final String CREATE_MUTE = """
            CREATE TABLE IF NOT EXISTS reminder_mute (
                symbol   TEXT PRIMARY KEY,
                muted_at TEXT NOT NULL
            )""";

    private final JdbcConnectionFactory connections;

    public ReminderRepository(JdbcConnectionFactory connections) {
        this.connections = connections;
        try (Connection conn = connections.open(); Statement ddl = conn.createStatement()) {
            ddl.execute(CREATE_REMINDER);
            ddl.execute(CREATE_MUTE);
            ddl.execute("CREATE INDEX IF NOT EXISTS idx_reminder_due ON reminder(due_date)");
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise the reminder tables", e);
        }
    }

    // ---- reads -------------------------------------------------------------

    /** Every hand-entered reminder, soonest first. Empty when the DB doesn't exist yet. */
    public List<Reminder> loadAll() {
        List<Reminder> out = new ArrayList<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, symbol, due_date, rule_text, action_text, enabled, created_at, "
                             + "updated_at FROM reminder ORDER BY due_date, id")) {
            while (rs.next()) out.add(read(rs));
        } catch (Exception e) {
            log.warn("Could not load reminders", e);
        }
        return out;
    }

    /** One reminder by id, or null when it isn't there (or can't be read). */
    public Reminder find(long id) {
        if (!connections.dbExists()) return null;
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, symbol, due_date, rule_text, action_text, enabled, created_at, "
                             + "updated_at FROM reminder WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        } catch (Exception e) {
            log.warn("Could not read reminder {}", id, e);
            return null;
        }
    }

    /** Symbols whose earnings reminder is switched off. Uppercase, as stored. */
    public Set<String> mutedSymbols() {
        Set<String> out = new LinkedHashSet<>();
        if (!connections.dbExists()) return out;
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT symbol FROM reminder_mute")) {
            while (rs.next()) out.add(rs.getString(1));
        } catch (Exception e) {
            log.warn("Could not load muted earnings reminders", e);
        }
        return out;
    }

    // ---- writes ------------------------------------------------------------

    /** Insert one reminder, enabled. Returns its new id. */
    public long add(String symbol, LocalDate dueDate, String rule, String action) {
        String now = Instant.now().toString();
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO reminder (symbol, due_date, rule_text, action_text, enabled, "
                             + "created_at, updated_at) VALUES (?, ?, ?, ?, 1, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            setSymbol(ps, 1, symbol);
            ps.setString(2, dueDate.toString());
            ps.setString(3, blankToNull(rule));
            ps.setString(4, blankToNull(action));
            ps.setString(5, now);
            ps.setString(6, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not save the reminder", e);
        }
    }

    /**
     * Update the three editable fields. The symbol is deliberately not among them: a reminder about
     * a different position is a different reminder, and quietly re-pointing one would carry its
     * history — and today's already-sent marker — onto a name it was never about.
     */
    public boolean update(long id, LocalDate dueDate, String rule, String action) {
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE reminder SET due_date = ?, rule_text = ?, action_text = ?, "
                             + "updated_at = ? WHERE id = ?")) {
            ps.setString(1, dueDate.toString());
            ps.setString(2, blankToNull(rule));
            ps.setString(3, blankToNull(action));
            ps.setString(4, Instant.now().toString());
            ps.setLong(5, id);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            throw new IllegalStateException("Could not update reminder " + id, e);
        }
    }

    /** Switch one reminder on or off, leaving everything else as it was. */
    public boolean setEnabled(long id, boolean enabled) {
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE reminder SET enabled = ?, updated_at = ? WHERE id = ?")) {
            ps.setInt(1, enabled ? 1 : 0);
            ps.setString(2, Instant.now().toString());
            ps.setLong(3, id);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            throw new IllegalStateException("Could not switch reminder " + id, e);
        }
    }

    public boolean delete(long id) {
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM reminder WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            throw new IllegalStateException("Could not delete reminder " + id, e);
        }
    }

    /** Switch a symbol's earnings reminder off (mute) or back on. Idempotent either way. */
    public void setEarningsMuted(String symbol, boolean muted) {
        String key = symbol == null ? "" : symbol.trim().toUpperCase();
        if (key.isEmpty()) throw new IllegalStateException("Cannot mute an unnamed symbol");
        try (Connection conn = connections.open()) {
            if (muted) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO reminder_mute (symbol, muted_at) VALUES (?, ?) "
                                + "ON CONFLICT(symbol) DO UPDATE SET muted_at = excluded.muted_at")) {
                    ps.setString(1, key);
                    ps.setString(2, Instant.now().toString());
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM reminder_mute WHERE symbol = ?")) {
                    ps.setString(1, key);
                    ps.executeUpdate();
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not switch the earnings reminder for " + key, e);
        }
    }

    // ---- helpers -----------------------------------------------------------

    private static Reminder read(ResultSet rs) throws Exception {
        return new Reminder(
                rs.getLong("id"),
                rs.getString("symbol"),
                LocalDate.parse(rs.getString("due_date")),
                rs.getString("rule_text"),
                rs.getString("action_text"),
                rs.getInt("enabled") != 0,
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }

    private static void setSymbol(PreparedStatement ps, int idx, String symbol) throws Exception {
        String s = symbol == null ? "" : symbol.trim().toUpperCase();
        if (s.isEmpty()) ps.setNull(idx, Types.VARCHAR);
        else ps.setString(idx, s);
    }

    /** Blank free text is stored as null — an empty rule is not a rule that says nothing. */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
