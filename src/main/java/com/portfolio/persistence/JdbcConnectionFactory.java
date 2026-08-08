package com.portfolio.persistence;

import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Single-owner gateway to the SQLite database file. Holds the on-disk path,
 * exposes connections, and ensures the parent directory exists. All repositories
 * route their JDBC traffic through here so concurrency safety (e.g. a future
 * price-poller) can be added in one place.
 *
 * <p>Connections run in WAL mode with {@code synchronous=NORMAL}: readers are never blocked by
 * a writer, so a bulk price backfill can't lock the dashboard's reads out (in the default
 * rollback-journal mode every write excluded all readers, and reads that outlasted the busy
 * timeout degraded to empty results — blank charts). WAL persists in the database file; the
 * {@code -wal}/{@code -shm} sidecars mean a raw file copy should happen after a clean shutdown
 * (or via {@code sqlite3 .backup}).
 *
 * <p>Every connection also carries a {@code busy_timeout}: with WAL this only arbitrates
 * writer-vs-writer, e.g. a user action (a watchlist add / threshold edit) landing mid-write of a
 * background price job waits instead of failing instantly with SQLITE_BUSY. It's set generously
 * (15s): normal bulk writes are chunked (500 rows/commit) so the lock is released frequently and a
 * UI write slips in within a chunk boundary, but a rarer long writer (first-run backfill, an
 * adj_close rebuild, a big Tradeweb import) could otherwise blow past a short timeout and surface
 * to the user as a failed save or "could not load". Waiting a few extra seconds beats failing.
 */
public class JdbcConnectionFactory {

    private static final int BUSY_TIMEOUT_MS = 15_000;

    private final Path dbDir;
    private final Path dbPath;
    private final Properties connectionProperties;

    public JdbcConnectionFactory(Path dbDir) {
        this.dbDir = dbDir;
        this.dbPath = dbDir.resolve("portfolio.db");
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(BUSY_TIMEOUT_MS);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        this.connectionProperties = config.toProperties();
    }

    public Path dbDir() {
        return dbDir;
    }

    public Path dbPath() {
        return dbPath;
    }

    public boolean dbExists() {
        return Files.exists(dbPath);
    }

    public Connection open() throws SQLException, IOException {
        Files.createDirectories(dbDir);
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath, connectionProperties);
    }
}
