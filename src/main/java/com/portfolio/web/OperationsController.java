package com.portfolio.web;

import com.portfolio.application.ExportExcelService;
import com.portfolio.application.ImportCashService;
import com.portfolio.application.ImportGiltPricesService;
import com.portfolio.application.LedgerGroupingService;
import com.portfolio.application.PriceFetchJob;
import com.portfolio.application.SyncFromCashService;
import com.portfolio.application.SyncPortfolioService;
import com.portfolio.application.SyncResult;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Mutating endpoints: the buttons on the dashboard that change persisted state — sync,
 * export, import (cash + gilts), manual price entry, full-history rebuild, and the
 * single-symbol benchmark backfill triggered from the Returns tab.
 */
@Controller
public class OperationsController {

    private static final Logger log = LoggerFactory.getLogger(OperationsController.class);

    private final SyncPortfolioService syncService;
    private final SyncFromCashService syncFromCashService;
    private final ExportExcelService exportService;
    private final ImportCashService importCashService;
    private final ImportGiltPricesService importGiltPricesService;
    private final PriceFetchJob priceFetchJob;
    private final PriceHistoryRepository priceHistoryRepository;
    private final KeyValueStore settings;
    private final LedgerGroupingService groupingService;

    public OperationsController(SyncPortfolioService syncService,
                                SyncFromCashService syncFromCashService,
                                ExportExcelService exportService,
                                ImportCashService importCashService,
                                ImportGiltPricesService importGiltPricesService,
                                PriceFetchJob priceFetchJob,
                                PriceHistoryRepository priceHistoryRepository,
                                KeyValueStore settings,
                                LedgerGroupingService groupingService) {
        this.syncService = syncService;
        this.syncFromCashService = syncFromCashService;
        this.exportService = exportService;
        this.importCashService = importCashService;
        this.importGiltPricesService = importGiltPricesService;
        this.priceFetchJob = priceFetchJob;
        this.priceHistoryRepository = priceHistoryRepository;
        this.settings = settings;
        this.groupingService = groupingService;
    }

    @PostMapping("/sync")
    public String sync(@RequestParam(name = "iiSippCash", required = false, defaultValue = "0") String iiSippCash,
                       @RequestParam(name = "iiSippCashUsd", required = false, defaultValue = "0") String iiSippCashUsd,
                       Model model) {
        model.addAttribute("result", syncService.sync(
                WebSupport.persistIiSippCash(settings, iiSippCash),
                WebSupport.persistIiSippCashUsd(settings, iiSippCashUsd)));
        model.addAttribute("completedAt", WebSupport.now());
        return "fragments/portfolio :: result";
    }

    /**
     * The cash-ledger view, optionally collapsed into groups. {@code groupBy} names a
     * {@link LedgerGroupingService.GroupBy} option; an absent or unknown value renders the flat
     * table, so a bookmarked URL or a hand-edited request can never produce an error page.
     *
     * <p>Changing the dropdown re-runs {@code sync()} rather than regrouping in the browser,
     * which is what keeps {@code Σgain ÷ Σcost} in one implementation instead of a second copy in
     * {@code dashboard.js}. It is cheap: FX is TTL-cached behind {@code CachingFxRateProvider}
     * and everything else the sync touches is local SQLite.
     */
    @PostMapping("/sync-from-cash")
    public String syncFromCash(@RequestParam(name = "groupBy", required = false) String groupBy,
                               Model model) {
        SyncResult result = syncFromCashService.sync();
        LedgerGroupingService.GroupBy by = groupingService.parse(groupBy);
        model.addAttribute("result", result);
        model.addAttribute("groups", groupingService.group(result.holdings(), by));
        model.addAttribute("groupBy", by.id());
        model.addAttribute("groupOptions", groupingService.options());
        model.addAttribute("completedAt", WebSupport.now());
        return "fragments/portfolio-ledger :: result";
    }

    /**
     * Writes the cash-ledger view to Excel. Re-runs the sync rather than exporting whatever the
     * panel last rendered, so the file is current — the same bargain the Open-orders export makes
     * by re-parsing.
     */
    @PostMapping("/sync-from-cash/export")
    public String exportFromCash(Model model) {
        model.addAttribute("export", exportService.exportLedger(syncFromCashService.sync()));
        model.addAttribute("completedAt", WebSupport.now());
        return "fragments/export :: result";
    }

