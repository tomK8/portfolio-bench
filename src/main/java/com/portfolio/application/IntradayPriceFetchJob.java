package com.portfolio.application;

import com.portfolio.adapter.YahooPriceFetcher;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.model.IntradayBar;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.persistence.KeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
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

    /**
     * How long we keep 1-minute rows. Longer than {@link #FETCH_LOOKBACK_DAYS} on purpose:
     * Yahoo only serves ~7 days of 1-minute history per request, so we can't backfill 15 days
     * in one shot — but by pruning at 15 rather than 7 the window <em>accumulates</em> as the
     * every-tick job keeps appending, growing from 7 to 15 days over the first week and then
     * holding. Feeds the watchlist popup's fine-grained 1D–15D charts.
     */
    static final int RETENTION_DAYS = 15;
    /** Yahoo's hard ceiling for {@code interval=1m}. Fetching further back returns nothing, so
     *  the first-run / gap-fill lookback stops here rather than at the retention horizon. */
    static final int FETCH_LOOKBACK_DAYS = 7;
    /** Start the first-run fetch just inside Yahoo's serve window so the prune that follows is a no-op. */
    private static final Duration LOOKBACK = Duration.ofDays(FETCH_LOOKBACK_DAYS).minusMinutes(5);
    private static final long THROTTLE_MS = 500;   // be polite to Yahoo

    private final CashTransactionRepository cashRepo;
    private final IntradayPriceRepository intradayRepo;
    private final YahooPriceFetcher fetcher;
    private final YahooTickerMap tickers;
    private final KeyValueStore kv;

    public IntradayPriceFetchJob(CashTransactionRepository cashRepo, IntradayPriceRepository intradayRepo,
                                 YahooPriceFetcher fetcher, YahooTickerMap tickers, KeyValueStore kv) {
        this.cashRepo = cashRepo;
        this.intradayRepo = intradayRepo;
        this.fetcher = fetcher;
        this.tickers = tickers;
        this.kv = kv;
    }

    public void run() {
        Set<String> tickerSet = PriceFetchSupport.tickersToFetch(cashRepo, tickers, kv);

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
            PriceFetchSupport.sleep(THROTTLE_MS);
        }

        int pruned = intradayRepo.pruneIntradayBefore(now.minus(Duration.ofDays(RETENTION_DAYS)));
        if (pruned > 0) log.info("Intraday prune — removed {} rows older than {} days", pruned, RETENTION_DAYS);
    }
}
