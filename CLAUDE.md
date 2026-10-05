# CLAUDE.md

Guidance for Claude Code in this repository. Prefer reading the code; this file covers the
cross-cutting bits that aren't obvious from any single file.

**This file is the index, not the manual.** It holds what applies everywhere — layering, the
FX and null conventions, the endpoint map, the "how to add X" recipes — plus a routing table
to `docs/`, which holds the per-feature detail and the reasoning behind it. Read the relevant
`docs/` file *before* changing code in that area; each index line names the invariant it
guards, so the guard fires even if you never open the file.

Do not use `@docs/...` import syntax here. Claude Code inlines `@` imports at startup, which
would put every doc back into every session and defeat the split.

## Build, test & run

```bash
mvn compile                            # compile
mvn test                               # excludes @Tag("integration")
mvn test -Dtest=AJBellSippParserTest   # single class
mvn package -DskipTests                # jar
mvn spring-boot:run                    # foreground (dev)
scripts/start.sh / scripts/stop.sh     # daemon (writes ~/log/portfolio-bench.log)
node --test src/test/js/*.test.mjs   # JS unit tests
```

Server binds to loopback only, no auth — single-user local tool. Override directories with
`--portfolio.input-dir=…`, `--portfolio.db-dir=…`, `--portfolio.output-dir=…` (blank → defaults
`~/Downloads`, `~/Documents/Investing`, `~/Documents`).

Daemon mode: `start.sh` backgrounds `target/portfolio-bench-1.0-SNAPSHOT.jar`, writes a PID file,
rotates the previous log to a timestamped name. The JVM's stdout/stderr is redirected into the
log file, so non-Logback output (early classloader errors, OOM dumps) is captured too.
`spring.main.banner-mode=log` routes the Spring banner through SLF4J.

**`start.sh` runs a prebuilt jar and never rebuilds.** After any change: `mvn package` first,
then restart — otherwise you are testing the old build.

### Demo mode

```bash
python3 scripts/make-demo-data.py   # demo/input and demo/db
scripts/demo.sh                     # :8081, beside the real daemon
```

The same jar pointed at `demo/`, on sample data. Runs on its own port/PID/log so `start.sh` is
unaffected, and `--portfolio.demo=true` (command line, which outranks
`~/.portfolio-bench.properties`) puts a banner on every page.

**Figures come from a generator profile outside this repository** (`PB_DEMO_PROFILE`, default
`~/.portfolio-bench-demo/profile.py`); there is no fallback and nothing builds without it. How
it produces them is not documented here, in the code, in the history or in the UI — do not add
it. `demo/` and the profile are both uncommitted; this repo is public.
See [docs/demo-mode.md](docs/demo-mode.md).

### Test fixtures

`src/test/resources/` **must contain only synthetic data — no real brokerage exports.** Fixtures
are committed so parser tests can load files from disk. Real exports were never committed; keep
it that way. Regenerate `Holdings.xlsx` with openpyxl if its shape needs to change.

## Architecture

Spring Boot web app, layered ports-and-adapters. **Spring is confined to `web` and `config`**;
`domain`, `application`, `adapter`, `parser` and `persistence` are framework-free.

