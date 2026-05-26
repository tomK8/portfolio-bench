package com.pension.web;

import com.pension.PortfolioDatabase;
import com.pension.application.ExportExcelService;
import com.pension.application.ExportResult;
import com.pension.application.ImportCashResult;
import com.pension.application.ImportCashService;
import com.pension.application.SyncPortfolioService;
import com.pension.application.SyncResult;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

@Controller
public class DashboardController {

    private final SyncPortfolioService syncService;
    private final ExportExcelService exportService;
    private final ImportCashService importCashService;
    private final PortfolioDatabase db;

    public DashboardController(SyncPortfolioService syncService,
                               ExportExcelService exportService,
                               ImportCashService importCashService,
                               PortfolioDatabase db) {
        this.syncService = syncService;
        this.exportService = exportService;
        this.importCashService = importCashService;
        this.db = db;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("appName", "Pension Aggregator");
        model.addAttribute("iiSippCash", db.loadLastIiSippCash().toPlainString());
        return "dashboard";
    }

    @PostMapping("/sync")
    public String sync(@RequestParam(name = "iiSippCash", required = false, defaultValue = "0") String iiSippCash,
                       Model model) {
        BigDecimal cash = parseCash(iiSippCash);
        db.saveLastIiSippCash(cash);
        model.addAttribute("result", syncService.sync(cash));
        return "fragments/portfolio :: result";
    }

    @PostMapping("/export")
    public String export(@RequestParam(name = "iiSippCash", required = false, defaultValue = "0") String iiSippCash,
                         Model model) {
        BigDecimal cash = parseCash(iiSippCash);
        db.saveLastIiSippCash(cash);
        model.addAttribute("export", exportService.export(cash));
        return "fragments/export :: result";
    }

    @PostMapping("/import-cash")
    public String importCash(Model model) {
        ImportCashResult result = importCashService.importCash();
        model.addAttribute("cashImport", result);
        return "fragments/import :: result";
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
}
