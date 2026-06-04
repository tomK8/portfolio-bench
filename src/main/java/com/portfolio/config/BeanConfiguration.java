package com.portfolio.config;

import com.portfolio.ExcelReportWriter;
import com.portfolio.adapter.FrankfurterFxClient;
import com.portfolio.adapter.HoldingFileLocator;
import com.portfolio.adapter.YahooPriceFetcher;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.application.*;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import com.portfolio.persistence.SnapshotRepository;
import com.portfolio.port.FxRateProvider;
import com.portfolio.port.HistoricalFxRateProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * The only place outside the web layer that knows about Spring. Wires the
 * framework-agnostic adapters, repositories and application services as beans, so the
 * domain, adapter, persistence and application classes stay free of Spring annotations.
 *
 * <p>{@code portfolio.input-dir}, {@code portfolio.db-dir} and {@code portfolio.output-dir}
 * are overridable (blank = use the production defaults) — primarily so tests and
 * local runs can point at temporary directories instead of ~/Downloads and ~/Documents.
 */
@Configuration
public class BeanConfiguration {

    private static final Path DEFAULT_DB_DIR =
            Path.of(System.getProperty("user.home"), "Documents", "Investing");

    private static Path inputDir(String configured) {
        return configured.isBlank()
                ? Path.of(System.getProperty("user.home"), "Downloads")
                : Path.of(configured);
    }

    private static Path dbDir(String configured) {
        return configured.isBlank() ? DEFAULT_DB_DIR : Path.of(configured);
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
    public JdbcConnectionFactory jdbcConnectionFactory(@Value("${portfolio.db-dir:}") String dbDir) {
        return new JdbcConnectionFactory(dbDir(dbDir));
    }

    @Bean
    public KeyValueStore keyValueStore(JdbcConnectionFactory connections) {
        return new KeyValueStore(connections.dbDir());
    }

    @Bean
    public SnapshotRepository snapshotRepository(JdbcConnectionFactory connections) {
        return new SnapshotRepository(connections);
    }

    @Bean
    public CashTransactionRepository cashTransactionRepository(JdbcConnectionFactory connections,
                                                               KeyValueStore keyValueStore) {
        return new CashTransactionRepository(connections, keyValueStore);
    }

    @Bean
    public PriceHistoryRepository priceHistoryRepository(JdbcConnectionFactory connections) {
        return new PriceHistoryRepository(connections);
    }

    @Bean
    public IntradayPriceRepository intradayPriceRepository(JdbcConnectionFactory connections) {
        return new IntradayPriceRepository(connections);
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
    public DividendService dividendService(CashTransactionRepository cashTransactionRepository) {
        return new DividendService(cashTransactionRepository);
    }

    @Bean
    public SyncPortfolioService syncPortfolioService(PortfolioGatherer portfolioGatherer,
                                                     SnapshotRepository snapshotRepository,
                                                     IntradayPriceRepository intradayPriceRepository,
                                                     DividendService dividendService,
                                                     YahooTickerMap yahooTickerMap) {
        return new SyncPortfolioService(portfolioGatherer, snapshotRepository, intradayPriceRepository,
                dividendService, yahooTickerMap);
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
                                               JdbcConnectionFactory jdbcConnectionFactory,
                                               CashTransactionRepository cashTransactionRepository,
                                               HistoricalFxRateProvider historicalFxRateProvider) {
        return new ImportCashService(inputDir(inputDir), jdbcConnectionFactory.dbDir(),
                cashTransactionRepository, historicalFxRateProvider);
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
    public PriceFetchJob priceFetchJob(CashTransactionRepository cashTransactionRepository,
                                       PriceHistoryRepository priceHistoryRepository,
                                       YahooPriceFetcher yahooPriceFetcher,
                                       YahooTickerMap yahooTickerMap) {
        return new PriceFetchJob(cashTransactionRepository, priceHistoryRepository,
                yahooPriceFetcher, yahooTickerMap);
    }

    @Bean
    public IntradayPriceFetchJob intradayPriceFetchJob(CashTransactionRepository cashTransactionRepository,
                                                       IntradayPriceRepository intradayPriceRepository,
                                                       YahooPriceFetcher yahooPriceFetcher,
                                                       YahooTickerMap yahooTickerMap) {
        return new IntradayPriceFetchJob(cashTransactionRepository, intradayPriceRepository,
                yahooPriceFetcher, yahooTickerMap);
    }
}
