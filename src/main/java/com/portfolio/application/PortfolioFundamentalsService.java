package com.portfolio.application;

import com.portfolio.adapter.YahooQuoteSummaryFetcher;
import com.portfolio.adapter.YahooQuoteSummaryFetcher.QuoteSummary;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.Instruments;
import com.portfolio.persistence.FundamentalsRepository;
import com.portfolio.persistence.FundamentalsRepository.Cached;
import com.portfolio.persistence.KeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Snapshot of current-state fundamentals for every currently-held symbol — what powers
 * the per-holding Snapshot tab.
 *
 * <p>Reads are now DB-backed: {@link #snapshot()} returns whatever {@link FundamentalsRepository}
 * has cached and never blocks on Yahoo. The {@link FundamentalsFetchJob} runs on startup and
 * a 6-hour cron to refresh the cache in the background, so the tab is instant after the
 * first run regardless of server restarts.
 *
 * <p>"Currently held" comes from {@link PriceFetchSupport#HELD_SYMBOLS_KEY}, written by
 * the most recent {@code /sync}. Bonds are skipped (Yahoo doesn't cover gilts). Each
 * symbol is resolved to its Yahoo ticker via {@link YahooTickerMap} so {@code EQQQ} becomes
 * {@code EQQQ.L} before the network call, then re-stamped to {@code EQQQ} for display.
 *
 * <p>Throttle is the same 500ms used by the price-fetch jobs; a full ~50-ticker refresh is
 * ~30s end-to-end, which is fine in the background.
 */
public class PortfolioFundamentalsService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioFundamentalsService.class);

    private static final long THROTTLE_MS = 500;

    private final YahooQuoteSummaryFetcher fetcher;
    private final YahooTickerMap tickerMap;
    private final KeyValueStore keyValueStore;
    private final FundamentalsRepository repo;

    public PortfolioFundamentalsService(YahooQuoteSummaryFetcher fetcher,
                                        YahooTickerMap tickerMap,
                                        KeyValueStore keyValueStore,
                                        FundamentalsRepository repo) {
        this.fetcher = fetcher;
        this.tickerMap = tickerMap;
        this.keyValueStore = keyValueStore;
        this.repo = repo;
    }

    /**
     * Build the response from the DB cache. Held symbols without a cached row appear as
     * placeholder {@code missing=true} entries so the table can still list them while the
     * background job catches up.
     */
    public Snapshot snapshot() {
        List<String> heldSorted = heldInternalSymbols();
        Map<String, Cached> cached = repo.loadAll();

        List<QuoteSummary> rows = new ArrayList<>(heldSorted.size());
        Instant latest = null;
        for (String internal : heldSorted) {
            Cached c = cached.get(internal);
            if (c == null) {
                rows.add(QuoteSummary.empty(internal));
            } else {
                rows.add(withSymbol(c.quote(), internal));
                if (latest == null || c.fetchedAt().isAfter(latest)) latest = c.fetchedAt();
            }
        }
        return new Snapshot(rows, latest);
    }

    /**
     * Refresh every held symbol's row from Yahoo, upserting to the DB as each fetch
     * completes. Throttled to stay polite. Safe to invoke from a background thread —
     * callers are the {@link FundamentalsFetchJob} startup hook, the cron, and the
     * manual {@code POST /portfolio-fundamentals/refresh} button.
     */
    public RefreshResult refresh() {
        List<String> heldSorted = heldInternalSymbols();
        int fetched = 0;
        long start = System.currentTimeMillis();
        for (String internal : heldSorted) {
            if (fetched > 0) PriceFetchSupport.sleep(THROTTLE_MS);
            String yahooTicker = tickerMap.tickerFor(internal);
            QuoteSummary q = fetcher.fetch(yahooTicker);
            repo.save(internal, q, Instant.now());
            fetched++;
        }
        long elapsedMs = System.currentTimeMillis() - start;
        log.info("Fundamentals refresh: {} ticker(s) in {} ms", fetched, elapsedMs);
        return new RefreshResult(fetched, elapsedMs);
    }

    private List<String> heldInternalSymbols() {
        Set<String> held = keyValueStore.getStringSet(PriceFetchSupport.HELD_SYMBOLS_KEY);
        Set<String> filtered = new TreeSet<>(Comparator.naturalOrder());
        for (String s : held) {
            if (!Instruments.isBond(s) && !"CASH".equals(s)) filtered.add(s);
        }
        return new ArrayList<>(filtered);
    }

    private static QuoteSummary withSymbol(QuoteSummary q, String displaySymbol) {
        return new QuoteSummary(displaySymbol, q.currency(), q.price(), q.marketCap(),
                q.trailingPe(), q.forwardPe(), q.pegRatio(), q.beta(),
                q.week52High(), q.week52Low(), q.targetMeanPrice(),
                q.extra(), q.labels(), q.missing());
    }

    public record Snapshot(List<QuoteSummary> rows, Instant lastUpdatedAt) {}

    public record RefreshResult(int fetched, long elapsedMs) {}
}
