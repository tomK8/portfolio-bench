package com.pension.domain;

import com.pension.domain.model.DividendEntry;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Converts a dividend payment into its GBP equivalent using FX rates expressed
 * as units of foreign currency per 1 GBP. Pure domain logic; previously embedded
 * in the Swing dividend dialog.
 */
public class DividendCalculator {

    public DividendEntry toEntry(String paymentDate, String account, String symbol,
                                 String currency, double amount, Map<String, BigDecimal> rates) {
        double fxToGbp = switch (currency) {
            case "USD", "EUR" -> rates.getOrDefault(currency, BigDecimal.ONE).doubleValue();
            default -> 1.0;
        };
        double amountGbp = "GBP".equals(currency) ? amount : amount / fxToGbp;
        return new DividendEntry(paymentDate, account, symbol, currency, amount, fxToGbp, amountGbp);
    }
}
