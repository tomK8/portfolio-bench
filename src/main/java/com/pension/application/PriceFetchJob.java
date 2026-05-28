package com.pension.application;

import com.pension.PortfolioDatabase;
import com.pension.adapter.YahooPriceFetcher;
import com.pension.adapter.YahooTickerMap;
import com.pension.domain.model.PriceBar;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Maintains the local {@code price_history} table from Yahoo Finance. Driven by every symbol
 * ever traded (not just current holdings); gilts are skipped (broker price stays source of truth).
 * Idempotent: the first run backfills ~10 years per ticker, later runs fetch only the gap to today.
 */
public class PriceFetchJob {

    private static final int LOOKBACK_YEARS = 10;
    private static final long THROTTLE_MS = 500;   // be polite to Yahoo: ≤1 request / 500ms

    private final PortfolioDatabase db;
    private final YahooPriceFetcher fetcher;
    private final YahooTickerMap tickers;

    public PriceFetchJob(PortfolioDatabase db, YahooPriceFetcher fetcher, YahooTickerMap tickers) {
        this.db = db;
        this.fetcher = fetcher;
        this.tickers = tickers;
    }

    public void run() {
        LocalDate today = LocalDate.now();

        Set<String> tickerSet = new LinkedHashSet<>();   // dedups e.g. GOOG/GOOGL → GOOG
        for (String symbol : db.distinctTradedSymbols()) {
            if (tickers.isGilt(symbol)) {
                System.out.println("Skipping " + symbol + " — gilts not supported");
                continue;
            }
            tickerSet.add(tickers.tickerFor(symbol));
        }

        for (String ticker : tickerSet) {
            LocalDate latest = db.getLatestPriceDate(ticker);
            LocalDate from = (latest == null) ? today.minusYears(LOOKBACK_YEARS) : latest.plusDays(1);
            if (from.isAfter(today)) {
                System.out.println("Skipped " + ticker + " — already up to date");
                continue;
            }
            List<PriceBar> bars = fetcher.fetch(ticker, from, today);
            int saved = db.savePriceBars(bars);
            System.out.printf("Fetched %d rows for %s (%d new)%n", bars.size(), ticker, saved);
            sleep(THROTTLE_MS);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
