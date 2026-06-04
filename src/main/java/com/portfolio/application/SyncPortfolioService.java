package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.PortfolioAggregator;
import com.portfolio.domain.PortfolioMetrics;
import com.portfolio.domain.model.AggHolding;
import com.portfolio.domain.model.Holding;
import com.portfolio.domain.model.IntradayPrice;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.persistence.SnapshotRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gathers the latest holdings, aggregates, records a snapshot and returns the aggregated view.
 *
 * <p>Framework-agnostic: collaborators are passed in; pure domain calculators are
 * instantiated directly. Spring wiring lives in the config layer, not here.
 */
public class SyncPortfolioService {

    private final PortfolioGatherer gatherer;
    private final SnapshotRepository snapshots;
    private final IntradayPriceRepository intraday;
    private final DividendService dividendService;
    private final YahooTickerMap tickerMap;

    public SyncPortfolioService(PortfolioGatherer gatherer,
                                SnapshotRepository snapshots,
                                IntradayPriceRepository intraday,
                                DividendService dividendService,
                                YahooTickerMap tickerMap) {
        this.gatherer = gatherer;
        this.snapshots = snapshots;
        this.intraday = intraday;
        this.dividendService = dividendService;
        this.tickerMap = tickerMap;
    }

    public SyncResult sync(BigDecimal iiSippCash) {
        GatheredPortfolio gathered = gatherer.gather();

        if (gathered.holdings().isEmpty()) {
            return SyncResult.empty(gathered.rates());
        }

        Map<String, IntradayPrice> latestBySymbol = latestPricesBySymbol(gathered.holdings());
        List<AggHolding> aggregated = new PortfolioAggregator().aggregate(
                gathered.holdings(), gathered.rates(),
                dividendService.dividendsBySymbol(gathered.holdings()),
                latestBySymbol);
        PortfolioMetrics.Totals totals = new PortfolioMetrics().compute(aggregated, iiSippCash);

        snapshots.saveSnapshot(totals.totalGbp(), totals.totalGainGbp(), totals.totalCashGbp(),
                totals.returnPct(), totals.totalReturn(), gathered.rates());

        List<String> sources = new ArrayList<>(gathered.sources().keySet());
        return new SyncResult(aggregated, totals, gathered.rates(), iiSippCash, sources, false);
    }

    /**
     * Bulk-load the latest cached intraday price per holding, re-keyed by <b>internal symbol</b>
     * (upper-cased) — that's how the aggregator looks them up alongside dividends. Gilts and
     * CASH rows are filtered out upstream (they have no Yahoo price); a holding whose ticker
     * has no cached bar simply won't appear in the result and the RT columns render as "—".
     */
    private Map<String, IntradayPrice> latestPricesBySymbol(List<Holding> holdings) {
        Map<String, String> tickerBySymbol = new LinkedHashMap<>();   // preserves first-seen order
        for (Holding h : holdings) {
            String sym = h.getSecurityId();
            if (sym == null || sym.equals("CASH") || Instruments.isBond(sym)) continue;
            tickerBySymbol.putIfAbsent(sym.toUpperCase(), tickerMap.tickerFor(sym));
        }
        if (tickerBySymbol.isEmpty()) return Map.of();

        Map<String, IntradayPrice> byTicker = intraday.loadLatestIntradayPrices(tickerBySymbol.values());
        Map<String, IntradayPrice> bySymbol = new HashMap<>();
        for (Map.Entry<String, String> e : tickerBySymbol.entrySet()) {
            IntradayPrice p = byTicker.get(e.getValue());
            if (p != null) bySymbol.put(e.getKey(), p);
        }
        return bySymbol;
    }
}
