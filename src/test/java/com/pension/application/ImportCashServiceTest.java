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
    void notFoundWhenNoFile() {
        assertEquals(ImportCashResult.Status.NOT_FOUND, ajBell(service()).status());
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
