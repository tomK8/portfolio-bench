package com.portfolio.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Single-owner gateway to the SQLite database file. Holds the on-disk path,
 * exposes connections, and ensures the parent directory exists. All repositories
 * route their JDBC traffic through here so concurrency safety (e.g. a future
 * price-poller) can be added in one place.
 */
public class JdbcConnectionFactory {

    private final Path dbDir;
    private final Path dbPath;

    public JdbcConnectionFactory(Path dbDir) {
        this.dbDir = dbDir;
        this.dbPath = dbDir.resolve("portfolio.db");
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
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }
}
