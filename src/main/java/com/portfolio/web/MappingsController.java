package com.portfolio.web;

import com.portfolio.adapter.YahooPriceFetcher;
import com.portfolio.application.InstrumentMappingService;
import com.portfolio.application.InstrumentMappingService.MappingsView;
import com.portfolio.application.InstrumentMappingService.SaveResult;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Instrument mappings, added from the dashboard where the gap shows up rather than from a source
 * edit — see {@link InstrumentMappingService}.
 *
 * <p>JSON in / JSON out (the Open orders panel drives it with {@code fetch}, not htmx). Validation
 * failures are {@link IllegalArgumentException} → 400 plain text via {@link GlobalExceptionAdvice},
 * which is what the client shows next to the row.
 */
@Controller
public class MappingsController {

    private final InstrumentMappingService mappings;

    public MappingsController(InstrumentMappingService mappings) {
        this.mappings = mappings;
    }

    /** Every mapping: the user's rows plus the committed baseline, for the panel's mapping list. */
    @GetMapping("/mappings")
    @ResponseBody
    public MappingsView list() {
        return mappings.view();
    }

    /** Live check of a Yahoo ticker — writes nothing. */
    @PostMapping("/mappings/verify")
    @ResponseBody
    public YahooPriceFetcher.TickerProbe verify(@RequestParam String ticker) {
        return mappings.probe(ticker);
    }

    /**
     * Save whatever the row supplies: a ticker mapping, a name mapping, or (the new-instrument case)
     * both. The ticker is written first and its probe can veto the whole save, so a rejected ticker
     * never leaves a half-applied pair behind.
     */
    @PostMapping("/mappings/save")
    @ResponseBody
    public SaveResult save(@RequestParam String symbol,
                           @RequestParam(required = false) String ticker,
                           @RequestParam(required = false) String keyword,
                           @RequestParam(required = false) String rawName,
                           @RequestParam(required = false, defaultValue = "false") boolean force) {
        SaveResult tickerResult = null;
        if (ticker != null && !ticker.isBlank()) {
            tickerResult = mappings.saveTicker(symbol, ticker, force);
            if (!tickerResult.ok()) return tickerResult;
        }
        if (keyword == null || keyword.isBlank()) {
            if (tickerResult != null) return tickerResult;
            throw new IllegalArgumentException("Please enter a Yahoo ticker, some match text, or both.");
        }

        SaveResult aliasResult = mappings.saveAlias(keyword, symbol, rawName);
        String message = tickerResult == null
                ? aliasResult.message()
                : aliasResult.message() + " " + tickerResult.message();
        return new SaveResult(true, message, aliasResult.ledgerRowsUpdated(),
                tickerResult == null ? null : tickerResult.probe());
    }

    /** Remove a user mapping. Exactly one of {@code symbol} / {@code keyword} identifies it. */
    @PostMapping("/mappings/delete")
    @ResponseBody
    public MappingsView delete(@RequestParam(required = false) String symbol,
                               @RequestParam(required = false) String keyword) {
        if (symbol != null && !symbol.isBlank()) mappings.deleteTicker(symbol);
        else if (keyword != null && !keyword.isBlank()) mappings.deleteAlias(keyword);
        else throw new IllegalArgumentException("Nothing to delete — no symbol or match text given.");
        return mappings.view();
    }
}
