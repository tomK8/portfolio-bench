package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.application.AllocationService.AllocationPoint;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.IntradayPrice;
import com.portfolio.domain.model.TransactionType;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.CashTransactionRepository.CashBalance;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.port.FxRateProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Portfolio concentration metrics.
 *
 * <p><b>HHI</b> (Herfindahl-Hirschman Index) = Σ wᵢ² over fractional position weights. Range
 * [1/N, 1]: an equally-weighted N-position book sits at 1/N; a single-name book sits at 1.
 * <b>Effective N</b> = 1/HHI, the equivalent count of equally-weighted positions — easier to
 * read than HHI itself ("12 effective positions" vs. "HHI=0.083"). HHI is computed against
 * the <i>invested</i> base, not total; cash is reported separately so cash drag doesn't
 * silently lower HHI by adding a giant non-position weight.
 *
 * <p><b>Top-N</b> = cumulative weight of the N largest positions (relative to invested base).
 * Top-1 vs. top-3 vs. top-10 vs. HHI together tell you whether concentration is in one
 * dominant name (top-1 ≈ HHI) or evenly heavy across a handful (top-3 high but HHI flat).
 *
 * <p><b>Current snapshot</b> uses live intraday prices and live FX from a per-account
 * ledger replay — same shape as {@link DividendIncomeService}. <b>Trend</b> reuses
 * {@link AllocationService}'s weekly samples (historical {@code price_history} closes +
 * historical FX) — slightly different valuation engine from the snapshot, so the latest
 * trend point and the headline numbers can disagree by a few basis points.
 */
public class ConcentrationService {

    private static final Logger log = LoggerFactory.getLogger(ConcentrationService.class);
    private static final int WEIGHT_SCALE = 6;
    private static final int GBP_SCALE = 2;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final AllocationService allocationService;
    private final CashTransactionRepository cashRepo;
    private final IntradayPriceRepository intradayRepo;
    private final FxRateProvider fxRateProvider;
    private final YahooTickerMap tickerMap;

    public ConcentrationService(AllocationService allocationService,
                                CashTransactionRepository cashRepo,
                                IntradayPriceRepository intradayRepo,
                                FxRateProvider fxRateProvider,
                                YahooTickerMap tickerMap) {
        this.allocationService = allocationService;
        this.cashRepo = cashRepo;
        this.intradayRepo = intradayRepo;
        this.fxRateProvider = fxRateProvider;
        this.tickerMap = tickerMap;
    }

    public ConcentrationMetrics metrics() {
        Snapshot snapshot = buildCurrentSnapshot();
        List<TrendPoint> trend = computeTrend(allocationService.timeline().points());
        return new ConcentrationMetrics(snapshot, trend);
    }

