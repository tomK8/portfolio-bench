package com.portfolio.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdbcConnectionFactoryTest {

    @TempDir
    Path dbDir;

    /**
     * WAL is what keeps readers from being locked out while a bulk price backfill writes —
     * in the default rollback-journal mode every write excluded all readers and reads that
     * outlasted the busy timeout degraded to empty results (blank charts on the dashboard).
     */
    @Test
    void connectionsRunInWalMode() throws Exception {
        JdbcConnectionFactory factory = new JdbcConnectionFactory(dbDir);
        try (Connection conn = factory.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
            rs.next();
            assertEquals("wal", rs.getString(1).toLowerCase());
        }
        // WAL persists in the database file — a later connection sees it too.
        try (Connection conn = factory.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
            rs.next();
            assertEquals("wal", rs.getString(1).toLowerCase());
        }
    }
}
