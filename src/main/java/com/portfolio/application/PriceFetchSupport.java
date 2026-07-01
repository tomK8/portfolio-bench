package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.Instruments;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.WatchlistRepository;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Shared building blocks for the price-fetch jobs. The daily and intraday jobs both fetch
 * the same universe (every traded symbol, minus gilts, mapped to Yahoo tickers); keeping
 * that resolution and the polite-throttle sleep in one place avoids drift between them.
 */
final class PriceFetchSupport {

    /** Key under which {@link com.portfolio.application.SyncPortfolioService} persists the current holdings symbol set. */
    static final String HELD_SYMBOLS_KEY = "held_symbols";

    private PriceFetchSupport() {
    }

    /**
     * Distinct, gilt-filtered, Yahoo-resolved tickers, preserving insertion order so logs read
     * in a stable sequence. Tickers that resolve to the same Yahoo symbol (e.g. multiple internal
     * share-class spellings) are deduplicated.
     *
     * <p>Four sources: every symbol ever traded (from the cash ledger), the symbol set
     * persisted by the most recent dashboard /sync, the watchlist symbol set, and the
     * replacement targets used by the Scenario tab's substitute overrides. The second source
     * covers a freshly bought name
     * whose cash statement hasn't been imported yet. The third covers substitute targets
     * (e.g. {@code NVDA=QQQ}) — those tickers are never traded, so without this hook the
     * scenario engine would project them with stale or missing history. The default
     * substitute {@link HistoricalScenarioService#DEFAULT_SUBSTITUTE} is always included
     * so the fallback symbol stays current too.
     */
    static Set<String> tickersToFetch(CashTransactionRepository cashRepo, YahooTickerMap tickers,
                                      KeyValueStore kv) {
        Set<String> symbols = new LinkedHashSet<>(cashRepo.distinctTradedSymbols());
        symbols.addAll(kv.getStringSet(HELD_SYMBOLS_KEY));
        // Watchlist names (owned or not) so freshly added symbols get daily + intraday prices
        // on the next tick, even before any cash statement mentions them.
        symbols.addAll(kv.getStringSet(WatchlistRepository.WATCHLIST_SYMBOLS_KEY));
        for (String line : kv.getStringSet(HistoricalScenarioService.SUBSTITUTES_KEY)) {
            int eq = line.indexOf('=');
            if (eq <= 0 || eq >= line.length() - 1) continue;
            String v = line.substring(eq + 1).trim().toUpperCase();
            if (!v.isEmpty()) symbols.add(v);
        }
        symbols.add(HistoricalScenarioService.DEFAULT_SUBSTITUTE);
        Set<String> out = new LinkedHashSet<>();
        for (String symbol : symbols) {
            if (Instruments.isBond(symbol)) continue;
            out.add(tickers.tickerFor(symbol));
        }
        return out;
    }

    /** Cooperative sleep — restores the interrupt flag instead of swallowing it. */
    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