    @PostMapping("/export")
    public String export(@RequestParam(name = "iiSippCash", required = false, defaultValue = "0") String iiSippCash,
                         @RequestParam(name = "iiSippCashUsd", required = false, defaultValue = "0") String iiSippCashUsd,
                         Model model) {
        model.addAttribute("export", exportService.export(
                WebSupport.persistIiSippCash(settings, iiSippCash),
                WebSupport.persistIiSippCashUsd(settings, iiSippCashUsd)));
        model.addAttribute("completedAt", WebSupport.now());
        return "fragments/export :: result";
    }

    @PostMapping("/import-cash")
    public String importCash(Model model) {
        model.addAttribute("cashImports", importCashService.importCash());
        model.addAttribute("completedAt", WebSupport.now());
        return "fragments/import :: result";
    }

    @PostMapping("/import-gilt-prices")
    public String importGiltPrices(Model model) {
        model.addAttribute("giltImports", importGiltPricesService.importAll());
        model.addAttribute("completedAt", WebSupport.now());
        return "fragments/import-gilt-prices :: result";
    }

    /**
     * Re-fetches the full 30-year window for every traded symbol and re-derives
     * {@code adj_close} from Yahoo's dividend + split events. Long-running (~110 tickers ×
     * throttle ≈ 1–2 min), so the UI shows a spinner.
     */
    @PostMapping("/rebuild-prices")
    public String rebuildPrices(Model model) {
        int refreshed = priceFetchJob.runFullRebuild();
        model.addAttribute("rebuildCount", refreshed);
        model.addAttribute("completedAt", WebSupport.now());
        return "fragments/rebuild-prices :: result";
    }

    /**
     * Insert (or upsert via {@link PriceHistoryRepository#upsertPriceBars}) a single
     * manually-entered price_history row. For UCITS or delisted names Yahoo doesn't cover —
     * saves the user from hand-editing the SQLite file. open/high/low default to the close
     * so OHLC charts still render. {@code adjClose} = close (no dividend adjustment),
     * splitFactor = 1.0.
     */
    @PostMapping("/prices/manual")
    @ResponseBody
    public Map<String, Object> manualPrice(@RequestParam("symbol") String symbolRaw,
                                           @RequestParam("date") String dateRaw,
                                           @RequestParam("close") String closeRaw,
                                           @RequestParam(name = "currency", defaultValue = "GBP") String currency) {
        String symbol = symbolRaw == null ? "" : symbolRaw.trim().toUpperCase();
        if (symbol.isEmpty()) throw new IllegalArgumentException("symbol is required");
        LocalDate date;
        try {
            date = LocalDate.parse(dateRaw);
        } catch (Exception e) {
            throw new IllegalArgumentException("date must be YYYY-MM-DD");
        }
        double close;
        try {
            close = Double.parseDouble(closeRaw.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("close must be a number");
        }
        if (close <= 0) throw new IllegalArgumentException("close must be positive");

        PriceBar bar = new PriceBar(symbol, date, close, close, close, close, close, 1.0, null, currency);
        int rows = priceHistoryRepository.upsertPriceBars(List.of(bar));
        log.info("Manual price entry: {} {} {} {} → {} rows", symbol, date, close, currency, rows);
        return Map.of("symbol", symbol, "date", date.toString(), "rowsAffected", rows);
    }

    /**
     * One-shot Yahoo backfill for a benchmark ticker the user typed in. Reuses
     * {@link PriceFetchJob#fetchSingle} — same 30-year window, same {@code adj_close}
     * re-derivation. Returns the number of {@code price_history} rows written.
     */
    @PostMapping("/returns/benchmark/fetch")
    @ResponseBody
    public Map<String, Object> fetchBenchmark(@RequestParam("symbol") String symbol) {
        String sym = symbol == null ? "" : symbol.trim().toUpperCase();
        int rows = sym.isEmpty() ? 0 : priceFetchJob.fetchSingle(sym);
        log.info("Benchmark backfill: {} → {} rows", sym, rows);
        return Map.of("symbol", sym, "rows", rows);
    }
}
