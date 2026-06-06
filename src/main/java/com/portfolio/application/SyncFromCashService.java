package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.CashLedgerReconstructor;
import com.portfolio.domain.CashLedgerReconstructor.Position;
import com.portfolio.domain.DividendAttributor;
import com.portfolio.domain.DividendAttributor.Attribution;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.PortfolioMetrics;
import com.portfolio.domain.model.AggHolding;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.IntradayPrice;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.CashTransactionRepository.CashBalance;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.port.FxRateProvider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The cash-ledger-derived counterpart to {@link SyncPortfolioService}: rebuilds the
 * portfolio entirely from {@code cash_transactions} + the latest cached intraday prices,
 * with no broker holdings file involved. Designed to sit next to the holdings-based view
 * as a cross-check on the data: a close match between the two confirms the cash ledger
 * is complete and the price/FX pipelines are consistent.
 *
 * <p>Differences worth knowing about when comparing the two side-by-side:
 * <ul>
 *   <li>Cost basis is the GBP amount that hit the cash ledger at trade time (historical
 *       FX). The holdings-based path converts native cost at <em>current</em> FX, so
 *       gain/loss numbers diverge on multi-currency positions.</li>
 *   <li>Market value comes purely from the latest intraday quote × current FX. The
 *       holdings-based path prefers the broker-supplied GBP value when present.</li>
 *   <li>CASH rows are derived from the latest stored running balance per
 *       {@code (account, currency)} — no II SIPP form input.</li>
 *   <li>Listing currency is taken from the intraday price feed when available
 *       (AJBell's cash rows are GBP regardless of the security's actual listing).</li>
 * </ul>
 */
public class SyncFromCashService {

    private static final Map<String, String> ACCOUNT_LABELS = Map.of(
            "AJBell", "AJ Bell SIPP",
            "RothIRA", "Roth IRA",
            "II", "II SIPP");

    private final CashTransactionRepository repo;
    private final IntradayPriceRepository intraday;
    private final FxRateProvider fxRateProvider;
    private final YahooTickerMap tickerMap;

    public SyncFromCashService(CashTransactionRepository repo,
                               IntradayPriceRepository intraday,
                               FxRateProvider fxRateProvider,
                               YahooTickerMap tickerMap) {
        this.repo = repo;
        this.intraday = intraday;
        this.fxRateProvider = fxRateProvider;
        this.tickerMap = tickerMap;
    }

    public SyncResult sync() {
        Map<String, BigDecimal> rates;
        try {
            rates = fxRateProvider.fetchRates();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch FX rates", e);
        }

        List<CashTransaction> rows = repo.loadDividendTransactions();
        List<CashBalance> cashBalances = repo.latestCashBalances();
        if (rows.isEmpty() && cashBalances.isEmpty()) {
            return SyncResult.empty(rates);
        }

        List<Position> positions = new CashLedgerReconstructor().reconstruct(rows);
        Map<String, Attribution> attributed = new DividendAttributor().attributeBySymbol(rows);
        Map<String, IntradayPrice> latestBySymbol = latestPricesBySymbol(positions);

        // Drop positions with no intraday price: we can't value them, and per the design of this
        // view (compare against the holdings RT total) silently substituting cost basis would
        // make totals diverge for any position we can't price live.
        List<AggHolding> aggregated = new ArrayList<>();
        for (Position p : positions) {
            IntradayPrice ip = latestBySymbol.get(p.securityId().toUpperCase());
            if (ip == null) continue;
            aggregated.add(toAggHolding(p, ip, attributed, rates));
        }
        for (CashBalance cb : cashBalances) {
            aggregated.add(cashRow(cb));
        }
        aggregated.sort(Comparator.comparingInt(SyncFromCashService::section)
                .thenComparing(h -> h.securityId().equals("CASH") ? "~" : h.securityId()));

        PortfolioMetrics.Totals totals = new PortfolioMetrics().compute(aggregated, BigDecimal.ZERO);
        List<String> sources = new ArrayList<>();
        for (CashBalance cb : cashBalances) {
            String label = ACCOUNT_LABELS.getOrDefault(cb.accountDbValue(), cb.accountDbValue());
            if (!sources.contains(label)) sources.add(label);
        }
        if (sources.isEmpty()) sources.add("cash ledger");

        return new SyncResult(aggregated, totals, rates, BigDecimal.ZERO, sources, false, List.of());
    }

    // ---- Holding construction ----------------------------------------------

