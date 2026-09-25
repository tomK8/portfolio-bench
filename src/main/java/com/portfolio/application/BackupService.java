package com.portfolio.application;

import com.portfolio.persistence.JdbcConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Off-machine copy of the data that could not be reconstructed if this laptop were lost.
 *
 * <p><b>Only the irreplaceable rows.</b> Prices, intraday bars, FX, dividends per share,
 * earnings dates and fundamentals are ~1.37 million of the ~1.37 million rows in the database
 * and essentially all of its 280MB — and every one of them can be re-fetched from Yahoo,
 * Frankfurter or SEC for the cost of a backfill. Copying them would turn a 30KB archive that
 * fits in an email into a 200MB one that does not, in exchange for saving an afternoon.
 * What cannot be re-fetched is roughly a thousand rows: a ledger reaching back to 2016 that
 * carries corrections no broker re-download reproduces, hand-written trade notes, the
 * ladder/switch groups that encode an intention rather than an observation, and the
 * hand-curated symbol mappings — a rename re-keys ledger rows and is not undoable.
 *
 * <p>The {@code KeyValueStore} files go in for the same reason. Most are symbol lists, but
 * {@code roth_balance_brought_forward} is a hand-determined seed with nothing to derive it
 * from: lose it and Roth's whole cash chain is wrong.
 *
 * <p>Written as a ZIP because it is the one container both macOS and the iOS Files app open by
 * double-click, and because {@code java.util.zip} is in the JDK — a tar would need a library.
 * The SQL inside is plain {@code INSERT} statements against the real schema, so restoring is
 * {@code sqlite3 portfolio.db < portfolio-essentials.sql} with no tooling of ours involved.
 * That matters: a backup that can only be read by the program that wrote it is a backup that
 * fails exactly when the program is what broke.
 */
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    /**
     * Tables whose rows exist nowhere else. Deliberately a fixed list rather than "everything
     * except the big ones": a new table should have to be classified by hand, because getting
     * it wrong in the silent direction means discovering the omission during a restore.
     */
    static final List<String> TABLES = List.of(
            "cash_transactions",      // the ledger, back to 2016 — the one that really matters
            "dividend_events",        // per-account attributed dividends
            "order_history",          // incl. closed orders the broker no longer lists
            "order_group",            // ladders and share-class switches: intent, not observation
            "order_group_member",
            "order_group_intent",
            "trade_notes",            // hand-written, keyed to ledger rowids
            "watchlist",              // symbols + thresholds
            "reminder",               // hand-typed dates, rules and actions: no feed supplies these
            "reminder_mute",          // which earnings reminders were switched off — a standing instruction
            "instrument_aliases",     // hand-curated; a rename re-keys ledger rows, not undoable
            "ticker_mappings",
            "portfolio_snapshots");

    /** Keep the new archive plus this many older ones. */
    private static final int KEEP = 3;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String PREFIX = "portfolio-backup-";
    private static final String SUFFIX = ".zip";
    /** Matches only what this class writes, so rotation can never reach another file. */
    private static final Pattern OURS =
            Pattern.compile(Pattern.quote(PREFIX) + "\\d{4}-\\d{2}-\\d{2}" + Pattern.quote(SUFFIX));

    private final JdbcConnectionFactory connections;
    private final Path destination;

    public BackupService(JdbcConnectionFactory connections, Path destination) {
        this.connections = connections;
        this.destination = destination;
    }

    public Path destination() {
        return destination;
    }

    /** When the most recent archive was written, or null if there is none. */
    public LocalDate lastBackupDate() {
        return archives().stream()
                .map(p -> p.getFileName().toString())
                .map(n -> n.substring(PREFIX.length(), n.length() - SUFFIX.length()))
                .map(s -> {
                    try {
                        return LocalDate.parse(s);
                    } catch (RuntimeException e) {
                        return null;
                    }
                })
                .filter(d -> d != null)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    /**
     * Write today's archive and prune older ones.
     *
     * <p>Throws rather than degrading, unlike the background price jobs: a backup that quietly
     * did nothing is worse than no backup at all, because it is indistinguishable from one that
     * worked right up until the day it was needed. The scheduler catches and logs.
     */
    public Result run() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Path target = destination.resolve(PREFIX + LocalDate.now().format(STAMP) + SUFFIX);
        try {
            Files.createDirectories(destination);
            Path tmp = Files.createTempFile("portfolio-backup", ".zip");
            try {
                try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp))) {
                    writeSql(zip, counts);
                    writeKeyValueFiles(zip);
                    writeReadme(zip, counts);
                }
                // Move into place only once the archive is complete, so an interrupted run can
                // never leave a truncated file sitting where a good one used to be.
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Backup failed: " + e.getMessage(), e);
        }
        int pruned = prune();
        long bytes;
        try {
            bytes = Files.size(target);
        } catch (IOException e) {
            bytes = -1;
        }
        int rows = counts.values().stream().mapToInt(Integer::intValue).sum();
        log.info("Backup written: {} ({} rows across {} tables, {} KB, {} old archive(s) pruned)",
                target, rows, counts.size(), bytes / 1024, pruned);
        return new Result(target, rows, bytes, counts, pruned);
    }

    private void writeSql(ZipOutputStream zip, Map<String, Integer> counts)
            throws IOException, SQLException {
        zip.putNextEntry(new ZipEntry("portfolio-essentials.sql"));
        Writer w = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
        w.write("-- portfolio-bench essential tables, " + java.time.Instant.now() + "\n");
        w.write("-- Restore:  sqlite3 portfolio.db < portfolio-essentials.sql\n");
        w.write("BEGIN TRANSACTION;\n");
        try (Connection conn = connections.open(); Statement st = conn.createStatement()) {
            for (String table : TABLES) {
                String ddl = ddlFor(conn, table);
                if (ddl == null) {
                    log.warn("Backup: table {} not present — skipped", table);
                    continue;
                }
                w.write("\nDROP TABLE IF EXISTS " + table + ";\n");
                w.write(ddl + ";\n");
                int n = 0;
                try (ResultSet rs = st.executeQuery("SELECT * FROM " + table)) {
                    ResultSetMetaData md = rs.getMetaData();
                    int cols = md.getColumnCount();
                    while (rs.next()) {
                        StringBuilder sb = new StringBuilder("INSERT INTO ").append(table)
                                .append(" VALUES(");
                        for (int i = 1; i <= cols; i++) {
                            if (i > 1) sb.append(',');
                            sb.append(literal(rs.getObject(i)));
                        }
                        w.write(sb.append(");\n").toString());
                        n++;
                    }
                }
                counts.put(table, n);
            }
        }
        w.write("COMMIT;\n");
        w.flush();
        zip.closeEntry();
    }

    private static String ddlFor(Connection conn, String table) throws SQLException {
        try (var ps = conn.prepareStatement(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** SQLite literal. Strings are single-quoted with doubled quotes; blobs become X'..'. */
    private static String literal(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        if (v instanceof byte[] b) {
            StringBuilder sb = new StringBuilder("X'");
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.append('\'').toString();
        }
        return "'" + v.toString().replace("'", "''") + "'";
    }

    /**
     * The KeyValueStore is one .txt per key beside portfolio.db, not a table, so a SQL dump
     * misses it entirely — including the hand-entered Roth brought-forward seed.
     */
    private void writeKeyValueFiles(ZipOutputStream zip) throws IOException {
        Path dir = connections.dbDir();
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .sorted()
                    .toList();
            for (Path f : files) {
                zip.putNextEntry(new ZipEntry("keyvalue/" + f.getFileName()));
                zip.write(Files.readAllBytes(f));
                zip.closeEntry();
            }
        }
    }

    private void writeReadme(ZipOutputStream zip, Map<String, Integer> counts) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("portfolio-bench backup — ").append(java.time.Instant.now()).append("\n\n");
        sb.append("Contains the data that cannot be re-fetched from a public source.\n");
        sb.append("Prices, FX, dividends, earnings and fundamentals are NOT here: they are\n");
        sb.append("re-downloadable, and including them would make this archive 200MB.\n\n");
        sb.append("Restore:\n");
        sb.append("  1. Stop the app (scripts/stop.sh).\n");
        sb.append("  2. sqlite3 ~/Documents/Investing/portfolio.db < portfolio-essentials.sql\n");
        sb.append("  3. Copy keyvalue/*.txt into ~/Documents/Investing/\n");
        sb.append("  4. Start the app and press Rebuild adj_close to refetch price history.\n\n");
        sb.append("Tables:\n");
        counts.forEach((t, n) -> sb.append(String.format("  %-22s %6d rows%n", t, n)));
        zip.putNextEntry(new ZipEntry("README.txt"));
        zip.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private List<Path> archives() {
        if (!Files.isDirectory(destination)) return List.of();
        try (var stream = Files.list(destination)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> OURS.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException e) {
            log.warn("Could not list backup directory {}", destination, e);
            return List.of();
        }
    }

    /** Delete all but the newest {@link #KEEP}. Only ever touches names {@link #OURS} matches. */
    private int prune() {
        List<Path> all = new ArrayList<>(archives());
        int removed = 0;
        for (int i = KEEP; i < all.size(); i++) {
            try {
                Files.delete(all.get(i));
                removed++;
            } catch (IOException e) {
                log.warn("Could not prune old backup {}", all.get(i), e);
            }
        }
        return removed;
    }

    public record Result(Path file, int rows, long bytes,
                         Map<String, Integer> tableCounts, int pruned) {
    }
}
