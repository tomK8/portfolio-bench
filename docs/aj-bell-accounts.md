# Two AJ Bell accounts


AJ Bell holds two portfolios behind one login — the SIPP (`A50742S`) and a Dealing account
(`A50742D`). They export three files each, and only one of the three says which account it is for.

| Export | SIPP | Dealing | Says which account? |
|---|---|---|---|
| holdings | `portfolio-A50742S-SIPP (66).csv` | `portfolio-A50742D-Dealing account.csv` | **yes** — in the filename *and* in a `Portfolio` column on every row |
| cash | `cashstatements.csv` | `cashstatements.csv` | no |
| orders | `activeorders (68).csv` | `activeorders (69).csv` | no |

The cash and order exports are identical in filename pattern, header, column order and reference
format. There is no account number in them, no portfolio field, nothing. The browser suffixes
whichever was downloaded second, so even the numbering is an artefact of click order.

## The rule: a file belongs to the account whose holdings it exclusively names

`AjBellPortfolios.route` (pure) + `AjBellFileRouter` (reads the directory). A statement or order
list names instruments; each account's holdings export says what that account holds; so the file
goes to the account whose positions it names. The holdings export is the registry, and it is a
**live source** — re-exported every time, restating the same fact — rather than a list of "the
dealing account's tickers" kept somewhere, which could only ever be wrong and would go stale the
first time a position was opened or closed. Self-regulating over maintained.

**"Exclusively" is load-bearing.** A symbol both accounts hold says nothing about which export is in
hand, so it is evidence for *neither* rather than for both. Only a symbol unique to one account
decides anything.

**Three outcomes, and two of them are not an account.**

- `RESOLVED` — exactly one account was exclusively named.
- `CONFLICTING` — two were. The file cannot be about both.
- `UNRECOGNISED` — none was.

**Guessing is the one unacceptable outcome.** A statement filed against the wrong ledger corrupts
two accounts at once, and the AJ Bell dedup key is `(date, type, symbol, amount_gbp)` *scoped per
account* — so no later import would repair it, and the error would compound every time the running
balance chain was rebuilt. An unroutable file is therefore read by nobody, left on disk, and
reported: `ImportCashResult.REFUSED` on the import panel, `OpenOrdersService.withFileWarnings` on
the orders panel.

`CONFLICTING` is also the alarm asked for when this was specified — "SIPP will never hold BARC, and
if I ever forget, flag it in huge red letters". It is exactly what a SIPP that has started holding a
dealing-account name produces, and it fires without anybody having named BARC in the code. (The
AJ Bell *ledger* already carries historic BARC — bought 2021-11-09, sold 2022-01-26 — which is why
the registry is built from current holdings and not from traded history.)

## Selection, which is the other half of the problem

Both paths previously took **the single most recent file** matching a pattern. That was right with
one AJ Bell account and silently wrong with two: `activeorders (69).csv` (Dealing) is newer than
`(68)` (SIPP) merely because it was downloaded second, so the SIPP's twelve resting orders would
have vanished from the panel with no error anywhere. The newest file is now chosen **per account**.

On the orders side this needs no change to `OpenOrdersService`'s loop: there is one
`AJBellOrdersParser` per account and each one's `supports` claims only the files routed to it, so
"newest supported file wins" already yields the newest file per account. On the cash side
`AjBellCashImporter` does the selection itself and returns one result row per account.

**Only files that matter are reported.** The input directory holds dozens of archived exports naming
positions long since closed; those are unroutable and uninteresting. The cutoff is the newest
*resolved* file — an unroutable file **newer** than one that could be placed is the real signal that
something was just downloaded and cannot be accounted for.

**Caching.** `supports` now opens files, and `accountFor` is called once per candidate, so the
registry is cached against a fingerprint of the holdings exports' names and mtimes (no content
read). Verdicts are cached per file against mtime, size **and the registry** — a downloaded export
never changes, but the holdings it is judged against do, and the same orders file can route
differently once the other account's positions are known.

