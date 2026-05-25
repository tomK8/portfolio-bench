package com.pension.model;

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
) {}
