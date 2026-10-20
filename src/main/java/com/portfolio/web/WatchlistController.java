package com.portfolio.web;

import com.portfolio.application.PortfolioFundamentalsService;
import com.portfolio.application.PriceFetchJob;
import com.portfolio.application.WatchlistAlertJob;
import com.portfolio.application.WatchlistService;
import com.portfolio.domain.Instruments;
import com.portfolio.application.WatchlistService.QuoteView;
import com.portfolio.application.WatchlistService.Row;
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
 * live data needed to time a trim/rebuy, plus per-symbol alert thresholds and the free-text note
 * that says what to do when one of them fires.
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

    /** Longest note accepted — see {@link #parseNote}. */
    static final int MAX_NOTE_CHARS = 500;

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

    /**
     * The named lists that make up the Tools tab strip. Always at least one — the repository
     * migration promotes a pre-lists watchlist into the first.
     */
    @GetMapping("/watchlist/lists")
    @ResponseBody
    public java.util.List<WatchlistRepository.WatchList> lists() {
        return watchlistRepo.lists();
    }

    @GetMapping("/watchlist")
    @ResponseBody
    public WatchlistView watchlist(@RequestParam(name = "list", required = false) Long list) {
        return watchlistService.view(listOrFirst(list));
    }

    /** Lightweight near-real-time refresh (see {@link WatchlistService#quotes()}) — polled by the dashboard. */
    @GetMapping("/watchlist/quotes")
    @ResponseBody
    public QuoteView quotes(@RequestParam(name = "list", required = false) Long list) {
        return watchlistService.quotes(listOrFirst(list));
    }

    /**
     * One row for a single symbol, so the price popup can be opened from tables outside this tab
     * (the Live positions table) without the symbol having to be on the watchlist. Read-only —
     * looking at a held name must never add it to the watchlist.
     */
    @GetMapping("/watchlist/row")
    @ResponseBody
    public Row row(@RequestParam("symbol") String symbol) {
        return watchlistService.row(symbol);
    }

    @PostMapping("/watchlist/add")
    @ResponseBody
    public WatchlistView add(@RequestParam("symbol") String symbol,
                             @RequestParam(name = "highPct", required = false) String highPct,
                             @RequestParam(name = "movePct", required = false) String movePct,
                             @RequestParam(name = "priceAbove", required = false) String priceAbove,
                             @RequestParam(name = "priceBelow", required = false) String priceBelow,
                             @RequestParam(name = "list", required = false) Long list) {
        String sym = symbol == null ? "" : symbol.trim().toUpperCase();
        if (sym.isEmpty()) throw new IllegalArgumentException("Symbol is required.");
        long listId = listOrFirst(list);
        watchlistRepo.add(sym, parsePct(highPct, "high threshold"), parsePct(movePct, "move threshold"),
                parsePrice(priceAbove, "above level"), parsePrice(priceBelow, "below level"), listId);
        watchlistAlertJob.clearFiredFor(sym);   // new/changed triggers re-arm today (no stale dedup)
        backfillInBackground(sym);
        return watchlistService.view(listId);
    }

    @PostMapping("/watchlist/threshold")
    @ResponseBody
    public WatchlistView threshold(@RequestParam("symbol") String symbol,
                                   @RequestParam(name = "highPct", required = false) String highPct,
                                   @RequestParam(name = "movePct", required = false) String movePct,
                                   @RequestParam(name = "priceAbove", required = false) String priceAbove,
                                   @RequestParam(name = "priceBelow", required = false) String priceBelow,
                                   @RequestParam(name = "list", required = false) Long list) {
        // Thresholds are global to the symbol, so this writes one row however many lists hold it;
        // the list param only says which rows to send back.
        watchlistRepo.updateThresholds(symbol, parsePct(highPct, "high threshold"),
                parsePct(movePct, "move threshold"),
                parsePrice(priceAbove, "above level"), parsePrice(priceBelow, "below level"));
        watchlistAlertJob.clearFiredFor(symbol);   // a corrected target re-arms same-day
        return watchlistService.view(listOrFirst(list));
    }

    /**
     * The per-symbol note — what to do when this name's alert fires. Free text; nothing parses it.
     *
     * <p>Like the thresholds it is <b>global to the symbol</b> (one row however many lists hold
     * it), and for the same reason: there is one set of triggers and one email, so a note that
     * differed per tab could contradict the signal it is advice about.
     *
     * <p>Unlike a threshold edit this does <b>not</b> re-arm today's alerts. The dedup exists so a
     * changed <em>setting</em> can fire fresh; rewording the plan changes nothing about where the
     * price is, and re-arming would re-mail a signal already sent for the same levels.
     */
    @PostMapping("/watchlist/note")
    @ResponseBody
    public WatchlistView note(@RequestParam("symbol") String symbol,
                              @RequestParam(name = "note", required = false) String note,
                              @RequestParam(name = "list", required = false) Long list) {
        watchlistRepo.updateNote(symbol, parseNote(note));
        return watchlistService.view(listOrFirst(list));
    }

    /**
     * The horizon every {@code P(N)} cell is priced over — <b>one stored setting for the whole
     * table</b>, not a box per row.
     *
     * <p>This is a comparison grid, so a column whose N differed by row would be as many questions
     * as there are rows under one header. Stored rather than browser state for the same reason the
     * ladder round trip's horizon is: the header names the N, and a figure and its label must come
     * from the same place.
     *
     * <p>Out of range is clamped, not refused — a stray keystroke should move the horizon to the
     * nearest one that can be priced, not fail the panel.
     */
    @PostMapping("/watchlist/horizon")
    @ResponseBody
    public WatchlistView horizon(@RequestParam("sessions") String sessions,
                                 @RequestParam(name = "list", required = false) Long list) {
        watchlistService.setTouchSessions(parseSessions(sessions));
        return watchlistService.view(listOrFirst(list));
    }

    @PostMapping("/watchlist/remove")
    @ResponseBody
    public WatchlistView remove(@RequestParam("symbol") String symbol,
                                @RequestParam(name = "list", required = false) Long list) {
        long listId = listOrFirst(list);
        watchlistRepo.remove(symbol, listId);
        watchlistAlertJob.clearFiredFor(symbol);   // drop any fired flags for a symbol no longer watched
        return watchlistService.view(listId);
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
    public WatchlistService.ReturnsGrid returns(@RequestParam(name = "list", required = false) Long list) {
        return watchlistService.returns(listOrFirst(list));
    }

    @PostMapping("/watchlist/move")
    @ResponseBody
    public WatchlistView move(@RequestParam("symbol") String symbol,
                              @RequestParam("dir") String dir,
                              @RequestParam(name = "list", required = false) Long list) {
        boolean up = "up".equalsIgnoreCase(dir == null ? "" : dir.trim());
        boolean down = "down".equalsIgnoreCase(dir == null ? "" : dir.trim());
        if (!up && !down) throw new IllegalArgumentException("dir must be 'up' or 'down'.");
        long listId = listOrFirst(list);
        watchlistRepo.move(symbol, up, listId);
        return watchlistService.view(listId);
    }

    /**
     * Save one list's order wholesale — the drag-and-drop drop. The client posts the rows in the
     * order they are displayed, which is deliberately what gets stored: if a column sort is
     * active, dragging a row is how that arrangement becomes the manual order. The ↑↓ buttons
     * keep their own single-swap path (they are also the only reorder a keyboard or a touch
     * screen can reach, where HTML5 drag events never fire).
     */
    @PostMapping("/watchlist/reorder")
    @ResponseBody
    public WatchlistView reorder(@RequestParam("symbols") java.util.List<String> symbols,
                                 @RequestParam(name = "list", required = false) Long list) {
        long listId = listOrFirst(list);
        watchlistRepo.reorder(symbols, listId);
        return watchlistService.view(listId);
    }

    // ---- list management -------------------------------------------------------

    @PostMapping("/watchlist/lists/create")
    @ResponseBody
    public java.util.List<WatchlistRepository.WatchList> createList(@RequestParam("name") String name) {
        watchlistRepo.createList(name);
        return watchlistRepo.lists();
    }

    @PostMapping("/watchlist/lists/rename")
    @ResponseBody
    public java.util.List<WatchlistRepository.WatchList> renameList(@RequestParam("list") long list,
                                                                    @RequestParam("name") String name) {
        watchlistRepo.renameList(list, name);
        return watchlistRepo.lists();
    }

    /** Deleting a list also drops any symbol it held that no other list holds. */
    @PostMapping("/watchlist/lists/delete")
    @ResponseBody
    public java.util.List<WatchlistRepository.WatchList> deleteList(@RequestParam("list") long list) {
        watchlistRepo.deleteList(list);
        return watchlistRepo.lists();
    }

    @PostMapping("/watchlist/lists/move")
    @ResponseBody
    public java.util.List<WatchlistRepository.WatchList> moveList(@RequestParam("list") long list,
                                                                  @RequestParam("dir") String dir) {
        boolean up = "up".equalsIgnoreCase(dir == null ? "" : dir.trim());
        boolean down = "down".equalsIgnoreCase(dir == null ? "" : dir.trim());
        if (!up && !down) throw new IllegalArgumentException("dir must be 'up' or 'down'.");
        watchlistRepo.moveList(list, up);
        return watchlistRepo.lists();
    }

    /**
     * The column layout for one list: an ordered list of keys, empty when the list has never
     * been customised (which the client reads as "use the default set").
     */
    @GetMapping("/watchlist/columns")
    @ResponseBody
    public java.util.List<String> columns(@RequestParam(name = "list", required = false) Long list) {
        return watchlistRepo.columnsFor(listOrFirst(list));
    }

    /**
     * Replace one list's columns. Sending none clears the rows, which restores the default set —
     * so "reset to default" is the same call with an empty selection rather than a second verb.
     */
    @PostMapping("/watchlist/columns")
    @ResponseBody
    public java.util.List<String> saveColumns(@RequestParam(name = "list", required = false) Long list,
                                              @RequestParam(name = "keys", required = false) java.util.List<String> keys) {
        long listId = listOrFirst(list);
        watchlistRepo.saveColumns(listId, keys == null ? java.util.List.of() : keys);
        return watchlistRepo.columnsFor(listId);
    }

    /**
     * A missing or unknown {@code list} means the first one. The dashboard always sends an id, so
     * this covers a bookmarked URL and — more usefully — a tab still open on a list another
     * window has just deleted: it falls back rather than erroring.
     */
    private long listOrFirst(Long requested) {
        if (requested == null) return watchlistRepo.firstListId();
        for (WatchlistRepository.WatchList l : watchlistRepo.lists()) {
            if (l.id() == requested) return requested;
        }
        return watchlistRepo.firstListId();
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

    /** Blank or unparseable is refused; a number out of range is clamped by the service. */
    private static int parseSessions(String s) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException("Horizon is required.");
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Horizon '" + s + "' is not a whole number of sessions.");
        }
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

    /**
     * A note is free text, so the only rule is a length cap — long enough for a sentence or two of
     * instruction, short enough to read in an email that may carry a dozen of them. Over the cap is
     * refused rather than truncated: silently losing the end of a plan is worse than being told to
     * shorten it. Blank is passed straight through; the repository stores it as "no note".
     */
    private static String parseNote(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        if (trimmed.length() > MAX_NOTE_CHARS) {
            throw new IllegalArgumentException("Note must be " + MAX_NOTE_CHARS
                    + " characters or fewer (was " + trimmed.length() + ").");
        }
        return trimmed;
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