    private AggHolding toAggHolding(Position p, IntradayPrice ip,
                                    Map<String, Attribution> attributed,
                                    Map<String, BigDecimal> rates) {
        Currency ccy = resolveCurrency(p, ip);
        BigDecimal[] rt = realtime(p.securityId(), ccy, p.quantity(), ip, rates);
        BigDecimal latestPrice = rt[0], rtNative = rt[1], rtGbp = rt[2];

        // Market value = live intraday × current FX. The "saved" marketValueGbp on AggHolding
        // is set to the same value so totals from PortfolioMetrics match — there is no
        // separate broker-supplied number in this view.
        BigDecimal marketValueGbp = rtGbp;
        BigDecimal gainGbp = (rtGbp != null) ? rtGbp.subtract(p.costGbp()) : null;
        BigDecimal gainPct = (gainGbp != null && p.costGbp().compareTo(BigDecimal.ZERO) != 0)
                ? gainGbp.divide(p.costGbp(), 10, RoundingMode.HALF_UP) : null;

        Attribution a = attributed.get(p.securityId().toUpperCase());
        BigDecimal dividend = a != null ? a.dividendGbp() : BigDecimal.ZERO;
        BigDecimal totalGain = gainGbp != null ? gainGbp.add(dividend) : null;
        BigDecimal totalGainPct = (totalGain != null && p.costGbp().compareTo(BigDecimal.ZERO) != 0)
                ? totalGain.divide(p.costGbp(), 10, RoundingMode.HALF_UP) : null;

        StringBuilder srcStr = new StringBuilder();
        for (String acc : p.accounts()) {
            if (srcStr.length() > 0) srcStr.append(", ");
            srcStr.append(ACCOUNT_LABELS.getOrDefault(acc, acc));
        }
        return new AggHolding(p.securityId(), p.quantity(), null,
                marketValueGbp, gainGbp, gainPct, dividend, totalGain, totalGainPct,
                ccy, srcStr.toString(), latestPrice, rtNative, rtGbp);
    }

    private AggHolding cashRow(CashBalance cb) {
        Currency ccy = Currency.getInstance(cb.currency());
        BigDecimal nativeAmount = cb.cashNative() != null
                ? BigDecimal.valueOf(cb.cashNative()) : BigDecimal.valueOf(cb.cashGbp());
        BigDecimal gbp = BigDecimal.valueOf(cb.cashGbp());
        String src = ACCOUNT_LABELS.getOrDefault(cb.accountDbValue(), cb.accountDbValue());
        return new AggHolding("CASH", nativeAmount, BigDecimal.ONE, gbp,
                null, null, BigDecimal.ZERO, null, null,
                ccy, src, null, null, null);
    }

    /**
     * Listing currency from the intraday feed when available (Yahoo knows). GBp (London
     * pence) normalises to GBP for display — the value formula handles the divide-by-100
     * separately. Falls back to the cash row currency (the settlement ccy) when there's
     * no intraday data — best guess in that case.
     */
    private static Currency resolveCurrency(Position p, IntradayPrice ip) {
        if (ip != null) {
            String c = ip.currency();
            if ("GBp".equals(c)) return Currency.getInstance("GBP");
            try {
                return Currency.getInstance(c);
            } catch (Exception ignored) {
            }
        }
        return Currency.getInstance(p.tradeCurrency());
    }

    /**
     * Returns {@code {latestPrice, rtMarketValue, rtMarketValueGbp}} — same shape as
     * {@link com.portfolio.domain.PortfolioAggregator}'s private equivalent. Kept as a
     * separate copy because the original is private and the two callers handle the
     * "currency is unknown" fallback differently.
     */
    private static BigDecimal[] realtime(String securityId, Currency ccy, BigDecimal qty,
                                         IntradayPrice ip, Map<String, BigDecimal> rates) {
        BigDecimal[] empty = {null, null, null};
        if (ip == null) return empty;
        BigDecimal price = BigDecimal.valueOf(ip.close());
        if ("GBp".equals(ip.currency()) && "GBP".equals(ccy.getCurrencyCode())) {
            price = price.movePointLeft(2);
        }
        BigDecimal rtNative = Instruments.isBond(securityId)
                ? price.multiply(qty).movePointLeft(2)
                : price.multiply(qty);
        BigDecimal rtGbp;
        if ("GBP".equals(ccy.getCurrencyCode())) {
            rtGbp = rtNative;
        } else {
            BigDecimal rate = rates.get(ccy.getCurrencyCode());
            rtGbp = (rate == null || rate.signum() == 0)
                    ? null : rtNative.divide(rate, 10, RoundingMode.HALF_UP);
        }
        return new BigDecimal[]{price, rtNative, rtGbp};
    }

    private Map<String, IntradayPrice> latestPricesBySymbol(List<Position> positions) {
        Map<String, String> tickerBySymbol = new LinkedHashMap<>();
        for (Position p : positions) {
            String sym = p.securityId();
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

    private static int section(AggHolding h) {
        if (Instruments.isBond(h.securityId())) return 4;
        return switch (h.currency().getCurrencyCode()) {
            case "USD" -> 0;
            case "GBP" -> 1;
            case "EUR" -> 2;
            default -> 3;
        };
    }
}
