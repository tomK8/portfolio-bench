package com.portfolio.application;

import com.portfolio.adapter.YahooPriceFetcher;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.CashTransactionRepository;
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

    public PriceFetchJob(CashTransactionRepository cashRepo, PriceHistoryRepository priceRepo,
                         YahooPriceFetcher fetcher, YahooTickerMap tickers) {
        this.cashRepo = cashRepo;
        this.priceRepo = priceRepo;
        this.fetcher = fetcher;
        this.tickers = tickers;
    }

    public void run() {
        LocalDate today = LocalDate.now();
        Set<String> tickerSet = PriceFetchSupport.tickersToFetch(cashRepo, tickers);

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
}