    private Snapshot buildCurrentSnapshot() {
        List<CashTransaction> rows = cashRepo.loadDividendTransactions();
        Map<String, BigDecimal> rates;
        try {
            rates = fxRateProvider.fetchRates();
        } catch (Exception e) {
            log.warn("Could not fetch FX rates; non-GBP positions will fall back to null value", e);
            rates = Map.of();
        }

        // Walk ledger once, accumulating per-(account, symbol) shares.
        // Splits handled the same way as CashLedgerReconstructor: signed delta on a
        // zero-cash row scales the running share count proportionally.
        Map<String, BigDecimal> sharesByKey = new LinkedHashMap<>();
        for (CashTransaction t : rows) {
            if (t.type() != TransactionType.TRANSACTION) continue;
            String sym = t.symbol();
            if (sym == null || sym.isBlank()) continue;
            String key = t.account().dbValue() + "|" + sym.toUpperCase();
            BigDecimal qty = BigDecimal.valueOf(t.quantity());
            if (t.amount() < 0) {
                sharesByKey.merge(key, qty.abs(), BigDecimal::add);
            } else if (t.amount() > 0) {
                sharesByKey.merge(key, qty.abs().negate(), BigDecimal::add);
            } else {
                sharesByKey.merge(key, qty, BigDecimal::add);
            }
        }

        // Pull intraday prices for every symbol with a positive share count.
        Map<String, String> tickerBySymbol = new LinkedHashMap<>();
        for (var e : sharesByKey.entrySet()) {
            if (e.getValue().signum() <= 0) continue;
            String sym = keySymbol(e.getKey());
            String ticker = Instruments.isBond(sym) ? sym : tickerMap.tickerFor(sym);
            tickerBySymbol.putIfAbsent(sym, ticker);
        }
        Map<String, IntradayPrice> byTicker =
                intradayRepo.loadLatestIntradayPrices(tickerBySymbol.values());
        Map<String, IntradayPrice> bySymbol = new LinkedHashMap<>();
        for (var e : tickerBySymbol.entrySet()) {
            IntradayPrice p = byTicker.get(e.getValue());
            if (p != null) bySymbol.put(e.getKey(), p);
        }

        // Value each (account, symbol) → GBP.
        Map<String, BigDecimal> gbpByAccount = new LinkedHashMap<>();
        Map<String, BigDecimal> gbpBySymbol = new LinkedHashMap<>();
        BigDecimal investedTotal = BigDecimal.ZERO;
        for (var e : sharesByKey.entrySet()) {
            BigDecimal shares = e.getValue();
            if (shares.signum() <= 0) continue;
            String account = keyAccount(e.getKey());
            String sym = keySymbol(e.getKey());
            BigDecimal gbp = valueGbp(sym, shares, bySymbol.get(sym), rates);
            if (gbp == null) continue;
            gbpByAccount.merge(account, gbp, BigDecimal::add);
            gbpBySymbol.merge(sym, gbp, BigDecimal::add);
            investedTotal = investedTotal.add(gbp);
        }

        // Cash from latestCashBalances — already FX-converted at the snapshot row's rate.
        // For consistency with the holdings view, the dashboard's headline cash uses the
        // current FX; here we use the stored cashGbp because what matters for concentration
        // is the relative size, and the drift is sub-1% on a multi-hundred-k pot.
        BigDecimal cashTotal = BigDecimal.ZERO;
        Map<String, BigDecimal> cashByAccount = new LinkedHashMap<>();
        for (CashBalance cb : cashRepo.latestCashBalances()) {
            BigDecimal gbp = BigDecimal.valueOf(cb.cashGbp());
            cashTotal = cashTotal.add(gbp);
            cashByAccount.merge(cb.accountDbValue(), gbp, BigDecimal::add);
        }
        for (var e : cashByAccount.entrySet()) {
            gbpByAccount.merge(e.getKey(), e.getValue(), BigDecimal::add);
        }

        BigDecimal total = investedTotal.add(cashTotal);
        if (total.signum() <= 0) return Snapshot.empty();

        // Per-symbol weights against the INVESTED base (not total — cash is reported
        // separately so HHI isn't artificially deflated by uninvested cash).
        List<SymbolWeight> bySymbolList = new ArrayList<>();
        for (var e : gbpBySymbol.entrySet()) {
            BigDecimal w = investedTotal.signum() > 0
                    ? e.getValue().divide(investedTotal, WEIGHT_SCALE, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            bySymbolList.add(new SymbolWeight(e.getKey(), money(e.getValue()), w));
        }
        bySymbolList.sort(Comparator.comparing(SymbolWeight::weight).reversed());

        BigDecimal hhi = bySymbolList.stream()
                .map(s -> s.weight().multiply(s.weight()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
        BigDecimal effectiveN = hhi.signum() > 0
                ? BigDecimal.ONE.divide(hhi, 4, RoundingMode.HALF_UP) : null;

        BigDecimal cashShare = total.signum() > 0
                ? cashTotal.divide(total, WEIGHT_SCALE, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        // By account (against TOTAL since accounts hold both invested + cash).
        List<AccountWeight> byAccount = new ArrayList<>();
        for (var e : gbpByAccount.entrySet()) {
            BigDecimal w = e.getValue().divide(total, WEIGHT_SCALE, RoundingMode.HALF_UP);
            byAccount.add(new AccountWeight(e.getKey(), money(e.getValue()), w));
        }
        byAccount.sort(Comparator.comparing(AccountWeight::weight).reversed());

        // By asset class.
        BigDecimal bondTotal = BigDecimal.ZERO;
        BigDecimal equityTotal = BigDecimal.ZERO;
        for (var e : gbpBySymbol.entrySet()) {
            if (Instruments.isBond(e.getKey())) bondTotal = bondTotal.add(e.getValue());
            else equityTotal = equityTotal.add(e.getValue());
        }
        List<AssetClassWeight> byAssetClass = List.of(
                new AssetClassWeight("Equities", money(equityTotal),
                        weightOf(equityTotal, total)),
                new AssetClassWeight("Bonds", money(bondTotal),
                        weightOf(bondTotal, total)),
                new AssetClassWeight("Cash", money(cashTotal),
                        weightOf(cashTotal, total)));

        return new Snapshot(
                money(total), money(cashTotal), money(investedTotal),
                cashShare, hhi, effectiveN,
                topNWeight(bySymbolList, 1),
                topNWeight(bySymbolList, 3),
                topNWeight(bySymbolList, 5),
                topNWeight(bySymbolList, 10),
                bySymbolList.size(),
                bySymbolList, byAccount, byAssetClass);
    }

    private static BigDecimal weightOf(BigDecimal part, BigDecimal whole) {
        return whole.signum() > 0
                ? part.divide(whole, WEIGHT_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
    }

    private static BigDecimal topNWeight(List<SymbolWeight> sorted, int n) {
        BigDecimal s = BigDecimal.ZERO;
        for (int i = 0; i < Math.min(n, sorted.size()); i++) {
            s = s.add(sorted.get(i).weight());
        }
        return s.setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * HHI / effective-N / position-count per weekly allocation sample. Weights use the same
     * invested-only base as the current snapshot, so the trend is comparable to the headline
     * HHI even though the underlying price feed (price_history close vs. live intraday)
     * differs.
     */
    static List<TrendPoint> computeTrend(List<AllocationPoint> points) {
        List<TrendPoint> out = new ArrayList<>();
        for (AllocationPoint p : points) {
            if (p.investedGbp().signum() <= 0) {
                out.add(new TrendPoint(p.date(), BigDecimal.ZERO, null, 0));
                continue;
            }
            BigDecimal hhi = BigDecimal.ZERO;
            int count = 0;
            for (var e : p.symbolGbp().entrySet()) {
                if (e.getValue().signum() <= 0) continue;
                count++;
                BigDecimal w = e.getValue().divide(p.investedGbp(), WEIGHT_SCALE, RoundingMode.HALF_UP);
                hhi = hhi.add(w.multiply(w));
            }
            hhi = hhi.setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
            BigDecimal effN = hhi.signum() > 0
                    ? BigDecimal.ONE.divide(hhi, 4, RoundingMode.HALF_UP) : null;
            out.add(new TrendPoint(p.date(), hhi, effN, count));
        }
        return out;
    }

    private BigDecimal valueGbp(String symbol, BigDecimal shares, IntradayPrice ip,
                                Map<String, BigDecimal> rates) {
        if (ip == null) return null;
        BigDecimal price = BigDecimal.valueOf(ip.close());
        String ccy = ip.currency();
        if ("GBp".equals(ccy)) {
            price = price.movePointLeft(2);
            ccy = "GBP";
        }
        BigDecimal native_ = Instruments.isBond(symbol)
                ? price.multiply(shares).divide(HUNDRED, 10, RoundingMode.HALF_UP)
                : price.multiply(shares);
        if ("GBP".equals(ccy)) return native_;
        BigDecimal rate = rates.get(ccy);
        if (rate == null || rate.signum() == 0) return null;
        return native_.divide(rate, 10, RoundingMode.HALF_UP);
    }

    private static String keyAccount(String key) {
        return key.substring(0, key.indexOf('|'));
    }

    private static String keySymbol(String key) {
        return key.substring(key.indexOf('|') + 1);
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? null : v.setScale(GBP_SCALE, RoundingMode.HALF_UP);
    }

    // ---- DTOs ----

    public record SymbolWeight(String symbol, BigDecimal gbp, BigDecimal weight) {
    }

    public record AccountWeight(String account, BigDecimal gbp, BigDecimal weight) {
    }

    public record AssetClassWeight(String kind, BigDecimal gbp, BigDecimal weight) {
    }

    /**
     * Concentration trend at one sample date. {@code positionCount} is the raw number of
     * symbols with positive GBP value at that date. {@code effectiveN} is null at samples
     * before any position was held (HHI=0).
     */
    public record TrendPoint(String date, BigDecimal hhi, BigDecimal effectiveN,
                             int positionCount) {
    }

    public record Snapshot(BigDecimal totalGbp,
                           BigDecimal cashGbp,
                           BigDecimal investedGbp,
                           BigDecimal cashShare,
                           BigDecimal hhi,
                           BigDecimal effectiveN,
                           BigDecimal top1Share,
                           BigDecimal top3Share,
                           BigDecimal top5Share,
                           BigDecimal top10Share,
                           int positionCount,
                           List<SymbolWeight> bySymbol,
                           List<AccountWeight> byAccount,
                           List<AssetClassWeight> byAssetClass) {

        public static Snapshot empty() {
            return new Snapshot(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, null,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    0, List.of(), List.of(), List.of());
        }
    }

    public record ConcentrationMetrics(Snapshot snapshot, List<TrendPoint> trend) {
    }
}
