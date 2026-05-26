package com.pension.application;

import com.pension.PortfolioDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportCashServiceTest {

    private static final String STATEMENT = """
            Date,Description,Receipt (GBP),Payment (GBP),Balance (GBP)
            02/05/2026,Gross interest,5.00,,105.00
            01/05/2026,BALANCE B/F,,,100.00
            """;

    @TempDir Path inputDir;
    @TempDir Path dbDir;

    private ImportCashService service() {
        return new ImportCashService(inputDir, new PortfolioDatabase(dbDir));
    }

    private void writeStatement() throws IOException {
        Files.writeString(inputDir.resolve("cashstatements.csv"), STATEMENT);
    }

    @Test
    void notFoundWhenNoFile() {
        ImportCashResult result = service().importCash();
        assertEquals(ImportCashResult.Status.NOT_FOUND, result.status());
    }

    @Test
    void importsThenArchivesFile() throws IOException {
        writeStatement();

        ImportCashResult result = service().importCash();

        assertEquals(ImportCashResult.Status.IMPORTED, result.status());
        assertTrue(result.inserted() > 0);
        assertFalse(Files.exists(inputDir.resolve("cashstatements.csv")), "source should be moved out of input dir");
        assertTrue(Files.exists(dbDir.resolve("cashstatements_" + java.time.LocalDate.now() + ".csv")),
                "archived copy should exist in the db dir");
    }

    @Test
    void reimportingIdenticalFileFindsNoNewDataAndDeletes() throws IOException {
        writeStatement();
        service().importCash();          // first import: archived

        writeStatement();                // identical file appears again
        ImportCashResult result = service().importCash();

        assertEquals(ImportCashResult.Status.NO_NEW_DATA, result.status());
        assertFalse(Files.exists(inputDir.resolve("cashstatements.csv")), "duplicate should be deleted");
    }
}
