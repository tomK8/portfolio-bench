package com.portfolio.web;

import com.portfolio.application.LadderService;
import com.portfolio.application.OpenOrdersService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.List;

/**
 * Serves the Ladders block of the Open orders tab — the groups of rungs the user has decided belong
 * together, including the rungs they have decided on but not placed.
 *
 * <p>Every endpoint here renders the <em>same</em> fragment from a freshly loaded
 * {@link OpenOrdersService#load()}, rather than each mutation patching what is on screen. A rung
 * moving changes the ladder's net position, its cash lines and where the marker sits, so the whole
 * block is the unit that has to be redrawn; and re-reading through the orders service is what keeps
 * the rungs' analytics identical to the table's, since it is the same load that produced them.
 *
 * <p>Thin, like {@link OrdersController}: it parses request text and delegates. Writes throw
 * {@link IllegalStateException} and are rendered as the shared error fragment by
 * {@code GlobalExceptionAdvice} — the app-wide "write paths throw, reads degrade" split.
 */
@Controller
public class LaddersController {

    private final LadderService ladders;
    private final OpenOrdersService openOrdersService;

    public LaddersController(LadderService ladders, OpenOrdersService openOrdersService) {
        this.ladders = ladders;
        this.openOrdersService = openOrdersService;
    }

    @GetMapping("/ladders")
    public String ladders(Model model) {
        return render(model);
    }

    /**
     * @param symbol the one security this ladder's rungs trade. Required: a ladder with no security
     *               has no price axis to lay rungs on and nothing to offer as a rung.
     */
    @PostMapping("/ladders/create")
    public String create(@RequestParam String name, @RequestParam String symbol, Model model) {
        ladders.createLadder(name, symbol);
        return render(model);
    }

    @PostMapping("/ladders/delete")
    public String delete(@RequestParam long id, Model model) {
        ladders.deleteLadder(id);
        return render(model);
    }

    /**
     * Assign open orders to a ladder. Membership is only ever created here, by the user naming the
     * orders — nothing in the pipeline infers it from symbol, date or price.
     */
    @PostMapping("/ladders/rungs/add")
    public String addRungs(@RequestParam long id,
                           @RequestParam(name = "orderKey", required = false) List<String> orderKeys,
                           Model model) {
        if (orderKeys != null && !orderKeys.isEmpty()) ladders.addRungs(id, orderKeys);
        return render(model);
    }

    /** Take an order out of a ladder. The order itself is untouched — this is broker truth. */
    @PostMapping("/ladders/rungs/remove")
    public String removeRung(@RequestParam String orderKey, Model model) {
        ladders.removeRung(orderKey);
        return render(model);
    }

    /**
     * Record a rung decided on but not placed — the cash isn't in that account yet, or the broker
     * won't take a limit on the instrument. It exists only in this panel and never reaches the
     * orders table.
     *
     * <p>Quantity and price are taken as text and parsed leniently for the same reason the probe's
     * are: a half-typed number must come back as "give it a quantity", not a 400.
     */
    @PostMapping("/ladders/intents/add")
    public String addIntent(@RequestParam long id,
                            @RequestParam String account,
                            @RequestParam String side,
                            @RequestParam(required = false) String quantity,
                            @RequestParam(required = false) String price,
                            @RequestParam(required = false) String note,
                            Model model) {
        ladders.addIntent(id, account, side, decimalOrNull(quantity), decimalOrNull(price), note);
        return render(model);
    }

    @PostMapping("/ladders/intents/delete")
    public String deleteIntent(@RequestParam long intentId, Model model) {
        ladders.deleteIntent(intentId);
        return render(model);
    }

    /**
     * The user says an intended rung has been placed, and names the order it became.
     *
     * <p>The only path from intended to real, and it is a button rather than a rule. A feed match —
     * same symbol, same side, near enough the same price — would be easy and would sometimes be
     * wrong, and being wrong here means the reasoning written against one decision ends up attached
     * to a different order.
     */
    @PostMapping("/ladders/intents/placed")
    public String markPlaced(@RequestParam long intentId, @RequestParam String orderKey,
                             Model model) {
        ladders.markPlaced(intentId, orderKey);
        // The one mutation that redraws the whole panel rather than the block. Linking can move the
        // intent's note onto the order, and that note is a cell of the table below — leaving the
        // table on the render from before the link would show an empty Note beside a rung that says
        // the note moved there.
        model.addAttribute("orders", openOrdersService.load());
        model.addAttribute("completedAt", WebSupport.now());
        return "fragments/orders :: result";
    }

    /**
     * One load, one fragment. The block always redraws from the orders service's own report, so the
     * rungs on screen are the rows the table would show, priced by the pass that priced them.
     */
    private String render(Model model) {
        model.addAttribute("orders", openOrdersService.load());
        model.addAttribute("completedAt", WebSupport.now());
        return "fragments/ladders :: list";
    }

    private static BigDecimal decimalOrNull(String s) {
        try {
            return (s == null || s.isBlank()) ? null : new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
