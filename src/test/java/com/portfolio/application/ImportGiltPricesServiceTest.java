package com.portfolio.application;

import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.PriceHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImportGiltPricesServiceTest {

    @TempDir Path inputDir;
    @TempDir Path archiveDir;

    private PriceHistoryRepository repo() {
        return new PriceHistoryRepository(new JdbcConnectionFactory(archiveDir));
    }

    @Test
    void importAllReadsFileUpsertsAndArchives() throws Exception {
        Path sample = Path.of("src/test/resources/tradeweb-gilts-sample.csv");
        Path dropped = inputDir.resolve("Tradeweb_FTSE_ClosePrices.csv");
        Files.copy(sample, dropped);

        PriceHistoryRepository repo = repo();
        ImportGiltPricesService svc = new ImportGiltPricesService(inputDir, archiveDir, repo);

        List<GiltPriceImportResult> results = svc.importAll();
        assertEquals(1, results.size());
        GiltPriceImportResult r = results.get(0);
        assertEquals(GiltPriceImportResult.Status.IMPORTED, r.status());
        assertEquals(3, r.touched());

        assertFalse(Files.exists(dropped), "source file moved out of Downloads");
        assertTrue(r.detail().contains("GILT_3.75pct_2038"),
                "archived name embeds the gilt symbol; was: " + r.detail());
        assertTrue(Files.exists(Path.of(r.detail())), "file lives at the archived path");

        assertEquals(93.300, repo.getPriceOn("GILT 3.75% 2038", LocalDate.of(2023, 6, 1)).close());
    }

    @Test
    void importAllReportsNotFoundWhenNoFilesMatch() {
        List<GiltPriceImportResult> results =
                new ImportGiltPricesService(inputDir, archiveDir, repo()).importAll();

        assertEquals(1, results.size());
        assertEquals(GiltPriceImportResult.Status.NOT_FOUND, results.get(0).status());
    }
}
