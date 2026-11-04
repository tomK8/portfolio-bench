package com.portfolio.domain.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * One ticker's snapshot. Nulls mean "not provided by Yahoo for this instrument" (typical
 * for UK ETFs, bonds, ADRs). {@code missing=true} on full fetch failure so the UI can show
 * a placeholder row rather than dropping the symbol.
 */
public record QuoteSummary(String symbol, String currency,
                           BigDecimal price, BigDecimal marketCap,
                           BigDecimal trailingPe, BigDecimal forwardPe, BigDecimal pegRatio,
                           BigDecimal beta, BigDecimal week52High, BigDecimal week52Low,
                           BigDecimal targetMeanPrice,
                           Map<String, BigDecimal> extra,
                           Map<String, String> labels,
                           Map<String, BigDecimal> sectorWeights,
                           List<FundHolding> topHoldings,
                           boolean missing) {

    /**
     * Back-compatible form for every caller that has no fund composition to supply — which
     * is all of them outside the Yahoo parser, since composition only ever comes from Yahoo.
     */
    public QuoteSummary(String symbol, String currency, BigDecimal price, BigDecimal marketCap,
                        BigDecimal trailingPe, BigDecimal forwardPe, BigDecimal pegRatio,
                        BigDecimal beta, BigDecimal week52High, BigDecimal week52Low,
                        BigDecimal targetMeanPrice, Map<String, BigDecimal> extra,
                        Map<String, String> labels, boolean missing) {
        this(symbol, currency, price, marketCap, trailingPe, forwardPe, pegRatio, beta,
                week52High, week52Low, targetMeanPrice, extra, labels,
                Map.of(), List.of(), missing);
    }

    public static QuoteSummary empty(String symbol) {
        return new QuoteSummary(symbol, null, null, null, null, null, null, null, null,
                null, null, Map.of(), Map.of(), true);
    }

    /**
     * One named constituent of a fund. {@code weight} is a fraction of fund NAV (0.0804 =
     * 8.04%), in the same scale as {@link #sectorWeights}.
     */
    public record FundHolding(String symbol, String name, BigDecimal weight) {
    }
}
