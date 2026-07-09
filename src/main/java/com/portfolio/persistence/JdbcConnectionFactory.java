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
 * <p>Every connection carries a {@code busy_timeout}: the background price jobs write every
 * minute, so without it a user action (e.g. a watchlist add) that lands mid-write fails
 * instantly with SQLITE_BUSY instead of waiting the few milliseconds the lock is held.
 */
public class JdbcConnectionFactory {

    private static final int BUSY_TIMEOUT_MS = 5_000;

    private final Path dbDir;
    private final Path dbPath;
    private final Properties connectionProperties;

    public JdbcConnectionFactory(Path dbDir) {
        this.dbDir = dbDir;
        this.dbPath = dbDir.resolve("portfolio.db");
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(BUSY_TIMEOUT_MS);
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
