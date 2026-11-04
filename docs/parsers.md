# Broker parsers

How a holdings or cash export becomes `Holding` / `CashTransaction` rows. The three-step recipe for
adding one is in the CLAUDE.md index; this file carries the per-broker rules that the recipe does
not imply, and the reasoning for each.

See also [aj-bell-accounts.md](aj-bell-accounts.md) — two accounts at one broker, and why an
unplaceable export is reported rather than guessed.

## Roth IRA

**Roth exports keep listing a position after it is sold out** — zero shares, zero value, but a
live price — so `RothIraParser` drops any non-cash row whose quantity is exactly zero. The broker
is inconsistent about it (sometimes the row just disappears), so dropping it is also what makes
the view read the same either way. Cash is exempt (a zero balance is a fact about the account,
not a stale row) and negative quantities are kept (a short is a real position).
`DividendService.reconcile` already treats an absent holding as zero shares, so dropping the row
changes nothing there.

**What counts as cash in a Roth export is the broker's own `Asset Classification` column**, not a
list of tickers in `RothIraParser`. The sweep vehicle changes — a bank deposit (`BDP`) until
2026-10-30, a government money fund (`WAGI`) after it — and each change arrives as a new row that a
maintained list would read as an equity, which on a money fund means a $1.00 position the price
jobs then chase. All of them carry `Cash, Money Funds and Bank Deposits`, which is also what the
export's own header totals into the figure the account calls its cash, so one CASH row per account
reconciles against it. `normaliseSecurityId`'s ticker pair stays as the fallback for an export that
states no classification, and is the only test `History*.xlsx` can apply — it has no such column,
and the sweep is booked nowhere in it, so the ledger never sees the money fund at all.

File-detection: `Holdings*.xlsx` (Roth), `portfolio*.csv` (AJBell — SIPP or Dealing, told apart by
the file's own `Portfolio` column), UUID `.csv` (II SIPP holdings; distinguished from II cash by
header sniff), `cashstatements*.csv` (AJBell cash), `History*.xlsx` (Roth cash), UUID `.csv` with
`Debit,Credit,Running Balance` header (II cash).

**Two AJ Bell accounts, and only their holdings export says which.** The cash and order exports are
identical in name, header and format for both, so which account one belongs to is decided on the
instruments it names — and an export that cannot be placed is read by nobody and reported, never
guessed. See [aj-bell-accounts.md](aj-bell-accounts.md).

## Why these are rules and not parser details

Each one is a decision about what the export *means*, taken once, where every reader of the parsed
rows gets the same answer:

- A zero-quantity row is **absent**, not a holding worth nothing — the same distinction as
  "absent is never zero" in the index, applied at the point the data enters.
- Cash is the broker's own **classification**, not our list of tickers, because the sweep vehicle
  changes without notice and a maintained list reads each change as an equity. That is
  "self-regulating over maintained" at the parser boundary.
- Negative quantities are kept: a short is a real position, and dropping it would silently
  understate exposure rather than reporting a gap.
