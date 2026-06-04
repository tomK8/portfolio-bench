package com.portfolio.application;

import com.portfolio.adapter.YahooPriceFetcher;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.model.IntradayBar;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.IntradayPriceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Sibling to {@link PriceFetchJob} for 1-minute intraday closes. Feeds the dashboard's
 * RT-value columns and (later) intraday charts. Gilts skipped (no Yahoo coverage);
 * rows older than {@link #RETENTION_DAYS} are pruned at the end of each tick.
 *
 * <p>Per-ticker fetch is incremental — starting from the latest stored timestamp (or
 * the retention horizon on first run) so we don't re-pull what we already have and
 * never insert rows the prune would immediately discard.
 */
public class IntradayPriceFetchJob {

    private static final Logger log = LoggerFactory.getLogger(IntradayPriceFetchJob.class);

    static final int RETENTION_DAYS = 7;
    /** Start the first-run fetch just inside the retention window so the prune that follows is a no-op. */
    private static final Duration LOOKBACK = Duration.ofDays(RETENTION_DAYS).minusMinutes(5);
    private static final long THROTTLE_MS = 500;   // be polite to Yahoo

    private final CashTransactionRepository cashRepo;
    private final IntradayPriceRepository intradayRepo;
    private final YahooPriceFetcher fetcher;
    private final YahooTickerMap tickers;

    public IntradayPriceFetchJob(CashTransactionRepository cashRepo, IntradayPriceRepository intradayRepo,
                                 YahooPriceFetcher fetcher, YahooTickerMap tickers) {
        this.cashRepo = cashRepo;
        this.intradayRepo = intradayRepo;
        this.fetcher = fetcher;
        this.tickers = tickers;
    }

    public void run() {
        Set<String> tickerSet = new LinkedHashSet<>();
        for (String symbol : cashRepo.distinctTradedSymbols()) {
            if (tickers.isGilt(symbol)) continue;
            tickerSet.add(tickers.tickerFor(symbol));
        }

        Instant now = Instant.now();
        Instant earliest = now.minus(LOOKBACK);

        for (String ticker : tickerSet) {
            Instant latest = intradayRepo.getLatestIntradayTs(ticker);
            Instant from = (latest == null) ? earliest : latest.plusSeconds(60);
            if (from.isBefore(earliest)) from = earliest;   // catch-up after a long gap → clamp to window
            if (!from.isBefore(now)) {
                log.info("Intraday {} — up to date", ticker);
                continue;
            }

            List<IntradayBar> bars = fetcher.fetchIntraday(ticker, from, now);
            int saved = intradayRepo.saveIntradayBars(bars);
            log.info("Intraday {} — {} bars ({} new)", ticker, bars.size(), saved);
            sleep(THROTTLE_MS);
        }

        int pruned = intradayRepo.pruneIntradayBefore(now.minus(Duration.ofDays(RETENTION_DAYS)));
        if (pruned > 0) log.info("Intraday prune — removed {} rows older than {} days", pruned, RETENTION_DAYS);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
