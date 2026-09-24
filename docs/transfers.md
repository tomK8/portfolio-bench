# In-specie transfers


Shares moving from one account to another with nothing sold, nothing bought and no cash moving —
Tools → **Transfers**. `AssetTransferService` + `TransfersController`; the rows are ordinary
`cash_transactions` of the new `TransactionType.TRANSFER`.

**Nothing in a broker export announces this, which is why it is typed in.** A cash statement
cannot: no cash moves, so no row appears in it. (AJ Bell's own "Transfer Out" rows *are* in the
cash statement and are an unrelated thing — cash withdrawals, classified `CONTRIBUTION`.) The
holdings files reveal it only after the fact, as a position that has vanished from one export and
turned up in another. So the ledger went on reporting seven positions at AJ Bell after they had
moved to II — right about what was held, wrong about where — and no import would ever have fixed
it. Grep confirmed the transfer advice the user was reading exists in **no** downloadable file.

**Two rows, one per account, one transaction.** Positive amount = shares leaving, negative =
arriving — the `TRANSACTION` sign convention, deliberately, so `CashLedgerReconstructor` drives its
FIFO lot engine off the new type with one relaxed guard instead of a parallel branch that could
drift. Both legs or neither: every consumer assumes the pair exists, and a lone leg would make the
portfolio gain or lose a position outright.

**The magnitude is the cost basis, not cash and not market value, and the tool computes it rather
than asking.** It is the source account's own surviving FIFO cost, so total cost — and therefore
gain — is invariant across the move. Market value would rewrite the basis and destroy the gain.
The broker's figure would be *almost* right and quietly wrong: **AJ Bell pools cost at a Section
104 average while this ledger tracks FIFO lots.** On the live book they agreed to the penny for six
of the seven positions (the paste's "value" column *is* book cost — GOOG £37,663.74, BNP
£30,067.75, INTC £1,662.02 all reconciled exactly against the stored buys) and disagreed on
Societe Generale, where AJ Bell says £2,085.35 and FIFO says £1,943.34. Taken from the statement,
that £142 would have booked itself as a phantom loss the moment the transfer was recorded.
**That FIFO-vs-pooling divergence is pre-existing and portfolio-wide**, not something transfers
introduced — it is simply the first place it became visible.

**Why no account column is rewritten.** The obvious "nifty database update" — re-key the existing
AJ Bell rows to II — is unusable: the buys carry negative amounts that debited AJ Bell's cash, so
moving them retroactively corrupts both accounts' balance chains and mis-attributes dividends
already received; and the AJBell dedup key is `(transaction_date, type, symbol, amount_gbp)` within
the account, so the next cash import would re-insert the originals and double-count the position.

**The attribution moves for free.** `CashLedgerReconstructor` already drops a `(account, symbol)`
timeline whose quantity reaches zero *before* building the `sources` set, so emptying the source
account's lots removes it from every Sources column, from the account grouping and from the
per-account dividend lots, with no separate notion of "where is this held". `reconstructByAccount`
is the un-merged form the transfer screen reads to find the position and its cost.

**What the type does and does not reach**, given every read filter is an allowlist:
- **In** `loadDividendTransactions()` (`TRANSACTION`/`DIVIDEND`/`TRANSFER`), because the
  per-`(account, symbol)` lot engines need it — the receiving account would otherwise hold no lots
  for the position and its next dividend would fail to attribute. Also in `distinctTradedSymbols()`,
  so a name that only ever arrived in-specie still gets a price feed.
- **Out of** `loadContributions()` and `broughtForwardByDate()`: no money entered or left, so it is
  neither a contribution nor an external flow in the TWR. `PositionIrr` needs no change at all —
  it admits only `TRANSACTION`/`DIVIDEND` as flows, so a transfer contributes none, and its
  episode walk sums quantity across accounts where the pair nets to zero.
- **Excluded from `latestCashBalances()` and from the derived balance chain.** The amount is a cost
  basis; letting it set an account's reported balance would wipe out tens of thousands of pounds
  that never moved, and inside `rebuildBalanceChain`'s running sum the error would propagate to
  every later row. `SUM(CASE WHEN type = 'TRANSFER' THEN 0 ELSE amount END)`.
- **Inert in both whole-ledger replays** (`AllocationService.apply`, `PortfolioValueService.apply`
  and `applyWithCost`). Skipping the pair is not a shortcut but the only correct handling: those
  track quantity and cost **globally per symbol**, so the legs necessarily cancel — and processing
  them would *introduce* error, because the weighted-average cost the outgoing leg releases is
  taken against the global pool, which for a name held in several accounts is not the cost the
  losing account carried.

