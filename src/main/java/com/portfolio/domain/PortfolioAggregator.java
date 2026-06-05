package com.portfolio.domain;

import com.portfolio.domain.model.AggHolding;
import com.portfolio.domain.model.Holding;
import com.portfolio.domain.model.IntradayPrice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class PortfolioAggregator {

    public static BigDecimal toGbp(Holding h, Map<String, BigDecimal> gbpRates) {
        if (h.getCurrentMarketValueGbp() != null) return h.getCurrentMarketValueGbp();
        BigDecimal mktVal = h.getCurrentMarketValue();
        if (mktVal == null) return null;
        BigDecimal rate = gbpRates.get(h.getCurrency().getCurrencyCode());
        if (rate == null || rate.compareTo(BigDecimal.ZERO) == 0) return null;
        return mktVal.divide(rate, 10, RoundingMode.HALF_UP);
    }

    public static BigDecimal costInGbp(Holding h, Map<String, BigDecimal> gbpRates) {
        if (h.getCostBasisGbp() != null) return h.getCostBasisGbp();
        if (h.getAvgPricePaid() == null) return null;
        BigDecimal native_ = h.getQuantity().multiply(h.getAvgPricePaid());
        BigDecimal rate = gbpRates.getOrDefault(h.getCurrency().getCurrencyCode(), BigDecimal.ONE);
        return rate.compareTo(BigDecimal.ZERO) == 0 ? null
                : native_.divide(rate, 10, RoundingMode.HALF_UP);
    }

    public List<AggHolding> aggregate(List<Holding> holdings, Map<String, BigDecimal> gbpRates,
                                      Map<String, BigDecimal> dividendsBySymbol,
                                      Map<String, IntradayPrice> latestPricesBySymbol) {
        Map<Key, Accumulator> accs = groupAccumulators(holdings, gbpRates);
        return accs.values().stream()
                .map(acc -> buildAggHolding(acc, dividendsBySymbol, latestPricesBySymbol, gbpRates))
                .sorted(Comparator.comparingInt(this::section).thenComparing(this::sortKey))
                .collect(Collectors.toList());
    }

    /**
     * Convenience overload — call sites that don't carry intraday prices (Excel export,
     * older tests) pass nothing and get null RT fields.
     */
    public List<AggHolding> aggregate(List<Holding> holdings, Map<String, BigDecimal> gbpRates,
                                      Map<String, BigDecimal> dividendsBySymbol) {
        return aggregate(holdings, gbpRates, dividendsBySymbol, Map.of());
    }

    /** Pass 1: fold raw holdings into per-{@code (securityId, currency)} totals. */
    private static Map<Key, Accumulator> groupAccumulators(List<Holding> holdings,
                                                           Map<String, BigDecimal> gbpRates) {
        Map<Key, Accumulator> accs = new LinkedHashMap<>();
        for (Holding h : holdings) {
            Key key = new Key(h.getSecurityId(), h.getCurrency().getCurrencyCode());
            Accumulator acc = accs.computeIfAbsent(key, k -> new Accumulator(h.getSecurityId(), h.getCurrency()));
            acc.add(h, gbpRates);
        }
        return accs;
    }

    /** Pass 2: derive the per-symbol view (averages, gains, dividends, RT) from one accumulator. */
    private static AggHolding buildAggHolding(Accumulator acc,
                                              Map<String, BigDecimal> dividendsBySymbol,
                                              Map<String, IntradayPrice> latestPricesBySymbol,
                                              Map<String, BigDecimal> gbpRates) {
        BigDecimal avg = acc.hasCost && acc.qty.compareTo(BigDecimal.ZERO) > 0
                ? acc.nativeCost.divide(acc.qty, 10, RoundingMode.HALF_UP) : null;
        BigDecimal gain = acc.hasCostGbp ? acc.mktValGbp.subtract(acc.totalCostGbp) : null;
        BigDecimal pct = (acc.hasCostGbp && acc.totalCostGbp.compareTo(BigDecimal.ZERO) != 0)
                ? gain.divide(acc.totalCostGbp, 10, RoundingMode.HALF_UP) : null;

        BigDecimal dividend = dividendsBySymbol.getOrDefault(
                acc.securityId.toUpperCase(), BigDecimal.ZERO);
        BigDecimal totalGain = gain != null ? gain.add(dividend) : null;
        BigDecimal totalGainPct = (acc.hasCostGbp && acc.totalCostGbp.compareTo(BigDecimal.ZERO) != 0)
                ? totalGain.divide(acc.totalCostGbp, 10, RoundingMode.HALF_UP) : null;

        BigDecimal[] rt = realtime(acc.securityId, acc.currency, acc.qty, latestPricesBySymbol, gbpRates);

        return new AggHolding(acc.securityId, acc.qty, avg, acc.mktValGbp, gain, pct,
                dividend, totalGain, totalGainPct, acc.currency, String.join(", ", acc.srcs),
                rt[0], rt[1], rt[2]);
    }

    /**
     * Returns {@code {latestPrice, rtMarketValue, rtMarketValueGbp}}; all null when unavailable.
     *
     * <p>Gilt prices arrive as clean prices per £100 nominal (e.g. 75.01), so the value formula
     * is {@code qty × price / 100}. {@code latestPrice} keeps the clean-price form a UK
     * investor expects to see in the Price column; the divide-by-100 happens only inside the
     * market-value calculation.
     */
    private static BigDecimal[] realtime(String securityId, Currency ccy, BigDecimal qty,
                                         Map<String, IntradayPrice> prices,
                                         Map<String, BigDecimal> gbpRates) {
        BigDecimal[] empty = {null, null, null};
        if (securityId.equals("CASH")) return empty;

        IntradayPrice p = prices.get(securityId.toUpperCase());
        if (p == null) return empty;

        BigDecimal price = BigDecimal.valueOf(p.close());
        if ("GBp".equals(p.currency()) && "GBP".equals(ccy.getCurrencyCode())) {
            price = price.movePointLeft(2);   // pence → pounds
        }
        BigDecimal rtNative = Instruments.isBond(securityId)
                ? price.multiply(qty).movePointLeft(2)   // clean price is per £100 nominal
                : price.multiply(qty);

        BigDecimal rtGbp;
        if ("GBP".equals(ccy.getCurrencyCode())) {
            rtGbp = rtNative;
        } else {
            BigDecimal rate = gbpRates.get(ccy.getCurrencyCode());
            rtGbp = (rate == null || rate.compareTo(BigDecimal.ZERO) == 0)
                    ? null : rtNative.divide(rate, 10, RoundingMode.HALF_UP);
        }
        return new BigDecimal[]{price, rtNative, rtGbp};
    }

    private int section(AggHolding h) {
        if (Instruments.isBond(h.securityId())) return 4;
        return switch (h.currency().getCurrencyCode()) {
            case "USD" -> 0;
            case "GBP" -> 1;
            case "EUR" -> 2;
            default -> 3;
        };
    }

    private String sortKey(AggHolding h) {
        return h.securityId().equals("CASH") ? "~" : h.securityId();
    }

    private record Key(String id, String ccy) {
    }

    /**
     * Per-{@code (securityId, currency)} running totals built during the first pass.
     * Mutable on purpose — gets folded into an immutable {@link AggHolding} in the second pass.
     */
    private static final class Accumulator {
        final String securityId;
        final Currency currency;
        final Set<String> srcs = new LinkedHashSet<>();
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal nativeCost = BigDecimal.ZERO;
        BigDecimal mktValGbp = BigDecimal.ZERO;
        BigDecimal totalCostGbp = BigDecimal.ZERO;
        boolean hasCost = false;
        boolean hasCostGbp = false;

        Accumulator(String securityId, Currency currency) {
            this.securityId = securityId;
            this.currency = currency;
        }

        void add(Holding h, Map<String, BigDecimal> gbpRates) {
            qty = qty.add(h.getQuantity());
            srcs.add(h.getSource());

            if (h.getAvgPricePaid() != null) {
                nativeCost = nativeCost.add(h.getQuantity().multiply(h.getAvgPricePaid()));
                hasCost = true;
            }
            BigDecimal gbp = toGbp(h, gbpRates);
            if (gbp != null) mktValGbp = mktValGbp.add(gbp);

            BigDecimal costGbp = costInGbp(h, gbpRates);
            if (costGbp != null) {
                totalCostGbp = totalCostGbp.add(costGbp);
                hasCostGbp = true;
            }
        }
    }
}
