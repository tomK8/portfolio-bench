package com.pension.domain;

import com.pension.domain.model.AggHolding;
import com.pension.domain.model.Holding;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
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

    public static boolean isBond(String id) {
        return id.contains("%") || id.toUpperCase().startsWith("GILT");
    }

    public List<AggHolding> aggregate(List<Holding> holdings, Map<String, BigDecimal> gbpRates,
                                      Map<String, BigDecimal> dividendsBySymbol) {
        record Key(String id, String ccy) {
        }

        class Acc {
            final Set<String> srcs = new LinkedHashSet<>();
            String securityId;
            BigDecimal qty = BigDecimal.ZERO;
            BigDecimal nativeCost = BigDecimal.ZERO;
            boolean hasCost = false;
            BigDecimal mktValGbp = BigDecimal.ZERO;
            BigDecimal totalCostGbp = BigDecimal.ZERO;
            boolean hasCostGbp = false;
            Currency currency;
        }

        Map<Key, Acc> map = new LinkedHashMap<>();

        for (Holding h : holdings) {
            Key key = new Key(h.getSecurityId(), h.getCurrency().getCurrencyCode());
            Acc acc = map.computeIfAbsent(key, k -> {
                Acc a = new Acc();
                a.securityId = h.getSecurityId();
                a.currency = h.getCurrency();
                return a;
            });
            acc.qty = acc.qty.add(h.getQuantity());
            acc.srcs.add(h.getSource());

            if (h.getAvgPricePaid() != null) {
                acc.nativeCost = acc.nativeCost.add(h.getQuantity().multiply(h.getAvgPricePaid()));
                acc.hasCost = true;
            }
            BigDecimal gbp = toGbp(h, gbpRates);
            if (gbp != null) acc.mktValGbp = acc.mktValGbp.add(gbp);

            BigDecimal costGbp = costInGbp(h, gbpRates);
            if (costGbp != null) {
                acc.totalCostGbp = acc.totalCostGbp.add(costGbp);
                acc.hasCostGbp = true;
            }
        }

        return map.values().stream().map(acc -> {
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

                    return new AggHolding(acc.securityId, acc.qty, avg, acc.mktValGbp, gain, pct,
                            dividend, totalGain, totalGainPct, acc.currency, String.join(", ", acc.srcs));
                }).sorted(Comparator.comparingInt(this::section).thenComparing(this::sortKey))
                .collect(Collectors.toList());
    }

    private int section(AggHolding h) {
        if (isBond(h.securityId())) return 4;
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
}
