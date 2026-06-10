package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.Instruments;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.KeyValueStore;

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
     * <p>Union of two sources: every symbol ever traded (from the cash ledger) plus the symbol
     * set persisted by the most recent dashboard /sync. The second source covers a freshly bought
     * name whose cash statement hasn't been imported yet — without it the new ticker would be
     * invisible to the intraday job until the user imported a cash statement, leaving the RT
     * column blank.
     */
    static Set<String> tickersToFetch(CashTransactionRepository cashRepo, YahooTickerMap tickers,
                                      KeyValueStore kv) {
        Set<String> symbols = new LinkedHashSet<>(cashRepo.distinctTradedSymbols());
        symbols.addAll(kv.getStringSet(HELD_SYMBOLS_KEY));
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
