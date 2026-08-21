package com.portfolio.application;

import com.portfolio.ExcelReportWriter;
import com.portfolio.domain.PortfolioMetrics;
import com.portfolio.domain.model.AggHolding;
import com.portfolio.adapter.HoldingFileLocator;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.port.FxRateProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExportExcelServiceTest {

    private static final FxRateProvider FX =
            () -> Map.of("GBP", BigDecimal.ONE, "USD", new BigDecimal("1.25"));

    @TempDir
    Path inputDir;
    @TempDir
    Path dbDir;
    @TempDir
    Path outputDir;

    private ExportExcelService service() {
        CashTransactionRepository repo = new CashTransactionRepository(
                new JdbcConnectionFactory(dbDir), new KeyValueStore(dbDir));
        PortfolioGatherer gatherer = new PortfolioGatherer(FX, new HoldingFileLocator(inputDir));
        return new ExportExcelService(gatherer, new DividendService(repo), new ExcelReportWriter(), outputDir);
    }

    @Test
    void emptyWhenNoInputFiles() {
        ExportResult result = service().export(BigDecimal.ZERO, BigDecimal.ZERO);
        assertTrue(result.empty());
        assertTrue(result.files().isEmpty());
    }

    @Test
    void writesBothWorkbooks() throws IOException {
        Files.writeString(inputDir.resolve("11111111-1111-1111-1111-111111111111.csv"),
                "Symbol,Qty,Market Value,Book Cost\nAAPL,10,$1500.00,$1000.00\n");

        ExportResult result = service().export(new BigDecimal("500"), BigDecimal.ZERO);

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

    // ---- Cash-ledger and Live exports -------------------------------------
    // Both take the view's own result rather than re-deriving it, so these assert the file lands
    // in the output dir under the expected name — not the browser's download folder.

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static SyncResult ledgerResult(List<AggHolding> holdings) {
        return new SyncResult(holdings, new PortfolioMetrics().compute(holdings, BigDecimal.ZERO),
                Map.of("USD", new BigDecimal("1.25")), BigDecimal.ZERO, List.of("Roth IRA"), false,
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void ledgerExportWritesOneWorkbookToTheOutputDir() {
        AggHolding h = new AggHolding("NVDA", new BigDecimal("10"), null, new BigDecimal("1500.00"),
                new BigDecimal("500.00"), new BigDecimal("0.5"), BigDecimal.ZERO,
                new BigDecimal("500.00"), new BigDecimal("0.5"), Currency.getInstance("USD"),
                "Roth IRA", new BigDecimal("187.5"), new BigDecimal("1875.00"),
                new BigDecimal("1500.00"), null);

        ExportResult result = service().exportLedger(ledgerResult(List.of(h)));

        assertFalse(result.empty());
        assertEquals(1, result.files().size());
        Path file = outputDir.resolve("Portfolio Ledger-" + LocalDate.now().format(DATE_FMT) + ".xlsx");
        assertTrue(Files.exists(file), () -> "expected " + file);
        assertEquals(file.toString(), result.files().get(0));
    }

    @Test
    void ledgerExportOfAnEmptyLedgerWritesNothing() {
        assertTrue(service().exportLedger(SyncResult.empty(Map.of())).empty());
        assertTrue(service().exportLedger(ledgerResult(List.of())).empty());
    }

    @Test
    void liveExportWritesOneWorkbookToTheOutputDir() {
        LivePricesService.Row row = new LivePricesService.Row("NVDA", "USD", new BigDecimal("10"),
                new BigDecimal("187.5"), new BigDecimal("0.01"), new BigDecimal("12.00"),
                new BigDecimal("1500.00"), "Roth IRA", Instant.parse("2026-08-21T12:58:00Z"),
                "REGULAR", "OPEN", null, null, null, null, null);
        LivePricesService.Snapshot snapshot = new LivePricesService.Snapshot(List.of(row),
                new LivePricesService.Totals(new BigDecimal("1500.00"), BigDecimal.ZERO,
                        new BigDecimal("1500.00"), new BigDecimal("12.00"), new BigDecimal("0.01")),
                Instant.now(), Instant.parse("2026-08-21T12:58:00Z"));

        ExportResult result = service().exportLive(snapshot);

        assertFalse(result.empty());
        Path file = outputDir.resolve("Live Positions-" + LocalDate.now().format(DATE_FMT) + ".xlsx");
        assertTrue(Files.exists(file), () -> "expected " + file);
    }

    @Test
    void liveExportWithNoPricedPositionsWritesNothing() {
        // An empty sheet of a live view reads as "the portfolio is empty" rather than
        // "no quotes have arrived yet", so there is nothing worth writing.
        LivePricesService.Snapshot empty = new LivePricesService.Snapshot(List.of(),
                new LivePricesService.Totals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, null), Instant.now(), null);

        assertTrue(service().exportLive(empty).empty());
    }
}
