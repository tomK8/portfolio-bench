package com.portfolio.application;

import com.portfolio.PortfolioDatabase;
import com.portfolio.domain.PortfolioAggregator;
import com.portfolio.domain.PortfolioMetrics;
import com.portfolio.domain.model.AggHolding;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * On-demand equivalent of the original batch run, minus the Swing prompts, Excel
 * export and cash-statement import (those are separate operations). Gathers the
 * latest holdings, aggregates, records a snapshot and returns the aggregated view.
 *
 * <p>Framework-agnostic: collaborators are passed in; pure domain calculators are
 * instantiated directly. Spring wiring lives in the config layer, not here.
 */
public class SyncPortfolioService {

    private final PortfolioGatherer gatherer;
    private final PortfolioDatabase db;
    private final DividendService dividendService;

    public SyncPortfolioService(PortfolioGatherer gatherer, PortfolioDatabase db,
                                DividendService dividendService) {
        this.gatherer = gatherer;
        this.db = db;
        this.dividendService = dividendService;
    }

    public SyncResult sync(BigDecimal iiSippCash) {
        GatheredPortfolio gathered = gatherer.gather();

        if (gathered.holdings().isEmpty()) {
            return SyncResult.empty(gathered.rates());
        }

        List<AggHolding> aggregated = new PortfolioAggregator().aggregate(
                gathered.holdings(), gathered.rates(), dividendService.dividendsBySymbol(gathered.holdings()));
        PortfolioMetrics.Totals totals = new PortfolioMetrics().compute(aggregated, iiSippCash);

        db.saveSnapshot(totals.totalGbp(), totals.totalGainGbp(), totals.totalCashGbp(),
                totals.returnPct(), totals.totalReturn(), gathered.rates());

        List<String> sources = new ArrayList<>(gathered.sources().keySet());
        return new SyncResult(aggregated, totals, gathered.rates(), iiSippCash, sources, false);
    }
}
