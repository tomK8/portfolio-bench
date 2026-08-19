package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.Instruments;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.WatchlistRepository;
import org.slf4j.Logger;

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
     * <p>Sources: every symbol ever traded (from the cash ledger), the symbol set persisted by
     * the most recent dashboard /sync, the watchlist symbol set, each strategy tab's cohort, the
     * symbols named by resting orders, and the replacement targets used by the Scenario tab's
     * substitute overrides. The second source
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
        // Swing-strategy universe (many not held/traded) so the nightly daily job keeps their
        // 3-year history current for the Swing stats tab.
        symbols.addAll(kv.getStringSet(SwingAnalysisService.SWING_SYMBOLS_KEY));
        // Trend-filter series (QQQ + ^NDX + ^IRX) so the nightly job maintains the backtest's
        // primary/extension/cash history once the deep backfill has seeded it.
        symbols.addAll(kv.getStringSet(TrendAnalysisService.TREND_SYMBOLS_KEY));
        // Momentum-rotation universe + benchmarks so the nightly job keeps their deep history
        // current for the Momentum tab (fetch set = universe ∪ benchmarks ∪ regime index).
        symbols.addAll(kv.getStringSet(MomentumAnalysisService.MOMENTUM_SYMBOLS_KEY));
        // Momentum-signals asset-class cohort (pinned ETF universe + benchmarks) for the
        // five-variant signal comparison tab.
        symbols.addAll(kv.getStringSet(MomentumSignalsService.SIGNALS_SYMBOLS_KEY));
        // Range-strategy cohort (information-infrastructure names, mostly not held) so the
        // nightly job keeps their daily history current for the Range floor tab.
        symbols.addAll(kv.getStringSet(RangeAnalysisService.RANGE_SYMBOLS_KEY));
        // Symbols named by a resting order (published by the Open orders tab). A limit order is
        // frequently on something not held and never traded, so no other source names it — and the
        // whole point of the tab's price columns is how far the market sits from that limit.
        symbols.addAll(kv.getStringSet(OpenOrdersService.ORDER_SYMBOLS_KEY));
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

    /**
     * The single wording for "we have no prices for this and here is the fix", shared by the daily
     * and intraday jobs so a symbol that needs a mapping says the same thing wherever it surfaces.
     * The {@code symbol} is the internal name when known (null when only the resolved ticker is in
     * hand) — equal to {@code ticker} for an unmapped symbol, which is the case that gets the
     * copy-paste fix rather than a "correct the existing entry" nudge.
     *
     * <p>Fired only when nothing has <em>ever</em> been stored for the ticker: an established name
     * returning no rows is an ordinary quiet fetch (weekend, already current), while a first fetch
     * that comes back empty means the ticker itself is wrong — the identity fallback aimed
     * {@code PHP} (Primary Health Properties, LSE) at a US mutual fund delisted in 2019.
     */
    static void warnNoPriceData(Logger log, String symbol, String ticker) {
        if (ticker.equals(symbol)) {
            log.warn("No price data for {} — nothing on Yahoo answers to that ticker, which we tried "
                    + "only because the symbol has no mapping. Add one on the dashboard (the sync "
                    + "and Open orders tabs offer the field inline, and check the ticker before "
                    + "saving): {} → {}.L for a London listing (.PA Paris, .AS Amsterdam, .DE "
                    + "Frankfurt).", symbol, symbol, symbol);
        } else if (symbol != null) {
            log.warn("No price data for {} — its mapping to {} does not resolve on Yahoo. Correct "
                    + "it on the dashboard; a user mapping overrides the committed one.", symbol, ticker);
        } else {
            log.warn("No price data for ticker {} — check the mapping that resolves to it.", ticker);
        }
    }

    /**
     * One-time notice that a symbol is being priced through the identity fallback rather than an
     * explicit mapping. Absence from the map is legitimate for US listings, so this is INFO, not a
     * warning — but it is worth a line in the log because the fallback can silently hit a *real*
     * foreign instrument: unmapped {@code BLND} (British Land, LSE, ~440p) resolves to an unrelated
     * NYSE ticker trading near $1.50, which prices the position wrongly instead of not at all.
     * {@link #warnNoPriceData} cannot catch that case — data does come back.
     */
    static void noteIdentityTicker(Logger log, String ticker) {
        log.info("First price fetch for {} — no mapping, assuming a US listing of the same name. If "
                + "it is a non-US listing, add the mapping on the dashboard (e.g. {} → {}.L) — the "
                + "identity fallback can resolve to an unrelated instrument.", ticker, ticker, ticker);
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
