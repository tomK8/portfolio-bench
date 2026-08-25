package com.portfolio.application;

import com.portfolio.adapter.YahooPriceFetcher;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.DividendYield;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.DividendHistoryRepository;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Maintains the local {@code price_history} table from Yahoo Finance. Driven by every symbol
 * ever traded (not just current holdings); gilts are skipped (broker price stays source of truth).
 * Idempotent: the first run backfills ~30 years per ticker, later runs re-fetch from the latest
 * stored day through today and upsert. Re-fetching the latest day matters because Yahoo's daily
 * bar for an in-progress session reports the current trading price as {@code close} — if the job
 * ran mid-session (e.g. a startup tick), the day's stored close is wrong until it's overwritten.
 * The 22:00 London cron run completes after US close, so today's close ends up correct by EoD.
 */
public class PriceFetchJob {

    private static final Logger log = LoggerFactory.getLogger(PriceFetchJob.class);

    private static final int LOOKBACK_YEARS = 30;
    private static final long THROTTLE_MS = 500;   // be polite to Yahoo: ≤1 request / 500ms
    private static final int FRESH_LOOKBACK_DAYS = 4;   // history newer than this skips a re-add backfill

    private final CashTransactionRepository cashRepo;
    private final PriceHistoryRepository priceRepo;
    private final YahooPriceFetcher fetcher;
    private final YahooTickerMap tickers;
    private final KeyValueStore kv;
    /** Null in tests that only exercise price writes; every dividend write is guarded. */
    private final DividendHistoryRepository divRepo;

    public PriceFetchJob(CashTransactionRepository cashRepo, PriceHistoryRepository priceRepo,
                         YahooPriceFetcher fetcher, YahooTickerMap tickers, KeyValueStore kv) {
        this(cashRepo, priceRepo, fetcher, tickers, kv, null);
    }

    public PriceFetchJob(CashTransactionRepository cashRepo, PriceHistoryRepository priceRepo,
                         YahooPriceFetcher fetcher, YahooTickerMap tickers, KeyValueStore kv,
                         DividendHistoryRepository divRepo) {
        this.cashRepo = cashRepo;
        this.priceRepo = priceRepo;
        this.fetcher = fetcher;
        this.tickers = tickers;
        this.kv = kv;
        this.divRepo = divRepo;
    }

    /**
     * Persists the dividend events that rode along with a price fetch.
     *
     * <p>{@code fullWindow} is the whole point: only a fetch that asked for a symbol's entire
     * history may record a scan marker, because only then does "no dividends" mean the instrument
     * pays none. The nightly incremental run covers the gap since the last stored bar and sees no
     * dividends on almost every night, so a marker from there would libel every payer as an
     * accumulator. It still writes what it finds, so a newly-declared dividend lands the same night.
     */
    private void captureDividends(String ticker, YahooPriceFetcher.DailySeries series,
                                  LocalDate windowStart, boolean fullWindow) {
        if (divRepo == null || series == null) return;
        List<DividendYield.Payment> payments = new ArrayList<>();
        String currency = null;
        for (YahooPriceFetcher.DividendEvent e : series.dividends()) {
            payments.add(new DividendYield.Payment(e.exDate(), e.amount()));
            if (currency == null) currency = e.currency();
        }
        if (!payments.isEmpty()) divRepo.saveDividends(ticker, payments, currency);
        if (fullWindow && !series.bars().isEmpty()) {
            divRepo.markFullScan(ticker, windowStart, payments.size());
        }
    }

