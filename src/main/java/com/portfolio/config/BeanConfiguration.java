package com.portfolio.config;

import com.portfolio.ExcelReportWriter;
import com.portfolio.adapter.FrankfurterFxClient;
import com.portfolio.adapter.GiltPriceFetcher;
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
                                                     YahooTickerMap yahooTickerMap,
                                                     CashTransactionRepository cashTransactionRepository,
                                                     KeyValueStore keyValueStore) {
        return new SyncPortfolioService(portfolioGatherer, snapshotRepository, intradayPriceRepository,
                dividendService, yahooTickerMap, cashTransactionRepository, keyValueStore);
    }

    @Bean
    public PortfolioValueService portfolioValueService(CashTransactionRepository cashTransactionRepository,
                                                       PriceHistoryRepository priceHistoryRepository,
                                                       HistoricalFxRateProvider historicalFxRateProvider,
                                                       YahooTickerMap yahooTickerMap,
                                                       KeyValueStore keyValueStore) {
        return new PortfolioValueService(cashTransactionRepository, priceHistoryRepository,
                historicalFxRateProvider, yahooTickerMap, keyValueStore);
    }

    @Bean
    public PortfolioReturnService portfolioReturnService(PortfolioValueService portfolioValueService,
                                                         CashTransactionRepository cashTransactionRepository,
                                                         KeyValueStore keyValueStore,
                                                         HistoricalFxRateProvider historicalFxRateProvider) {
        return new PortfolioReturnService(portfolioValueService, cashTransactionRepository,
                keyValueStore, historicalFxRateProvider);
    }

    @Bean
    public PortfolioRiskService portfolioRiskService(PortfolioReturnService portfolioReturnService,
                                                     KeyValueStore keyValueStore) {
        return new PortfolioRiskService(portfolioReturnService, keyValueStore);
    }

    @Bean
    public DividendIncomeService dividendIncomeService(CashTransactionRepository cashTransactionRepository,
                                                       IntradayPriceRepository intradayPriceRepository,
                                                       FxRateProvider fxRateProvider,
                                                       YahooTickerMap yahooTickerMap) {
        return new DividendIncomeService(cashTransactionRepository, intradayPriceRepository,
                fxRateProvider, yahooTickerMap);
    }

    @Bean
    public PositionDetailService positionDetailService(CashTransactionRepository cashTransactionRepository,
                                                       PriceHistoryRepository priceHistoryRepository,
                                                       IntradayPriceRepository intradayPriceRepository,
                                                       FxRateProvider fxRateProvider,
                                                       YahooTickerMap yahooTickerMap) {
        return new PositionDetailService(cashTransactionRepository, priceHistoryRepository,
                intradayPriceRepository, fxRateProvider, yahooTickerMap);
    }

    @Bean
    public ConcentrationService concentrationService(AllocationService allocationService,
                                                     CashTransactionRepository cashTransactionRepository,
                                                     IntradayPriceRepository intradayPriceRepository,
                                                     FxRateProvider fxRateProvider,
                                                     YahooTickerMap yahooTickerMap) {
        return new ConcentrationService(allocationService, cashTransactionRepository,
                intradayPriceRepository, fxRateProvider, yahooTickerMap);
    }

    @Bean
    public TargetAllocationService targetAllocationService(ConcentrationService concentrationService,
                                                           KeyValueStore keyValueStore) {
        return new TargetAllocationService(concentrationService, keyValueStore);
    }

    @Bean
    public DividendAuditService dividendAuditService(CashTransactionRepository cashTransactionRepository) {
        return new DividendAuditService(cashTransactionRepository);
    }

    @Bean
    public ReconciliationService reconciliationService(CashTransactionRepository cashTransactionRepository,
                                                       PriceHistoryRepository priceHistoryRepository,
                                                       IntradayPriceRepository intradayPriceRepository,
                                                       YahooTickerMap yahooTickerMap) {
        return new ReconciliationService(cashTransactionRepository, priceHistoryRepository,
                intradayPriceRepository, yahooTickerMap);
    }

    @Bean
    public HealthService healthService(JdbcConnectionFactory jdbcConnectionFactory) {
        return new HealthService(jdbcConnectionFactory);
    }

    @Bean
    public CurrencyExposureService currencyExposureService(AllocationService allocationService,
                                                           CashTransactionRepository cashTransactionRepository,
                                                           IntradayPriceRepository intradayPriceRepository,
                                                           FxRateProvider fxRateProvider,
                                                           HistoricalFxRateProvider historicalFxRateProvider,
                                                           YahooTickerMap yahooTickerMap) {
        return new CurrencyExposureService(allocationService, cashTransactionRepository,
                intradayPriceRepository, fxRateProvider, historicalFxRateProvider, yahooTickerMap);
    }

    @Bean
    public BenchmarkReturnService benchmarkReturnService(PortfolioValueService portfolioValueService,
                                                         PriceHistoryRepository priceHistoryRepository,
                                                         YahooTickerMap yahooTickerMap) {
        return new BenchmarkReturnService(portfolioValueService, priceHistoryRepository, yahooTickerMap);
    }

    @Bean
    public WhatIfService whatIfService(CashTransactionRepository cashTransactionRepository,
                                       PriceHistoryRepository priceHistoryRepository,
                                       HistoricalFxRateProvider historicalFxRateProvider,
                                       YahooTickerMap yahooTickerMap,
                                       KeyValueStore keyValueStore) {
        return new WhatIfService(cashTransactionRepository, priceHistoryRepository,
                historicalFxRateProvider, yahooTickerMap, keyValueStore);
    }

    @Bean
    public AllocationService allocationService(CashTransactionRepository cashTransactionRepository,
                                               PriceHistoryRepository priceHistoryRepository,
                                               HistoricalFxRateProvider historicalFxRateProvider,
                                               YahooTickerMap yahooTickerMap,
                                               KeyValueStore keyValueStore) {
        return new AllocationService(cashTransactionRepository, priceHistoryRepository,
                historicalFxRateProvider, yahooTickerMap, keyValueStore);
    }

    @Bean
    public AttributionService attributionService(CashTransactionRepository cashTransactionRepository,
                                                 PriceHistoryRepository priceHistoryRepository,
                                                 IntradayPriceRepository intradayPriceRepository,
                                                 HistoricalFxRateProvider historicalFxRateProvider,
                                                 FxRateProvider fxRateProvider,
                                                 YahooTickerMap yahooTickerMap) {
        return new AttributionService(cashTransactionRepository, priceHistoryRepository,
                intradayPriceRepository, historicalFxRateProvider, fxRateProvider, yahooTickerMap);
    }

    @Bean
    public ContributionService contributionService(CashTransactionRepository cashTransactionRepository,
                                                   KeyValueStore keyValueStore,
                                                   HistoricalFxRateProvider historicalFxRateProvider) {
        return new ContributionService(cashTransactionRepository, keyValueStore,
                historicalFxRateProvider);
    }

    @Bean
    public SyncFromCashService syncFromCashService(CashTransactionRepository cashTransactionRepository,
                                                   IntradayPriceRepository intradayPriceRepository,
                                                   FxRateProvider fxRateProvider,
                                                   YahooTickerMap yahooTickerMap) {
        return new SyncFromCashService(cashTransactionRepository, intradayPriceRepository,
                fxRateProvider, yahooTickerMap);
    }

    @Bean
    public LivePricesService livePricesService(CashTransactionRepository cashTransactionRepository,
                                               IntradayPriceRepository intradayPriceRepository,
                                               PriceHistoryRepository priceHistoryRepository,
                                               FxRateProvider fxRateProvider,
                                               YahooTickerMap yahooTickerMap) {
        return new LivePricesService(cashTransactionRepository, intradayPriceRepository,
                priceHistoryRepository, fxRateProvider, yahooTickerMap);
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
    public AjBellCashImporter ajBellCashImporter(JdbcConnectionFactory jdbcConnectionFactory,
                                                 CashTransactionRepository cashTransactionRepository) {
        return new AjBellCashImporter(jdbcConnectionFactory.dbDir(), cashTransactionRepository);
    }

    @Bean
    public RothIraCashImporter rothIraCashImporter(JdbcConnectionFactory jdbcConnectionFactory,
                                                   CashTransactionRepository cashTransactionRepository,
                                                   HistoricalFxRateProvider historicalFxRateProvider) {
        return new RothIraCashImporter(jdbcConnectionFactory.dbDir(),
                cashTransactionRepository, historicalFxRateProvider);
    }

    @Bean
    public IiCashImporter iiCashImporter(JdbcConnectionFactory jdbcConnectionFactory,
                                         CashTransactionRepository cashTransactionRepository,
                                         HistoricalFxRateProvider historicalFxRateProvider) {
        return new IiCashImporter(jdbcConnectionFactory.dbDir(),
                cashTransactionRepository, historicalFxRateProvider);
    }

    @Bean
    public ImportCashService importCashService(@Value("${portfolio.input-dir:}") String inputDir,
                                               java.util.List<CashImporter> cashImporters) {
        return new ImportCashService(inputDir(inputDir), cashImporters);
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
                                       YahooTickerMap yahooTickerMap,
                                       KeyValueStore keyValueStore) {
        return new PriceFetchJob(cashTransactionRepository, priceHistoryRepository,
                yahooPriceFetcher, yahooTickerMap, keyValueStore);
    }

    @Bean
    public IntradayPriceFetchJob intradayPriceFetchJob(CashTransactionRepository cashTransactionRepository,
                                                       IntradayPriceRepository intradayPriceRepository,
                                                       YahooPriceFetcher yahooPriceFetcher,
                                                       YahooTickerMap yahooTickerMap,
                                                       KeyValueStore keyValueStore) {
        return new IntradayPriceFetchJob(cashTransactionRepository, intradayPriceRepository,
                yahooPriceFetcher, yahooTickerMap, keyValueStore);
    }

    @Bean
    public GiltPriceFetcher giltPriceFetcher() {
        return new GiltPriceFetcher();
    }

    @Bean
    public GiltPriceFetchJob giltPriceFetchJob(GiltPriceFetcher giltPriceFetcher,
                                               IntradayPriceRepository intradayPriceRepository,
                                               PriceHistoryRepository priceHistoryRepository) {
        return new GiltPriceFetchJob(giltPriceFetcher, intradayPriceRepository, priceHistoryRepository);
    }

    @Bean
    public ImportGiltPricesService importGiltPricesService(@Value("${portfolio.input-dir:}") String inputDir,
                                                           JdbcConnectionFactory jdbcConnectionFactory,
                                                           PriceHistoryRepository priceHistoryRepository) {
        return new ImportGiltPricesService(inputDir(inputDir), jdbcConnectionFactory.dbDir(),
                priceHistoryRepository);
    }
}
