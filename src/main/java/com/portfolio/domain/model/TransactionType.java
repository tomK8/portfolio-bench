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
    OPENING_BALANCE,
    /**
     * One leg of an <b>in-specie transfer</b> — the same shares moving from one account to
     * another, with no sale, no purchase and no cash changing hands anywhere. Written in pairs by
     * {@code AssetTransferService}: a positive-amount leg on the account losing the position and a
     * negative-amount leg on the one gaining it, both on the transfer date, both in one database
     * transaction.
     *
     * <p><b>Nothing in a broker export announces this.</b> AJ Bell's cash statement cannot: no cash
     * moves, so no row appears in it (its own "Transfer Out" rows are cash withdrawals, an unrelated
     * thing). The holdings files show it only after the fact, as a position that has vanished from
     * one export and appeared in another. So these rows are typed in.
     *
     * <p><b>The sign convention is the {@code TRANSACTION} one, deliberately.</b> Positive amount =
     * shares leaving, negative = shares arriving, exactly as a sell and a buy read, so
     * {@link com.portfolio.domain.CashLedgerReconstructor} drives its FIFO lot engine off this type
     * with one relaxed guard rather than a parallel branch. The magnitude is <b>the cost basis
     * moving with the shares, not cash and not market value</b> — that is what makes the
     * portfolio's total cost, and therefore its gain, invariant across a transfer.
     *
     * <p><b>What reads it, and what must not.</b> Like {@code OPENING_BALANCE} this benefits from
     * every filter being an allowlist, but the split is different: it joins
     * {@code loadDividendTransactions()} (the per-{@code (account, symbol)} lot engines genuinely
     * need it — the receiving account has no lots for the position otherwise, and its next dividend
     * would fail to attribute) while staying out of {@code loadContributions()} and the TWR's
     * external-flow term, because no money entered or left the portfolio. It is excluded from
     * {@code latestCashBalances()} and from the derived balance chain: a row that moves no cash must
     * not be able to set an account's cash balance. And it is <b>inert</b> in the whole-ledger
     * replays behind the Allocation and Value-over-time charts, which track quantity and cost
     * globally per symbol — there the two legs necessarily cancel, so skipping the pair is exactly
     * right and processing it could only introduce error.
     */
    TRANSFER
}
