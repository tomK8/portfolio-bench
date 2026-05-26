package com.pension.application;

import com.pension.PortfolioDatabase;
import com.pension.adapter.HoldingFileLocator;
import com.pension.domain.PortfolioAggregator;
import com.pension.domain.PortfolioMetrics;
import com.pension.domain.model.AggHolding;
import com.pension.domain.model.Holding;
import com.pension.parser.AJBellSippParser;
import com.pension.parser.AccountParser;
import com.pension.parser.IISippParser;
import com.pension.parser.ParseException;
import com.pension.parser.RothIraParser;
import com.pension.port.FxRateProvider;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * On-demand equivalent of the original batch run, minus the Swing prompts, Excel
 * export and cash-statement import (those are separate operations). Fetches FX
 * rates, parses the latest holding files, aggregates, records a snapshot and
 * returns the aggregated view.
 *
 * <p>Framework-agnostic: collaborators (FX, persistence, file location) are passed
 * in; pure domain calculators are instantiated directly. Spring wiring lives in
 * the config layer, not here.
 */
public class SyncPortfolioService {

    private final FxRateProvider fxRateProvider;
    private final PortfolioDatabase db;
    private final HoldingFileLocator fileLocator;

    public SyncPortfolioService(FxRateProvider fxRateProvider,
                                PortfolioDatabase db,
                                HoldingFileLocator fileLocator) {
        this.fxRateProvider = fxRateProvider;
        this.db = db;
        this.fileLocator = fileLocator;
    }

    public SyncResult sync(BigDecimal iiSippCash) {
        Map<String, BigDecimal> rates;
        try {
            rates = fxRateProvider.fetchRates();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch FX rates", e);
        }

        // AJBellSippParser needs the live rates, so parsers are built per run.
        List<AccountParser> parsers = List.of(
                new RothIraParser(),
                new AJBellSippParser(rates),
                new IISippParser());

        List<Holding> holdings = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        try {
            for (AccountParser parser : parsers) {
                Optional<Path> file = fileLocator.findMostRecent(parser);
                if (file.isPresent()) {
                    holdings.addAll(parser.parse(file.get()));
                    sources.add(parser.sourceName());
                }
            }
        } catch (IOException | ParseException e) {
            throw new IllegalStateException("Failed to read or parse input files", e);
        }

        if (holdings.isEmpty()) {
            return SyncResult.empty(rates);
        }

        List<AggHolding> aggregated = new PortfolioAggregator().aggregate(holdings, rates);
        Map<String, BigDecimal> dividendsBySymbol = db.loadDividendsBySymbol();
        PortfolioMetrics.Totals totals = new PortfolioMetrics().compute(aggregated, iiSippCash);

        db.saveSnapshot(totals.totalGbp(), totals.totalGainGbp(), totals.totalCashGbp(),
                totals.returnPct(), totals.totalReturn(), rates);

        return new SyncResult(aggregated, totals, rates, dividendsBySymbol, iiSippCash, sources, false);
    }
}
