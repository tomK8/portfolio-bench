# Persistence


`com.portfolio.persistence` is the single owner of JDBC traffic. All repos route through
`JdbcConnectionFactory`; each repo runs its DDL once in its constructor (not on every read/write).

- `SnapshotRepository` — `portfolio_snapshots` (one row per snapshot date; re-run same day
  overwrites). **Write-only** since the Period-diff panel started valuing its own boundaries —
  kept as an audit trail of what each broker reported at sync time, read by no view.
- `CashTransactionRepository` — `cash_transactions`; per-broker save methods (`saveAjBell`,
  `saveRothIra`, `saveII`) hold each broker's dedup + balance-derivation rules. Reads:
  `loadDividendTransactions` (TRANSACTION + DIVIDEND, for dividend FIFO and the cash-ledger
  view), `loadContributions` (CONTRIBUTION-only, for the Contributions chart),
  `loadAllTransactions` (everything in date order, for the Value-over-time forward
  replay), `latestCashBalances` (latest stored cash per `(account, currency)`,
  feeds the cash-ledger view's CASH rows and the holdings view's reconciliation panel),
  `earliestTransactionDate(Account)` (anchors Roth's seed), `distinctTradedSymbols`.
- `PriceHistoryRepository` — `price_history` (daily OHLCV).
- `DividendHistoryRepository` — `dividend_history` (one row per `(symbol, ex_date)`, keyed on the
  **Yahoo ticker** like `price_history`) + `dividend_scan` (per-symbol "full window read" marker).
  See "Derived dividend yields" in [price-history.md](price-history.md). Reads and writes both degrade — it rides on a background job.
- `IntradayPriceRepository` — `price_intraday` (1-min closes). `loadLatestIntradayPrices` is a bulk
  query, not N round-trips.
- `FxRateRepository` — `fx_rates` (one row per `(currency, date)`, rate in the app-wide
  foreign-per-GBP convention). Cache behind `application/CachedHistoricalFxRateProvider`, which
  is what `BeanConfiguration` wires as the `HistoricalFxRateProvider` bean — so all ~13
  `fetchRateSeries` callers inherit it. Published past rates never change, so coverage is the
  interval `[earliest, latest]` and only an uncovered head/tail is fetched; the tail is
  re-checked at most once per 15 min per currency (a burst of tab clicks stampeding Frankfurter
  is what caused the timeouts in the first place) — and a gap the source can never fill costs
  nothing: a weekend-only gap is skipped outright (Frankfurter follows ECB working days, so on a
  Sunday the series legitimately ends Friday), and a re-check that returns nothing newer backs off
  to `NO_PROGRESS_TTL` (1h) instead of re-asking every quarter hour until today's ~16:00 CET
  publication. A `Clock` is injectable purely so those calendar rules are unit-testable. **On a fetch failure it serves what's cached**
  and only throws when the range is entirely uncached. `PriceFetchScheduler` warms the full
  history on startup — 2000→today for USD+EUR is one call each, ~13,600 rows total, so a cold
  start during a Frankfurter outage still prices non-GBP positions. Reads *and* writes degrade.
- `EarningsDateRepository` — `earnings_dates` (§D earnings-proxy cache: one row per `(symbol, date)`;
  reads/cache-fill degrade, never throw).
- `InstrumentMappingRepository` — `ticker_mappings` (symbol → Yahoo ticker, overriding
  `yahoo-tickers.properties`) + `instrument_aliases` (description keyword → symbol). Reads degrade,
  the dashboard's writes throw. See [instrument-mappings.md](instrument-mappings.md).
- `KeyValueStore` — file-backed scalar settings, one `<key>.txt` per key under `<db-dir>`:
  - `ii_sipp_cash_last` — last II SIPP **GBP** cash balance entered on the dashboard.
  - `ii_sipp_cash_last_usd` / `ii_sipp_cash_last_eur` — the same for **USD** and **EUR**; one key
    per box on the form, declared in `IiCashField`. Each is converted via current FX before
    summing into the holdings view's totals. The sterling key carries no currency in its name
    because sterling was the only box when it was chosen — renaming it now would strand a
    balance II's export cannot supply again.
  - `roth_balance_brought_forward` — RothIRA opening balance in **USD**, used to seed the
    running balance on first import. Charts must convert to GBP via historical FX
    (see `ContributionService.rothSeedAsGbp`, `PortfolioValueService.timeline`) — treating
    the file as GBP would understate the gap substantially.
  - `orders_use_drift` — the app-wide drift switch for the open-orders touch model
    (`true`/`false`; absent = the shipped driftless default). Written by the checkbox on the Open
    orders panel, read by `OpenOrdersService.effectiveConfig` — see [open-orders.md](open-orders.md).
  - `gilt_sedols` — SEDOL → `GILT {coupon}% {year}`, `key=value` lines, merged on every
    `GiltPriceFetchJob` tick. Read by `IiCashImporter`; see "Gilt SEDOLs" in [price-history.md](price-history.md).
    (`getStringMap`/`putStringMap` split on the **first** `=` only — a gilt id contains a space
    and a `%`, and splitting on whitespace would mangle it.)
  - `held_symbols` — newline-separated set of currently-held symbols, written by
    `SyncPortfolioService` after each `/sync`. Unioned with the cash-ledger symbol set in
    `PriceFetchSupport.tickersToFetch` so freshly bought names get an intraday quote before
    their cash statement is imported.

**Account / TransactionType persistence:** the enums map to TEXT columns via `Account.dbValue()`
("AJBell"/"RothIRA"/"II"/"Equate") and `TransactionType.name()`. The CHECK constraints are
**generated from the enums** in `TABLE_BODY`, and `migrateConstraints` rebuilds an existing table
whenever the stored DDL no longer lists every constant — so adding an account or a row type is one
enum constant and no SQL. It reads the stored `CREATE` back from `sqlite_master` (there is no
PRAGMA for CHECK expressions) and compares the constraint lists only, not the whole statement:
SQLite stores the original text verbatim, so comparing the body would rebuild on every start after
a cosmetic edit. Without this the gap is invisible until the first insert of a new value — the
create is `CREATE TABLE IF NOT EXISTS`, so a fresh install accepts what an existing database still
rejects, which is exactly how the primary key drifted in June 2026. Renaming a constant still
needs a data migration; only adding one is free.

**Write paths throw, reads degrade.** `SnapshotRepository.saveSnapshot` and the three
`CashTransactionRepository.save*` methods throw `IllegalStateException` on failure
(`@ExceptionHandler` renders `fragments/error`). Query methods and background-job inserts
(`savePriceBars`, `saveIntradayBars`) log + return empty/0 — they must not crash the scheduler.

**Migrations:** see `CashTransactionRepository`'s constructor for the idempotent pattern
(`CREATE TABLE IF NOT EXISTS` + `try { ALTER TABLE … } catch (SQLException ignored) {}`).


## Cash dedup keys & broker import rules

**Cash dedup keys (one per broker, all cross-file):**
- AJBell: `(transaction_date, type, symbol, amount_gbp)`. AJ Bell can retroactively insert a
  dividend mid-file, which shifts every later row's running balance — so identity is the row
  itself, not its position. Re-importing UPDATEs `cash_balance_gbp` on matching rows.
  Integrity check: if the file's earliest row is newer than every stored AJBell row, the
  import **throws** as a gap (a window has been lost between exports). Legacy duplicates
  from prior (date, balance)-keyed imports are collapsed on next load — highest-rowid wins.
- RothIRA: `(date, symbol, quantity, type, amount)` — native USD amount, FX-stable across
  imports. Balance continues from latest stored `cash_balance`, or the
  `roth_balance_brought_forward` KV seed (USD) on first import. **Plus a second, group-level
  pass (`repartitionedFills`), because the broker re-partitions fills between exports.** On
  2026-08-19 a 50-share MRNA sale exported as two partial fills (37 + 13); the next day's export
  carried the same sale as one aggregated row with a different description ("Unsolicited Order
  Allocated Order Your Broker Acted As Agent"). `-50/7350` matched neither `-37/5439` nor
  `-13/1911`, so the row-level key read it as a *third* sale: 100 shares disposed of against a
  122-share holding, the position zeroed and USD cash overstated by $7,350 (£5,394). The guard
  reconciles per `(date, symbol, type)` group — if the unmatched rows leave the group's **net
  quantity and net amount** unchanged (tolerances `FILL_QTY_EPSILON` 1e-6 / `FILL_AMOUNT_EPSILON`
  0.005), it's the same economic event re-partitioned. **The file's version then supersedes the
  stored one**: the stored rows are deleted and the file's inserted, in either direction
  (aggregate→split too). Adopting rather than merely skipping is what keeps the ledger converging
  on the broker's own row shape — skipping would leave the partials stored forever against an
  aggregate every later export repeats, so the plain row-level key would never match again and
  the group guard would have to fire on every import from then on. Adopted, the row key matches
  by itself and the guard is a second line of defence rather than the only one. A group whose
  totals genuinely changed — a further trade in the same name later the same day — is deliberately
  left alone and dedups row by row as before, so real activity is never swallowed.
- **The adopt path rebuilds the derived balance chain.** Adopted rows are appended at the tail, so
  they sort after anything else already stored on their date (19 Aug held MRNA fills *and* a WMT
  buy) and every balance from there on is out of sequence. `rebuildBalanceChain` recomputes
  `cash_balance`/`cash_balance_gbp` for the whole account as `anchor + running sum of amounts`;
  the anchor is re-derived from the first stored row as `cash_balance − amount`, **not** from the
  `seed` parameter, which `RothIraCashImporter` passes as zero on every import after the first.
  Delete + insert + rebuild run in one transaction, and the rebuilt closing balance is asserted
  against `lastBalance + Σ(genuinely new amounts)` — a re-partitioned group nets out unchanged by
  definition, so any drift means the adoption was wrong and the whole thing rolls back. With no
  usable anchor it degrades to skip-only and logs a WARN.
- The holdings-vs-ledger reconciliation panel is what surfaced this, not the importer. AJ Bell and
  II have the same shape of key and are theoretically vulnerable; neither has ever shown the
  behaviour, so the guard is Roth-only.
  Row-level dedup is a **multiset**, not a set: `existingKeys` counts occurrences and each file row
  *consumes* one (`consumeStoredRow`). Identical rows being real means plain set membership let the
  n-th identical file row match the same single key as the first, so a genuinely new repeat of an
  already-stored fill was skipped in silence — and that is exactly the case `repartitionedFills`
  declines to handle, since a group whose net has changed is real activity and is meant to fall
  through to row-by-row dedup.
- **The table's own primary key is `(transaction_date, account, type, symbol, amount_gbp,
  cash_balance_gbp)`, and `cash_balance_gbp` is in it on purpose.** A broker can work one order as
  two partial fills of the same size at the same price, producing rows identical in every other key
  column — Citi did exactly that with MRNA on 2026-08-21 (two sales of 3 shares at 148.23, $444.69
  each, inside a six-fill order). Both are real events and both must be storable; the running
  balance is what separates them, because the second fill lands on top of the first.
  The column joined the key in `67b1544` (2026-06-04) but no migration came with it, and the create
  is `CREATE TABLE IF NOT EXISTS` — so a database made before then kept the old five-column key
  while every test and every fresh install ran on the six-column one. Invisible until two identical
  fills arrive, at which point the import dies on a PK violation with the dedup logic having done
  nothing wrong. `migratePrimaryKey` (constructor, after the `cash_balance` ALTER so the rebuild can
  copy that column by name) detects the gap with one `PRAGMA table_info` read and rebuilds the table
  transactionally, asserting the row count across the copy. **The copy names all twelve columns on
  both sides**: a legacy table carries `cash_balance` appended after `description` rather than in
  its declared position, so `SELECT *` would transpose the two balances into the description column.
  `TABLE_BODY` is shared by the create and the rebuild so the schema has one definition — two copies
  is how it drifted in the first place.
- II: `(date, type, symbol, amount, currency)` within `account='II'`. The currency component
  isolates the GBP and USD ledgers within the shared account so dividend FIFO still groups by
  `(account, symbol)` across currencies.

