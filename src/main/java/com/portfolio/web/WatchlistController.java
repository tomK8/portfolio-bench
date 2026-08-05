package com.portfolio.web;

import com.portfolio.application.PortfolioFundamentalsService;
import com.portfolio.application.PriceFetchJob;
import com.portfolio.application.WatchlistAlertJob;
import com.portfolio.application.WatchlistService;
import com.portfolio.domain.Instruments;
import com.portfolio.application.WatchlistService.QuoteView;
import com.portfolio.application.WatchlistService.Series;
import com.portfolio.application.WatchlistService.WatchlistView;
import com.portfolio.persistence.WatchlistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.math.BigDecimal;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The volatility-trading watchlist: a user-maintained set of symbols (owned or not) with the
 * live data needed to time a trim/rebuy, plus per-symbol alert thresholds.
 *
 * <p>Reads ({@code GET /watchlist}, {@code GET /watchlist/series}) are instant — they render
 * whatever prices/fundamentals are already cached. Adding a symbol kicks a background backfill
 * (daily prices + a fundamentals refresh) so a brand-new name fills in within a minute or two;
 * the row shows a placeholder until then and the UI polls. Mutation validation errors bubble
 * through {@link GlobalExceptionAdvice} to the standard error fragment.
 */
@Controller
public class WatchlistController {

    private static final Logger log = LoggerFactory.getLogger(WatchlistController.class);

    private final WatchlistService watchlistService;
    private final WatchlistRepository watchlistRepo;
    private final PriceFetchJob priceFetchJob;
    private final PortfolioFundamentalsService fundamentalsService;
    private final WatchlistAlertJob watchlistAlertJob;

