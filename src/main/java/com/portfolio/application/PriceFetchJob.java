package com.portfolio.application;

import com.portfolio.adapter.YahooPriceFetcher;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Maintains the local {@code price_history} table from Yahoo Finance. Driven by every symbol
 * ever traded (not just current holdings); gilts are skipped (broker price stays source of truth).
 * Idempotent: the first run backfills ~10 years per ticker, later runs re-fetch from the latest
 * stored day through today and upsert. Re-fetching the latest day matters because Yahoo's daily
 * bar for an in-progress session reports the current trading price as {@code close} — if the job
 * ran mid-session (e.g. a startup tick), the day's stored close is wrong until it's overwritten.
 * The 22:00 London cron run completes after US close, so today's close ends up correct by EoD.
 */
public class PriceFetchJob {

    private static final Logger log = LoggerFactory.getLogger(PriceFetchJob.class);

    private static final int LOOKBACK_YEARS = 10;
    private static final long THROTTLE_MS = 500;   // be polite to Yahoo: ≤1 request / 500ms

    private final CashTransactionRepository cashRepo;
    private final PriceHistoryRepository priceRepo;
    private final YahooPriceFetcher fetcher;
    private final YahooTickerMap tickers;
    private final KeyValueStore kv;

    public PriceFetchJob(CashTransactionRepository cashRepo, PriceHistoryRepository priceRepo,
                         YahooPriceFetcher fetcher, YahooTickerMap tickers, KeyValueStore kv) {
        this.cashRepo = cashRepo;
        this.priceRepo = priceRepo;
        this.fetcher = fetcher;
        this.tickers = tickers;
        this.kv = kv;
    }

    public void run() {
        LocalDate today = LocalDate.now();
        Set<String> tickerSet = PriceFetchSupport.tickersToFetch(cashRepo, tickers, kv);

        for (String ticker : tickerSet) {
            LocalDate latest = priceRepo.getLatestPriceDate(ticker);
            // First run backfills; subsequent runs include the latest stored day so its close
            // gets refreshed if it was captured mid-session.
            LocalDate from = (latest == null) ? today.minusYears(LOOKBACK_YEARS) : latest;
            if (from.isAfter(today)) {
                log.info("Skipped {} — already up to date", ticker);
                continue;
            }
            List<PriceBar> bars = fetcher.fetch(ticker, from, today);
            int touched = priceRepo.upsertPriceBars(bars);
            log.info("Fetched {} rows for {} ({} written)", bars.size(), ticker, touched);
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
            // Cover anything already stored. If we have rows older than the 10-year cutoff
            // (early portfolio history that the first backfill caught), we must refetch them
            // too — otherwise their stale adj_close survives the rebuild and pollutes
            // total-return math at the start of the timeline.
            LocalDate earliest = priceRepo.getEarliestPriceDate(ticker);
            LocalDate from = (earliest != null && earliest.isBefore(defaultFrom)) ? earliest : defaultFrom;
            List<PriceBar> bars = fetcher.fetch(ticker, from, today);
            int touched = priceRepo.upsertPriceBars(bars);
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
        if (com.portfolio.domain.Instruments.isBond(symbol)) {
            log.info("fetchSingle skipped {} — bonds are not on Yahoo", symbol);
            return 0;
        }
        String ticker = tickers.tickerFor(symbol);
        LocalDate today = LocalDate.now();
        List<PriceBar> bars = fetcher.fetch(ticker, today.minusYears(LOOKBACK_YEARS), today);
        int touched = priceRepo.upsertPriceBars(bars);
        log.info("fetchSingle {} → {}: {} rows ({} written)", symbol, ticker, bars.size(), touched);
        return touched;
    }
}