**Storage details.** The legs carry a **zero** `cash_balance`, which is a placeholder rather than a
claim: `cash_balance_gbp` is part of the primary key, and a null there would make two identical
transfers distinct to SQLite — the duplicate guard would fail exactly when wanted. Nothing reads
the value; both queries that could are filtered on the type. `deleteTransfer` is **not** restricted
to manual accounts (unlike `deleteManualRow`) and safely so: a `TRANSFER` row exists in no broker
export, so no import can re-create or collide with it. It asserts `type = 'TRANSFER'` in the
statement and rolls back unless exactly two rows match — half a transfer is worse than none.

**Loading the row is not replaying it.** Three engines replay the ledger per `(account, symbol)` —
`CashLedgerReconstructor`, `PositionDetailService.LotEngine` and `DividendAttributor.Timeline` — and
`loadDividendTransactions()` hands the `TRANSFER` rows to all three. Only the first acted on them.
The other two dropped the type on their opening guard (`if (t.type() != TRANSACTION) return;`, and a
`switch` with no `TRANSFER` arm), so for them the shares never left the losing account and never
arrived at the gaining one. Nothing failed loudly, because a transfer both legs ignore is
self-consistent: the totals still reconcile, the position simply sits in the wrong account. It
became visible only when a transferred position was **sold at its new account** — INTC: 300 bought
at AJ Bell, 200 sold there, 100 moved to II on 2026-08-20, all 100 sold on 2026-09-17.

- **Position tab.** AJ Bell still held the 100, so the II sale found no lots to consume and emitted
  no closed lot. The exit dropped out of Realized (£3,267 rather than £9,744) and the shares stayed
  on the books as £7,570 of unrealized gain on a position that no longer existed. The chart drew
  the sale anyway — markers come straight from the ledger, not from the lot engine — so the tab
  showed a sale its own table denied, which is what the report was.
- **Dividend attribution.** The gaining account held no lots, so a dividend it received on
  transferred shares divided by a zero share count and was "attributed to nothing": GOOG's £48.95
  of 2026-09-15, silently, with no reconciliation warning because the losing account's phantom
  shares still made the symbol's total come out right.

**The lots travel; they are not closed and re-opened.** Both engines now hand the consumed lot
slices across the pair, so the gaining account keeps each slice's original open date, its own
per-share cost and its native currency. Closing at the source and opening one blended lot at the
destination would book a fake disposal, re-date every lot to the day of the move and average away
lots bought at different prices. `DividendAttributor` carries one thing further: each slice brings
the dividend per share it had accrued, re-based onto the gaining timeline's cumulative — shares that
moved were not sold, so they do not forfeit what they earned. Both replay in **ledger order** rather
than timeline by timeline, which is what guarantees the legs are seen leaving-first whichever
account happens to appear in the ledger first.

**A dividend can arrive after the shares have gone.** The payer pays whoever held the stock on the
record date, and that can precede the move by weeks: AJ Bell was paid £16.87 on MSFT on 2026-09-10
for 25 shares that had left on 2026-08-20. Attributing it where it landed would attribute it to
nothing, so a payment reaching a timeline the transfer **emptied** follows the shares to their new
home. A timeline that still holds the name — it bought it again, or moved only part of it — is the
holder of record itself and keeps its own payment.

**A position the ledger has fully exited is measured at zero, not unknown.** "Absent is never zero"
governs a figure we could not obtain; nothing held is a figure we have. So on zero shares the
Position tab reports market value £0 and unrealized £0, and Total return is realized + dividends —
where before it showed `—`, because market value came back null and dropped total return with it. A
position we *do* hold whose value cannot be established stays null exactly as it was: the test is
whether shares are held, never whether the number happens to be zero.

**In-flight is handled by recording early, not by a new state.** Enter the transfer the day you
initiate it and the ledger says the destination immediately; until the shares arrive, the Reconcile
tab reports the position as present in the ledger and absent from the destination's export, which
*is* the in-flight signal, at no extra machinery. GLE was the live case — out of AJ Bell, not yet
in II — and is recorded like the rest.

**The whole position moves.** Partial transfers are representable (the rows carry a quantity) but
not offered: a partial move would need its cost split at some convention and the lots reordered at
the destination, and nothing has needed it. Quantity and cost are read off the ledger and shown for
confirmation rather than typed.

**First recorded live on 2026-08-20** — seven positions, AJ Bell → II SIPP, in one sitting. The
check that mattered: cash, Return on invested and Return on total came out identical either side,
and total value/total gain moved by the same small amount, which was market drift between the two
syncs rather than anything the transfer did.

