package com.portfolio.web;

import com.portfolio.application.AssetTransferService;
import com.portfolio.domain.model.Account;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * In-specie transfers between accounts — the same shares moving broker to broker, with no sale,
 * no purchase and no cash. A pure ledger write, independent of the price and import pipelines.
 *
 * <p>No background price warm here, unlike {@link ManualPositionsController}: a transferred symbol
 * was already held somewhere in the portfolio a moment ago, so it is already in every fetch
 * universe and already has a price. Moving it between accounts cannot make it new.
 */
@RestController
public class TransfersController {

    private final AssetTransferService service;

    public TransfersController(AssetTransferService service) {
        this.service = service;
    }

    @GetMapping("/transfers")
    public AssetTransferService.TransferReport view() {
        return service.report();
    }

    /**
     * Records a transfer. A bad request — same account both ends, or a source that does not hold
     * the position — comes back as {@code ok:false} with the reason, which the screen shows beside
     * the form; only an unexpected failure is left to the global handler.
     */
    @PostMapping("/transfers/record")
    public Map<String, Object> record(@RequestParam String date,
                                      @RequestParam String from,
                                      @RequestParam String to,
                                      @RequestParam String symbol) {
        try {
            AssetTransferService.Result r = service.transfer(
                    LocalDate.parse(date), Account.fromDbValue(from), Account.fromDbValue(to), symbol);
            return Map.of("ok", true,
                    "message", "Moved " + r.quantity().stripTrailingZeros().toPlainString() + " "
                            + r.symbol() + " from " + r.fromLabel() + " to " + r.toLabel()
                            + " on " + r.date() + ", carrying £" + r.costGbp() + " of cost basis.");
        } catch (IllegalArgumentException e) {
            return Map.of("ok", false, "message", e.getMessage());
        } catch (IllegalStateException e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
    }

    @PostMapping("/transfers/delete")
    public Map<String, Object> delete(@RequestParam long outRowid, @RequestParam long inRowid) {
        boolean deleted = service.delete(outRowid, inRowid);
        return Map.of("ok", deleted, "message",
                deleted ? "Transfer removed — the position is back where it was."
                        : "Could not remove both legs; nothing was changed.");
    }
}
