package com.portfolio.domain.model;

import java.math.BigDecimal;
import java.util.Currency;

public record AggHolding(
        String securityId,
        BigDecimal quantity,
        BigDecimal avgPricePaid,
        BigDecimal marketValueGbp,
        BigDecimal gainGbp,         // price appreciation only
        BigDecimal gainPct,         // decimal fraction: 0.125 = 12.5%
        BigDecimal dividendGbp,     // dividends received (£); never null, ZERO when none
        BigDecimal totalGainGbp,    // gainGbp + dividendGbp; null when gainGbp is unknown
        BigDecimal totalGainPct,    // totalGainGbp / cost; null when cost is unknown/zero
        Currency currency,
        String sources
) {
    /**
     * ISO code (e.g. "USD") — lets views read the currency without navigating java.util.Currency.
     */
    public String currencyCode() {
        return currency.getCurrencyCode();
    }
}
