package com.pension.application;

import com.pension.ExcelReportWriter;
import com.pension.PortfolioDatabase;
import com.pension.domain.PortfolioAggregator;
import com.pension.domain.model.AggHolding;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Writes the two date-stamped Excel workbooks (full report + summary) to the
 * output directory — the on-demand equivalent of the batch run's Excel step.
 * Produces files only; recording a snapshot is the separate sync operation.
 */
public class ExportExcelService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PortfolioGatherer gatherer;
    private final PortfolioDatabase db;
    private final ExcelReportWriter writer;
    private final Path outputDir;

    public ExportExcelService(PortfolioGatherer gatherer, PortfolioDatabase db,
                              ExcelReportWriter writer, Path outputDir) {
        this.gatherer = gatherer;
        this.db = db;
        this.writer = writer;
        this.outputDir = outputDir;
    }

    public ExportResult export(BigDecimal iiSippCash) {
        GatheredPortfolio gathered = gatherer.gather();
        if (gathered.holdings().isEmpty()) {
            return ExportResult.nothing();
        }

        List<AggHolding> aggregated =
                new PortfolioAggregator().aggregate(gathered.holdings(), gathered.rates());
        Map<String, BigDecimal> dividendsBySymbol = db.loadDividendsBySymbol();
        String date = LocalDateTime.now().format(DATE_FMT);

        try {
            Files.createDirectories(outputDir);

            Path fullReport = outputDir.resolve("portfolio" + date + ".xlsx");
            writer.writeFullReport(fullReport, aggregated, gathered.holdings(), gathered.sources(),
                    gathered.rates(), iiSippCash, dividendsBySymbol);

            Path summary = outputDir.resolve("Portfolio Summary-" + date + ".xlsx");
            writer.writeSummaryReport(summary, aggregated, gathered.rates(), iiSippCash, dividendsBySymbol);

            return new ExportResult(false, List.of(fullReport.toString(), summary.toString()));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write Excel reports to " + outputDir, e);
        }
    }
}
