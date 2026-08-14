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
        List<String> dailyCloseFallbacks,
        List<AssetRecon> assetRecon,
        /** Held symbols with no price feed at all — see {@code SyncPortfolioService.unpricedSymbols}. */
        List<UnpricedSymbol> unpricedSymbols) {

    /** Per {@code (account, currency)} comparison of holdings-side vs ledger-side cash, in GBP. */
    public record CashRecon(String account, String currency,
                            BigDecimal holdingsGbp, BigDecimal ledgerGbp, BigDecimal diffGbp,
                            boolean warn) {
    }

    /**
     * A held symbol nothing has ever priced, with the Yahoo ticker actually tried — kept as two
     * fields rather than one display string so the dashboard can offer to correct the mapping
     * inline, pre-filled, instead of only naming the problem.
     */
    public record UnpricedSymbol(String symbol, String ticker) {

        /** "PHP" when the ticker is the symbol itself, else "PHP (ticker PHP.L)". */
        public String display() {
            return symbol.equals(ticker) ? symbol : symbol + " (ticker " + ticker + ")";
        }
    }

    /** Per-symbol comparison of holdings-side RT market value vs ledger-side RT market value, in GBP. */
    public record AssetRecon(String symbol,
                             BigDecimal holdingsGbp, BigDecimal ledgerGbp, BigDecimal diffGbp) {
    }

    public static SyncResult empty(Map<String, BigDecimal> rates) {
        return new SyncResult(List.of(), null, rates, BigDecimal.ZERO, List.of(), true,
                List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
