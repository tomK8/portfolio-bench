package com.portfolio.web;

import com.portfolio.application.AttributionService;
import com.portfolio.application.AttributionService.AttributionResult;
import com.portfolio.application.BenchmarkReturnService;
import com.portfolio.application.BenchmarkReturnService.BenchmarkTimeline;
import com.portfolio.application.ContributionService;
import com.portfolio.application.ContributionService.ContributionTimeline;
import com.portfolio.application.ExportExcelService;
import com.portfolio.application.ImportCashService;
import com.portfolio.application.ImportGiltPricesService;
import com.portfolio.application.PortfolioReturnService;
import com.portfolio.application.PortfolioReturnService.ReturnTimeline;
import com.portfolio.application.PortfolioValueService;
import com.portfolio.application.PortfolioValueService.ValueTimeline;
import com.portfolio.application.PriceFetchJob;
import com.portfolio.application.SyncFromCashService;
import com.portfolio.application.SyncPortfolioService;
import com.portfolio.application.WhatIfService;
import com.portfolio.application.WhatIfService.Weight;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.KeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Controller
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    /** KV key for the last II SIPP GBP cash balance entered on the dashboard. */
    static final String II_SIPP_CASH_KEY = "ii_sipp_cash_last";

    /** KV key for the last II SIPP USD cash balance entered on the dashboard. */
    static final String II_SIPP_CASH_USD_KEY = "ii_sipp_cash_last_usd";

    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final SyncPortfolioService syncService;
    private final SyncFromCashService syncFromCashService;
    private final ExportExcelService exportService;
    private final ImportCashService importCashService;
    private final ImportGiltPricesService importGiltPricesService;
    private final ContributionService contributionService;
    private final PortfolioValueService portfolioValueService;
    private final PortfolioReturnService portfolioReturnService;
    private final BenchmarkReturnService benchmarkReturnService;
    private final WhatIfService whatIfService;
    private final AttributionService attributionService;
    private final PriceFetchJob priceFetchJob;
    private final CashTransactionRepository cashRepo;
    private final KeyValueStore settings;

    public DashboardController(SyncPortfolioService syncService,
                               SyncFromCashService syncFromCashService,
                               ExportExcelService exportService,
                               ImportCashService importCashService,
                               ImportGiltPricesService importGiltPricesService,
                               ContributionService contributionService,
                               PortfolioValueService portfolioValueService,
                               PortfolioReturnService portfolioReturnService,
                               BenchmarkReturnService benchmarkReturnService,
                               WhatIfService whatIfService,
                               AttributionService attributionService,
                               PriceFetchJob priceFetchJob,
                               CashTransactionRepository cashRepo,
                               KeyValueStore settings) {
        this.syncService = syncService;
        this.syncFromCashService = syncFromCashService;
        this.exportService = exportService;
        this.importCashService = importCashService;
        this.importGiltPricesService = importGiltPricesService;
        this.contributionService = contributionService;
        this.portfolioValueService = portfolioValueService;
        this.portfolioReturnService = portfolioReturnService;
        this.benchmarkReturnService = benchmarkReturnService;
        this.whatIfService = whatIfService;
        this.attributionService = attributionService;
        this.priceFetchJob = priceFetchJob;
        this.cashRepo = cashRepo;
        this.settings = settings;
    }

    private static String now() {
        return LocalTime.now().format(HMS);
    }

    private static BigDecimal parseCash(String raw) {
        String clean = raw.replace(",", "").replace("£", "").trim();
        if (clean.isEmpty()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(clean);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("appName", "Portfolio Bench");
        model.addAttribute("iiSippCash",
                settings.getBigDecimal(II_SIPP_CASH_KEY, BigDecimal.ZERO).toPlainString());
        model.addAttribute("iiSippCashUsd",
                settings.getBigDecimal(II_SIPP_CASH_USD_KEY, BigDecimal.ZERO).toPlainString());
        return "dashboard";
    }

    /** Parse + persist GBP form value. */
    private BigDecimal persistIiSippCash(String raw) {
        BigDecimal cash = parseCash(raw);
        settings.putBigDecimal(II_SIPP_CASH_KEY, cash);
        return cash;
    }

    /** Parse + persist USD form value. */
    private BigDecimal persistIiSippCashUsd(String raw) {
        BigDecimal cash = parseCash(raw);
        settings.putBigDecimal(II_SIPP_CASH_USD_KEY, cash);
        return cash;
    }

    @PostMapping("/sync")
    public String sync(@RequestParam(name = "iiSippCash", required = false, defaultValue = "0") String iiSippCash,
                       @RequestParam(name = "iiSippCashUsd", required = false, defaultValue = "0") String iiSippCashUsd,
                       Model model) {
        model.addAttribute("result", syncService.sync(
                persistIiSippCash(iiSippCash), persistIiSippCashUsd(iiSippCashUsd)));
        model.addAttribute("completedAt", now());
        return "fragments/portfolio :: result";
    }

    @PostMapping("/sync-from-cash")
    public String syncFromCash(Model model) {
        model.addAttribute("result", syncFromCashService.sync());
        model.addAttribute("completedAt", now());
        return "fragments/portfolio-ledger :: result";
    }

    @PostMapping("/export")
    public String export(@RequestParam(name = "iiSippCash", required = false, defaultValue = "0") String iiSippCash,
                         @RequestParam(name = "iiSippCashUsd", required = false, defaultValue = "0") String iiSippCashUsd,
                         Model model) {
        model.addAttribute("export", exportService.export(
                persistIiSippCash(iiSippCash), persistIiSippCashUsd(iiSippCashUsd)));
        model.addAttribute("completedAt", now());
        return "fragments/export :: result";
    }

    @PostMapping("/import-cash")
    public String importCash(Model model) {
        model.addAttribute("cashImports", importCashService.importCash());
        model.addAttribute("completedAt", now());
        return "fragments/import :: result";
    }

    @GetMapping("/contributions")
    @ResponseBody
    public ContributionTimeline contributions() {
        return contributionService.timeline();
    }

    @GetMapping("/portfolio-value")
    @ResponseBody
    public ValueTimeline portfolioValue() {
        return portfolioValueService.timeline();
    }

    @GetMapping("/returns")
    @ResponseBody
    public ReturnTimeline returns() {
        return portfolioReturnService.timeline();
    }

    /**
     * Cumulative return of a single benchmark ticker (any Yahoo-style symbol), anchored at the
     * portfolio's inception date. If {@code price_history} has no rows for the symbol the
     * response is empty with {@code missing=true}; the UI then prompts the user to confirm a
     * background Yahoo backfill via {@link #fetchBenchmark}.
     */
    @GetMapping("/returns/benchmark")
    @ResponseBody
    public BenchmarkTimeline benchmarkReturns(@RequestParam("symbol") String symbol) {
        return benchmarkReturnService.timeline(symbol);
    }

    /**
     * One-shot Yahoo backfill for a benchmark ticker the user typed in. Reuses
     * {@link PriceFetchJob#fetchSingle} — same ~10-year window, same {@code adj_close}
     * re-derivation. Returns the number of {@code price_history} rows written.
     */
    @PostMapping("/returns/benchmark/fetch")
    @ResponseBody
    public java.util.Map<String, Object> fetchBenchmark(@RequestParam("symbol") String symbol) {
        String sym = symbol == null ? "" : symbol.trim().toUpperCase();
        int rows = sym.isEmpty() ? 0 : priceFetchJob.fetchSingle(sym);
        log.info("Benchmark backfill: {} → {} rows", sym, rows);
        return java.util.Map.of("symbol", sym, "rows", rows);
    }

    /** Held / previously-traded symbols, for the benchmark dropdown. */
    @GetMapping("/returns/symbols")
    @ResponseBody
    public List<String> tradedSymbols() {
        return cashRepo.distinctTradedSymbols();
    }

    /**
     * Per-symbol GBP P&amp;L over {@code [from, to]}. {@code to} defaults to today when blank.
     * See {@link AttributionService} for the formula.
     */
    @GetMapping("/attribution")
    @ResponseBody
    public AttributionResult attribution(@RequestParam(name = "from") String from,
                                         @RequestParam(name = "to", required = false) String to) {
        LocalDate fromDate = LocalDate.parse(from);
        LocalDate toDate = (to == null || to.isBlank()) ? LocalDate.now() : LocalDate.parse(to);
        return attributionService.attribute(fromDate, toDate);
    }

    /**
     * Backtest a fixed basket against the actual contribution history. Symbols and weights
     * arrive as two parallel lists from the form (e.g. {@code symbols=GOOG&symbols=EQQQ}).
     * Weights are percentages — converted to fractions here so the service can stay in
     * basis-point land. Validation errors bubble through {@link #handleBadRequest}.
     */
    @PostMapping("/whatif")
    @ResponseBody
    public ValueTimeline whatIf(@RequestParam(name = "symbols") List<String> symbols,
                                @RequestParam(name = "weights") List<String> weights,
                                @RequestParam(name = "backfill", defaultValue = "false") boolean backfill) {
        if (symbols.size() != weights.size()) {
            throw new IllegalArgumentException("Symbols and weights must have the same length.");
        }
        List<Weight> basket = new ArrayList<>();
        for (int i = 0; i < symbols.size(); i++) {
            String sym = symbols.get(i) == null ? "" : symbols.get(i).trim();
            String w = weights.get(i) == null ? "" : weights.get(i).trim();
            if (sym.isEmpty() && w.isEmpty()) continue;          // skip blank rows
            if (sym.isEmpty()) throw new IllegalArgumentException("Row " + (i + 1) + ": symbol is required when a weight is set.");
            if (w.isEmpty()) throw new IllegalArgumentException("Row " + (i + 1) + ": weight is required when a symbol is set.");
            BigDecimal pct;
            try {
                pct = new BigDecimal(w);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Row " + (i + 1) + ": weight '" + w + "' is not a number.");
            }
            basket.add(new Weight(sym.toUpperCase(),
                    pct.divide(new BigDecimal("100"), 10, java.math.RoundingMode.HALF_UP)));
        }
        // First pass returns whatever we already know — the chart still renders with any
        // missing basket symbol's allocation falling back to GBP cash. If the caller passes
        // backfill=true (after confirming the prompt client-side), fetch each missing symbol
        // from Yahoo (~2s each, throttled inside the job) and re-run with fresh data.
        ValueTimeline preview = whatIfService.timeline(basket);
        if (backfill && preview.missingPrices() != null && !preview.missingPrices().isEmpty()) {
            for (var mp : preview.missingPrices()) {
                int rows = priceFetchJob.fetchSingle(mp.symbol());
                log.info("What-if backfill: {} → {} rows", mp.symbol(), rows);
            }
            return whatIfService.timeline(basket);
        }
        return preview;
    }

    /**
     * Re-fetches the full 10-year window for every traded symbol and re-derives
     * {@code adj_close} from Yahoo's dividend + split events. Long-running (~50 tickers ×
     * throttle ≈ 25–60s), so the UI shows a spinner.
     */
    @PostMapping("/rebuild-prices")
    public String rebuildPrices(Model model) {
        int refreshed = priceFetchJob.runFullRebuild();
        model.addAttribute("rebuildCount", refreshed);
        model.addAttribute("completedAt", now());
        return "fragments/rebuild-prices :: result";
    }

    @PostMapping("/import-gilt-prices")
    public String importGiltPrices(Model model) {
        model.addAttribute("giltImports", importGiltPricesService.importAll());
        model.addAttribute("completedAt", now());
        return "fragments/import-gilt-prices :: result";
    }

    /**
     * Bad-basket errors from {@code POST /whatif}: respond 400 with a plain-text message so
     * the JSON-fetching client can display it directly.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public org.springframework.http.ResponseEntity<String> handleBadRequest(IllegalArgumentException e) {
        return org.springframework.http.ResponseEntity
                .badRequest().body(e.getMessage());
    }

    /**
     * Surfaces write-path failures (snapshot save, cash import, integrity gaps) as a friendly
     * fragment in the result panel rather than a stark 500. The full stack trace already lives
     * in the log file.
     */
    @ExceptionHandler(IllegalStateException.class)
    public String handlePersistenceFailure(IllegalStateException e, Model model) {
        log.warn("Action failed", e);
        model.addAttribute("errorMessage", e.getMessage());
        model.addAttribute("completedAt", now());
        return "fragments/error :: result";
    }
}
