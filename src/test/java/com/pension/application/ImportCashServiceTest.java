package com.pension.application;

import com.pension.PortfolioDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ImportCashServiceTest {

    private static final String STATEMENT = """
            Date,Description,Receipt (GBP),Payment (GBP),Balance (GBP)
            02/05/2026,Gross interest,5.00,,105.00
            01/05/2026,BALANCE B/F,,,100.00
            """;

    @TempDir
    Path inputDir;
    @TempDir
    Path dbDir;

    private ImportCashService service() {
        // No History.xlsx in these tests, so the FX provider is never invoked.
        return new ImportCashService(inputDir, new PortfolioDatabase(dbDir),
                (ccy, start, end) -> Map.of(start, BigDecimal.ONE));
    }

    private ImportCashResult ajBell(ImportCashService service) {
        return service.importCash().stream()
                .filter(r -> "AJBell".equals(r.source()))
                .findFirst().orElseThrow();
    }

    private void writeStatement() throws IOException {
        Files.writeString(inputDir.resolve("cashstatements.csv"), STATEMENT);
    }

    @Test
    void absentFilesProduceNoResultRow() {
        assertTrue(service().importCash().isEmpty(), "no files in input dir → no results");
    }

    @Test
    void picksMostRecentMatchWhenMultipleCopiesExist() throws IOException {
        Files.writeString(inputDir.resolve("cashstatements.csv"), """
                Date,Description,Receipt (GBP),Payment (GBP),Balance (GBP)
                01/04/2026,Old file,1.00,,1.00
                """);
        // newer copy with extra row + suffix in filename
        Path newer = inputDir.resolve("cashstatements (1).csv");
        Files.writeString(newer, STATEMENT);
        Files.setLastModifiedTime(newer,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000));

        ImportCashResult result = ajBell(service());

        assertEquals(ImportCashResult.Status.IMPORTED, result.status());
        assertFalse(Files.exists(newer), "newer file should be archived out of input dir");
        assertTrue(Files.exists(inputDir.resolve("cashstatements.csv")), "older file should remain untouched");
    }

    @Test
    void importsThenArchivesFile() throws IOException {
        writeStatement();

        ImportCashResult result = ajBell(service());

        assertEquals(ImportCashResult.Status.IMPORTED, result.status());
        assertTrue(result.inserted() > 0);
        assertFalse(Files.exists(inputDir.resolve("cashstatements.csv")), "source should be moved out of input dir");
        assertTrue(Files.exists(dbDir.resolve("cashstatements_" + LocalDate.now() + ".csv")),
                "archived copy should exist in the db dir");
    }

    @Test
    void reimportingIdenticalFileFindsNoNewDataAndDeletes() throws IOException {
        writeStatement();
        ajBell(service());               // first import: archived

        writeStatement();                // identical file appears again
        ImportCashResult result = ajBell(service());

        assertEquals(ImportCashResult.Status.NO_NEW_DATA, result.status());
        assertFalse(Files.exists(inputDir.resolve("cashstatements.csv")), "duplicate should be deleted");
    }
}
