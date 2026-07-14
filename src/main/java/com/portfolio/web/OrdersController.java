package com.portfolio.web;

import com.portfolio.application.OpenOrdersService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * Serves the Open orders tab — a read-only table of resting orders parsed from the latest broker
 * order-status exports, plus an Excel export of the same. Thin, like the sync endpoints: it renders
 * htmx fragments and delegates all work to {@link OpenOrdersService}.
 */
@Controller
public class OrdersController {

    private final OpenOrdersService openOrdersService;

    public OrdersController(OpenOrdersService openOrdersService) {
        this.openOrdersService = openOrdersService;
    }

    @GetMapping("/orders")
    public String orders(Model model) {
        model.addAttribute("orders", openOrdersService.load());
        model.addAttribute("completedAt", WebSupport.now());
        return "fragments/orders :: result";
    }

    /**
     * Writes the current open orders to a date-stamped .xlsx in the output directory (~/Documents),
     * alongside the portfolio exports — not the browser's download folder. Reuses the shared export
     * result fragment to report the written path.
     */
    @PostMapping("/orders/export")
    public String exportOrders(Model model) {
        model.addAttribute("export", openOrdersService.export());
        model.addAttribute("completedAt", WebSupport.now());
        return "fragments/export :: result";
    }
}
