package com.pension.application;

import com.pension.PortfolioDatabase;
import com.pension.domain.PortfolioAggregator;
import com.pension.domain.PortfolioMetrics;
import com.pension.domain.model.AggHolding;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    public SyncPortfolioService(PortfolioGatherer gatherer, PortfolioDatabase db) {
        this.gatherer = gatherer;
        this.db = db;
    }

    public SyncResult sync(BigDecimal iiSippCash) {
        GatheredPortfolio gathered = gatherer.gather();

        if (gathered.holdings().isEmpty()) {
            return SyncResult.empty(gathered.rates());
        }

        List<AggHolding> aggregated =
                new PortfolioAggregator().aggregate(gathered.holdings(), gathered.rates());
        Map<String, BigDecimal> dividendsBySymbol = db.loadDividendsBySymbol();
        PortfolioMetrics.Totals totals = new PortfolioMetrics().compute(aggregated, iiSippCash);

        db.saveSnapshot(totals.totalGbp(), totals.totalGainGbp(), totals.totalCashGbp(),
                totals.returnPct(), totals.totalReturn(), gathered.rates());

        List<String> sources = new ArrayList<>(gathered.sources().keySet());
        return new SyncResult(aggregated, totals, gathered.rates(), dividendsBySymbol,
                iiSippCash, sources, false);
    }
}
