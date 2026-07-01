package com.portfolio.web;

import com.portfolio.application.FundamentalsFetchJob;
import com.portfolio.application.PriceFetchJob;
import com.portfolio.application.WatchlistService;
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
    private final FundamentalsFetchJob fundamentalsFetchJob;

    public WatchlistController(WatchlistService watchlistService,
                               WatchlistRepository watchlistRepo,
                               PriceFetchJob priceFetchJob,
                               FundamentalsFetchJob fundamentalsFetchJob) {
        this.watchlistService = watchlistService;
        this.watchlistRepo = watchlistRepo;
        this.priceFetchJob = priceFetchJob;
        this.fundamentalsFetchJob = fundamentalsFetchJob;
    }

    @GetMapping("/watchlist")
    @ResponseBody
    public WatchlistView watchlist() {
        return watchlistService.view();
    }

    @PostMapping("/watchlist/add")
    @ResponseBody
    public WatchlistView add(@RequestParam("symbol") String symbol,
                             @RequestParam(name = "highPct", required = false) String highPct,
                             @RequestParam(name = "movePct", required = false) String movePct) {
        String sym = symbol == null ? "" : symbol.trim().toUpperCase();
        if (sym.isEmpty()) throw new IllegalArgumentException("Symbol is required.");
        watchlistRepo.add(sym, parsePct(highPct, "high threshold"), parsePct(movePct, "move threshold"));
        backfillInBackground(sym);
        return watchlistService.view();
    }

    @PostMapping("/watchlist/threshold")
    @ResponseBody
    public WatchlistView threshold(@RequestParam("symbol") String symbol,
                                   @RequestParam(name = "highPct", required = false) String highPct,
                                   @RequestParam(name = "movePct", required = false) String movePct) {
        watchlistRepo.updateThresholds(symbol, parsePct(highPct, "high threshold"),
                parsePct(movePct, "move threshold"));
        return watchlistService.view();
    }

    @PostMapping("/watchlist/remove")
    @ResponseBody
    public WatchlistView remove(@RequestParam("symbol") String symbol) {
        watchlistRepo.remove(symbol);
        return watchlistService.view();
    }

    @GetMapping("/watchlist/series")
    @ResponseBody
    public Series series(@RequestParam("symbol") String symbol,
                         @RequestParam(name = "window", defaultValue = "5D") String window) {
        return watchlistService.series(symbol, window);
    }

    /**
     * Fetch daily prices for a newly added symbol and refresh the fundamentals cache, off the
     * request thread so the UI returns immediately. Intraday quotes arrive on the next scheduled
     * intraday tick (≤1 min) via the mirrored watchlist symbol set.
     */
    private void backfillInBackground(String symbol) {
        Thread t = new Thread(() -> {
            try {
                int rows = priceFetchJob.fetchSingle(symbol);
                log.info("Watchlist add backfill: {} → {} daily rows", symbol, rows);
                fundamentalsFetchJob.run();
            } catch (RuntimeException e) {
                log.warn("Watchlist add backfill failed for {}", symbol, e);
            }
        }, "watchlist-add-backfill");
        t.setDaemon(true);
        t.start();
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
}
