package com.portfolio.web;

import com.portfolio.application.FundamentalsService;
import com.portfolio.application.FundamentalsService.FundamentalsReport;
import com.portfolio.application.HealthService;
import com.portfolio.application.HealthService.Health;
import com.portfolio.application.PortfolioFundamentalsService;
import com.portfolio.application.PortfolioFundamentalsService.Snapshot;
import com.portfolio.application.PortfolioValueService.ValueTimeline;
import com.portfolio.application.PriceFetchJob;
import com.portfolio.application.ReconciliationService;
import com.portfolio.application.ReconciliationService.Report;
import com.portfolio.application.TradeNotesService;
import com.portfolio.application.TradeNotesService.TradeJournal;
import com.portfolio.application.WhatIfService;
import com.portfolio.application.WhatIfService.Weight;
import com.portfolio.persistence.SnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The "deeper analysis" endpoints: per-ticker fundamentals, the per-holding fundamentals
 * snapshot, the what-if basket backtest, the trade journal, the saved-snapshots diff,
 * the data-quality reconciliation report, and the lightweight health summary.
 *
 * <p>Shares {@link PriceFetchJob} with {@link OperationsController}: the what-if endpoint
 * needs an on-demand backfill for symbols the user types in. Spring injects the same
 * singleton into both controllers.
 */
@Controller
public class AnalysisController {

    private static final Logger log = LoggerFactory.getLogger(AnalysisController.class);

    private final FundamentalsService fundamentalsService;
    private final PortfolioFundamentalsService portfolioFundamentalsService;
    private final WhatIfService whatIfService;
    private final TradeNotesService tradeNotesService;
    private final SnapshotRepository snapshotRepo;
    private final ReconciliationService reconciliationService;
    private final HealthService healthService;
    private final PriceFetchJob priceFetchJob;

    public AnalysisController(FundamentalsService fundamentalsService,
                              PortfolioFundamentalsService portfolioFundamentalsService,
                              WhatIfService whatIfService,
                              TradeNotesService tradeNotesService,
                              SnapshotRepository snapshotRepo,
                              ReconciliationService reconciliationService,
                              HealthService healthService,
                              PriceFetchJob priceFetchJob) {
        this.fundamentalsService = fundamentalsService;
        this.portfolioFundamentalsService = portfolioFundamentalsService;
        this.whatIfService = whatIfService;
        this.tradeNotesService = tradeNotesService;
        this.snapshotRepo = snapshotRepo;
        this.reconciliationService = reconciliationService;
        this.healthService = healthService;
        this.priceFetchJob = priceFetchJob;
    }

    /**
     * Decomposes a stock's price growth into earnings growth vs multiple expansion over a
     * window. {@code symbol} must be in the supported set (US hyperscalers for now); see
     * {@link FundamentalsService} for the formula. {@code years} defaults to 10 — shorter
     * windows are honoured but EDGAR coverage tails off before ~2009 so going beyond is
     * pointless.
     */
    @GetMapping("/fundamentals")
    @ResponseBody
    public FundamentalsReport fundamentals(@RequestParam(name = "symbol") String symbol,
                                           @RequestParam(name = "years", defaultValue = "10") int years) {
        return fundamentalsService.report(symbol, years);
    }

    @GetMapping("/fundamentals/tickers")
    @ResponseBody
    public List<String> fundamentalsTickers() {
        return fundamentalsService.supportedTickers();
    }

    /**
     * Current-state fundamentals snapshot (P/E, market cap, beta, …) for every held symbol.
     * First call after server restart fetches ~50 tickers from Yahoo and takes ~30s; later
     * calls within the service's CACHE_TTL (6h) are instant.
     */
    @GetMapping("/portfolio-fundamentals")
    @ResponseBody
    public Snapshot portfolioFundamentals() {
        return portfolioFundamentalsService.snapshot();
    }