    public void run() {
        LocalDate today = LocalDate.now();
        Set<String> tickerSet = PriceFetchSupport.tickersToFetch(cashRepo, tickers, kv);

        for (String ticker : tickerSet) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("Daily price fetch interrupted — stopping early");
                return;
            }
            LocalDate latest = priceRepo.getLatestPriceDate(ticker);
            // First run backfills; subsequent runs include the latest stored day so its close
            // gets refreshed if it was captured mid-session.
            LocalDate from = (latest == null) ? today.minusYears(LOOKBACK_YEARS) : latest;
            if (from.isAfter(today)) {
                log.info("Skipped {} — already up to date", ticker);
                continue;
            }
            YahooPriceFetcher.DailySeries series = fetcher.fetchSeries(ticker, from, today);
            List<PriceBar> bars = series.bars();
            int touched = priceRepo.upsertPriceBars(bars);
            // A first-ever fetch spans the whole lookback, so its empty dividend list is evidence;
            // an incremental one covers days, so it may only add to what is already stored.
            captureDividends(ticker, series, from, latest == null);
            log.info("Fetched {} rows for {} ({} written)", bars.size(), ticker, touched);
            // Only on the first-ever fetch for a ticker: an empty response means the ticker is
            // wrong, and a populated one still deserves a note if we reached it by identity.
            if (latest == null) {
                // Only an identity-resolved ticker is also the internal symbol; for a mapped
                // one the job never sees the left-hand side, so it reports the ticker alone.
                if (bars.isEmpty()) PriceFetchSupport.warnNoPriceData(log,
                        tickers.resolvedByIdentity(ticker) ? ticker : null, ticker);
                else if (tickers.resolvedByIdentity(ticker)) PriceFetchSupport.noteIdentityTicker(log, ticker);
            }
            PriceFetchSupport.sleep(THROTTLE_MS);
        }
    }

    /**
     * Re-fetches the full {@value #LOOKBACK_YEARS}-year window for every ticker in the universe
     * and upserts. Each Yahoo response is reparsed end-to-end, which is the only way to refresh
     * historical {@code adj_close} after a new dividend or split — Yahoo's bundled adjclose
     * field is unreliable for UK listings, so the fetcher derives total-return adj_close from the
     * event stream on every call. Triggered manually via the dashboard; not on a cron schedule.
     *
     * @return number of tickers actually refreshed
     */
    public int runFullRebuild() {
        LocalDate today = LocalDate.now();
        LocalDate defaultFrom = today.minusYears(LOOKBACK_YEARS);
        Set<String> tickerSet = PriceFetchSupport.tickersToFetch(cashRepo, tickers, kv);
        int refreshed = 0;
        for (String ticker : tickerSet) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("Price rebuild interrupted — stopping early");
                return refreshed;
            }
            // Cover anything already stored. If we have rows older than the lookback cutoff
            // (early portfolio history that the first backfill caught), we must refetch them
            // too — otherwise their stale adj_close survives the rebuild and pollutes
            // total-return math at the start of the timeline.
            LocalDate earliest = priceRepo.getEarliestPriceDate(ticker);
            LocalDate from = (earliest != null && earliest.isBefore(defaultFrom)) ? earliest : defaultFrom;
            YahooPriceFetcher.DailySeries series = fetcher.fetchSeries(ticker, from, today);
            List<PriceBar> bars = series.bars();
            int touched = priceRepo.upsertPriceBars(bars);
            captureDividends(ticker, series, from, true);
            log.info("Rebuild: {} from {} → {} rows ({} written)", ticker, from, bars.size(), touched);
            if (!bars.isEmpty()) refreshed++;
            PriceFetchSupport.sleep(THROTTLE_MS);
        }
        return refreshed;
    }

    /**
     * Backfills the full {@value #LOOKBACK_YEARS}-year window for one specific symbol — used by
     * the what-if simulator when a basket symbol has no rows in {@code price_history} yet, so the
     * simulation can value it instead of leaving the allocation as GBP cash. {@code symbol} is
     * the internal symbol (not the Yahoo ticker) — gilts are no-ops here since they aren't on
     * Yahoo. Returns the row count written; 0 means Yahoo had nothing or rejected the ticker.
     */
    public int fetchSingle(String symbol) {
        return fetchSingle(symbol, LOOKBACK_YEARS);
    }

    /**
     * Watchlist-add helper: only backfill when the symbol's daily history is missing or stale.
     * A name that's already covered — held, or removed-then-re-added the same day — skips the
     * ~{@value #LOOKBACK_YEARS}-year re-fetch entirely, so the add's single small SQLite write
     * doesn't contend with a redundant multi-thousand-row upsert on the one writer (the source of
     * the "add once, then everything blocks until refresh" contention). "Fresh" = a stored close
     * within {@value #FRESH_LOOKBACK_DAYS} days (spans a Fri→Mon weekend plus a holiday). Bonds are
     * no-ops (not on Yahoo). Returns the row count written; 0 means skipped or nothing to fetch.
     */
    public int fetchSingleIfStale(String symbol) {
        if (com.portfolio.domain.Instruments.isBond(symbol)) return 0;
        String ticker = tickers.tickerFor(symbol);
        LocalDate latest = priceRepo.getLatestPriceDate(ticker);
        if (latest != null && !latest.isBefore(LocalDate.now().minusDays(FRESH_LOOKBACK_DAYS))) {
            log.info("fetchSingle skipped {} — history already fresh (latest {})", symbol, latest);
            return 0;
        }
        return fetchSingle(symbol);
    }

    /**
     * Same as {@link #fetchSingle(String)} but with an explicit lookback depth — the trend-filter
     * backfill needs ~45 years for {@code ^NDX}/{@code ^IRX} (Yahoo serves the Nasdaq-100 index
     * from 1985 and the 13-week T-bill yield from the 1960s), deeper than the standard window.
     * Once the rows exist, the nightly incremental run maintains them from the latest stored day,
     * and {@code runFullRebuild} already refetches from the earliest stored row when it predates
     * the default window.
     */
    public int fetchSingle(String symbol, int lookbackYears) {
        if (com.portfolio.domain.Instruments.isBond(symbol)) {
            log.info("fetchSingle skipped {} — bonds are not on Yahoo", symbol);
            return 0;
        }
        String ticker = tickers.tickerFor(symbol);
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusYears(lookbackYears);
        YahooPriceFetcher.DailySeries series = fetcher.fetchSeries(ticker, from, today);
        List<PriceBar> bars = series.bars();
        int touched = priceRepo.upsertPriceBars(bars);
        captureDividends(ticker, series, from, true);
        log.info("fetchSingle {} → {}: {} rows ({} written)", symbol, ticker, bars.size(), touched);
        if (bars.isEmpty() && priceRepo.getLatestPriceDate(ticker) == null) {
            PriceFetchSupport.warnNoPriceData(log, symbol, ticker);
        }
        return touched;
    }
}