    // Single-thread so two quick adds queue instead of racing each other's SQLite writes.
    private final ExecutorService backfillExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "watchlist-add-backfill");
        t.setDaemon(true);
        return t;
    });

    public WatchlistController(WatchlistService watchlistService,
                               WatchlistRepository watchlistRepo,
                               PriceFetchJob priceFetchJob,
                               PortfolioFundamentalsService fundamentalsService,
                               WatchlistAlertJob watchlistAlertJob) {
        this.watchlistService = watchlistService;
        this.watchlistRepo = watchlistRepo;
        this.priceFetchJob = priceFetchJob;
        this.fundamentalsService = fundamentalsService;
        this.watchlistAlertJob = watchlistAlertJob;
    }

    @GetMapping("/watchlist")
    @ResponseBody
    public WatchlistView watchlist() {
        return watchlistService.view();
    }

    /** Lightweight near-real-time refresh (see {@link WatchlistService#quotes()}) — polled by the dashboard. */
    @GetMapping("/watchlist/quotes")
    @ResponseBody
    public QuoteView quotes() {
        return watchlistService.quotes();
    }

    @PostMapping("/watchlist/add")
    @ResponseBody
    public WatchlistView add(@RequestParam("symbol") String symbol,
                             @RequestParam(name = "highPct", required = false) String highPct,
                             @RequestParam(name = "movePct", required = false) String movePct,
                             @RequestParam(name = "priceAbove", required = false) String priceAbove,
                             @RequestParam(name = "priceBelow", required = false) String priceBelow) {
        String sym = symbol == null ? "" : symbol.trim().toUpperCase();
        if (sym.isEmpty()) throw new IllegalArgumentException("Symbol is required.");
        watchlistRepo.add(sym, parsePct(highPct, "high threshold"), parsePct(movePct, "move threshold"),
                parsePrice(priceAbove, "above level"), parsePrice(priceBelow, "below level"));
        watchlistAlertJob.clearFiredFor(sym);   // new/changed triggers re-arm today (no stale dedup)
        backfillInBackground(sym);
        return watchlistService.view();
    }

    @PostMapping("/watchlist/threshold")
    @ResponseBody
    public WatchlistView threshold(@RequestParam("symbol") String symbol,
                                   @RequestParam(name = "highPct", required = false) String highPct,
                                   @RequestParam(name = "movePct", required = false) String movePct,
                                   @RequestParam(name = "priceAbove", required = false) String priceAbove,
                                   @RequestParam(name = "priceBelow", required = false) String priceBelow) {
        watchlistRepo.updateThresholds(symbol, parsePct(highPct, "high threshold"),
                parsePct(movePct, "move threshold"),
                parsePrice(priceAbove, "above level"), parsePrice(priceBelow, "below level"));
        watchlistAlertJob.clearFiredFor(symbol);   // a corrected target re-arms same-day
        return watchlistService.view();
    }

    @PostMapping("/watchlist/remove")
    @ResponseBody
    public WatchlistView remove(@RequestParam("symbol") String symbol) {
        watchlistRepo.remove(symbol);
        watchlistAlertJob.clearFiredFor(symbol);   // drop any fired flags for a symbol no longer watched
        return watchlistService.view();
    }

    /**
     * The conventional gilts currently priced (from {@code GiltPriceFetchJob}) — feeds the
     * Watchlist add-box autocomplete so a gilt buy-limit alert is entered by picking a valid,
     * priced name (e.g. {@code GILT 4.75% 2043}) rather than typing one.
     */
    @GetMapping("/watchlist/gilts")
    @ResponseBody
    public java.util.List<String> gilts() {
        return watchlistService.giltSymbols();
    }

    @GetMapping("/watchlist/series")
    @ResponseBody
    public Series series(@RequestParam("symbol") String symbol,
                         @RequestParam(name = "window", defaultValue = "5D") String window) {
        return watchlistService.series(symbol, window);
    }

    @GetMapping("/watchlist/vol")
    @ResponseBody
    public WatchlistService.VolTerms vol(@RequestParam("symbol") String symbol) {
        return watchlistService.volatility(symbol);
    }

    @GetMapping("/watchlist/returns")
    @ResponseBody
    public WatchlistService.ReturnsGrid returns() {
        return watchlistService.returns();
    }

    @PostMapping("/watchlist/move")
    @ResponseBody
    public WatchlistView move(@RequestParam("symbol") String symbol,
                              @RequestParam("dir") String dir) {
        boolean up = "up".equalsIgnoreCase(dir == null ? "" : dir.trim());
        boolean down = "down".equalsIgnoreCase(dir == null ? "" : dir.trim());
        if (!up && !down) throw new IllegalArgumentException("dir must be 'up' or 'down'.");
        watchlistRepo.move(symbol, up);
        return watchlistService.view();
    }

    /**
     * Fetch daily prices for a newly added symbol and refresh <em>that one symbol's</em>
     * fundamentals, off the request thread so the UI returns immediately. Intraday quotes arrive
     * on the next scheduled intraday tick (≤1 min) via the mirrored watchlist symbol set.
     *
     * <p>Deliberately narrow: the price fetch is skipped when the symbol's history is already
     * fresh ({@link PriceFetchJob#fetchSingleIfStale}) and only the added symbol's fundamentals
     * are refreshed (not the whole ~45-name universe). Both keep this background task's SQLite
     * writes short so a following watchlist add/remove doesn't lose the writer lock and fail.
     * The nightly/cron fundamentals refresh still covers the full universe.
     */
    private void backfillInBackground(String symbol) {
        backfillExecutor.submit(() -> {
            try {
                int rows = priceFetchJob.fetchSingleIfStale(symbol);
                log.info("Watchlist add backfill: {} → {} daily rows", symbol, rows);
                if (!Instruments.isBond(symbol)) {   // gilts have no Yahoo fundamentals coverage
                    fundamentalsService.refreshSymbol(symbol);
                }
            } catch (RuntimeException e) {
                log.warn("Watchlist add backfill failed for {}", symbol, e);
            }
        });
    }

    private static BigDecimal parsePct(String s, String name) {
        if (s == null || s.isBlank()) return null;
        try {
            BigDecimal v = new BigDecimal(s.replace("%", "").trim());
            if (v.signum() < 0) throw new IllegalArgumentException(name + " must not be negative.");
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " '" + s + "' is not a number.");
        }
    }

    /** Parse a price level: blank → null (disabled); rejects negatives; strips any currency prefix. */
    private static BigDecimal parsePrice(String s, String name) {
        if (s == null || s.isBlank()) return null;
        String cleaned = s.replaceAll("[£$€,\\s]", "").trim();
        if (cleaned.isEmpty()) return null;
        try {
            BigDecimal v = new BigDecimal(cleaned);
            if (v.signum() < 0) throw new IllegalArgumentException(name + " must not be negative.");
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " '" + s + "' is not a number.");
        }
    }
}
