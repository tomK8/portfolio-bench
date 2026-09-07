# Manual positions


Some accounts have no downloadable statement at all — an employee share plan, say. Those positions
are typed in on the **Manual positions** tab (Tools) and stored as ordinary `cash_transactions`
rows. `ManualPositionService` owns it; `ManualPositionsController` is a thin
`@RestController` over `/positions/manual`.

**The ledger, not a table of its own — that is the whole design.** There are two position
pipelines: the holdings files feed the Files tab *alone*, while the cash ledger feeds everything
else (Allocation, Value over time, Returns, Period diff, Attribution, sector/style breakdowns,
Live, Concentration, Currency exposure, Correlation, Watchlist gain, Earnings ranking). The replay
is entirely generic — `AllocationService.apply` and `PortfolioValueService.apply` key cash on
`account.dbValue() + "|" + currency` and derive quantity from the **sign of `amount`** — so a
hand-entered row reaches all of them with no consumer written for it. A `manual_positions` table
would have been a second source of truth that all ~15 consumers had to learn about, and a sale
would then have to be recorded in two places.

**Each acquisition is two rows**, and the pairing is load-bearing:

| type | symbol | qty | amount |
|---|---|---|---|
| `OPENING_BALANCE` | `GBP` | 0 | `+cost` |
| `TRANSACTION` | `BARC` | 7227 | `−cost` |

The buy alone would drive the account's cash to −£cost everywhere the replay sums `amount`
forward, because those replays derive cash rather than reading the stored balance.

**Why `OPENING_BALANCE` rather than `CONTRIBUTION`.** Shares that vested from an employer are not
money anyone paid in, and the existing brought-forward mechanism (the Roth USD seed) is a KV scalar
special-cased in four services, each hardcoding `"RothIRA|USD"` — generalising it meant four edits
and a second special case in each. A new `TransactionType` gets the same semantics almost for free,
because **every existing read filter is an allowlist**: `loadContributions()` is
`type = 'CONTRIBUTION'` (so the Contributions chart and Period diff's `contributionsGbp` stay
honest), `loadDividendTransactions()` is `type IN ('TRANSACTION','DIVIDEND')` (so it is neither a
position nor an invested-pool flow), and the unfiltered `loadAllTransactions()` picks up the cash,
which is what has to happen. The **one** place it is added deliberately is
`PortfolioReturnService.broughtForwardByDate()`, so the step lands in the TWR's `C` term and in
Period diff's existing brought-forward tile + warning instead of booking as a one-day gain.
A **withdrawal** is the mirror — a negative `OPENING_BALANCE` — so money leaving the portfolio
reads as an external flow rather than a loss.

**`Account.isManual()` is a property of the account, not a list held elsewhere**, so
`manualAccounts()` drives both the screen's broker dropdown and the holdings projection. The write
path (`CashTransactionRepository.saveManual` / `deleteManualRow`) **refuses an imported account**:
a row deleted there would simply return on the next import, and the ledger has no other undo.
There is deliberately **no dedup rule** — the three broker paths exist because an export re-states
rows the ledger already holds, whereas a person fills in a form once; the primary key is still the
backstop against entering the same buy twice. Balances are **recomputed, never supplied**
(`rebuildBalanceChain`, anchor 0): a manual row is nearly always back-dated, so it lands ahead of
rows already stored and every balance from there on would otherwise be out of sequence.

**The holdings-file side is derived from the ledger, not stored twice.**
`ManualPositionService.holdings()` reconstructs via `CashLedgerReconstructor` and emits `Holding`s
that `PortfolioGatherer` appends after the file parsers (a failure there is caught — the broker
exports parsed fine and are worth showing). That one hook buys three things: the position appears
on the Files tab and in the aggregated Excel sheet; it enters `held_symbols` (so the fundamentals,
earnings and price-fetch universes pick it up); and `SyncPortfolioService.reconcileAssets` sees the
symbol on **both** sides rather than reporting a position present in one source as permanent drift.
`sources` gains the account under a **null path** — `ExcelReportWriter` skips a source with no file
rather than trying to copy a raw sheet for it. An unpriced position keeps a **null** market value,
never zero: a zero is summed into the portfolio total as a real figure, a null is reported.

Prices need no work — `BARC=BARC.L` is already in `yahoo-tickers.properties`, and
`distinctTradedSymbols()` (`type IN ('TRANSACTION','DIVIDEND')`) picks the symbol up automatically
once the buy row exists. Every write still kicks a background `PriceFetchJob.fetchSingle` +
`IntradayPriceFetchJob.fetchNow` on a daemon thread, because the cash-ledger view **drops** any
equity with no intraday quote — a freshly entered symbol would otherwise be invisible on that tab
until the polling pass came round. The render never waits.

**Two fixes that came out of this work**, both worth keeping in mind:
- `loadRows`/`mapCashRow` are now the single query-and-map path behind every `CashTransaction`
  read (four near-identical copies before). An unknown `account` value **skips its row with a
  WARN** instead of throwing inside a `catch` that logged and returned the rows gathered so far —
  which silently truncated the *entire ledger* at that row, everywhere it is read.
- `SyncFromCashService` suppresses a CASH row whose GBP balance is exactly zero. Every account
  whose cash is fully deployed reports one, and it is noise in a value table.


## Panel overview

- **Manual positions** — `GET /positions/manual` → JSON, rendered as tables + three forms under the
  Tools group. Positions at an account with no downloadable export (Equate, the employee share
  plan), typed in and stored as ordinary `cash_transactions` rows. See the Manual positions section
  below.

