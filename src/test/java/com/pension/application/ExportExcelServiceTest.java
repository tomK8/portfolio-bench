package com.pension.application;

import com.pension.ExcelReportWriter;
import com.pension.PortfolioDatabase;
import com.pension.adapter.HoldingFileLocator;
import com.pension.port.FxRateProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportExcelServiceTest {

    private static final FxRateProvider FX =
            () -> Map.of("GBP", BigDecimal.ONE, "USD", new BigDecimal("1.25"));

    @TempDir Path inputDir;
    @TempDir Path dbDir;
    @TempDir Path outputDir;

    private ExportExcelService service() {
        PortfolioGatherer gatherer = new PortfolioGatherer(FX, new HoldingFileLocator(inputDir));
        return new ExportExcelService(gatherer, new PortfolioDatabase(dbDir), new ExcelReportWriter(), outputDir);
    }

    @Test
    void emptyWhenNoInputFiles() {
        ExportResult result = service().export(BigDecimal.ZERO);
        assertTrue(result.empty());
        assertTrue(result.files().isEmpty());
    }

    @Test
    void writesBothWorkbooks() throws IOException {
        Files.writeString(inputDir.resolve("11111111-1111-1111-1111-111111111111.csv"),
                "Symbol,Qty,Market Value,Book Cost\nAAPL,10,$1500.00,$1000.00\n");

        ExportResult result = service().export(new BigDecimal("500"));

        assertFalse(result.empty());
        assertEquals(2, result.files().size());

        try (var stream = Files.list(outputDir)) {
            List<Path> workbooks = stream.filter(p -> p.toString().endsWith(".xlsx")).toList();
            assertEquals(2, workbooks.size());
            for (Path workbook : workbooks) {
                assertTrue(Files.size(workbook) > 0, () -> workbook + " should be non-empty");
            }
        }
    }
}
