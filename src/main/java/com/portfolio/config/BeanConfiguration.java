package com.portfolio.config;

import com.portfolio.ExcelReportWriter;
import com.portfolio.adapter.CachingFxRateProvider;
import com.portfolio.application.CachedHistoricalFxRateProvider;
import com.portfolio.adapter.CikRegistry;
import com.portfolio.adapter.EdgarEarningsFetcher;
import com.portfolio.adapter.EdgarFundamentalsFetcher;
import com.portfolio.adapter.FrankfurterFxClient;
import com.portfolio.adapter.GiltPriceFetcher;
import com.portfolio.adapter.HoldingFileLocator;
import com.portfolio.adapter.NasdaqEarningsFetcher;
import com.portfolio.adapter.SmtpAlertNotifier;
import com.portfolio.adapter.YahooPriceFetcher;
import com.portfolio.adapter.YahooQuoteSummaryFetcher;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.application.*;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.application.EarningsService;
import com.portfolio.parser.AJBellOrdersParser;
import com.portfolio.parser.IIOrdersParser;
import com.portfolio.parser.RothOrdersParser;
import com.portfolio.persistence.EarningsCalendarRepository;
import com.portfolio.persistence.InstrumentMappingRepository;
import com.portfolio.persistence.EarningsDateRepository;
import com.portfolio.domain.EventMoves;
import com.portfolio.domain.SectorClassifier;
import com.portfolio.domain.StyleClassifier;
import com.portfolio.persistence.FundamentalsRepository;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.persistence.FxRateRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import com.portfolio.persistence.SnapshotRepository;
import com.portfolio.persistence.TradeNotesRepository;
import com.portfolio.persistence.WatchlistRepository;
import com.portfolio.port.AlertNotifier;
import com.portfolio.port.EarningsCalendarFeed;
import com.portfolio.port.EarningsCalendarProvider;
import com.portfolio.port.FxRateProvider;
import com.portfolio.port.HistoricalFxRateProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

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

    /** Live GBP FX rates, TTL-cached with stale-on-failure fallback (see the decorator). */
    @Bean
    public FxRateProvider fxRateProvider() {
        return new CachingFxRateProvider(new FrankfurterFxClient(), Duration.ofMinutes(15));
    }

    /**
     * Historical GBP FX, cached on disk. Published past rates never change, so the cache is
     * indefinitely valid and the API is touched only for an uncovered head or tail — which is what
     * stops a burst of tab clicks from timing Frankfurter out and silently zeroing every non-GBP
     * position on a window boundary. Every {@code fetchRateSeries} caller inherits this.
     */
    @Bean
    public CachedHistoricalFxRateProvider historicalFxRateProvider(FxRateRepository fxRateRepository) {
        return new CachedHistoricalFxRateProvider(fxRateRepository, new FrankfurterFxClient());
    }

    @Bean
    public FxRateRepository fxRateRepository(JdbcConnectionFactory jdbcConnectionFactory) {
        return new FxRateRepository(jdbcConnectionFactory);
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
    public FundamentalsRepository fundamentalsRepository(JdbcConnectionFactory connections) {
        return new FundamentalsRepository(connections);
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
    public OpenOrdersService openOrdersService(HoldingFileLocator holdingFileLocator,
                                               FxRateProvider fxRateProvider,
                                               ExcelReportWriter excelReportWriter,
                                               IntradayPriceRepository intradayPriceRepository,
                                               PriceHistoryRepository priceHistoryRepository,
                                               YahooTickerMap yahooTickerMap,
                                               InstrumentMappingRepository instrumentMappingRepository,
                                               KeyValueStore keyValueStore,
                                               IntradayPriceFetchJob intradayPriceFetchJob,
                                               PriceFetchJob priceFetchJob,
                                               EarningsCalendarRepository earningsCalendarRepository,
                                               EarningsCalendarService earningsCalendarService,
                                               @Value("${portfolio.output-dir:}") String outputDir) {
        Path dir = outputDir.isBlank()
                ? Path.of(System.getProperty("user.home"), "Documents")
                : Path.of(outputDir);
        return new OpenOrdersService(holdingFileLocator, List.of(
                new AJBellOrdersParser(instrumentMappingRepository::loadAliases),
                new IIOrdersParser(),
                new RothOrdersParser()), fxRateProvider, excelReportWriter, dir,
                intradayPriceRepository, priceHistoryRepository, yahooTickerMap, keyValueStore,
                // Off-cycle quote warm for symbols whose feed is visibly behind. One daemon thread,
                // so a burst of tab clicks queues instead of racing Yahoo — and the panel never
                // waits on it: the fetched price appears on the next reload.
                warmOnBackgroundThread(intradayPriceFetchJob),
                // The pre-trade limit probe reaches for symbols nothing else in the app has ever
                // named, so their daily series — the volatility every probability is scaled by — can
                // be missing entirely. fetchSingleIfStale is the right depth: it skips a name whose
                // history is already current, so re-probing a held position costs no HTTP at all.
                backfillOnBackgroundThread(priceFetchJob),
                // Scheduled-event variance reads the cached earnings calendar and never fetches
                // inline: a date we don't have yields an unadjusted probability and a warning, not
                // an inferred date. The Earnings tab and the calendar warmer keep the cache filled.
                new OrderEventVariance(earningsCalendarRepository, yahooTickerMap,
                        new EventMoves(), warmCalendarOnBackgroundThread(earningsCalendarService)));
    }

    /**
     * Fills the earnings calendar forward on a daemon thread. One thread, so a burst of tab clicks
     * queues rather than racing Nasdaq — and the panel never waits: an unadjusted probability is
     * rendered now and the event-adjusted one appears on a later reload.
     */
    private static IntConsumer warmCalendarOnBackgroundThread(EarningsCalendarService service) {
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "orders-calendar-warm");
            t.setDaemon(true);
            return t;
        });
        return days -> exec.submit(() -> service.warmForward(days));
    }

    private static Consumer<Collection<String>> warmOnBackgroundThread(IntradayPriceFetchJob job) {
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "orders-quote-warm");
            t.setDaemon(true);
            return t;
        });
        return tickers -> exec.submit(() -> job.fetchNow(tickers));
    }

    private static Consumer<String> backfillOnBackgroundThread(PriceFetchJob job) {
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "orders-history-warm");
            t.setDaemon(true);
            return t;
        });
        return symbol -> exec.submit(() -> job.fetchSingleIfStale(symbol));
    }

    @Bean
    public DividendService dividendService(CashTransactionRepository cashTransactionRepository) {
        return new DividendService(cashTransactionRepository);
    }

    @Bean
    public TradeNotesRepository tradeNotesRepository(JdbcConnectionFactory connections) {
        return new TradeNotesRepository(connections);
    }

    @Bean
    public TradeNotesService tradeNotesService(CashTransactionRepository cashTransactionRepository,
                                               TradeNotesRepository tradeNotesRepository) {
        return new TradeNotesService(cashTransactionRepository, tradeNotesRepository);
    }

    @Bean
    public SyncPortfolioService syncPortfolioService(PortfolioGatherer portfolioGatherer,
                                                     SnapshotRepository snapshotRepository,
                                                     IntradayPriceRepository intradayPriceRepository,
                                                     DividendService dividendService,
                                                     YahooTickerMap yahooTickerMap,
                                                     CashTransactionRepository cashTransactionRepository,
                                                     PriceHistoryRepository priceHistoryRepository,
                                                     KeyValueStore keyValueStore) {
        return new SyncPortfolioService(portfolioGatherer, snapshotRepository, intradayPriceRepository,
                dividendService, yahooTickerMap, cashTransactionRepository, priceHistoryRepository,
                keyValueStore);
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
    public CorrelationService correlationService(CashTransactionRepository cashTransactionRepository,
                                                 PriceHistoryRepository priceHistoryRepository,
                                                 IntradayPriceRepository intradayPriceRepository,
                                                 FxRateProvider fxRateProvider,
                                                 YahooTickerMap yahooTickerMap) {
        return new CorrelationService(cashTransactionRepository, priceHistoryRepository,
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
    public SectorClassifier sectorClassifier() {
        return new SectorClassifier();
    }

    @Bean
    public SectorBreakdownService sectorBreakdownService(AllocationService allocationService,
                                                        FundamentalsRepository fundamentalsRepository,
                                                        SectorClassifier sectorClassifier,
                                                        FundamentalsWarmer fundamentalsWarmer) {
        return new SectorBreakdownService(allocationService, fundamentalsRepository, sectorClassifier,
                fundamentalsWarmer);
    }

    @Bean
    public StyleClassifier styleClassifier() {
        return new StyleClassifier();
    }

    @Bean
    public StyleBreakdownService styleBreakdownService(AllocationService allocationService,
                                                       FundamentalsRepository fundamentalsRepository,
                                                       StyleClassifier styleClassifier,
                                                       FundamentalsWarmer fundamentalsWarmer) {
        return new StyleBreakdownService(allocationService, fundamentalsRepository, styleClassifier,
                fundamentalsWarmer);
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
                                                   YahooTickerMap yahooTickerMap,
                                                   PriceHistoryRepository priceHistoryRepository) {
        return new SyncFromCashService(cashTransactionRepository, intradayPriceRepository,
                fxRateProvider, yahooTickerMap, priceHistoryRepository);
    }

    @Bean
    public HistoricalScenarioService historicalScenarioService(SyncFromCashService syncFromCashService,
                                                               PriceHistoryRepository priceHistoryRepository,
                                                               HistoricalFxRateProvider historicalFxRateProvider,
                                                               YahooTickerMap yahooTickerMap,
                                                               KeyValueStore keyValueStore) {
        return new HistoricalScenarioService(syncFromCashService, priceHistoryRepository,
                historicalFxRateProvider, yahooTickerMap, keyValueStore);
    }

    @Bean
    public LivePricesService livePricesService(CashTransactionRepository cashTransactionRepository,
                                               IntradayPriceRepository intradayPriceRepository,
                                               PriceHistoryRepository priceHistoryRepository,
                                               FxRateProvider fxRateProvider,
                                               YahooTickerMap yahooTickerMap,
                                               MarketHoursCache marketHoursCache) {
        return new LivePricesService(cashTransactionRepository, intradayPriceRepository,
                priceHistoryRepository, fxRateProvider, yahooTickerMap, marketHoursCache);
    }

    /**
     * Shared between the intraday fetch job (writer) and the Live tab (reader) — a singleton so
     * the calendars the job collects on its pass are the ones the dashboard renders against.
     */
    @Bean
    public MarketHoursCache marketHoursCache() {
        return new MarketHoursCache();
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
                                                 CashTransactionRepository cashTransactionRepository,
                                                 InstrumentMappingRepository instrumentMappingRepository) {
        return new AjBellCashImporter(jdbcConnectionFactory.dbDir(), cashTransactionRepository,
                instrumentMappingRepository::loadAliases);
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
    public InstrumentMappingRepository instrumentMappingRepository(JdbcConnectionFactory connections) {
        return new InstrumentMappingRepository(connections);
    }

    /**
     * Committed {@code yahoo-tickers.properties} plus the user's {@code ticker_mappings} overlay.
     * A method reference, not the loaded map: the singleton re-reads it on every mutation, so a
     * mapping added on the dashboard reaches all ~30 consumers without a restart.
     */
    @Bean
    public YahooTickerMap yahooTickerMap(InstrumentMappingRepository instrumentMappingRepository) {
        return new YahooTickerMap(instrumentMappingRepository::loadTickerMappings);
    }

    @Bean
    public InstrumentMappingService instrumentMappingService(InstrumentMappingRepository instrumentMappingRepository,
                                                             YahooTickerMap yahooTickerMap,
                                                             YahooPriceFetcher yahooPriceFetcher,
                                                             PriceFetchJob priceFetchJob,
                                                             CashTransactionRepository cashTransactionRepository) {
        return new InstrumentMappingService(instrumentMappingRepository, yahooTickerMap,
                yahooPriceFetcher, priceFetchJob, cashTransactionRepository);
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
                                                       KeyValueStore keyValueStore,
                                                       MarketHoursCache marketHoursCache) {
        return new IntradayPriceFetchJob(cashTransactionRepository, intradayPriceRepository,
                yahooPriceFetcher, yahooTickerMap, keyValueStore, marketHoursCache);
    }

    @Bean
    public GiltPriceFetcher giltPriceFetcher() {
        return new GiltPriceFetcher();
    }

    @Bean
    public EdgarFundamentalsFetcher edgarFundamentalsFetcher() {
        return new EdgarFundamentalsFetcher();
    }

    @Bean
    public FundamentalsService fundamentalsService(EdgarFundamentalsFetcher edgarFundamentalsFetcher,
                                                   PriceHistoryRepository priceHistoryRepository) {
        return new FundamentalsService(edgarFundamentalsFetcher, priceHistoryRepository);
    }

    @Bean
    public YahooQuoteSummaryFetcher yahooQuoteSummaryFetcher() {
        return new YahooQuoteSummaryFetcher();
    }

    @Bean
    public PortfolioFundamentalsService portfolioFundamentalsService(
            YahooQuoteSummaryFetcher yahooQuoteSummaryFetcher,
            YahooTickerMap yahooTickerMap,
            KeyValueStore keyValueStore,
            FundamentalsRepository fundamentalsRepository) {
        return new PortfolioFundamentalsService(yahooQuoteSummaryFetcher, yahooTickerMap,
                keyValueStore, fundamentalsRepository);
    }

    @Bean
    public FundamentalsFetchJob fundamentalsFetchJob(PortfolioFundamentalsService portfolioFundamentalsService) {
        return new FundamentalsFetchJob(portfolioFundamentalsService);
    }

    /**
     * Off-cycle fundamentals fetch for the sector and style blocks. One daemon thread, so a burst
     * of tab clicks queues instead of racing Yahoo, and neither panel ever waits on it — the
     * sector appears on the next load. Same idiom as the Open-orders quote warm above.
     */
    @Bean
    public FundamentalsWarmer fundamentalsWarmer(PortfolioFundamentalsService portfolioFundamentalsService) {
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fundamentals-warm");
            t.setDaemon(true);
            return t;
        });
        return new FundamentalsWarmer(
                symbols -> exec.submit(() -> portfolioFundamentalsService.refreshSymbols(symbols)));
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

    // ---- Watchlist (volatility-trading monitor + alerts) --------------------

    @Bean
    public WatchlistRepository watchlistRepository(JdbcConnectionFactory connections,
                                                   KeyValueStore keyValueStore) {
        return new WatchlistRepository(connections, keyValueStore);
    }

    @Bean
    public WatchlistService watchlistService(WatchlistRepository watchlistRepository,
                                             PriceHistoryRepository priceHistoryRepository,
                                             IntradayPriceRepository intradayPriceRepository,
                                             FundamentalsRepository fundamentalsRepository,
                                             CashTransactionRepository cashTransactionRepository,
                                             FxRateProvider fxRateProvider,
                                             YahooTickerMap yahooTickerMap) {
        return new WatchlistService(watchlistRepository, priceHistoryRepository, intradayPriceRepository,
                fundamentalsRepository, cashTransactionRepository, fxRateProvider, yahooTickerMap);
    }

    /**
     * SMTP alert channel. Disabled by default — fill in {@code portfolio.alert.mail.*} (Gmail:
     * host {@code smtp.gmail.com}, port {@code 587}, STARTTLS, username + app password) and set
     * {@code enabled=true} to turn on watchlist emails.
     */
    @Bean
    public AlertNotifier alertNotifier(
            @Value("${portfolio.alert.mail.enabled:false}") boolean enabled,
            @Value("${portfolio.alert.mail.host:smtp.gmail.com}") String host,
            @Value("${portfolio.alert.mail.port:587}") int port,
            @Value("${portfolio.alert.mail.starttls:true}") boolean startTls,
            @Value("${portfolio.alert.mail.username:}") String username,
            @Value("${portfolio.alert.mail.password:}") String password,
            @Value("${portfolio.alert.mail.from:}") String from,
            @Value("${portfolio.alert.mail.to:}") String to) {
        return new SmtpAlertNotifier(new SmtpAlertNotifier.Settings(
                enabled, host, port, startTls, username, password, from, to));
    }

    @Bean
    public WatchlistAlertJob watchlistAlertJob(WatchlistService watchlistService,
                                               AlertNotifier alertNotifier,
                                               KeyValueStore keyValueStore) {
        return new WatchlistAlertJob(watchlistService, alertNotifier, keyValueStore);
    }

    // ---- Swing-strategy validation statistics -------------------------------

    @Bean
    public EarningsDateRepository earningsDateRepository(JdbcConnectionFactory connections) {
        return new EarningsDateRepository(connections);
    }

    @Bean
    public EarningsCalendarProvider earningsCalendarProvider(JdbcConnectionFactory jdbcConnectionFactory) {
        return new EdgarEarningsFetcher(new CikRegistry(jdbcConnectionFactory.dbDir()));
    }

    @Bean
    public EarningsService earningsService(EarningsCalendarProvider earningsCalendarProvider,
                                           EarningsDateRepository earningsDateRepository,
                                           KeyValueStore keyValueStore) {
        return new EarningsService(earningsCalendarProvider, earningsDateRepository, keyValueStore);
    }

    // ---- Earnings calendar (Nasdaq feed) ------------------------------------

    @Bean
    public EarningsCalendarFeed earningsCalendarFeed() {
        return new NasdaqEarningsFetcher();
    }

    @Bean
    public EarningsCalendarRepository earningsCalendarRepository(JdbcConnectionFactory connections) {
        return new EarningsCalendarRepository(connections);
    }

    @Bean
    public EarningsCalendarService earningsCalendarService(EarningsCalendarFeed earningsCalendarFeed,
                                                           EarningsCalendarRepository earningsCalendarRepository,
                                                           KeyValueStore keyValueStore,
                                                           AllocationService allocationService,
                                                           FundamentalsRepository fundamentalsRepository,
                                                           YahooTickerMap yahooTickerMap) {
        return new EarningsCalendarService(earningsCalendarFeed, earningsCalendarRepository,
                keyValueStore, allocationService, fundamentalsRepository, yahooTickerMap);
    }

    @Bean
    public SwingAnalysisService swingAnalysisService(PriceHistoryRepository priceHistoryRepository,
                                                     YahooTickerMap yahooTickerMap,
                                                     KeyValueStore keyValueStore,
                                                     PriceFetchJob priceFetchJob,
                                                     EarningsService earningsService) {
        return new SwingAnalysisService(priceHistoryRepository, yahooTickerMap,
                keyValueStore, priceFetchJob, earningsService);
    }

    // ---- Trend-filter backtest ----------------------------------------------

    @Bean
    public TrendAnalysisService trendAnalysisService(PriceHistoryRepository priceHistoryRepository,
                                                     YahooTickerMap yahooTickerMap,
                                                     KeyValueStore keyValueStore,
                                                     PriceFetchJob priceFetchJob) {
        return new TrendAnalysisService(priceHistoryRepository, yahooTickerMap,
                keyValueStore, priceFetchJob);
    }

    // ---- Momentum rotation backtest -----------------------------------------

    @Bean
    public MomentumAnalysisService momentumAnalysisService(PriceHistoryRepository priceHistoryRepository,
                                                           YahooTickerMap yahooTickerMap,
                                                           KeyValueStore keyValueStore,
                                                           PriceFetchJob priceFetchJob) {
        return new MomentumAnalysisService(priceHistoryRepository, yahooTickerMap,
                keyValueStore, priceFetchJob);
    }

    @Bean
    public MomentumSignalsService momentumSignalsService(PriceHistoryRepository priceHistoryRepository,
                                                         YahooTickerMap yahooTickerMap,
                                                         KeyValueStore keyValueStore,
                                                         PriceFetchJob priceFetchJob) {
        return new MomentumSignalsService(priceHistoryRepository, yahooTickerMap,
                keyValueStore, priceFetchJob);
    }

    @Bean
    public RangeAnalysisService rangeAnalysisService(PriceHistoryRepository priceHistoryRepository,
                                                     YahooTickerMap yahooTickerMap,
                                                     HistoricalFxRateProvider historicalFxRateProvider,
                                                     KeyValueStore keyValueStore,
                                                     PriceFetchJob priceFetchJob) {
        return new RangeAnalysisService(priceHistoryRepository, yahooTickerMap,
                historicalFxRateProvider, keyValueStore, priceFetchJob);
    }
}
