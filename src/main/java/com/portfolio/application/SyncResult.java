package com.portfolio.application;

import com.portfolio.domain.PortfolioMetrics;
import com.portfolio.domain.model.AggHolding;

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
        BigDecimal iiSippCash,
        List<String> sources,
        boolean empty,
        List<CashRecon> cashRecon,
        List<String> missingIntradayPrices,
        List<AssetRecon> assetRecon) {

    /** Per {@code (account, currency)} comparison of holdings-side vs ledger-side cash, in GBP. */
    public record CashRecon(String account, String currency,
                            BigDecimal holdingsGbp, BigDecimal ledgerGbp, BigDecimal diffGbp,
                            boolean warn) {
    }

    /** Per-symbol comparison of holdings-side RT market value vs ledger-side RT market value, in GBP. */
    public record AssetRecon(String symbol,
                             BigDecimal holdingsGbp, BigDecimal ledgerGbp, BigDecimal diffGbp) {
    }

    public static SyncResult empty(Map<String, BigDecimal> rates) {
        return new SyncResult(List.of(), null, rates, BigDecimal.ZERO, List.of(), true,
                List.of(), List.of(), List.of());
    }
}
