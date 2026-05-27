package com.pension.domain.model;

import java.math.BigDecimal;
import java.util.Currency;

public record AggHolding(
        String securityId,
        BigDecimal quantity,
        BigDecimal avgPricePaid,
        BigDecimal marketValueGbp,
        BigDecimal gainGbp,
        BigDecimal gainPct,      // decimal fraction: 0.125 = 12.5%
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
