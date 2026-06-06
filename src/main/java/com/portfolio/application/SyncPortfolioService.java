package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.PortfolioAggregator;
import com.portfolio.domain.PortfolioMetrics;
import com.portfolio.domain.model.AggHolding;
import com.portfolio.domain.model.Holding;
import com.portfolio.domain.model.IntradayPrice;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.CashTransactionRepository.CashBalance;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.persistence.SnapshotRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final CashTransactionRepository cashRepo;

    public SyncPortfolioService(PortfolioGatherer gatherer,
                                SnapshotRepository snapshots,
                                IntradayPriceRepository intraday,
                                DividendService dividendService,
                                YahooTickerMap tickerMap,
                                CashTransactionRepository cashRepo) {
        this.gatherer = gatherer;
        this.snapshots = snapshots;
        this.intraday = intraday;
        this.dividendService = dividendService;
        this.tickerMap = tickerMap;
        this.cashRepo = cashRepo;
    }

    public SyncResult sync(BigDecimal iiSippCashGbp, BigDecimal iiSippCashUsd) {
        GatheredPortfolio gathered = gatherer.gather();

        if (gathered.holdings().isEmpty()) {
            return SyncResult.empty(gathered.rates());
        }

        BigDecimal iiSippCash = combineIiCashAsGbp(iiSippCashGbp, iiSippCashUsd, gathered.rates());

        Map<String, IntradayPrice> latestBySymbol = latestPricesBySymbol(gathered.holdings());
        List<AggHolding> aggregated = new PortfolioAggregator().aggregate(
                gathered.holdings(), gathered.rates(),
                dividendService.dividendsBySymbol(gathered.holdings()),
                latestBySymbol);
        PortfolioMetrics.Totals totals = new PortfolioMetrics().compute(aggregated, iiSippCash);

        snapshots.saveSnapshot(totals.totalGbp(), totals.totalGainGbp(), totals.totalCashGbp(),
                totals.returnPct(), totals.totalReturn(), gathered.rates());

        List<String> sources = new ArrayList<>(gathered.sources().keySet());
        List<SyncResult.CashRecon> recon = reconcileCash(
                aggregated, iiSippCashGbp, iiSippCashUsd, gathered.rates());
        return new SyncResult(aggregated, totals, gathered.rates(), iiSippCash, sources, false, recon);
    }

    /**
     * Build a per-{@code (account, currency)} comparison between the holdings-side cash
     * (parsed CASH rows + form-supplied II SIPP figures) and the ledger-side latest stored
     * cash balances. The dashboard highlights any row whose GBP diff exceeds a small
     * threshold — a long-lived drift means broker fees or contributions aren't flowing into
     * one side or the other, and that's worth eyeballing.
     */
    private List<SyncResult.CashRecon> reconcileCash(List<AggHolding> aggregated,
                                                     BigDecimal iiCashGbp, BigDecimal iiCashUsd,
                                                     Map<String, BigDecimal> rates) {
        Map<String, BigDecimal> holdingsByKey = new LinkedHashMap<>();
        for (AggHolding h : aggregated) {
            if (!"CASH".equals(h.securityId())) continue;
            String source = h.sources();   // e.g. "AJ Bell SIPP" or "Roth IRA"
            String account = ACCOUNT_KEY_BY_SOURCE.getOrDefault(source, source);
            holdingsByKey.merge(account + "|" + h.currencyCode(), h.marketValueGbp(), BigDecimal::add);
        }
        if (iiCashGbp != null && iiCashGbp.signum() != 0) {
            holdingsByKey.merge("II|GBP", iiCashGbp, BigDecimal::add);
        }
        if (iiCashUsd != null && iiCashUsd.signum() != 0) {
            BigDecimal usdRate = rates.get("USD");
            if (usdRate != null && usdRate.signum() != 0) {
                holdingsByKey.merge("II|USD",
                        iiCashUsd.divide(usdRate, 10, RoundingMode.HALF_UP), BigDecimal::add);
            }
        }

        Map<String, BigDecimal> ledgerByKey = new LinkedHashMap<>();
        for (CashBalance cb : cashRepo.latestCashBalances()) {
            ledgerByKey.put(cb.accountDbValue() + "|" + cb.currency(), BigDecimal.valueOf(cb.cashGbp()));
        }

        List<SyncResult.CashRecon> recon = new ArrayList<>();
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        keys.addAll(holdingsByKey.keySet());
        keys.addAll(ledgerByKey.keySet());
        for (String k : keys) {
            BigDecimal hv = holdingsByKey.getOrDefault(k, BigDecimal.ZERO);
            BigDecimal lv = ledgerByKey.getOrDefault(k, BigDecimal.ZERO);
            BigDecimal diff = hv.subtract(lv);
            String[] parts = k.split("\\|", 2);
            boolean warn = diff.abs().compareTo(CASH_DRIFT_WARN_GBP) > 0;
            recon.add(new SyncResult.CashRecon(parts[0], parts[1],
                    hv.setScale(2, RoundingMode.HALF_UP),
                    lv.setScale(2, RoundingMode.HALF_UP),
                    diff.setScale(2, RoundingMode.HALF_UP),
                    warn));
        }
        return recon;
    }

    /** Diff threshold beyond which the reconciliation row goes red in the dashboard. */
    private static final BigDecimal CASH_DRIFT_WARN_GBP = new BigDecimal("50");

    /** Map from parser source labels (as shown in the {@code sources} column) to the DB account key. */
    private static final Map<String, String> ACCOUNT_KEY_BY_SOURCE = Map.of(
            "AJ Bell SIPP", "AJBell",
            "Roth IRA", "RothIRA",
            "II SIPP", "II");

    /**
     * II SIPP cash entered on the dashboard as two amounts (GBP + USD). Convert the USD
     * portion at the live rate we already fetched, sum, and feed that single GBP figure
     * into the metrics layer — saves the user the manual conversion each time the FX moves.
     */
    static BigDecimal combineIiCashAsGbp(BigDecimal gbp, BigDecimal usd,
                                         Map<String, BigDecimal> rates) {
        if (usd == null || usd.signum() == 0) return gbp;
        BigDecimal usdRate = rates.get("USD");
        if (usdRate == null || usdRate.signum() == 0) return gbp;
        return gbp.add(usd.divide(usdRate, 10, RoundingMode.HALF_UP));
    }

    /**
     * Bulk-load the latest cached intraday price per holding, re-keyed by <b>internal symbol</b>
     * (upper-cased) — that's how the aggregator looks them up alongside dividends. CASH rows
     * are filtered out (no price); stocks route through {@code YahooTickerMap}, gilts are
     * stored under their own {@code GILT %} symbol by {@link GiltPriceFetchJob} so the lookup
     * key equals the symbol itself. A holding whose symbol has no cached bar simply won't appear
     * in the result and the RT columns render as "—".
     */
    private Map<String, IntradayPrice> latestPricesBySymbol(List<Holding> holdings) {
        Map<String, String> tickerBySymbol = new LinkedHashMap<>();   // preserves first-seen order
        for (Holding h : holdings) {
            String sym = h.getSecurityId();
            if (sym == null || sym.equals("CASH")) continue;
            String upper = sym.toUpperCase();
            String ticker = Instruments.isBond(sym) ? upper : tickerMap.tickerFor(sym);
            tickerBySymbol.putIfAbsent(upper, ticker);
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
