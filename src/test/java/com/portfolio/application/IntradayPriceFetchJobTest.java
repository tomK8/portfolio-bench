package com.portfolio.application;

import com.portfolio.PortfolioDatabase;
import com.portfolio.adapter.YahooPriceFetcher;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.IntradayBar;
import com.portfolio.domain.model.IntradayPrice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class IntradayPriceFetchJobTest {

    @TempDir
    Path dbDir;

    /** Fake fetcher — records (ticker, from) pairs and returns canned bars. */
    private static class FakeFetcher extends YahooPriceFetcher {
        final Map<String, List<IntradayBar>> canned = new HashMap<>();
        final List<String> requested = new java.util.ArrayList<>();
        final Map<String, Instant> requestedFrom = new HashMap<>();

        @Override
        public List<IntradayBar> fetchIntraday(String ticker, Instant from, Instant to) {
            requested.add(ticker);
            requestedFrom.put(ticker, from);
            return canned.getOrDefault(ticker, List.of());
        }
    }

    private static CashTransaction tx(String date, String type, String symbol) {
        return new CashTransaction(date, "AJBell", type, symbol, 1, -10, "GBP",
                1.0, -10, 100.0, 100.0, date);
    }

    @Test
    void skipsGiltsAndPersistsBarsForEquities() {
        PortfolioDatabase db = new PortfolioDatabase(dbDir);
        db.saveCashTransactions(List.of(
                tx("2024-01-01", "TRANSACTION", "AAPL"),
                tx("2024-01-02", "TRANSACTION", "GILT 0.25% 2026")));

        // Recent timestamps — the prune step removes anything > 7 days old.
        Instant t0 = Instant.now().minus(java.time.Duration.ofMinutes(2));
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.canned.put("AAPL", List.of(
                new IntradayBar("AAPL", t0, 190.10, 1000L, "USD"),
                new IntradayBar("AAPL", t0.plusSeconds(60), 190.25, 900L, "USD")));

        new IntradayPriceFetchJob(db, fetcher, new YahooTickerMap()).run();

        assertEquals(List.of("AAPL"), fetcher.requested, "gilt was filtered out before the fetch loop");
        Map<String, IntradayPrice> latest = db.loadLatestIntradayPrices(Set.of("AAPL"));
        assertEquals(190.25, latest.get("AAPL").close());
    }

    @Test
    void fetchesOnlyTheGapSinceLatestStoredBar() {
        PortfolioDatabase db = new PortfolioDatabase(dbDir);
        db.saveCashTransactions(List.of(tx("2024-01-01", "TRANSACTION", "AAPL")));

        Instant lastSeen = Instant.now().minus(java.time.Duration.ofMinutes(30));
        db.saveIntradayBars(List.of(new IntradayBar("AAPL", lastSeen, 100.0, null, "USD")));

        FakeFetcher fetcher = new FakeFetcher();
        new IntradayPriceFetchJob(db, fetcher, new YahooTickerMap()).run();

        assertEquals(lastSeen.plusSeconds(60), fetcher.requestedFrom.get("AAPL"),
                "incremental fetch starts from the bar immediately after the last stored one");
    }

    @Test
    void firstRunStartsWithinRetentionWindowToAvoidImmediatePrune() {
        PortfolioDatabase db = new PortfolioDatabase(dbDir);
        db.saveCashTransactions(List.of(tx("2024-01-01", "TRANSACTION", "AAPL")));

        FakeFetcher fetcher = new FakeFetcher();
        Instant before = Instant.now();
        new IntradayPriceFetchJob(db, fetcher, new YahooTickerMap()).run();
        Instant after = Instant.now();

        Instant from = fetcher.requestedFrom.get("AAPL");
        assertNotNull(from);
        // Window starts a few minutes inside the 7-day retention horizon so the prune
        // that follows can't immediately drop what we just fetched.
        Instant retentionStart = before.minus(java.time.Duration.ofDays(7));
        assertTrue(from.isAfter(retentionStart),
                "first-run fetch starts after the prune cutoff (was " + from + ")");
        assertTrue(from.isBefore(after.minus(java.time.Duration.ofDays(6))),
                "first-run fetch covers most of the retention window (was " + from + ")");
    }

    @Test
    void prunesRowsOlderThanRetention() {
        PortfolioDatabase db = new PortfolioDatabase(dbDir);
        db.saveCashTransactions(List.of(tx("2024-01-01", "TRANSACTION", "AAPL")));

        Instant stale = Instant.now().minus(java.time.Duration.ofDays(10));
        Instant fresh = Instant.now().minus(java.time.Duration.ofMinutes(5));
        db.saveIntradayBars(List.of(
                new IntradayBar("AAPL", stale, 1.0, null, "USD"),
                new IntradayBar("AAPL", fresh, 2.0, null, "USD")));

        FakeFetcher fetcher = new FakeFetcher();   // no new bars
        new IntradayPriceFetchJob(db, fetcher, new YahooTickerMap()).run();

        Instant remaining = db.getLatestIntradayTs("AAPL");
        assertEquals(fresh, remaining, "fresh row remains");
        // Pruned rows mean the stale one is gone — load-latest still finds the fresh row only.
        assertEquals(2.0, db.loadLatestIntradayPrices(Set.of("AAPL")).get("AAPL").close());
    }
}
