package com.portfolio.web;

import com.portfolio.application.OpenOrdersService;
import com.portfolio.application.IntentEdits;
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
     * <p>The direction the pair is declared in no longer decides which side an order lands on —
     * either listing takes sells and buys. It does one job: {@code from} is the name the holding is
     * counted under in Allocation, Concentration and Currency, because it is the listing carrying
     * the price history, the ticker mapping and the classification already.
     *
     * @param from the line the holding is leaving, and the name its holding keeps
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
     *
     * <p>The listing is its own field rather than being read off the side. Either line of the pair
     * can carry either side now, so "Sell" no longer names a listing, and a leg priced against the
     * wrong line would be a sterling limit judged against a dollar quote. Optional, and a blank one
     * falls back to the one-way reading, so an older form still posts something valid.
     */
    @PostMapping("/switches/intents/add")
    public String addIntent(@RequestParam long id,
                            @RequestParam String account,
                            @RequestParam(required = false) String symbol,
                            @RequestParam String side,
                            @RequestParam(required = false) String quantity,
                            @RequestParam(required = false) String price,
                            @RequestParam(required = false) String note,
                            Model model) {
        switches.addIntent(id, account, symbol, side, decimalOrNull(quantity),
                decimalOrNull(price), note);
        return render(model);
    }

    @PostMapping("/switches/intents/delete")
    public String deleteIntent(@RequestParam long intentId, Model model) {
        switches.deleteIntent(intentId);
        return render(model);
    }

    /**
     * Re-price or re-size the intended legs, from the figures as they now stand on screen — the
     * switch's half of {@link LaddersController#updateIntents}, and the same rule: an intended leg
     * is the user's own record and is editable, a leg that came from a broker's CSV is not.
     *
     * <p>It is worth more here than on a ladder. The buy leg is an intent for days by construction,
     * so it is the leg most likely to need moving as the market does; and both limits are chosen
     * against each other rather than against one market — the pair's price ratio <em>is</em> the
     * exchange rate — so moving one and reading what it does to the cost, the implied cross and the
     * shortfall is the only way to settle them.
     */
    @PostMapping("/switches/intents/update")
    public String updateIntents(@RequestParam(name = "editKey", required = false) List<String> keys,
                                @RequestParam(name = "editPrice", required = false) List<String> prices,
                                @RequestParam(name = "editQty", required = false) List<String> quantities,
                                Model model) {
        for (IntentEdits.Typed t : IntentEdits.of(keys, prices, quantities).all()) {
            switches.updateIntent(t.intentId(), t.quantity(), t.price());
        }
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

    /**
     * Writes the whole Share-class switches block to a date-stamped workbook in the output
     * directory, and renders the shared export fragment naming the file. Re-derives on the press,
     * like every other export; {@code emptyNote} replaces the fragment's default sentence about
     * missing input files, which a switch does not need — the buy leg is usually an intent.
     */
    @PostMapping("/switches/export")
    public String export(Model model) {
        model.addAttribute("export", openOrdersService.exportSwitches());
        model.addAttribute("emptyNote", "Nothing to export — no switches declared yet.");
        model.addAttribute("completedAt", WebSupport.now());
        return "fragments/export :: result";
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
