package com.portfolio.application;

import com.portfolio.ExcelReportWriter;
import com.portfolio.domain.PortfolioAggregator;
import com.portfolio.domain.model.AggHolding;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Writes the date-stamped Excel workbooks to the output directory — the on-demand equivalent
 * of the batch run's Excel step. Produces files only; recording a snapshot is the separate sync
 * operation.
 *
 * <p>{@link #export} gathers the broker holdings files itself, the way the batch run does. The
 * cash-ledger and Live exports instead take the view's own result: both are cheap to recompute
 * but expensive to duplicate — re-deriving them here would put a second implementation of each
 * view's valuation next to the first, and the file could then disagree with the screen it was
 * downloaded from. The caller hands over what it just rendered.
 */
public class ExportExcelService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PortfolioGatherer gatherer;
    private final DividendService dividendService;
    private final ExcelReportWriter writer;
    private final Path outputDir;

    public ExportExcelService(PortfolioGatherer gatherer, DividendService dividendService,
                              ExcelReportWriter writer, Path outputDir) {
        this.gatherer = gatherer;
        this.dividendService = dividendService;
        this.writer = writer;
        this.outputDir = outputDir;
    }

    public ExportResult export(IiCash iiCash) {
        GatheredPortfolio gathered = gatherer.gather();
        if (gathered.holdings().isEmpty()) {
            return ExportResult.nothing();
        }

        BigDecimal iiSippCash = iiCash.totalGbp(gathered.rates());
        Map<String, BigDecimal> dividendsBySymbol = dividendService.dividendsBySymbol(gathered.holdings());
        List<AggHolding> aggregated = new PortfolioAggregator()
                .aggregate(gathered.holdings(), gathered.rates(), dividendsBySymbol);
        String date = LocalDateTime.now().format(DATE_FMT);

        try {
            Files.createDirectories(outputDir);

            Path fullReport = outputDir.resolve("portfolio" + date + ".xlsx");
            writer.writeFullReport(fullReport, aggregated, gathered.holdings(), gathered.sources(),
                    gathered.rates(), iiSippCash, dividendsBySymbol);

            Path summary = outputDir.resolve("Portfolio Summary-" + date + ".xlsx");
            writer.writeSummaryReport(summary, aggregated, gathered.rates(), iiSippCash);

            return new ExportResult(false, List.of(fullReport.toString(), summary.toString()));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write Excel reports to " + outputDir, e);
        }
    }

    /**
     * Writes the cash-ledger view to {@code Portfolio Ledger-YYYYMMDD.xlsx} in the output
     * directory (~/Documents — same place the other exports land, not the browser's download
     * folder). Takes the {@link SyncResult} the caller just produced, so the file matches the
     * table it came from.
     */
    public ExportResult exportLedger(SyncResult result) {
        if (result == null || result.empty() || result.holdings().isEmpty()) {
            return ExportResult.nothing();
        }
        return writeWorkbook("Portfolio Ledger-",
                () -> writer.ledgerWorkbook(result.holdings(), result.totals(), result.irrBySymbol()));
    }

    /**
     * Writes the Live tab's positions to {@code Live Positions-YYYYMMDD.xlsx} in the output
     * directory. A snapshot with no priced positions writes nothing — an empty sheet of a live
     * view reads as "the portfolio is empty" rather than "no quotes have arrived yet".
     */
    public ExportResult exportLive(LivePricesService.Snapshot snapshot) {
        if (snapshot == null || snapshot.rows().isEmpty()) {
            return ExportResult.nothing();
        }
        return writeWorkbook("Live Positions-", () -> writer.liveWorkbook(snapshot));
    }

    private ExportResult writeWorkbook(String namePrefix, WorkbookBytes bytes) {
        String date = LocalDate.now().format(DATE_FMT);
        try {
            Files.createDirectories(outputDir);
            Path file = outputDir.resolve(namePrefix + date + ".xlsx");
            Files.write(file, bytes.get());
            return new ExportResult(false, List.of(file.toString()));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write Excel report to " + outputDir, e);
        }
    }

    /** A workbook rendered to bytes; {@code IOException} so POI's writer can be passed directly. */
    private interface WorkbookBytes {
        byte[] get() throws IOException;
    }
}
