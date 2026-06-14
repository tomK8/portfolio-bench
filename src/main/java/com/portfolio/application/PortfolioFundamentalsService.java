package com.portfolio.application;

import com.portfolio.adapter.YahooQuoteSummaryFetcher;
import com.portfolio.adapter.YahooQuoteSummaryFetcher.QuoteSummary;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.Instruments;
import com.portfolio.persistence.KeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Snapshot of current-state fundamentals for every currently-held symbol — what powers
 * the Snapshot tab.
 *
 * <p>"Currently held" comes from {@link PriceFetchSupport#HELD_SYMBOLS_KEY}, written by
 * the most recent {@code /sync}. Bonds are skipped (Yahoo doesn't cover gilts at all).
 * Each symbol is resolved to its Yahoo ticker via {@link YahooTickerMap} so {@code EQQQ}
 * becomes {@code EQQQ.L} before the network call.
 *
 * <p>One HTTP round-trip per ticker is unavoidable — Yahoo doesn't accept comma-separated
 * tickers on this endpoint — but a 6-hour in-memory cache keeps repeat tab visits free.
 * Throttle between requests is the same 500ms used by the price-fetch jobs to stay polite.
 * A full ~50-ticker refresh is therefore ~30s end-to-end; the cache pays back from the
 * second visit onward.
 */
public class PortfolioFundamentalsService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioFundamentalsService.class);

    private static final java.time.Duration CACHE_TTL = java.time.Duration.ofHours(6);
    private static final long THROTTLE_MS = 500;

    private final YahooQuoteSummaryFetcher fetcher;
    private final YahooTickerMap tickerMap;
    private final KeyValueStore keyValueStore;

    private final java.util.Map<String, CachedQuote> cache = new ConcurrentHashMap<>();

    public PortfolioFundamentalsService(YahooQuoteSummaryFetcher fetcher,
                                        YahooTickerMap tickerMap,
                                        KeyValueStore keyValueStore) {
        this.fetcher = fetcher;
        this.tickerMap = tickerMap;
        this.keyValueStore = keyValueStore;
    }

    public Snapshot snapshot() {
        Set<String> held = keyValueStore.getStringSet(PriceFetchSupport.HELD_SYMBOLS_KEY);
        List<String> internalSymbols = new ArrayList<>();
        for (String s : held) {
            if (!Instruments.isBond(s) && !"CASH".equals(s)) internalSymbols.add(s);
        }
        internalSymbols.sort(Comparator.naturalOrder());

        List<QuoteSummary> rows = new ArrayList<>(internalSymbols.size());
        int fetched = 0;
        for (String internal : internalSymbols) {
            String yahooTicker = tickerMap.tickerFor(internal);
            QuoteSummary cached = fromCache(yahooTicker);
            QuoteSummary q;
            if (cached != null) {
                q = cached;
            } else {
                // Throttle goes *before* each fetch except the first, so calls are spaced
                // by THROTTLE_MS ms and the final iteration doesn't sleep needlessly.
                if (fetched > 0) PriceFetchSupport.sleep(THROTTLE_MS);
                q = fetcher.fetch(yahooTicker);
                cache.put(yahooTicker, new CachedQuote(q, Instant.now().plus(CACHE_TTL)));
                fetched++;
            }
            // Re-stamp with the user-facing internal symbol so the table doesn't suddenly
            // show "EQQQ.L" where the rest of the app shows "EQQQ".
            rows.add(withSymbol(q, internal));
        }
        if (fetched > 0) {
            log.info("Snapshot: fetched {} ticker(s), reused {} from cache",
                    fetched, internalSymbols.size() - fetched);
        }
        return new Snapshot(rows);
    }

    private QuoteSummary fromCache(String yahooTicker) {
        CachedQuote c = cache.get(yahooTicker);
        if (c == null) return null;
        if (Instant.now().isAfter(c.expiresAt)) return null;
        return c.quote;
    }

    private static QuoteSummary withSymbol(QuoteSummary q, String displaySymbol) {
        return new QuoteSummary(displaySymbol, q.currency(), q.price(), q.marketCap(),
                q.trailingPe(), q.forwardPe(), q.pegRatio(), q.beta(),
                q.week52High(), q.week52Low(), q.targetMeanPrice(),
                q.extra(), q.labels(), q.missing());
    }

    private record CachedQuote(QuoteSummary quote, Instant expiresAt) {}

    public record Snapshot(List<QuoteSummary> rows) {}
}