**An unmapped instrument name is invisible to routing.** The orders export carries no ticker, so a
name no keyword maps resolves to nothing and contributes no evidence. `barclays → BARC` is in
`AJBellOrdersParser.NAME_TO_SYMBOL` for exactly this reason: it is the dealing account's only
holding and therefore the only thing that can route its exports at all.

## A zero cost is not a cost of zero

AJ Bell reports `Cost (£) = 0.00` for shares transferred in in specie — it never saw the purchase,
so it has no basis to state. Its own `Change (%)` column says `0.00` on the same row, which is the
tell. Read literally, the position's entire market value books as gain: £31,845.28 of pure profit on
the BARC holding that arrived this way. `AJBellPortfolioParser.costOf` therefore reads a zero cost
against a non-zero value as **unknown**, not as zero. Absent is never zero.

Unknown is not where it ends, though. `PortfolioMetrics` skips a null gain from the numerator and
keeps the value in the denominator, so a position left at "unknown" dilutes Return on invested by
its whole size, quietly. The basis is not actually unknown — it travelled into the ledger with the
shares, which is the entire point of recording a transfer at **cost** rather than at market value.
`PortfolioGatherer.fillMissingCostFromLedger` gives such a holding the ledger's FIFO cost, and only
such a holding:

- **only where the export states nothing** — a broker that states a basis keeps it. AJ Bell pools at
  a Section 104 average while this ledger tracks FIFO lots, and silently preferring one over the
  other is how a phantom gain gets manufactured.
- **only when the two agree on the share count.** A disagreement means the ledger's cost is for a
  different number of shares and so is not this position's cost. The Reconcile tab is already
  reporting that; the right answer here is to leave the cost unknown.

## What the second account broke elsewhere

Both of these were latent and only a second account could surface them. Both were found by running
a sync, not by a test.

**Cash reconciled against no account at all.** `reconcileCash` read the *aggregated* holdings, and
the aggregator groups by security — every account's cash is the one security `CASH`. Two accounts
holding sterling merged into a single row whose `sources` read "AJ Bell SIPP, AJ Bell Dealing",
which names no account, so it keyed against none: the panel reported the whole £89,244.09 as drift
twice over, missing from the ledger on one row and missing from the holdings on the other. Cash is
reconciled per account, so it has to be read **before** aggregation — the unaggregated `Holding`
list, one row per source.

**A fourth lot engine that loaded `TRANSFER` rows without replaying them.**
`ManualPositionService.positions` filtered to `TRANSACTION` only, so Equate went on reporting the
BARC it had transferred away while the dealing account's export reported it too: the portfolio held
7,133 shares twice and the Assets reconciliation showed the position at exactly double
(£63,512.23 against £31,756.12). This is the same mistake [transfers.md](transfers.md) records
against three other engines, in a fourth place nobody had thought to look — loading the row is not
replaying it.

## Adding a third AJ Bell account

One constant in `Account`, one entry in `AjBellFileRouter.HOLDINGS_PARSERS` paired with a parser
subclass naming its `Portfolio` column value. The CHECK constraints regenerate from the enum, the
registry and the routing read the map, and the orders parsers are built from
`AjBellFileRouter.accounts()`. Nothing else is account-aware: every account label now comes from
`Account.displayName()`, and the private copies five services carried have been folded into it —
a second AJ Bell account was invisible in every one of them, which is what finally forced it.

## The move that prompted this (2026-09-24)

A BARC position left Equate (the employee share plan, hand-entered) for the new dealing account.
The ledger held 7,227 shares against the 7,133 that arrived; the remaining 94 are stuck at Equate
and were written out of the ledger rather than tracked — the manual rows were shrunk to 7,133 with
the £26,826 cost prorated to **£26,477.08** (£3.7119/share, unchanged), and the whole position then
transferred in specie. Equate's timeline reaches zero, so `CashLedgerReconstructor` drops it from
Sources, the account grouping and the dividend lots on its own, with its history intact.
