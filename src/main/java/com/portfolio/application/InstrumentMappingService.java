package com.portfolio.application;

import com.portfolio.adapter.YahooPriceFetcher;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.Instruments;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.InstrumentMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Adding an instrument mapping from the dashboard, rather than from a source edit and a redeploy.
 *
 * <p>A new instrument needs up to two mappings before it prices: a <b>name mapping</b> (the broker's
 * long description → the app's internal symbol) and a <b>ticker mapping</b> (internal symbol →
 * Yahoo ticker). Missing either one is silent in a different way — an unmapped name shows the full
 * description in the Symbol column with every live-price cell blank; an unmapped symbol falls
 * through to the identity ticker, which either finds nothing or, worse, finds the wrong live
 * listing. This service is the single write path for both, and it does the three things that have
 * to happen together:
 *
 * <ol>
 *   <li><b>Check before saving</b> — a ticker is probed against Yahoo, and the listing's name,
 *       currency and last close come back for the user to eyeball. Saving an unverified ticker is
 *       possible but explicit.</li>
 *   <li><b>Take effect immediately</b> — {@link YahooTickerMap} re-reads its overlay, so every
 *       one of its ~30 consumers sees the new mapping without a restart, and the daily series is
 *       backfilled in the background so the price columns fill in rather than staying blank until
 *       the nightly job.</li>
 *   <li><b>Repair what the gap already broke</b> — a name mapped after the cash statement was
 *       imported leaves ledger rows keyed by the long description. Those are re-keyed in place;
 *       see {@link CashTransactionRepository#renameSymbol}.</li>
 * </ol>
 */
public class InstrumentMappingService {

    private static final Logger log = LoggerFactory.getLogger(InstrumentMappingService.class);

    private final InstrumentMappingRepository repo;
    private final YahooTickerMap tickerMap;
    private final YahooPriceFetcher fetcher;
    private final PriceFetchJob priceFetchJob;
    private final CashTransactionRepository cashRepo;

    /** Serialised so two quick saves queue rather than racing each other's SQLite writes. */
    private final ExecutorService backfill = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mapping-backfill");
        t.setDaemon(true);
        return t;
    });

    public InstrumentMappingService(InstrumentMappingRepository repo, YahooTickerMap tickerMap,
                                    YahooPriceFetcher fetcher, PriceFetchJob priceFetchJob,
                                    CashTransactionRepository cashRepo) {
        this.repo = repo;
        this.tickerMap = tickerMap;
        this.fetcher = fetcher;
        this.priceFetchJob = priceFetchJob;
        this.cashRepo = cashRepo;
    }

    /** Everything the dashboard's mapping list shows: user rows first, then the committed baseline. */
    public MappingsView view() {
        List<BuiltInMapping> builtIn = new ArrayList<>();
        tickerMap.builtInMappings().forEach((sym, tk) -> builtIn.add(new BuiltInMapping(sym, tk)));
        return new MappingsView(repo.listTickerMappings(), repo.listAliases(), builtIn);
    }

    /** Live check of a Yahoo ticker — no writes. */
    public YahooPriceFetcher.TickerProbe probe(String yahooTicker) {
        return fetcher.probe(yahooTicker);
    }

    /**
     * Save symbol → Yahoo ticker. Probes first unless {@code force}; a probe that comes back
     * unusable aborts the save and is handed back so the dashboard can show why.
     */
    public SaveResult saveTicker(String symbol, String yahooTicker, boolean force) {
        String sym = require(symbol, "a symbol");
        String tk = require(yahooTicker, "a Yahoo ticker");

        YahooPriceFetcher.TickerProbe probe = force ? null : fetcher.probe(tk);
        if (probe != null && !probe.found()) {
            return new SaveResult(false, probe.message() == null
                    ? "Yahoo returned no prices for " + tk + "."
                    : probe.message(), 0, probe);
        }

        repo.saveTickerMapping(sym, tk, probe == null ? null : probe.currency(), probe != null);
        tickerMap.reload();
        backfillPrices(sym);

        String msg = sym + " → " + tk + " saved"
                + (probe != null && probe.name() != null ? " (" + probe.name() + ")" : "")
                + ". Fetching price history…";
        return new SaveResult(true, msg, 0, probe);
    }

    public void deleteTicker(String symbol) {
        repo.deleteTickerMapping(symbol);
        tickerMap.reload();
        // The stored verdict, if any, is about the ticker this mapping resolved to. That ticker is
        // no longer what the symbol means, so the diagnosis goes with it.
        priceFetchJob.forgetUnresolved(symbol);
    }

    /**
     * Save a description keyword → symbol, then re-key any ledger rows the missing mapping already
     * mis-stored. Returns how many rows were repaired so the dashboard can say so — silently fixing
     * a position's history is exactly the kind of change that should be reported.
     */
    public SaveResult saveAlias(String keyword, String symbol, String rawName) {
        String key = InstrumentMappingRepository.normaliseKeyword(require(keyword, "some match text"));
        String sym = require(symbol, "a symbol");

        repo.saveAlias(key, sym, rawName);
        int rekeyed = 0;
        for (String stored : strandedLedgerSymbols(key, sym)) {
            rekeyed += cashRepo.renameSymbol(stored, sym);
        }

        String msg = "\"" + key + "\" → " + sym + " saved"
                + (rekeyed > 0 ? ", and " + rekeyed + " ledger row(s) re-keyed" : "") + ".";
        return new SaveResult(true, msg, rekeyed, null);
    }

    public void deleteAlias(String keyword) {
        repo.deleteAlias(keyword);
    }

    /** Alias keywords for the parsers — longest first, so the most specific phrase wins. */
    public Map<String, String> aliases() {
        return repo.loadAliases();
    }

    /**
     * Ledger symbols that are really un-mapped descriptions matching this keyword, and so should
     * become {@code target}.
     *
     * <p>Deliberately narrow. The cash parser's fallback stores a cleaned-up <em>investment name</em>,
     * which always contains a space; a real ticker never does. Without that guard a short keyword
     * would be free to re-key genuine positions — "bp" alone matches the stored symbol {@code BP.}
     * — and a rename is not something the user can undo from the dashboard.
     */
    private List<String> strandedLedgerSymbols(String keyword, String target) {
        List<String> out = new ArrayList<>();
        for (String stored : cashRepo.distinctTradedSymbols()) {
            if (stored == null || stored.isBlank() || stored.equals(target)) continue;
            if (!stored.contains(" ")) continue;              // a ticker, not a stranded description
            if (Instruments.isBond(stored)) continue;         // gilt ids legitimately contain spaces
            if (stored.toLowerCase().contains(keyword)) out.add(stored);
        }
        return out;
    }

    private void backfillPrices(String symbol) {
        backfill.submit(() -> {
            try {
                int rows = priceFetchJob.fetchSingle(symbol);
                log.info("Backfilled {} daily price row(s) for newly mapped {}", rows, symbol);
            } catch (Exception e) {
                log.warn("Backfill after mapping {} failed", symbol, e);
            }
        });
    }

    private static String require(String value, String what) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) throw new IllegalArgumentException("Please enter " + what + ".");
        return v;
    }

    /** Committed {@code yahoo-tickers.properties} entry — shown read-only beside the user's rows. */
    public record BuiltInMapping(String symbol, String yahooTicker) {
    }

    public record MappingsView(List<InstrumentMappingRepository.TickerMapping> tickers,
                               List<InstrumentMappingRepository.InstrumentAlias> aliases,
                               List<BuiltInMapping> builtIn) {
    }

    /** {@code ok=false} means nothing was written and {@code message} says why. */
    public record SaveResult(boolean ok, String message, int ledgerRowsUpdated,
                             YahooPriceFetcher.TickerProbe probe) {
    }
}
