package com.portfolio.web;

import com.portfolio.application.OpenOrdersService;
import com.portfolio.application.SwitchService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.List;

/**
 * Serves the Share-class switches block of the Open orders tab — a holding moving from one listing
 * of a fund to another listing of the same fund.
 *
 * <p>Thin, and shaped exactly like {@link LaddersController}: every endpoint re-renders the
 * <em>same</em> fragment from a freshly loaded {@link OpenOrdersService#load()} rather than patching
 * what is on screen. A leg moving changes the progress, the cash lines, the comparison and which
 * step of the sequence is blocking, so the whole block is the unit that has to be redrawn; and
 * re-reading through the orders service is what keeps a leg's numbers identical to the table's,
 * since it is the same load that produced them.
 *
 * <p>Writes throw {@link IllegalStateException} and are rendered as the shared error fragment by
 * {@code GlobalExceptionAdvice} — the app-wide "write paths throw, reads degrade" split.
 */
@Controller
public class SwitchesController {

    private final SwitchService switches;
    private final OpenOrdersService openOrdersService;

    public SwitchesController(SwitchService switches, OpenOrdersService openOrdersService) {
        this.switches = switches;
        this.openOrdersService = openOrdersService;
    }

    @GetMapping("/switches")
    public String switches(Model model) {
        return render(model);
    }

    /**
     * Declare a switch.
     *
     * <p>Both listings are required, and declaring one is also the statement that they are the same
     * fund — which is what folds them into a single holding in the exposure views. Nothing infers
     * that from the tickers.
     *
     * @param from the line the holding is leaving
     * @param to   the line it is moving into
     */
    @PostMapping("/switches/create")
    public String create(@RequestParam String name, @RequestParam String from,
                         @RequestParam String to, Model model) {
        switches.createSwitch(name, from, to);
        return render(model);
    }

    @PostMapping("/switches/delete")
    public String delete(@RequestParam long id, Model model) {
        switches.deleteSwitch(id);
        return render(model);
    }

    /**
     * Assign orders to a switch. Which leg each lands on is read from its listing, never from the
     * user picking one — but which orders are in the switch at all is only ever the user's answer.
     */
    @PostMapping("/switches/legs/add")
    public String addLegs(@RequestParam long id,
                          @RequestParam(name = "orderKey", required = false) List<String> orderKeys,
                          Model model) {
        if (orderKeys != null && !orderKeys.isEmpty()) switches.addLegs(id, orderKeys);
        return render(model);
    }

    /** Take an order out of a switch. The order itself is untouched — this is broker truth. */
    @PostMapping("/switches/legs/remove")
    public String removeLeg(@RequestParam String orderKey, Model model) {
        switches.removeLeg(orderKey);
        return render(model);
    }

    /**
     * Record a leg decided on but not placed — on a switch that is usually the buy, and usually for
     * days, because the cash is in the wrong account and the wrong currency and no broker will chain
     * the two orders.
     *
     * <p>Quantity and price are taken as text and parsed leniently, as the ladder's are: a half-typed
     * number must come back as "give it a quantity", not a 400.
     */
    @PostMapping("/switches/intents/add")
    public String addIntent(@RequestParam long id,
                            @RequestParam String account,
                            @RequestParam String side,
                            @RequestParam(required = false) String quantity,
                            @RequestParam(required = false) String price,
                            @RequestParam(required = false) String note,
                            Model model) {
        switches.addIntent(id, account, side, decimalOrNull(quantity), decimalOrNull(price), note);
        return render(model);
    }

    @PostMapping("/switches/intents/delete")
    public String deleteIntent(@RequestParam long intentId, Model model) {
        switches.deleteIntent(intentId);
        return render(model);
    }

    /**
     * The user says an intended leg has been placed, and names the order it became — the only path
     * from intended to real. A feed match would be easy and would sometimes be wrong, and being
     * wrong here attaches one decision's reasoning to a different order.
     */
    @PostMapping("/switches/intents/placed")
    public String markPlaced(@RequestParam long intentId, @RequestParam String orderKey,
                             Model model) {
        switches.markPlaced(intentId, orderKey);
        // Targets the whole panel rather than this block, for the reason the ladder's does: linking
        // can move the intent's note onto the order, and that note is a cell of the table below.
        model.addAttribute("orders", openOrdersService.load());
        model.addAttribute("completedAt", WebSupport.now());
        return "fragments/orders :: result";
    }

    /** One load, one fragment — the block always redraws from the orders service's own report. */
    private String render(Model model) {
        model.addAttribute("orders", openOrdersService.load());
        model.addAttribute("completedAt", WebSupport.now());
        return "fragments/switches :: list";
    }

    private static BigDecimal decimalOrNull(String s) {
        try {
            return (s == null || s.isBlank()) ? null : new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
