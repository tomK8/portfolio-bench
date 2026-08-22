package com.portfolio.domain.model;

/**
 * Cash-ledger row classification. The {@code name()} of each constant is also the
 * persisted value in {@code cash_transactions.type}; the column's CHECK constraint is
 * derived from these constants at DDL time, so adding one here is all a new kind of row
 * needs — the repository migrates an existing database on the next start.
 */
public enum TransactionType {
    TRANSACTION,
    DIVIDEND,
    INTEREST,
    CHARGE,
    CONTRIBUTION,
    /**
     * An account's opening balance entering the ledger — money that was already there when
     * the recorded history starts, not money anyone paid in during it.
     *
     * <p>Every other read filter in {@link com.portfolio.persistence.CashTransactionRepository}
     * is an allowlist, so this type is excluded from them by construction: it is not a
     * {@code CONTRIBUTION} (so the Contributions chart and Period diff's contributions figure
     * stay honest), and not a {@code TRANSACTION}/{@code DIVIDEND} (so it is neither a position
     * nor an invested-pool flow). It is picked up only by the unfiltered
     * {@code loadAllTransactions()} replays, where it must count — the account's cash genuinely
     * steps by it — and by the TWR's external-flow term, so the step does not book as a one-day
     * gain. Labelling such a step a contribution once made a £138,857 account opening read as
     * £138,857 paid in.
     */
    OPENING_BALANCE
}
