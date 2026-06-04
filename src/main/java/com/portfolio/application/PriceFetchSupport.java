package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.Instruments;
import com.portfolio.persistence.CashTransactionRepository;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Shared building blocks for the price-fetch jobs. The daily and intraday jobs both fetch
 * the same universe (every traded symbol, minus gilts, mapped to Yahoo tickers); keeping
 * that resolution and the polite-throttle sleep in one place avoids drift between them.
 */
final class PriceFetchSupport {

    private PriceFetchSupport() {
    }

    /**
     * Distinct, gilt-filtered, Yahoo-resolved tickers from the cash ledger, preserving insertion
     * order so logs read in a stable sequence. Tickers that resolve to the same Yahoo symbol
     * (e.g. multiple internal share-class spellings) are deduplicated.
     */
    static Set<String> tickersToFetch(CashTransactionRepository cashRepo, YahooTickerMap tickers) {
        Set<String> out = new LinkedHashSet<>();
        for (String symbol : cashRepo.distinctTradedSymbols()) {
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
