package com.portfolio.web;

import com.portfolio.application.OpenOrdersService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.math.BigDecimal;

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
     * Re-prices the {@code P(N)} column's touch probability over a caller-supplied horizon, for
     * the editable sessions box on each row.
     *
     * <p>Takes the row's own numbers rather than a symbol because that keeps it a pure calculation:
     * no re-parse of the broker exports, no FX fetch, no database read — the panel already rendered
     * the current price, the limit and the base vol, and the horizon is the only thing changing.
     * Doing the arithmetic here rather than in the browser is what keeps this column and the fixed
     * 5d/20d ones on one implementation of the first-passage model.
     *
     * @param sessions trading sessions — the same unit P(5d)/P(20d) and the σ scaling use.
     */
    @GetMapping("/orders/touch")
    @ResponseBody
    public OpenOrdersService.TouchEstimate touch(@RequestParam BigDecimal current,
                                                 @RequestParam BigDecimal limit,
                                                 @RequestParam BigDecimal vol,
                                                 @RequestParam int sessions) {
        return openOrdersService.touchEstimate(current, limit, vol, sessions);
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
