package com.pension.config;

import com.pension.ExcelReportWriter;
import com.pension.PortfolioDatabase;
import com.pension.adapter.FrankfurterFxClient;
import com.pension.adapter.HoldingFileLocator;
import com.pension.application.ExportExcelService;
import com.pension.application.ImportCashService;
import com.pension.application.PortfolioGatherer;
import com.pension.application.RecordDividendsService;
import com.pension.application.SyncPortfolioService;
import com.pension.port.FxRateProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * The only place outside the web layer that knows about Spring. Wires the
 * framework-agnostic adapters and application services as beans, so the domain,
 * adapter and application classes stay free of Spring annotations.
 *
 * <p>{@code pension.input-dir}, {@code pension.db-dir} and {@code pension.output-dir}
 * are overridable (blank = use the production defaults) — primarily so tests and
 * local runs can point at temporary directories instead of ~/Downloads and ~/Documents.
 */
@Configuration
public class BeanConfiguration {

    @Bean
    public FxRateProvider fxRateProvider() {
        return new FrankfurterFxClient();
    }

    @Bean
    public PortfolioDatabase portfolioDatabase(@Value("${pension.db-dir:}") String dbDir) {
        return dbDir.isBlank() ? new PortfolioDatabase() : new PortfolioDatabase(Path.of(dbDir));
    }

    @Bean
    public HoldingFileLocator holdingFileLocator(@Value("${pension.input-dir:}") String inputDir) {
        return new HoldingFileLocator(inputDir(inputDir));
    }

    @Bean
    public ExcelReportWriter excelReportWriter() {
        return new ExcelReportWriter();
    }

    @Bean
    public PortfolioGatherer portfolioGatherer(FxRateProvider fxRateProvider,
                                               HoldingFileLocator holdingFileLocator) {
        return new PortfolioGatherer(fxRateProvider, holdingFileLocator);
    }

    @Bean
    public SyncPortfolioService syncPortfolioService(PortfolioGatherer portfolioGatherer,
                                                     PortfolioDatabase portfolioDatabase) {
        return new SyncPortfolioService(portfolioGatherer, portfolioDatabase);
    }

    @Bean
    public RecordDividendsService recordDividendsService(FxRateProvider fxRateProvider,
                                                         PortfolioDatabase portfolioDatabase) {
        return new RecordDividendsService(fxRateProvider, portfolioDatabase);
    }

    @Bean
    public ExportExcelService exportExcelService(PortfolioGatherer portfolioGatherer,
                                                 PortfolioDatabase portfolioDatabase,
                                                 ExcelReportWriter excelReportWriter,
                                                 @Value("${pension.output-dir:}") String outputDir) {
        Path dir = outputDir.isBlank()
                ? Path.of(System.getProperty("user.home"), "Documents")
                : Path.of(outputDir);
        return new ExportExcelService(portfolioGatherer, portfolioDatabase, excelReportWriter, dir);
    }

    @Bean
    public ImportCashService importCashService(@Value("${pension.input-dir:}") String inputDir,
                                               PortfolioDatabase portfolioDatabase) {
        return new ImportCashService(inputDir(inputDir), portfolioDatabase);
    }

    private static Path inputDir(String configured) {
        return configured.isBlank()
                ? Path.of(System.getProperty("user.home"), "Downloads")
                : Path.of(configured);
    }
}
