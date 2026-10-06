package com.portfolio.application;

import com.portfolio.adapter.YahooQuoteSummaryFetcher;
import com.portfolio.adapter.YahooQuoteSummaryFetcher.QuoteSummary;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.model.OperatingLine;
import com.portfolio.persistence.FundamentalsRepository;
import com.portfolio.persistence.FundamentalsRepository.Cached;
import com.portfolio.persistence.KeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
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

    /**
     * User-added symbols to show fundamentals for even though they aren't held (or watched) —
     * a plain newline set in the KV store, managed from the snapshot tab. Independent of the
     * volatility watchlist so you can inspect a name's fundamentals without committing it to
     * alerting.
     */
    public static final String EXTRA_SYMBOLS_KEY = "fundamentals_extra_symbols";

    private final YahooQuoteSummaryFetcher fetcher;
    private final YahooTickerMap tickerMap;
    private final KeyValueStore keyValueStore;
    private final FundamentalsRepository repo;
    private final OperatingLineService operatingLines;

    public PortfolioFundamentalsService(YahooQuoteSummaryFetcher fetcher,
                                        YahooTickerMap tickerMap,
                                        KeyValueStore keyValueStore,
                                        FundamentalsRepository repo,
                                        OperatingLineService operatingLines) {
        this.fetcher = fetcher;
        this.tickerMap = tickerMap;
        this.keyValueStore = keyValueStore;
        this.repo = repo;
        this.operatingLines = operatingLines;
    }

    /**
     * Build the response from the DB cache. Held symbols without a cached row appear as
     * placeholder {@code missing=true} entries so the table can still list them while the
     * background job catches up.
     */
    public Snapshot snapshot() {
        List<String> heldSorted = heldInternalSymbols();
        Map<String, Cached> cached = repo.loadAll();
        Map<String, OperatingLine> operating = operatingLines.all();

        List<QuoteSummary> rows = new ArrayList<>(heldSorted.size());
        // Only the lines belonging to rows this response actually carries. The refresh covers
        // every cached symbol, which can include one that has since left the universe (the
        // snapshot table outlives it by design — see docs/fundamentals.md), and a payload that
        // described a symbol absent from the table would be describing nothing.
        Map<String, OperatingLine> shown = new LinkedHashMap<>();
        Instant latest = null;
        for (String internal : heldSorted) {
            Cached c = cached.get(internal);
            if (c == null) {
                rows.add(QuoteSummary.empty(internal));
                continue;
            }
            OperatingLine line = operating.get(internal);
            if (line != null) shown.put(internal, line);
            rows.add(withOperatingLine(withSymbol(c.quote(), internal), line));
            if (latest == null || c.fetchedAt().isAfter(latest)) latest = c.fetchedAt();
        }
        return new Snapshot(rows, latest, filteredExtraSymbols(), shown);
    }

    /**
     * Fold the filings-sourced operating line into a Yahoo row, at <b>read</b> time.
     *
     * <p>It deliberately does not go into the stored blob. {@code extra_json} is rewritten whole
     * every four hours, which is what lets a removed Yahoo field migrate itself; filings move
     * quarterly, so merging here keeps each source on its own cadence and leaves that property
     * intact. See {@link com.portfolio.persistence.OperatingLineRepository}.
     *
     * <p>A withheld line contributes the {@code excluded:} labels and no figures, so the panel
     * renders the same muted "withheld" with its reason that a currency-gated ratio gets — a
     * bare dash could not distinguish a filer that reports no operating income from one whose
     * figures we declined to divide.
     */
    private static QuoteSummary withOperatingLine(QuoteSummary q, OperatingLine line) {
        if (line == null) return q;
        Map<String, BigDecimal> extra = new LinkedHashMap<>(
                q.extra() == null ? Map.of() : q.extra());
        Map<String, String> labels = new LinkedHashMap<>(
                q.labels() == null ? Map.of() : q.labels());

        if (line.withheld()) {
            labels.put("excluded:operatingIncomeTtm", line.withheldReason());
            labels.put("excluded:operatingMarginTtm", line.withheldReason());
        } else {
            extra.put("operatingIncomeTtm", line.operatingIncomeTtm());
            extra.put("operatingMarginTtm", line.operatingMarginTtm());
            labels.put("operatingPeriodEnd", line.periodEnd().toString());
            labels.put("operatingUnit", line.unit() == null ? "" : line.unit());
            labels.put("operatingRevenueConcept",
                    line.revenueConcept() == null ? "" : line.revenueConcept());
            if (line.derivedQuarterEnd() != null) {
                labels.put("operatingDerivedQuarterEnd", line.derivedQuarterEnd().toString());
            }
        }
        return new QuoteSummary(q.symbol(), q.currency(), q.price(), q.marketCap(),
                q.trailingPe(), q.forwardPe(), q.pegRatio(), q.beta(),
                q.week52High(), q.week52Low(), q.targetMeanPrice(),
                extra, labels, q.sectorWeights(), q.topHoldings(), q.missing());
    }

    /**
     * Add a symbol to show fundamentals for even if not held. Fetches its quoteSummary
     * synchronously so the row is populated on the next {@link #snapshot()} read; throws on a
     * blank or bond/CASH symbol. Idempotent — re-adding just refreshes the cached row.
     */
    public void addExtra(String symbolRaw) {
        String sym = symbolRaw == null ? "" : symbolRaw.trim().toUpperCase();
        if (sym.isEmpty()) throw new IllegalArgumentException("Symbol is required.");
        if (Instruments.isBond(sym) || "CASH".equals(sym)) {
            throw new IllegalArgumentException(sym + " has no Yahoo fundamentals coverage.");
        }
        Set<String> extra = new java.util.LinkedHashSet<>(keyValueStore.getStringSet(EXTRA_SYMBOLS_KEY));
        extra.add(sym);
        keyValueStore.putStringSet(EXTRA_SYMBOLS_KEY, extra);
        refreshSymbol(sym);
    }

    public void removeExtra(String symbolRaw) {
        String sym = symbolRaw == null ? "" : symbolRaw.trim().toUpperCase();
        Set<String> extra = new java.util.LinkedHashSet<>(keyValueStore.getStringSet(EXTRA_SYMBOLS_KEY));
        if (extra.remove(sym)) keyValueStore.putStringSet(EXTRA_SYMBOLS_KEY, extra);
    }

    /** Fetch and cache one symbol's fundamentals now (resolving to its Yahoo ticker). */
    public void refreshSymbol(String internal) {
        String yahooTicker = tickerMap.tickerFor(internal);
        QuoteSummary q = fetcher.fetch(yahooTicker);
        repo.save(internal, q, Instant.now());
    }

    /**
     * Fetch and cache several symbols now, throttled like {@link #refresh()}. This is the
     * off-cycle path {@link FundamentalsWarmer} drives on behalf of the sector and style blocks,
     * so it must not abandon the rest of the batch when one name fails — a symbol Yahoo cannot
     * answer for still gets its {@code missing=true} row, which is what stops the panel asking
     * again on the next paint.
     *
     * @return how many symbols were fetched
     */
    public int refreshSymbols(Collection<String> internals) {
        if (internals == null || internals.isEmpty()) return 0;
        int fetched = 0;
        for (String internal : internals) {
            if (internal == null || internal.isBlank()) continue;
            if (Thread.currentThread().isInterrupted()) {
                log.info("Fundamentals warm interrupted — stopping early");
                break;
            }
            if (fetched > 0) PriceFetchSupport.sleep(THROTTLE_MS);
            try {
                refreshSymbol(internal);
                fetched++;
            } catch (RuntimeException e) {
                log.warn("Fundamentals warm failed for {}", internal, e);
            }
        }
        return fetched;
    }

    /** The extra set minus anything that's also held — i.e. the rows the UI may offer to remove. */
    private List<String> filteredExtraSymbols() {
        List<String> out = new ArrayList<>();
        for (String s : keyValueStore.getStringSet(EXTRA_SYMBOLS_KEY)) {
            if (!Instruments.isBond(s) && !"CASH".equals(s)) out.add(s);
        }
        return out;
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
            if (Thread.currentThread().isInterrupted()) {
                log.info("Fundamentals refresh interrupted — stopping early");
                break;
            }
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
        Set<String> universe = new TreeSet<>(Comparator.naturalOrder());
        universe.addAll(keyValueStore.getStringSet(PriceFetchSupport.HELD_SYMBOLS_KEY));
        // Watchlist names too, so their P/E, beta and 52-week high/low populate the fundamentals
        // cache the watchlist screen reads from.
        universe.addAll(keyValueStore.getStringSet(
                com.portfolio.persistence.WatchlistRepository.WATCHLIST_SYMBOLS_KEY));
        // Symbols the user added purely to inspect fundamentals (not held, not watched).
        universe.addAll(keyValueStore.getStringSet(EXTRA_SYMBOLS_KEY));
        Set<String> filtered = new TreeSet<>(Comparator.naturalOrder());
        for (String s : universe) {
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

    /**
     * {@code extraSymbols} are the user-added (non-held) rows the UI can offer a remove control
     * for. {@code operatingLines} carries the filings-sourced quarterly series keyed by symbol —
     * the grid reads the two TTM figures merged into each row, the detail view reads the series
     * from here, and a symbol with no SEC filing to read is simply absent.
     */
    public record Snapshot(List<QuoteSummary> rows, Instant lastUpdatedAt, List<String> extraSymbols,
                           Map<String, OperatingLine> operatingLines) {}

    public record RefreshResult(int fetched, long elapsedMs) {}
}
