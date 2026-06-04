package com.portfolio.config;

import com.portfolio.ExcelReportWriter;
import com.portfolio.PortfolioDatabase;
import com.portfolio.adapter.FrankfurterFxClient;
import com.portfolio.adapter.HoldingFileLocator;
import com.portfolio.adapter.YahooPriceFetcher;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.application.*;
import com.portfolio.port.FxRateProvider;
import com.portfolio.port.HistoricalFxRateProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * The only place outside the web layer that knows about Spring. Wires the
 * framework-agnostic adapters and application services as beans, so the domain,
 * adapter and application classes stay free of Spring annotations.
 *
 * <p>{@code portfolio.input-dir}, {@code portfolio.db-dir} and {@code portfolio.output-dir}
 * are overridable (blank = use the production defaults) — primarily so tests and
 * local runs can point at temporary directories instead of ~/Downloads and ~/Documents.
 */
@Configuration
public class BeanConfiguration {

    private static Path inputDir(String configured) {
        return configured.isBlank()
                ? Path.of(System.getProperty("user.home"), "Downloads")
                : Path.of(configured);
    }

    @Bean
    public FxRateProvider fxRateProvider() {
        return new FrankfurterFxClient();
    }

    @Bean
    public HistoricalFxRateProvider historicalFxRateProvider() {
        return new FrankfurterFxClient();
    }

    @Bean
    public PortfolioDatabase portfolioDatabase(@Value("${portfolio.db-dir:}") String dbDir) {
        return dbDir.isBlank() ? new PortfolioDatabase() : new PortfolioDatabase(Path.of(dbDir));
    }

    @Bean
    public HoldingFileLocator holdingFileLocator(@Value("${portfolio.input-dir:}") String inputDir) {
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
    public DividendService dividendService(PortfolioDatabase portfolioDatabase) {
        return new DividendService(portfolioDatabase);
    }

    @Bean
    public SyncPortfolioService syncPortfolioService(PortfolioGatherer portfolioGatherer,
                                                     PortfolioDatabase portfolioDatabase,
                                                     DividendService dividendService) {
        return new SyncPortfolioService(portfolioGatherer, portfolioDatabase, dividendService);
    }

    @Bean
    public ExportExcelService exportExcelService(PortfolioGatherer portfolioGatherer,
                                                 DividendService dividendService,
                                                 ExcelReportWriter excelReportWriter,
                                                 @Value("${portfolio.output-dir:}") String outputDir) {
        Path dir = outputDir.isBlank()
                ? Path.of(System.getProperty("user.home"), "Documents")
                : Path.of(outputDir);
        return new ExportExcelService(portfolioGatherer, dividendService, excelReportWriter, dir);
    }

    @Bean
    public ImportCashService importCashService(@Value("${portfolio.input-dir:}") String inputDir,
                                               PortfolioDatabase portfolioDatabase,
                                               HistoricalFxRateProvider historicalFxRateProvider) {
        return new ImportCashService(inputDir(inputDir), portfolioDatabase, historicalFxRateProvider);
    }

    @Bean
    public YahooTickerMap yahooTickerMap() {
        return new YahooTickerMap();
    }

    @Bean
    public YahooPriceFetcher yahooPriceFetcher() {
        return new YahooPriceFetcher();
    }

    @Bean
    public PriceFetchJob priceFetchJob(PortfolioDatabase portfolioDatabase,
                                       YahooPriceFetcher yahooPriceFetcher,
                                       YahooTickerMap yahooTickerMap) {
        return new PriceFetchJob(portfolioDatabase, yahooPriceFetcher, yahooTickerMap);
    }
}
