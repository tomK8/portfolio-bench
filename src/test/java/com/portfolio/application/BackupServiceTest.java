package com.portfolio.application;

import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Covers what a restore actually depends on: the rows, the settings files, and rotation. */
class BackupServiceTest {

    @TempDir
    Path dbDir;
    @TempDir
    Path backupDir;

    private static CashTransaction row(String date, double amount) {
        return new CashTransaction(date, Account.AJBELL, TransactionType.TRANSACTION, "AAPL",
                1.0, amount, "GBP", 1.0, amount, 100.0, 100.0, "Purchase 1 Moody's Corp");
    }

    private BackupService service() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var repo = new CashTransactionRepository(cf, kv);
        repo.saveAjBell(List.of(row("2026-01-05", -100.0), row("2026-01-06", -200.0)));
        kv.putBigDecimal("roth_balance_brought_forward", new BigDecimal("186721.74"));
        return new BackupService(cf, backupDir);
    }

    private static String entry(Path zip, String name) throws IOException {
        try (var in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.getName().equals(name)) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    @Test
    void writesTheLedgerAsRestorableSql() throws IOException {
        BackupService.Result r = service().run();

        assertTrue(Files.exists(r.file()));
        String sql = entry(r.file(), "portfolio-essentials.sql");
        assertNotNull(sql, "the SQL dump must be in the archive");
        assertTrue(sql.contains("CREATE TABLE"), "schema, so a restore needs no other file");
        assertTrue(sql.contains("INSERT INTO cash_transactions"), sql.substring(0, 200));
        // A broker description containing an apostrophe must not end the SQL string early,
        // or the restore fails on a syntax error somewhere in the middle of the ledger.
        assertTrue(sql.contains("Moody''s Corp"), "apostrophe must be doubled, not passed through");
        assertEquals(2, r.tableCounts().get("cash_transactions"));
    }

    /**
     * The KeyValueStore is files beside the database, not a table, so a SQL-only backup would
     * silently drop the hand-entered Roth seed — the one figure with nothing to derive it from.
     */
    @Test
    void includesTheKeyValueSettingsFiles() throws IOException {
        BackupService.Result r = service().run();

        String seed = entry(r.file(), "keyvalue/roth_balance_brought_forward.txt");
        assertNotNull(seed, "settings files must be in the archive, not just the tables");
        assertTrue(seed.contains("186721.74"), seed);
    }

    @Test
    void keepsAtMostThreeArchivesAndTouchesNothingElse() throws IOException {
        BackupService svc = service();
        // Four older archives, plus a file that is not ours and must survive rotation.
        for (String d : List.of("2026-01-01", "2026-02-01", "2026-03-01", "2026-04-01")) {
            Files.writeString(backupDir.resolve("portfolio-backup-" + d + ".zip"), "old");
        }
        Path bystander = backupDir.resolve("tax-return-2025.zip");
        Files.writeString(bystander, "not ours");

        svc.run();

        try (var s = Files.list(backupDir)) {
            List<String> ours = s.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("portfolio-backup-")).sorted().toList();
            assertEquals(3, ours.size(), "newest plus two, oldest pruned: " + ours);
            assertTrue(ours.contains("portfolio-backup-" + java.time.LocalDate.now() + ".zip"));
        }
        assertTrue(Files.exists(bystander), "rotation must only ever delete its own archives");
    }

    @Test
    void reportsNoBackupBeforeTheFirstRun() {
        BackupService svc = service();
        assertNull(svc.lastBackupDate());
        svc.run();
        assertEquals(java.time.LocalDate.now(), svc.lastBackupDate());
    }
}
