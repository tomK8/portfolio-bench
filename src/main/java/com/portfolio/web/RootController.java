package com.portfolio.web;

import com.portfolio.persistence.KeyValueStore;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.math.BigDecimal;

/**
 * Landing-page controller. Renders the Thymeleaf {@code dashboard} template populated with
 * the last II SIPP cash values typed into the form (so the inputs remember what the user
 * entered across reloads). All other behavior lives in the JSON/POST endpoints under
 * {@link OperationsController}, {@link ChartsController} and {@link AnalysisController}.
 */
@Controller
public class RootController {

    private final KeyValueStore settings;

    public RootController(KeyValueStore settings) {
        this.settings = settings;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("appName", "Portfolio Bench");
        model.addAttribute("iiSippCash",
                settings.getBigDecimal(WebSupport.II_SIPP_CASH_KEY, BigDecimal.ZERO).toPlainString());
        model.addAttribute("iiSippCashUsd",
                settings.getBigDecimal(WebSupport.II_SIPP_CASH_USD_KEY, BigDecimal.ZERO).toPlainString());
        return "dashboard";
    }

    @GetMapping("/reference")
    public String reference() {
        return "reference";
    }

    @GetMapping("/glossary")
    public String glossary() {
        return "glossary";
    }
}
