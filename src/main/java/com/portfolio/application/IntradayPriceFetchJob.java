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
import java.time.LocalDate;
import java.util.Collection;
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

    /** Day the retention prune last ran — rows only age out daily, so scanning every tick is waste. */
    private LocalDate lastPruneDate;

    public void run() {
        Set<String> tickerSet = PriceFetchSupport.tickersToFetch(cashRepo, tickers, kv);

        Instant now = Instant.now();
        Instant earliest = now.minus(LOOKBACK);

        for (String ticker : tickerSet) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("Intraday fetch interrupted — stopping early");
                return;
            }
            if (fetchOne(ticker, now, earliest) < 0) continue;
            PriceFetchSupport.sleep(THROTTLE_MS);
        }

        LocalDate today = LocalDate.now();
        if (!today.equals(lastPruneDate)) {
            int pruned = intradayRepo.pruneIntradayBefore(now.minus(Duration.ofDays(RETENTION_DAYS)));
            lastPruneDate = today;
            if (pruned > 0) log.info("Intraday prune — removed {} rows older than {} days", pruned, RETENTION_DAYS);
        }
    }

    /**
     * Fetch the gap for a handful of tickers now, off the polling cycle. The scheduled pass walks
     * the whole universe at a 500ms throttle (~70s for ~106 tickers) and then waits 60s, so any one
     * symbol refreshes about every two minutes — fine for a background feed, too slow for a tab you
     * just opened. The Open orders panel calls this for the symbols whose quotes are visibly behind
     * their peers, so a reload a few seconds later shows a live price instead of the last close.
     *
     * <p>Runs on the caller's thread (the callers hand it to a background executor) and never
     * throws: a warm that fails leaves the scheduled pass to catch up as before.
     */
    public void fetchNow(Collection<String> tickerSet) {
        Instant now = Instant.now();
        Instant earliest = now.minus(LOOKBACK);
        for (String ticker : tickerSet) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                if (fetchOne(ticker, now, earliest) < 0) continue;
            } catch (Exception e) {
                log.warn("Intraday warm failed for {}", ticker, e);
                continue;
            }
            PriceFetchSupport.sleep(THROTTLE_MS);
        }
    }

    /**
     * One ticker's gap fetch. Returns the rows written, or −1 when the ticker was already up to
     * date (nothing fetched, so the caller can skip the politeness throttle).
     */
    private int fetchOne(String ticker, Instant now, Instant earliest) {
        Instant latest = intradayRepo.getLatestIntradayTs(ticker);
        Instant from = (latest == null) ? earliest : latest.plusSeconds(60);
        if (from.isBefore(earliest)) from = earliest;   // catch-up after a long gap → clamp to window
        if (!from.isBefore(now)) {
            log.info("Intraday {} — up to date", ticker);
            return -1;
        }
        List<IntradayBar> bars = fetcher.fetchIntraday(ticker, from, now);
        int saved = intradayRepo.saveIntradayBars(bars);
        log.info("Intraday {} — {} bars ({} new)", ticker, bars.size(), saved);
        return saved;
    }
}
