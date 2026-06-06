package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.CashLedgerReconstructor;
import com.portfolio.domain.CashLedgerReconstructor.Position;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.PortfolioAggregator;
import com.portfolio.domain.PortfolioMetrics;
import com.portfolio.domain.model.AggHolding;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.Holding;
import com.portfolio.domain.model.IntradayPrice;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.CashTransactionRepository.CashBalance;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.persistence.SnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gathers the latest holdings, aggregates, records a snapshot and returns the aggregated view.
 *
 * <p>Framework-agnostic: collaborators are passed in; pure domain calculators are
 * instantiated directly. Spring wiring lives in the config layer, not here.
 */
public class SyncPortfolioService {

    private static final Logger log = LoggerFactory.getLogger(SyncPortfolioService.class);

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
        List<String> missingIntraday = missingIntradayPrices(aggregated);
        for (String sym : missingIntraday) log.warn("No intraday price for held symbol {}", sym);
        List<SyncResult.AssetRecon> assetRecon = reconcileAssets(aggregated, latestBySymbol, gathered.rates());
        return new SyncResult(aggregated, totals, gathered.rates(), iiSippCash, sources, false,
                recon, missingIntraday, assetRecon);
    }

    /**
     * Per-symbol comparison of the holdings-side RT GBP value against the same value computed
     * fresh from the cash ledger (FIFO reconstruction × same intraday × same FX). Same qty +
     * price + FX should produce identical numbers; any nonzero diff points at a real
     * reconciliation issue — broker rounding vs. raw ledger quantity, a missed trade, a
     * currency-resolution mismatch, or a position present only in one source.
     */
    private List<SyncResult.AssetRecon> reconcileAssets(List<AggHolding> aggregated,
                                                        Map<String, IntradayPrice> latestBySymbol,
                                                        Map<String, BigDecimal> rates) {
        List<CashTransaction> rows = cashRepo.loadDividendTransactions();
        List<Position> positions = new CashLedgerReconstructor().reconstruct(rows);
        Map<String, BigDecimal> ledgerRtBySymbol = new HashMap<>();
        for (Position p : positions) {
            IntradayPrice ip = latestBySymbol.get(p.securityId().toUpperCase());
            if (ip == null) continue;
            BigDecimal v = ledgerRtGbp(p, ip, rates);
            if (v != null) ledgerRtBySymbol.put(p.securityId().toUpperCase(), v);
        }
        Map<String, String> displayName = new LinkedHashMap<>();
        Map<String, BigDecimal> holdingsRtBySymbol = new LinkedHashMap<>();
        for (AggHolding h : aggregated) {
            if ("CASH".equals(h.securityId())) continue;
            String sym = h.securityId().toUpperCase();
            BigDecimal hv = h.rtMarketValueGbp() != null ? h.rtMarketValueGbp() : h.marketValueGbp();
            if (hv == null) hv = BigDecimal.ZERO;
            holdingsRtBySymbol.merge(sym, hv, BigDecimal::add);
            displayName.putIfAbsent(sym, h.securityId());
        }

        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(holdingsRtBySymbol.keySet());
        keys.addAll(ledgerRtBySymbol.keySet());
        List<SyncResult.AssetRecon> out = new ArrayList<>();
        for (String sym : keys) {
            BigDecimal hv = holdingsRtBySymbol.getOrDefault(sym, BigDecimal.ZERO);
            BigDecimal lv = ledgerRtBySymbol.getOrDefault(sym, BigDecimal.ZERO);
            BigDecimal diff = hv.subtract(lv);
            if (diff.abs().compareTo(ASSET_DRIFT_WARN_GBP) <= 0) continue;
            out.add(new SyncResult.AssetRecon(displayName.getOrDefault(sym, sym),
                    hv.setScale(2, RoundingMode.HALF_UP),
                    lv.setScale(2, RoundingMode.HALF_UP),
                    diff.setScale(2, RoundingMode.HALF_UP)));
        }
        out.sort(Comparator.comparing((SyncResult.AssetRecon r) -> r.diffGbp().abs()).reversed());
        return out;
    }

    /** Threshold below which an asset diff is treated as rounding noise and hidden. */
    private static final BigDecimal ASSET_DRIFT_WARN_GBP = new BigDecimal("1.00");

    /**
     * Same RT-GBP formula as {@link PortfolioAggregator}/{@link SyncFromCashService}, applied
     * directly to a reconstructed ledger {@link Position}. Kept inline because the existing
     * realtime helpers are private to their respective classes and the calc is short.
     */
    private static BigDecimal ledgerRtGbp(Position p, IntradayPrice ip,
                                          Map<String, BigDecimal> rates) {
        if (ip == null) return null;
        Currency ccy;
        if ("GBp".equals(ip.currency())) {
            ccy = Currency.getInstance("GBP");
        } else {
            try {
                ccy = Currency.getInstance(ip.currency());
            } catch (Exception ignored) {
                ccy = Currency.getInstance(p.tradeCurrency());
            }
        }
        BigDecimal price = BigDecimal.valueOf(ip.close());
        if ("GBp".equals(ip.currency()) && "GBP".equals(ccy.getCurrencyCode())) {
            price = price.movePointLeft(2);
        }
        BigDecimal rtNative = Instruments.isBond(p.securityId())
                ? price.multiply(p.quantity()).movePointLeft(2)
                : price.multiply(p.quantity());
        if ("GBP".equals(ccy.getCurrencyCode())) return rtNative;
        BigDecimal rate = rates.get(ccy.getCurrencyCode());
        if (rate == null || rate.signum() == 0) return null;
        return rtNative.divide(rate, 10, RoundingMode.HALF_UP);
    }

    /**
     * Non-CASH holdings whose intraday lookup turned up nothing. Surfaced in the dashboard so
     * the user notices stale/missing feeds (a new ticker missing a Yahoo mapping, a gilt the
     * fetcher hasn't seen, an extended market closure pruning past 7-day retention).
     */
    private static List<String> missingIntradayPrices(List<AggHolding> aggregated) {
        List<String> out = new ArrayList<>();
        for (AggHolding h : aggregated) {
            if ("CASH".equals(h.securityId())) continue;
            if (h.rtMarketValueGbp() == null) out.add(h.securityId());
        }
        return out;
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
