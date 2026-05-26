package com.pension.application;

import com.pension.domain.PortfolioMetrics;
import com.pension.domain.model.AggHolding;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Outcome of a portfolio sync, shaped for display. When {@code empty} is true
 * no input files were found and {@code totals} is null.
 */
public record SyncResult(
        List<AggHolding> holdings,
        PortfolioMetrics.Totals totals,
        Map<String, BigDecimal> rates,
        Map<String, BigDecimal> dividendsBySymbol,
        BigDecimal iiSippCash,
        List<String> sources,
        boolean empty) {

    public static SyncResult empty(Map<String, BigDecimal> rates) {
        return new SyncResult(List.of(), null, rates, Map.of(), BigDecimal.ZERO, List.of(), true);
    }
}
