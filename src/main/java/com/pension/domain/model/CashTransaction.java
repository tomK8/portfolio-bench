package com.pension.domain.model;

public record CashTransaction(
        String transactionDate,   // YYYY-MM-DD
        String account,           // e.g. "AJBell"
        String type,              // TRANSACTION | DIVIDEND | INTEREST | CHARGE | CONTRIBUTION
        String symbol,            // ticker, GILT id, or "GBP" for non-security rows
        double quantity,          // shares / nominal; 0 when not applicable
        double amount,            // positive = cash in, negative = cash out
        String currency,          // "GBP" for AJBell, "USD" for RothIRA
        double fxToGbp,           // foreign units per 1 GBP; 1.0 for GBP accounts
        double amountGbp,         // same as amount when currency is GBP
        Double cashBalance,       // running balance after this row in native currency; nullable
        Double cashBalanceGbp,    // running balance after this row in GBP; nullable
        String description        // raw description from the source file
) {
}
