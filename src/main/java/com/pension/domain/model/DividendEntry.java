package com.pension.domain.model;

public record DividendEntry(
        String paymentDate,
        String account,
        String symbol,
        String currency,
        double amount,
        double fxToGbp,
        double amountGbp
) {
}
