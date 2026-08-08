package com.portfolio.web;

import com.portfolio.application.EarningsCalendarReport;
import com.portfolio.application.EarningsCalendarService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Serves the Earnings tab (under Tools). {@code GET /earnings} returns the reporting calendar over a
 * {@code [today−back, today+forward]} window as JSON, rendered client-side. Thin — all work is in
 * {@link EarningsCalendarService}. The window and filter are query params so the UI controls map
 * straight through; the service clamps the window to a safe bound.
 */
@Controller
public class EarningsController {

    private final EarningsCalendarService earningsService;

    public EarningsController(EarningsCalendarService earningsService) {
        this.earningsService = earningsService;
    }

    /**
     * @param back    calendar days before today (default 2)
     * @param forward calendar days after today (default 3)
     * @param minCapB market-cap floor in <b>$ billions</b> for the per-day tables (0/absent = all) —
     *                the "major earnings" filter; never applied to the user's own holdings
     * @param refresh bypass cache freshness and re-pull even today's roster (the Refresh button)
     */
    @GetMapping("/earnings")
    @ResponseBody
    public EarningsCalendarReport earnings(
            @RequestParam(name = "back", defaultValue = "2") int back,
            @RequestParam(name = "forward", defaultValue = "3") int forward,
            @RequestParam(name = "minCapB", required = false) Double minCapB,
            @RequestParam(name = "refresh", defaultValue = "false") boolean refresh) {

        Long minMarketCap = (minCapB != null && minCapB > 0)
                ? Math.round(minCapB * 1_000_000_000d)
                : null;
        return earningsService.report(back, forward, minMarketCap, refresh);
    }
}
