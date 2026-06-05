package com.portfolio.web;

import com.portfolio.application.ExportExcelService;
import com.portfolio.application.ImportCashService;
import com.portfolio.application.ImportGiltPricesService;
import com.portfolio.application.SyncPortfolioService;
import com.portfolio.persistence.KeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

@Controller
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    /** KV key for the last II SIPP cash balance entered on the dashboard. */
    static final String II_SIPP_CASH_KEY = "ii_sipp_cash_last";

    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final SyncPortfolioService syncService;
    private final ExportExcelService exportService;
    private final ImportCashService importCashService;
    private final ImportGiltPricesService importGiltPricesService;
    private final KeyValueStore settings;

    public DashboardController(SyncPortfolioService syncService,
                               ExportExcelService exportService,
                               ImportCashService importCashService,
                               ImportGiltPricesService importGiltPricesService,
                               KeyValueStore settings) {
        this.syncService = syncService;
        this.exportService = exportService;
        this.importCashService = importCashService;
        this.importGiltPricesService = importGiltPricesService;
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
        return "dashboard";
    }

    /**
     * Parses the II SIPP cash form field and persists the value, returning the parsed amount.
     * Shared by every action that takes the form parameter (sync, export).
     */
    private BigDecimal persistIiSippCash(String raw) {
        BigDecimal cash = parseCash(raw);
        settings.putBigDecimal(II_SIPP_CASH_KEY, cash);
        return cash;
    }

    @PostMapping("/sync")
    public String sync(@RequestParam(name = "iiSippCash", required = false, defaultValue = "0") String iiSippCash,
                       Model model) {
        model.addAttribute("result", syncService.sync(persistIiSippCash(iiSippCash)));
        model.addAttribute("completedAt", now());
        return "fragments/portfolio :: result";
    }

    @PostMapping("/export")
    public String export(@RequestParam(name = "iiSippCash", required = false, defaultValue = "0") String iiSippCash,
                         Model model) {
        model.addAttribute("export", exportService.export(persistIiSippCash(iiSippCash)));
        model.addAttribute("completedAt", now());
        return "fragments/export :: result";
    }

    @PostMapping("/import-cash")
    public String importCash(Model model) {
        model.addAttribute("cashImports", importCashService.importCash());
        model.addAttribute("completedAt", now());
        return "fragments/import :: result";
    }

    @PostMapping("/import-gilt-prices")
    public String importGiltPrices(Model model) {
        model.addAttribute("giltImports", importGiltPricesService.importAll());
        model.addAttribute("completedAt", now());
        return "fragments/import-gilt-prices :: result";
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
