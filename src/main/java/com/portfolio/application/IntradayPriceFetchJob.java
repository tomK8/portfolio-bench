package com.portfolio.application;

import com.portfolio.PortfolioDatabase;
import com.portfolio.adapter.YahooPriceFetcher;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.model.IntradayBar;

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

    static final int RETENTION_DAYS = 7;
    /** Start the first-run fetch just inside the retention window so the prune that follows is a no-op. */
    private static final Duration LOOKBACK = Duration.ofDays(RETENTION_DAYS).minusMinutes(5);
    private static final long THROTTLE_MS = 500;   // be polite to Yahoo

    private final PortfolioDatabase db;
    private final YahooPriceFetcher fetcher;
    private final YahooTickerMap tickers;

    public IntradayPriceFetchJob(PortfolioDatabase db, YahooPriceFetcher fetcher, YahooTickerMap tickers) {
        this.db = db;
        this.fetcher = fetcher;
        this.tickers = tickers;
    }

    public void run() {
        Set<String> tickerSet = new LinkedHashSet<>();
        for (String symbol : db.distinctTradedSymbols()) {
            if (tickers.isGilt(symbol)) continue;
            tickerSet.add(tickers.tickerFor(symbol));
        }

        Instant now = Instant.now();
        Instant earliest = now.minus(LOOKBACK);

        for (String ticker : tickerSet) {
            Instant latest = db.getLatestIntradayTs(ticker);
            Instant from = (latest == null) ? earliest : latest.plusSeconds(60);
            if (from.isBefore(earliest)) from = earliest;   // catch-up after a long gap → clamp to window
            if (!from.isBefore(now)) {
                System.out.println("Intraday " + ticker + " — up to date");
                continue;
            }

            List<IntradayBar> bars = fetcher.fetchIntraday(ticker, from, now);
            int saved = db.saveIntradayBars(bars);
            System.out.printf("Intraday %s — %d bars (%d new)%n", ticker, bars.size(), saved);
            sleep(THROTTLE_MS);
        }

        int pruned = db.pruneIntradayBefore(now.minus(Duration.ofDays(RETENTION_DAYS)));
        if (pruned > 0) System.out.println("Intraday prune — removed " + pruned + " rows older than " +
                RETENTION_DAYS + " days");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
