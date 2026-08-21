package com.portfolio.web;

import com.portfolio.application.OpenOrdersService;
import com.portfolio.domain.VolForecast;
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

    /**
     * @param halfLife   mean-reversion half-life in sessions for the volatility blend (default 20).
     * @param gtcSessions horizon assumed for an order with no dated expiry (default 90). Both are
     *                    query params so the forecast can be swept without a rebuild — the same
     *                    convention the strategy tabs use for their knobs.
     */
    @GetMapping("/orders")
    public String orders(@RequestParam(required = false) Double halfLife,
                         @RequestParam(required = false) Integer gtcSessions,
                         Model model) {
        model.addAttribute("orders", openOrdersService.load(VolForecast.Config.of(halfLife, gtcSessions)));
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
     * <p>It takes the row's four realized-vol windows rather than a single σ because the horizon is
     * what the volatility forecast depends on: a new N re-blends current state toward the long-run
     * anchor rather than rescaling one number by √N.
     *
     * @param sessions trading sessions — the same unit P(5d) and the σ scaling use.
     */
    @GetMapping("/orders/touch")
    @ResponseBody
    public OpenOrdersService.TouchEstimate touch(@RequestParam BigDecimal current,
                                                 @RequestParam BigDecimal limit,
                                                 @RequestParam(required = false) BigDecimal rv10,
                                                 @RequestParam(required = false) BigDecimal rv20,
                                                 @RequestParam(required = false) BigDecimal rv30,
                                                 @RequestParam(required = false) BigDecimal rv250,
                                                 @RequestParam int sessions,
                                                 @RequestParam(required = false) Double halfLife) {
        return openOrdersService.touchEstimate(current, limit,
                new VolForecast.Inputs(rv10, rv20, rv30, rv250), sessions,
                VolForecast.Config.of(halfLife, null));
    }

    /**
     * Prices a limit order that hasn't been placed yet — the pre-trade half of the touch-probability
     * columns. Renders the same statistics the table shows for a resting order, for a symbol and a
     * limit the reader is still deciding on.
     *
     * <p>Every parameter is taken as text and parsed leniently rather than bound to a
     * {@code BigDecimal} / {@code int}: the form re-fires as the limit box is typed into, and a
     * half-entered value must render a panel saying what's missing, not a 400 that leaves the last
     * result on screen looking current.
     */
    @GetMapping("/orders/probe")
    public String probe(@RequestParam(required = false) String symbol,
                        @RequestParam(required = false) String limit,
                        @RequestParam(required = false) String side,
                        @RequestParam(required = false) String sessions,
                        Model model) {
        model.addAttribute("probe", openOrdersService.probe(
                symbol, decimalOrNull(limit), side, intOrNull(sessions), VolForecast.Config.DEFAULT));
        return "fragments/orders :: probe";
    }

    private static BigDecimal decimalOrNull(String s) {
        try {
            return (s == null || s.isBlank()) ? null : new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intOrNull(String s) {
        try {
            return (s == null || s.isBlank()) ? null : Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
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
