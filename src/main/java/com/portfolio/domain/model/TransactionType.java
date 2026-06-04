package com.portfolio.domain.model;

/**
 * Cash-ledger row classification. The {@code name()} of each constant is also the
 * persisted value in {@code cash_transactions.type} (kept identical so the existing
 * CHECK constraint and on-disk data are unchanged).
 */
public enum TransactionType {
    TRANSACTION,
    DIVIDEND,
    INTEREST,
    CHARGE,
    CONTRIBUTION
}
