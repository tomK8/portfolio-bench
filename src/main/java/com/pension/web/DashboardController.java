package com.pension.web;

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

    public DashboardController(SyncPortfolioService syncService) {
        this.syncService = syncService;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("appName", "Pension Aggregator");
        return "dashboard";
    }

    @PostMapping("/sync")
    public String sync(@RequestParam(name = "iiSippCash", required = false, defaultValue = "0") String iiSippCash,
                       Model model) {
        SyncResult result = syncService.sync(parseCash(iiSippCash));
        model.addAttribute("result", result);
        return "fragments/portfolio :: result";
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