    /**
     * Backtest a fixed basket against the actual contribution history. Symbols and weights
     * arrive as two parallel lists from the form (e.g. {@code symbols=GOOG&symbols=EQQQ}).
     * Weights are percentages — converted to fractions here so the service can stay in
     * basis-point land. Validation errors bubble through {@link GlobalExceptionAdvice}.
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
                    pct.divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP)));
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
     * All TRANSACTION rows joined with optional notes/tags plus a tag-frequency summary.
     * Newest first so the UI lands on recent activity without paging logic.
     */
    @GetMapping("/trades")
    @ResponseBody
    public TradeJournal trades() {
        return tradeNotesService.journal();
    }

    /**
     * Upsert (or delete-when-empty) a trade annotation, then return the refreshed journal so
     * the UI's tag-frequency strip updates in the same round trip. Free text in {@code note},
     * comma-separated lowercase tags in {@code tags}; service normalises both.
     */
    @PostMapping("/trades/{rowid}/note")
    @ResponseBody
    public TradeJournal saveTradeNote(@PathVariable("rowid") long rowid,
                                      @RequestParam(name = "note", defaultValue = "") String note,
                                      @RequestParam(name = "tags", defaultValue = "") String tags) {
        return tradeNotesService.save(rowid, note, tags);
    }

    @GetMapping("/snapshots")
    @ResponseBody
    public List<SnapshotRepository.Snapshot> snapshots() {
        return snapshotRepo.listAll();
    }

    /**
     * Diff between two saved snapshots. The "from" defaults to the earliest, "to" to the
     * latest. Returns a summary delta object; per-symbol drift isn't tracked because
     * {@code portfolio_snapshots} stores only totals.
     */
    @GetMapping("/snapshots/delta")
    @ResponseBody
    public Map<String, Object> snapshotsDelta(@RequestParam(name = "from", required = false) String from,
                                              @RequestParam(name = "to", required = false) String to) {
        List<SnapshotRepository.Snapshot> all = snapshotRepo.listAll();
        if (all.isEmpty()) {
            return Map.of("from", null, "to", null, "delta", Map.of());
        }
        SnapshotRepository.Snapshot fromSnap = (from == null || from.isBlank())
                ? all.get(0) : findByDate(all, from);
        SnapshotRepository.Snapshot toSnap = (to == null || to.isBlank())
                ? all.get(all.size() - 1) : findByDate(all, to);
        if (fromSnap == null) throw new IllegalArgumentException("No snapshot for date " + from);
        if (toSnap == null) throw new IllegalArgumentException("No snapshot for date " + to);
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("totalValueGbp", subtract(toSnap.totalValueGbp(), fromSnap.totalValueGbp()));
        delta.put("totalGainGbp", subtract(toSnap.totalGainGbp(), fromSnap.totalGainGbp()));
        delta.put("totalCashGbp", subtract(toSnap.totalCashGbp(), fromSnap.totalCashGbp()));
        delta.put("returnPct", subtract(toSnap.returnPct(), fromSnap.returnPct()));
        delta.put("gbpusd", subtract(toSnap.gbpusd(), fromSnap.gbpusd()));
        delta.put("gbpeur", subtract(toSnap.gbpeur(), fromSnap.gbpeur()));
        long days = ChronoUnit.DAYS.between(
                LocalDate.parse(fromSnap.date()), LocalDate.parse(toSnap.date()));
        return Map.of("from", fromSnap, "to", toSnap, "delta", delta, "spanDays", days);
    }

    @GetMapping("/reconciliation")
    @ResponseBody
    public Report reconciliation() {
        return reconciliationService.report();
    }

    @GetMapping("/health")
    @ResponseBody
    public Health health() {
        return healthService.status();
    }

    private static SnapshotRepository.Snapshot findByDate(
            List<SnapshotRepository.Snapshot> snaps, String date) {
        for (var s : snaps) if (date.equals(s.date())) return s;
        return null;
    }

    private static BigDecimal subtract(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return null;
        return a.subtract(b);
    }
}