- `domain` — pure logic. `PortfolioAggregator` (two-pass: `groupAccumulators` →
  `buildAggHolding`; keyed on `(securityId, currency)`, **plus the account for cash** — a security
  pools across accounts, a cash balance never does), `PortfolioMetrics` (totals + `rtTotalGbp` for intraday valuation),
  `DividendAttributor` (FIFO), `CashLedgerReconstructor` (FIFO position-from-ledger
  reconstruction), `Irr` (the app's only XIRR solver) + `PositionIrr`, `Instruments`
  (`isBond`, `giltId`, `couponOf`, `assetClassOf` — single home of all four), `DividendYield`,
  `Reminders` (when a reminder is due, and the once-a-day key),
  `OrderFills` (the rule that reads a vanished order's fill out of the cash ledger),
  `AjBellPortfolios` (which AJ Bell account an export that does not name one belongs to),
  `VolForecast` + `AssetClass` (the open-orders touch model), `Ladders` (rung layout + per-account
  cash), `RoundTrips` (the path a ladder's next round trip has to walk), `Switches` (share-class switch: one yardstick, the index/FX split, progress in units),
  `ShareClasses` (the declared one-holding pairs), `PriceStats`, `StyleClassifier`,
  `SectorClassifier`, `IndustryGroups`. Sub-packages `domain.swing` / `domain.trend` /
  `domain.momentum` / `domain.range` hold the four backtest engines, `domain.grouping` the
  table-grouping keys. `domain.model`: `Holding` (Builder; many nullable fields), `AggHolding`,
  `CashTransaction`, `OpenOrder` + `OrderStatus`, `OrderGroup` + `GroupType` + `IntendedRung` +
  `Rung` (ladders and share-class switches), `EarningsEvent`, `Reminder`, `Account` + `TransactionType` enums,
  `PriceBar`, `IntradayBar`, `IntradayPrice`.
- `port` — `FxRateProvider`, `HistoricalFxRateProvider`, `EarningsCalendarProvider`,
  `EarningsCalendarFeed`, `AlertNotifier` (interfaces, faked in tests).
- `adapter` — `FrankfurterFxClient`, `HoldingFileLocator`, `AjBellFileRouter` (which of the two AJ
  Bell accounts a nameless export belongs to), `YahooPriceFetcher` (incl. `probe` + `search`),
  `YahooQuoteSummaryFetcher`, `YahooTickerMap`, `GiltPriceFetcher` (HL),
  `TradewebGiltPriceParser`, `NasdaqEarningsFetcher`, `EdgarEarningsFetcher` + `CikRegistry`,
  `SmtpAlertNotifier`.
- `parser` — `AccountParser` / `CashTransactionParser` / `OrderParser` interfaces + impls.
- `persistence` — `JdbcConnectionFactory` + its repositories + `KeyValueStore`.
- `application` — use-case orchestration; ~40 services. `PortfolioGatherer`,
  `SyncPortfolioService`, `SyncFromCashService`, `ManualPositionService`, `AssetTransferService`,
  `ExportExcelService`, `DividendService`, `AllocationService`, `PortfolioValueService`,
  `OrderFillReconciler` (settles a vanished order against its cash-ledger trade; run by both the
  orders panel and the cash import, and idle unless the ledger has moved),
  `PortfolioReturnService`, `AttributionService`, `OpenOrdersService`, `LadderService`,
  `SwitchService` + `GroupRungs` (what both group types share) + `ShareClassRegistry`
  + `IntentEdits` (the editable price/qty cells — intended rungs only),
  `LivePricesService`, `UnresolvedSymbols`,
  `WatchlistService`, `ReminderService` + `ReminderJob` (calendar reminders — hand-entered ones
  stored, earnings ones derived from the held/watched/ordered union),
  the five `*AnalysisService` backtests, `PriceFetchJob` +
  `IntradayPriceFetchJob` (share `PriceFetchSupport`).
- `web` — ~19 thin controllers (`RootController`, `ChartsController`, `AnalysisController`,
  `OperationsController`, `OrdersController`, `LaddersController`, `SwitchesController`,
  `LiveDashboardController`, `WatchlistController`, `RemindersController`,
  the five strategy controllers, …), plus `GlobalExceptionAdvice`
  (`@ExceptionHandler(IllegalStateException)` renders `fragments/error` instead of HTTP 500),
  `PriceFetchScheduler` and `WebSupport`.
- `config` — `BeanConfiguration`, `JacksonConfig` (serializes non-finite doubles as JSON `null`
  app-wide — empty distributions would otherwise emit bare `NaN` and corrupt the payload).
- Root — `ExcelReportWriter`, `PortfolioBenchApplication`.

Logging is SLF4J + Logback (`logback-spring.xml`); no `System.out` in production code.

UI is server-rendered Thymeleaf + htmx + Chart.js, all **vendored** — see
[docs/ui-conventions.md](docs/ui-conventions.md). 29 tab panels in six groups
(Holdings / Performance / Risk / Quality / Strategies / Tools). Tools also carries one tab per
named watchlist, all pointing at the single `panel-watchlist` and told apart by `data-list`.

## Conventions that apply everywhere

These recur across unrelated features. Breaking one is how the recorded incidents happened.

- **FX convention:** rates are *units of foreign currency per 1 GBP* (e.g. `{"USD": 1.3621}`).
  To convert native → GBP, **divide** by the rate.
- **Write paths throw, reads degrade.** `saveSnapshot` and the `CashTransactionRepository.save*`
  methods throw `IllegalStateException` (rendered as `fragments/error`). Query methods and
  background-job inserts log + return empty/0 — they must not crash the scheduler.
- **Absent is never zero.** A missing figure is `null` ("we don't know"), never `0` ("we
  measured zero"). A caller must be able to tell a non-payer from an unresolved yield, a
  cash row from a position with no cost basis, an unpriced holding from a worthless one.
  Zeroing instead is what manufactured a ~£299k phantom gain in Attribution (2026-08-09).
- **Excluded, and said so.** Anything that can't be valued or classified is dropped from the
  rows *and every total* and reported by name in the payload (`AttributionResult.excluded`,
  `SyncResult.unpricedSymbols`, `ValueTimeline.missingPrices`, the breakdown services'
  contributor `state`), which the UI renders as a warning strip. Never absorbed silently.
- **Censored, not dropped.** A trade whose outcome window runs past the data end is `OPEN` and
  counted as such; every backtest table reconciles `events = TP + SL + Time + Ambiguous + Open`.
- **Look-ahead-free by construction.** Trailing windows use closes *strictly prior* to the bar
  being judged, so a signal can't see its own move.
- **One accounting path per backtest.** `Backtester` / `RotationBacktester` / `RangeBacktester`
  each serve every variant, control and bootstrap iteration; variants differ by a parameter
  (a `Selector`, a `Ranker`, a `FlushTrigger`), never by a second code path.
- **Exports re-derive at the moment the button is pressed** — never a stale copy of what the
  panel is showing.
- **Repos run their DDL once in the constructor**, not per read/write. All JDBC goes through
  `JdbcConnectionFactory`; `com.portfolio.persistence` is its only owner.
- **Every lazy chart loader ends in a `.catch`** that releases its own latch and calls
  `panelLoadFailed(id, err, retryFn)`; the paired `clearPanelError(id)` goes right after the
  latch. `addEventListener` inside a loader must go through `bindOnce`. Details and the
  sub-view-id gotcha: [docs/ui-conventions.md](docs/ui-conventions.md).
- **Self-regulating over maintained.** Reject hand-written data tables when a live source
  publishes the same fact (gilt SEDOLs from HL, fund sector weights from Yahoo, CIKs from SEC).
  A maintained list can only ever be wrong, never right.

## Holding fields

- `currentMarketValue` — native currency amount.
- `currentMarketValueGbp` — pre-converted GBP from the source (AJ Bell does); `null` → convert via FX.
- `costBasisGbp` — GBP cost from the source (AJ Bell only); `null` → compute from `avgPricePaid` via FX.

## Dividends

Source: `cash_transactions WHERE type = 'DIVIDEND'` — no separate table, no manual-entry screen.
`DividendService.dividendsBySymbol` runs `DividendAttributor` over the cash ledger (FIFO lot
tracking — see the class doc), reconciles the reconstructed share count against the holdings
file, and logs WARN on disagreement >0.01 shares. Per-symbol amounts flow into `AggHolding`'s
`dividendGbp` / `totalGainGbp` / `totalGainPct`. Portfolio-level totals (`PortfolioMetrics`) are
price-appreciation only — dividends are **not** folded into the saved snapshot.

## Adding a new parser

1. Implement `AccountParser` (`parse`, `supports`, `sourceName`).
2. Register in `PortfolioGatherer` (parsers are built per run, after rates are fetched, because
   `AJBellSippParser` needs the live rates).
3. If the source provides GBP values directly, set `currentMarketValueGbp` + `costBasisGbp` on
   the `Holding`; otherwise leave them null and `PortfolioAggregator` will convert via FX.

**Roth exports keep listing a position after it is sold out** — zero shares, zero value, but a
live price — so `RothIraParser` drops any non-cash row whose quantity is exactly zero. The broker
is inconsistent about it (sometimes the row just disappears), so dropping it is also what makes
the view read the same either way. Cash is exempt (a zero balance is a fact about the account,
not a stale row) and negative quantities are kept (a short is a real position).
`DividendService.reconcile` already treats an absent holding as zero shares, so dropping the row
changes nothing there.

File-detection: `Holdings*.xlsx` (Roth), `portfolio*.csv` (AJBell — SIPP or Dealing, told apart by
the file's own `Portfolio` column), UUID `.csv` (II SIPP holdings; distinguished from II cash by
header sniff), `cashstatements*.csv` (AJBell cash), `History*.xlsx` (Roth cash), UUID `.csv` with
`Debit,Credit,Running Balance` header (II cash).

**Two AJ Bell accounts, and only their holdings export says which.** The cash and order exports are
identical in name, header and format for both, so which account one belongs to is decided on the
instruments it names — and an export that cannot be placed is read by nobody and reported, never
guessed. See [docs/aj-bell-accounts.md](docs/aj-bell-accounts.md).

## Adding a new cash source (broker)

1. Implement `CashTransactionParser` (`account()` → `Account` enum, `supports`, `parse`).
2. Add a `CashTransactionRepository.save…` method if the dedup rules differ, else reuse one.
3. Implement `CashImporter` by extending `AbstractCashImporter` (provides `matchingFiles`,
   `mostRecent`, `parse`, `archiveOrDelete`).
4. Register the importer as a bean in `BeanConfiguration`. Spring injects
   `List<CashImporter>` into `ImportCashService` in bean-declaration order, which is the order
   the dashboard shows results.

Per-broker dedup keys and their rationale: [docs/persistence.md](docs/persistence.md).

## Adding an account or a transaction type

One enum constant, no SQL. The CHECK constraints are **generated from the enums** and
`migrateConstraints` rebuilds an existing table whenever the stored DDL no longer lists every
constant. Renaming a constant still needs a data migration; adding one is free. Every read
filter is an **allowlist**, so a new type reaches nothing until you add it deliberately — see
[docs/persistence.md](docs/persistence.md).

## docs/ — read before changing the area

| Read this | Before you | The invariant it guards |
|---|---|---|
| [persistence.md](docs/persistence.md) | touch any repository, dedup rule or import path | Broker dedup keys are composite and cross-file; `cash_balance_gbp` is in the PK on purpose; read filters are allowlists |
| [gilts-and-bonds.md](docs/gilts-and-bonds.md) | touch a gilt parser, price source or bond valuation | `Instruments.giltId` is the **only** place a gilt id is built — never concatenate `"GILT " + coupon`; bonds use the per-£100-nominal formula in 4 places |
| [price-history.md](docs/price-history.md) | change a fetch job, ticker mapping or `adj_close` | `close` for market value, `adj_close` for total return; a missing mapping fails two different ways; a ticker Yahoo refuses is *recorded* (`UnresolvedSymbols`), never left as "fetching"; derived dividend yields need a scan marker |
| [instrument-mappings.md](docs/instrument-mappings.md) | change alias/ticker resolution, or the II SIPP cash boxes | Both sides go through `Instruments.matchKey`; a rename re-keys ledger rows and is not undoable; the cash boxes are declared once in `IiCashField` (one per currency) and travel on as one `IiCash`, so adding a currency is a constant plus a label — and a blank box means *keep the stored balance*, never zero |
| [sync-views.md](docs/sync-views.md) | change either sync tab or the Portfolio % column | Ledger view drops unpriced equities but not bonds; II SIPP cash comes from the form, one box per currency and each reconciled in its own currency, blank ≠ zero; a row opens the shared price popup and a `CASH` row carries no `data-symbol` |
| [cash-ledger-irr.md](docs/cash-ledger-irr.md) | touch the Annualised return column or `Irr` | IRR not CAGR; current episode only; unsolvable writes **no** cell, never a zero; two day-count bases are deliberate |
| [table-grouping.md](docs/table-grouping.md) | add a grouping key to either table | Exactly one group per holding; Gain % is `Σgain ÷ Σcost`, never the mean of members; a new key is a resolver class and nothing else |
| [open-orders.md](docs/open-orders.md) | change the orders panel, a parser or the probe | Parsers build via `OpenOrder.parsed(...)`; open/closed filter is a denylist and `OrderStatus` is its only vocabulary; quote age is judged against same-market peers, not the clock; **a verdict on a vanished order lives in its own columns and the broker's own word always outranks it** — the cash-ledger match writes only `FILLED` (no trade is not evidence of a cancellation), reads the side off the sign of the *cash*, and each trade is claimed once so a modified Roth order's ghost cannot claim its twin's fill; **ladder membership is never inferred and an intended rung is never auto-promoted**; a rung is never pruned; **only an intended rung's price and quantity are editable** — an order from a broker export is never restated here — and blank is never zero; a ladder's round trip is the **next** one, matched by quantity and never by rung pairs, and it is a sequential first passage — never a product of the two rungs' own probabilities — priced from **today's** price over its own selectable horizon |
| [share-class-switches.md](docs/share-class-switches.md) | change the switches block, or any exposure view | A switch is **declared, never detected**, and declaring it is also what folds the two listings into one holding in Allocation/Concentration/Currency; the legs are **one table in two sections** so the two sides share one column geometry, and the sections are the two **directions**, not the two lines — a side is chosen by side and *either* side may sit on *either* listing, which is what the listing column and the GBP sort are for; both legs go on one GBP yardstick before anything is compared and a side spanning both lines has **no** native average; the index/FX split is an exact identity over each side's own blended market, and the basis *block* stays the two lines, unmoved by an edited limit — evidence you can edit is not evidence; "complete" means the units arrived, not that nothing is resting |
| [vol-forecast.md](docs/vol-forecast.md) | change P(N), σ_eff, drift or the calibration log | σ is *forecast* for the horizon, never a trailing window of that length; drift is a **price** drift (class total return − that name's yield); the probe writes no calibration line; one σ_eff serves a whole barrier sequence, because how the window splits between its legs is not a window to forecast for |
| [live-tab.md](docs/live-tab.md) | change the Live tab or intraday session handling | Day Δ is anchored on the quote's **trading day**, not the calendar day; `loadLatestIntradayPrices` is regular-session only |
| [attribution.md](docs/attribution.md) | change the Attribution window or its totals | A boundary that can't be valued is excluded from rows *and* totals; annualising under 30 days is blank |
| [period-diff.md](docs/period-diff.md) | change Period diff or the TWR | Contributions and brought-forward are two figures, never one; flows count over `(from, to]` |
| [charts.md](docs/charts.md) | change Contributions or Value over time | Roth's USD seed converts at the FX rate on its earliest ledger date, not today's |
| [allocation-breakdowns.md](docs/allocation-breakdowns.md) | change sector, style or AI/Tech | The sector table is a partition, the AI/Tech tile an **overlay** — never sum them; `Instruments` owns the wrapper sets, properties files must not redeclare them; unclassified is three states |
| [excel-exports.md](docs/excel-exports.md) | touch any Download Excel button | `setForceFormulaRecalculation(true)` or every formula opens blank; an empty view writes literals, not `SUM` over an empty range; the Ladders/Switches sheets carry no formula at all — a cash column total *is* the netting across accounts both panels forbid |
| [watchlist.md](docs/watchlist.md) | change the watchlist, alerts or the shared price popup | The popup lives at body level, not inside a tab panel; an unwatched symbol gets every trigger **disabled**; five tables open it and `data-symbol` is the only test of whether a row can; the per-window high/low is the **traded** range over the span the label claims — every window sees the live session, so they nest; the close-only `*` is judged per extreme, not per window; a stored `0.0` is absent, not a price; lists are membership only — **alerts are global to the symbol** — and a symbol in no list is deleted, never left orphaned in the fetch universe; a column layout that resolves to nothing falls back to the defaults, never to a blank table; the header label wraps inside a capped span (a `<th>` ignores `max-width`) and the data never wraps; an active sort is re-applied after every in-place patch and owns the reconcile's order, and hand-placing a row is what clears it; a drop saves the order it shows and a cancelled drag saves nothing |
| [reminders.md](docs/reminders.md) | change the Reminders tab, a reminder rule or the send job | Nothing here is evaluated — a watchlist trigger fires when the market says so, a reminder when the **calendar** does, and `rule`/`action` are free text nothing parses; a hand-entered reminder is open-ended (only a person stops it) while an earnings one is a window whose **lower** bound is what keeps a stale snapshot date from mailing a print that already happened; earnings reminders are **derived, never stored** — only the mute is, and it is a standing instruction about the symbol, not one print; `nextEarnings` returns three outcomes (dated / undated / not applicable) because a gilt's silence is an answer and a company's is a gap; "daily" is the per-day dedup key, not the schedule, and the sent-set is written only after the mail actually goes |
| [earnings.md](docs/earnings.md) | change the earnings calendar, the holdings section, or the next-earnings date on a fundamentals row | Three-valued feed: `[]` is a successful empty read, `null` is a failure; three earnings sources answer three different questions, and the holdings section reads two of them in a fixed order — the calendar outranks the snapshot, a snapshot date counts only while still ahead, and `REPORTING`/`EXPECTED`/`NOT_COVERED`/`NONE_SCHEDULED` are four distinct silences; the snapshot's date rides the quoteSummary call already being made and stores **no** date rather than a zero, an estimated day is marked, never shown as fixed, and a day already reported is muted, never blanked and never relabelled |
| [manual-positions.md](docs/manual-positions.md) | change hand-entered positions | Each acquisition is **two** rows; `OPENING_BALANCE`, not `CONTRIBUTION`; balances are recomputed, never supplied |
| [aj-bell-accounts.md](docs/aj-bell-accounts.md) | touch any AJ Bell parser, or add a second account at one broker | Only the holdings export names its account, so cash and orders are routed by the instruments they name against it — a live registry, never a maintained list; evidence must be **exclusive** to one account, and a file naming two or naming none is read by nobody and reported rather than guessed; selection is newest-per-**account**, because newest-overall silently hid one account's orders behind the other's; a broker's zero cost against a real value is **unknown**, and the ledger's FIFO basis stands in for it only where the export states none and the share counts agree |
| [transfers.md](docs/transfers.md) | change in-specie transfers, or any per-account lot engine | Both legs or neither; the magnitude is the source account's **FIFO cost**, not market value and not the broker's pooled figure; **every per-`(account, symbol)` lot engine must replay `TRANSFER`** — loading the row is not replaying it; the two that ignored it showed a sold-out position as still held, and a fourth (`ManualPositionService`) showed a transferred-away position at double; lots travel with their open date, cost and accrued dividend, and a payment reaching an emptied timeline follows the shares |
| [strategy-swing.md](docs/strategy-swing.md) | change the swing lab | Every flush family reports its placebo and matched bootstrap beside it; pooled cells report episodes, not just events |
| [strategy-trend.md](docs/strategy-trend.md) | change the trend filter | Every variant starts at the latest `firstValid` of any configured signal; the pre-1999 splice is a price-only proxy and is labelled |
| [strategy-momentum.md](docs/strategy-momentum.md) | change momentum or the signal comparison | V1 must reproduce the Momentum tab bit-for-bit; the adoption rule is pre-registered and printed verbatim; controls run on the fit window only |
| [strategy-range.md](docs/strategy-range.md) | change the range-floor backtest | Defaults *are* the pre-registration — any override flags the run EXPLORATORY; signals on split-adjusted price, P&L in total-return space |
| [ui-conventions.md](docs/ui-conventions.md) | add a tab, chart loader, sortable table or vendored library | Loaders need `.catch` + `panelLoadFailed`; tab groups are derived from the DOM; vendored libs are pinned by URL and load in a fixed order; a sort lives in the header's `data-sort-dir`, indexes by `cellIndex`, and is re-applied after anything that re-renders or patches rows |
| [demo-mode.md](docs/demo-mode.md) | run the app on demo data, or change the generator | The generator profile lives **outside the repo** and its method is documented nowhere — never add a fallback or explain it; the ledger is built in the **database** and holdings in the **files**, because holdings have no table and files cannot reproduce the ledger; `order_key` must be rebuilt or every ladder rung orphans |
| [backup.md](docs/backup.md) | change what is backed up, or the schedule | Only the ~1000 rows that cannot be re-fetched, plus the `KeyValueStore` files a SQL dump would miss; the dump is plain SQL so a restore needs none of our code; due-by-elapsed-time, not a calendar cron, because the laptop is usually shut |
| [postmortems.md](docs/postmortems.md) | investigate a reconciliation gap between views | Resolved incidents kept for the invariants they justify |

## Not documented yet

These ship and have no `docs/` file. Read the service; there is no recorded design intent
beyond the code, so treat the source as the specification.

| Area | Tab | Entry point |
|---|---|---|
| Risk metrics (Sharpe/Sortino/Calmar, drawdown, underwater) | Risk | `PortfolioRiskService` — `GET /risk`, `POST /risk/rate` |
| Correlation matrix + diversification ratio | Correlations | `CorrelationService` — `GET /correlations` |
| TWR / money-weighted returns, annual bars, benchmarks | Returns | `PortfolioReturnService`, `BenchmarkReturnService` — `GET /returns{,/mwr,/benchmark,/symbols}` |
| Dividend history + audit | Dividends | `DividendService` — `GET /dividends`, `/dividends/audit` |
| Per-position detail | Position | `PositionDetailService` — `GET /position` |
| Concentration | Allocation | `ConcentrationService` — `GET /concentration` |
| Currency exposure | Allocation | `CurrencyExposureService` — `GET /currency` |
| Target-allocation drift | Allocation | `GET /allocation/targets` (+ `POST`) |
| What-if planner | What-if | `WhatIfService` — `POST /whatif` |
| Historical scenario replay | Scenario | `HistoricalScenarioService` — `GET /scenario`, `/scenario/substitutes` |
| Fundamentals snapshot + diff | Fundamentals | `PortfolioFundamentalsService` — `GET /fundamentals`, `/portfolio-fundamentals` |
| Trade journal (notes keyed by `rowid`) | Journal | `TradeNotesService` — `GET /trades`, `POST /trades/{rowid}/note` |
| Holdings-vs-ledger reconciliation | Reconcile | `GET /reconciliation` |
| Glossary / reference pages | — | `RootController` — `GET /glossary`, `/reference` |
| Manual price entry | — | `POST /prices/manual` |

## Endpoint map

| Endpoint | Controller | Service / notes |
|---|---|---|
| `POST /sync` | Operations | `SyncPortfolioService` |
| `POST /sync-from-cash` (`?groupBy=`) | Operations | `SyncFromCashService` / `LedgerGroupingService` |
| `POST /sync-from-cash/export` | Operations | `ExportExcelService.exportLedger` (→ ~/Documents) |
| `POST /export` | Operations | `ExportExcelService` |
| `POST /import-cash` | Operations | `ImportCashService` |
| `POST /import-gilt-prices` | Operations | `ImportGiltPricesService` |
| `POST /rebuild-prices` | Operations | full-window refetch + `adj_close` rebuild |
| `POST /prices/manual` | Operations | hand-entered price row |
| `POST /backup` | Operations | `BackupService` — irreplaceable tables + settings → iCloud Drive |
| `POST /returns/benchmark/fetch` | Operations | benchmark series backfill |
| `GET /live`, `GET /live/stream` (SSE) | LiveDashboard | `LivePricesService` |
| `POST /live/export` | LiveDashboard | `ExportExcelService.exportLive` |
| `GET /orders` | Orders | `OpenOrdersService` |
| `GET /orders/touch` | Orders | `OpenOrdersService.touchEstimate` (re-blends σ at the new N) |
| `GET /orders/probe` | Orders | `OpenOrdersService.probe` (writes no calibration line) |
| `GET /ladders`, `POST /ladders/{create,delete}` | Ladders | `LadderService` — groups of rungs on one security; `P(10)`/`P(30)` are **fixed** horizons, not the table's per-order `P(N)`, and the summary's round trip has a third horizon of its own |
| `POST /ladders/rungs/{add,remove}` | Ladders | assign / unassign real orders; never inferred |
| `POST /ladders/intents/{add,delete,placed}` | Ladders | rungs decided on but not placed; `placed` is the only intended→real path |
| `POST /ladders/intents/update` | Ladders | the editable price/qty cells — **intended rungs only**; a rung from a broker export is fixed (`IntentEdits`) |
| `POST /ladders/horizon` | Ladders | the round-trip horizon — one **stored** app-wide setting (default 63 sessions), so the panel and the export can never be priced over different windows; out of range is clamped, not refused |
| `POST /ladders/export` | Ladders | `OpenOrdersService.exportLadders` — `Ladders-{date}.xlsx`, 4 sheets, the round-trip one keyed by **direction** (→ ~/Documents) |
| `GET /switches`, `POST /switches/{create,delete}` | Switches | `SwitchService` — one holding moving between two listings of **one** fund; creating one also declares the pair to every exposure view |
| `POST /switches/legs/{add,remove}` | Switches | assign / unassign orders; which side is read from the side and the listing only says what it is priced against, membership never inferred |
| `POST /switches/intents/{add,delete,placed}` | Switches | the buy leg before the cash arrives — the ordinary state here; the listing is stored (the side no longer names it) and a blank one falls back to the one-way rule; `placed` is the only intended→real path |
| `POST /switches/intents/update` | Switches | the same editable cells as the ladder's — the buy leg is an intent for days, so it is the one an edit can reach |
| `POST /switches/export` | Switches | `OpenOrdersService.exportSwitches` — `Share-Class Switches-{date}.xlsx`, 5 sheets |
| `POST /orders/drift`, `POST /orders/event-var` | Orders | stored app-wide model switches |
| `POST /orders/note` | Orders | `OrderHistoryRepository.saveNote` |
| `POST /orders/status` | Orders | what became of an order the broker stopped listing without saying — **only where it was silent**, stored in its own column so a re-parse cannot undo it; blank clears and hands the row back to the cash-ledger matcher |
| `POST /orders/export` | Orders | `OpenOrdersService.export` |
| `GET /allocation`, `/allocation/sectors`, `/allocation/styles` | Charts | `AllocationService`, `SectorBreakdownService`, `StyleBreakdownService` |
| `GET`/`POST /allocation/targets` | Charts | target-allocation drift |
| `GET /concentration`, `/currency`, `/correlations`, `/risk` | Charts | `ConcentrationService`, `CurrencyExposureService`, `CorrelationService`, `PortfolioRiskService` |
| `POST /risk/rate` | Charts | risk-free rate override |
| `GET /contributions`, `/portfolio-value` | Charts | `ContributionService`, `PortfolioValueService` |
| `GET /attribution` | Charts | `AttributionService` |
| `GET /period-diff` | Charts | `PortfolioReturnService.periodDiff` |
| `GET /returns`, `/returns/mwr`, `/returns/benchmark`, `/returns/symbols` | Charts | `PortfolioReturnService`, `BenchmarkReturnService` |
| `GET /dividends`, `/dividends/audit` | Charts | `DividendService` |
| `GET /position` | Charts | `PositionDetailService` |
| `GET /fundamentals`, `/fundamentals/tickers` | Analysis | fundamentals snapshot + diff |
| `GET /portfolio-fundamentals` (+ `/add`, `/remove`, `/refresh`) | Analysis | `PortfolioFundamentalsService` |
| `GET /reconciliation` | Analysis | holdings-vs-ledger reconciliation |
| `GET /scenario`, `GET`/`POST /scenario/substitutes` | Analysis | `HistoricalScenarioService` |
| `POST /whatif` | Analysis | `WhatIfService` |
| `GET /trades`, `POST /trades/{rowid}/note` | Analysis | `TradeNotesService` |
| `GET /health` | Analysis | liveness |
| `GET /mappings`, `POST /mappings/{verify,save,delete}` | Mappings | `InstrumentMappingService` |
| `GET /watchlist` (`?list=`), `/watchlist/{row,series,vol,quotes,returns,gilts,lists}` | Watchlist | `WatchlistService` — `quotes`/`returns` take `?list=`; `row`/`series`/`vol`/`gilts` are symbol-scoped |
| `POST /watchlist/{add,remove,threshold,move}` (`&list=`) | Watchlist | `WatchlistRepository` — `remove` drops the symbol from *that* list, and outright only if it was its last |
| `POST /watchlist/reorder` (`&list=`) | Watchlist | the drag-and-drop drop — saves the order **as displayed** (so a drag under an active sort is what makes that sort the manual order); `move` stays the ↑↓ single swap, and the only reorder a keyboard or touch screen can reach |
| `POST /watchlist/lists/{create,rename,delete,move}` | Watchlist | the named lists; the last one cannot be deleted |
| `GET`/`POST /watchlist/columns` (`?list=`) | Watchlist | the per-list column layout; **no rows = the default set**, so saving none is "reset to default" |
| `GET /earnings` | Earnings | `EarningsCalendarService` |
| `GET /reminders` | Reminders | `ReminderService` — stored reminders + the derived earnings ones in one payload; `deliveryEnabled` says whether anything is actually being mailed |
| `POST /reminders/{add,update,delete,toggle}` | Reminders | the hand-entered half; **date, rule and action are the editable fields — the symbol is not**, and a blank date or rule is refused rather than defaulted |
| `POST /reminders/earnings/toggle` | Reminders | the mute, keyed by **symbol** (there is no row) — a muted symbol stays listed, never hidden |
| `POST /reminders/send` | Reminders | `ReminderJob` on demand; same per-day dedup, so it cannot double-send |
| `GET /positions/manual`, `POST /positions/manual/{buy,sell,withdraw,delete}` | ManualPositions | `ManualPositionService` |
| `GET /transfers`, `POST /transfers/{record,delete}` | Transfers | `AssetTransferService` |
| `GET /swing`, `POST /swing/{symbols,backfill}` | Swing | `SwingAnalysisService` |
| `GET /trend`, `POST /trend/backfill` | Trend | `TrendAnalysisService` |
| `GET /momentum`, `GET`/`POST /momentum/symbols`, `POST /momentum/backfill` | Momentum | `MomentumAnalysisService` |
| `GET /momentum-signals`, `POST /momentum-signals/backfill` | MomentumSignals | `MomentumSignalsService` |
| `GET /range`, `POST /range/backfill` | Range | `RangeAnalysisService` |
| `GET /`, `/glossary`, `/reference` | Root | dashboard shell + static reference pages |
