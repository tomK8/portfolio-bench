# CLAUDE.md

Guidance for Claude Code in this repository. Prefer reading the code; this file covers the
cross-cutting bits that aren't obvious from any single file.

This file is intentionally detailed. Helps remember various things we have done. Does not
seem to be wasting too many context tokens.

## Build, test & run

```bash
mvn compile                            # compile
mvn test                               # excludes @Tag("integration")
mvn test -Dtest=AJBellSippParserTest   # single class
mvn package -DskipTests                # jar
mvn spring-boot:run                    # foreground (dev)
scripts/start.sh / scripts/stop.sh     # daemon (writes ~/log/portfolio-bench.log)
```

Server binds to loopback only, no auth — single-user local tool. Override directories with
`--portfolio.input-dir=…`, `--portfolio.db-dir=…`, `--portfolio.output-dir=…` (blank → defaults
`~/Downloads`, `~/Documents/Investing`, `~/Documents`).

Daemon mode: `start.sh` backgrounds `target/portfolio-bench-1.0-SNAPSHOT.jar`, writes a PID file,
rotates the previous log to a timestamped name. The JVM's stdout/stderr is redirected into the
log file, so non-Logback output (early classloader errors, OOM dumps) is captured too.
`spring.main.banner-mode=log` routes the Spring banner through SLF4J.

### Test fixtures

`src/test/resources/` **must contain only synthetic data — no real brokerage exports.** Fixtures
are committed so parser tests can load files from disk. Real exports were never committed; keep
it that way. Regenerate `Holdings.xlsx` with openpyxl if its shape needs to change.

## Architecture

Spring Boot web app, layered ports-and-adapters. **Spring is confined to `web` and `config`**;
`domain`, `application`, `adapter`, `parser` and `persistence` are framework-free.

- `domain` — pure logic. `PortfolioAggregator` (two-pass: `groupAccumulators` →
  `buildAggHolding`), `PortfolioMetrics` (totals + `rtTotalGbp` for intraday valuation),
  `DividendAttributor` (FIFO), `CashLedgerReconstructor` (FIFO position-from-ledger
  reconstruction for the cash-ledger view), `Instruments.isBond` (single home of the
  bond/gilt predicate). `domain.model`: `Holding` (Builder; many nullable fields),
  `AggHolding`, `CashTransaction`, `Account` + `TransactionType` enums, `PriceBar`,
  `IntradayBar`, `IntradayPrice`.
- `port` — `FxRateProvider`, `HistoricalFxRateProvider` (interfaces, faked in tests).
- `adapter` — `FrankfurterFxClient` (Jackson + shared HttpClient with timeouts),
  `HoldingFileLocator`, `YahooPriceFetcher`, `YahooTickerMap`.
- `parser` — `AccountParser` / `CashTransactionParser` interfaces + impls.
- `persistence` — `JdbcConnectionFactory` + its repositories + `KeyValueStore`. See below.
- `application` — `PortfolioGatherer`, `SyncPortfolioService`, `SyncFromCashService`
  (cash-ledger-derived view), `ExportExcelService`, `DividendService`,
  `ContributionService` (timeline for the Contributions chart), `PortfolioValueService`
  (timeline for the Value-over-time chart), `ImportCashService` (composes
  `List<CashImporter>`), `PriceFetchJob` + `IntradayPriceFetchJob` (share
  `PriceFetchSupport`).
- `web` — `DashboardController` (thin; `@ExceptionHandler(IllegalStateException)` renders
  `fragments/error` instead of HTTP 500), `PriceFetchScheduler`.
- `config` — `BeanConfiguration`.
- Root — `ExcelReportWriter`, `PortfolioBenchApplication`.

UI: server-rendered Thymeleaf + htmx + Chart.js 4 + `chartjs-adapter-date-fns` + hammer.js +
`chartjs-plugin-zoom`, all **vendored** under `static/js/vendor/`. They were CDN-loaded until
2026-08-08, when a jsdelivr/unpkg blip left `Chart` undefined and every chart panel rendered
silently blank; a loopback-only tool gets no cache-sharing benefit from a CDN, so there was
nothing to weigh against that. They never auto-update: `scripts/vendor-manifest.txt` pins each
by exact URL (the URL *is* the version — no second list to drift) and `scripts/update-vendor.sh`
re-downloads them (`--check` for a dry run), staging and installing all-or-nothing so a
half-updated dir can't mix incompatible versions. Load order matters — `chart.umd.js` before the
adapter and the zoom plugin, `hammer.min.js` before the zoom plugin. Dashboard tabs:
- **From holdings files** — `POST /sync` → `fragments/portfolio`. Includes a cash
  reconciliation panel comparing the holdings-side cash (parsed CASH rows + form-supplied
  II SIPP £/$) against the ledger-side latest stored cash balance, with red rows for
  |diff| > £50 (threshold in `SyncPortfolioService.CASH_DRIFT_WARN_GBP`).
- **From cash ledger** — `POST /sync-from-cash` → `fragments/portfolio-ledger`. Slimmer
  table (no separate "Market Value £" / "RT Market Value £" columns — single combined
  value column). Drops positions without an intraday price — except bonds, which fall back to
  the latest `price_history` close (surfaced in a "Daily-close fallback" panel with the close
  date) so a dead gilt feed doesn't drop gilts from the totals.
- **Portfolio % column** (both tables above + the Excel `Portfolio` sheet): each row's
  `marketValueGbp ÷ totals.totalGbp`. On-screen it's computed in the Thymeleaf fragment as
  double division (guarded against null/zero total — no BigDecimal divide that could throw on
  a non-terminating quotient). In Excel it's a live formula `=IFERROR(E{row}/$E${totalRow},"")`
  (percent-styled) on every equity + cash row so it re-computes if the II SIPP cash input is
  edited; summary rows (Cash header / Total Cash / TOTAL) carry no %. See
  `ExcelReportWriter.writeAggregatedSheet` (`AGG_PCT` = last column). Both report workbooks set
  `wb.setForceFormulaRecalculation(true)` — POI writes formula cells with no cached value, so
  without the fullCalcOnLoad flag the Portfolio % column (and every total/return, which are also
  formulas) opens **blank** in a viewer that doesn't auto-recalc.
- **Open orders** — `GET /orders` → `fragments/orders` (htmx, re-fetched on each tab click), plus
  a "Download Excel" button (`POST /orders/export`). Resting orders across brokers parsed on demand
  from the newest order-status export per broker in the input dir; a current FX snapshot fills the
  Amount GBP column, and a live intraday/daily quote fills the **Current** price + **vs limit** %
  columns (how far the market sits from each resting limit). See the Open-orders section below.
- **Contributions** — `GET /contributions` → JSON, rendered by Chart.js. Cumulative
  per-account + Total.
- **Value over time** — `GET /portfolio-value` → JSON, Chart.js. Monthly portfolio GBP
  value; yellow warning panel above the chart lists symbols that were ever held but
  have zero rows in `price_history`.
- **Allocation** — `GET /allocation` → JSON, three Chart.js panels: cash-vs-invested
  stacked area, per-symbol stacked area (top 12 + "Other"), and a date-picker
  snapshot of the full breakdown on any chosen day. Weekly cadence (~550 samples
  over a 10-year history) via `AllocationService`; latest sample always pinned to
  today so the snapshot reflects current state even mid-week.
  The Breakdown sub-view carries two blocks above those charts, each with its own latch:
  the **Investment-style block** at the very top (`GET /allocation/styles` →
  `StyleBreakdownService`) and the **Sector & AI/Tech block** beneath it
  (`GET /allocation/sectors` → `SectorBreakdownService`) — see both sections below.
- **Attribution** — `GET /attribution?from=YYYY-MM-DD&to=YYYY-MM-DD` → JSON, Chart.js
  horizontal bar + sortable table. Per-symbol GBP P&L over a window via
  `AttributionService` (formula: `end_value − start_value + window_cash_flows`). Each
  row also reports a period return (P&L ÷ peak GBP capital deployed in the window) and
  the same return annualised to 365.25 days — **blank under `MIN_ANNUALIZE_DAYS` (30)**,
  because compounding a 5-day move 73× yields five-figure percentages that swamp the column
  (a real +6.7% over 5 days read as 11,299%). End-value uses live intraday + live FX
  when `to == today`, daily close + historical FX otherwise — so totals reconcile with
  the cash-ledger view. A symbol whose boundary can't be valued (no `price_history`, or no FX
  rate for its currency) is **excluded from the rows and from every total** and reported in
  `AttributionResult.excluded`, which the UI renders as a yellow strip. Valuing it at zero
  instead was silently catastrophic: an FX timeout put every USD position at £0 on the *start*
  boundary while the end boundary still priced off the cached live rates, manufacturing a
  ~£299k phantom gain that was indistinguishable from real data (2026-08-09). Boundary prices
  come from two bulk `PriceHistoryRepository.loadPricesAsOf` queries, **not** whole series —
  the old `loadPriceSeries` pulled ~245,000 rows per request to read 94 of them, which made a
  5d window cost the same as `All` (~0.65s → ~0.10s). `% of total` is each row's share of the
  *net* P&L, so it sums to 100% but inflates past 100% when winners and losers cancel — kept
  deliberately (the blow-up says the net is small), flagged in the header tooltip. The panel's
  preset buttons guard against out-of-order responses with an `attrSeq` latch in
  `runAttribution` — several requests are in flight at once and a slow early one landing last
  would otherwise repaint the tiles for a window the buttons no longer show.

- **Period diff** — `GET /period-diff?from=&to=` → JSON, rendered as stat tiles + a
  From/To/Delta table under the Performance group. Compares the portfolio on **any two dates**,
  valuing both boundaries from the cash ledger via `PortfolioReturnService.periodDiff` — it does
  **not** read `portfolio_snapshots` any more. (It used to diff two saved broker-written snapshot
  rows, which limited the picker to days a `/sync` happened to run and put the two ends on two
  different brokers' conventions.) Three figures, deliberately all shown because they only agree
  when no money moved: **Change** (raw end − start), **Gain** (change − external flows inside the
  window = what the market did), and **Total return** — the chain-linked daily TWR
  `Π(1 + (V_t − V_{t−1} − C_t)/V_{t−1}) − 1`, the same definition as the Returns tab, so a
  full-history window reproduces its since-inception number. Annualised is blank under
  `MIN_ANNUALIZE_DAYS` (30), same convention as Attribution. Flows are counted over
  **`(from, to]`** — a contribution landing on the start date is part of the opening balance, not
  of the window. **External flows are reported as two figures, not one**:
  `contributionsGbp` (ledger `CONTRIBUTION` rows — internal AJ Bell → II SIPP transfers net to
  zero across them, the outbound leg being recorded as a negative "Pension payment requested" /
  "Transfer Out" on the AJ Bell side) and `broughtForwardGbp` (the Roth USD seed as a single GBP
  step on Roth's earliest ledger date, surfaced in its own tile + a warning). Both come off the
  gain and both feed the TWR's `C` term — the value genuinely steps for a non-market reason — but
  only the first is money anyone paid in. Folding them together labelled a £138,857 account
  opening as £138,857 "contributed" over a 1y window (2026-08-09). `contributionsByDate()` still
  returns the combined map for `timeline()` / `moneyWeightedSummary()`, composed from
  `ledgerContributionsByDate()` + `broughtForwardByDate()`. Boundary valuation comes from `PortfolioValueService.dailyValuesIn(from, to)`,
  the windowed form of `dailyValues()`: the ledger still replays from its first row (quantities
  and cash must be caught up) but only in-window days are valued, so a 1y window prices ~365 days
  instead of ~9,500. It also returns `unpricedSymbols` — anything held in the window with no
  `price_history` rows values at zero on **both** ends, so it's surfaced as a warning rather than
  quietly shrinking the portfolio (same lesson as Attribution's `excluded`). Reversed dates, a
  window that predates the ledger, or a zero opening value degrade to null figures + a warning;
  nothing throws. Per-symbol P&L over the same window stays on the Attribution tab — not
  duplicated here.

- **Watchlist** — `GET /watchlist` → JSON, rendered as a table under the Tools group. A
  user-maintained set of symbols (owned or not — *not* unioned with holdings, kept lean by
  design) for volatility trading. Each row joins live price + today's move + trailing
  momentum (5d/30d) + a realized-vol term structure (5D/10D/30D/1Y annualised) + 52-wk
  high/low distances + P/E/beta
  + position/gain (blank when not held). Four per-symbol alert triggers with editable
  thresholds/levels: proximity to the 52-week high, |today's move|, and absolute price
  targets (≥ above and ≤ below, in the price's native currency). Row colour = alert state
  (green/red today-move by direction, blue near-high, amber ≥-target, purple ≤-target,
  pulsing when both near-high + big-move). Click a row for a
  popup: same fields + a price chart with a 1D/3D/5D/10D/15D (intraday) + 30D (daily) window
  toggle + a collapsible Fundamentals section. See the Watchlist section below.

- **Swing stats** — `GET /swing` → JSON, rendered as tables + a correlation heatmap under the
  Strategies group. Validation statistics for a short-term mean-reversion (dip-buy) strategy on a
  configurable high-vol semi/AI watchlist: realized-vol term structure; forward returns after
  down-day / flush triggers, always shown next to their unconditional baseline; an OHLC-touch
  barrier / time-stop trade simulation with full TP/SL/Time/Ambiguous/Open accounting, an all-days
  placebo and a matched-random bootstrap (does the flush trigger beat drift?); and cluster
  correlation (incl. tail correlation on down days). Every flush runs for **two trigger families in
  parallel** (§C) — an absolute −% drop and a σ-scaled −k×σ×√N flush that fires at each regime's own
  scale — each with an event log (§A), a per-year breakdown (§B) and a 200d-MA split (§E), in **all**
  and **ex-earnings** variants (§D). Everything is configurable and pooled across a "semi cluster"
  subset (pooled cells report distinct-date *episodes* alongside raw event counts); §9 regime split
  (full vs trailing 6 months) is shown side by side with each name's actual data span. §8 earnings
  hygiene is now active via an EDGAR filing-date proxy (US filers only; others flagged unverified).
  See the Swing section below.

- **Trend filter** — `GET /trend` → JSON, rendered as tables under the Strategies group. Backtest of a
  moving-average trend filter on the core Nasdaq-100 holding (QQQ total-return, spliced back with
  the ^NDX price index, cash leg from ^IRX): five signal clocks (month-anchored / daily
  first-touch / buffered ±x% / buffered N-in-a-row / monthly-close cross-check) × MA-length sweep,
  each cell with cost/execution/half-switch/0%-cash sensitivities, whipsaw accounting, a matched
  random-exposure bootstrap, calendar years, a named-episode table and an exportable trade log,
  plus an anchor-offset sweep and a live QQQ/EQQQ readout. See the Trend section below.

- **Momentum** — `GET /momentum` → JSON, rendered as tables + a monthly grid under the Strategies
  group.
  Cross-sectional 12-1 momentum rotation over a fixed ~33-name US large-cap universe: rank monthly
  by trailing 12-month return skipping the last month, hold top N (3/4/5) equal-weighted. The point
  is the comparison against controls, not raw CAGR (the universe is survivorship-biased, flagged in
  a red header banner): equal-weight baseline, matched random-selection bootstrap (dimmed inside the
  IQR), reversed-signal (bottom-N) check, QQQ/SPY. Recent 12 months held out as a labelled holdout;
  turnover + cost drag, a 200d-MA regime split and momentum-crash diagnostics per variant; expanding
  window; exportable rebalance log. See the Momentum section below.

- **Mom. signals** — `GET /momentum-signals` → JSON, rendered as tables under the Strategies group.
  Five-variant signal-speed/overlay comparison on a **pinned 17-ETF asset-class universe**
  (sector SPDRs + EFA/EEM/TLT/IEF/GLD/REIT): V1 incumbent 12-1 (must reproduce the Momentum tab
  bit-for-bit — asserted in tests), V2 6-1 (same 252-session *eligibility*, so signal speed isn't
  confounded with universe breadth), V3 equal-weight 3-1/6-1/12-1 cross-sectional z-blend, V4
  12-1 + absolute rf gate (a top-N slot idles in cash when the name's own 12-1 return ≤ the rf
  accrual over the window), V5 12-1 + 15% trailing stop off the peak close since entry (sells at
  that close, cash to next rebalance, free re-entry — whipsaw cost is part of what's measured).
  Verdicts follow a **pre-registered adoption rule** (beat V1 on Sharpe AND Calmar, in fit AND
  holdout, at the same N) printed verbatim, with a contamination disclosure (variants designed
  after peeking at 2025-26) and an ex-2026 POST-HOC re-check of any holdout edge; five
  pre-recorded predictions get per-N pass/fail marks. Episode panel (2008/2011/2020/2022/
  2026-gold, with GLD derate/gate/stop dates) is explicitly **non-decisional**. One shared
  random-N bootstrap per N ranks all five variants (the control is selection-agnostic). See the
  Momentum-signals section below.

- **Range floor** — `GET /range` → JSON, rendered as tables + an annotated chart under the
  Strategies group. Pre-registered backtest of a percentile-floor range strategy on a fixed 12-name
  information-infrastructure cohort (Family A exchanges: LSEG.L, DB1.DE, ICE, CME, NDAQ; Family B
  data/publishing: MSCI, SPGI, MCO, FDS, REL.L, WKL.AS, TRI; LSEG.L featured): buy when the close
  hits the trailing-252-session P10 (look-ahead-free, strictly-prior window) after ≥10 consecutive
  closes above it (buys dips from above; same run must recur after every exit — the
  anti-machine-gun rule), sell +10% via the flush harness's OHLC-touch convention. Accumulation
  mode (no stop; MAE + time-underwater per trade, open positions censored) is primary; trading
  mode (−15% stop, ambiguous-as-stop conservative column) secondary; a trailing-P75 exit variant
  rides along. The verdict is Arm A (strategy, £10k/entry, idle cash at rf) vs Arm B (buy-and-hold
  from A's first signal) vs Arm C (same purchase count at random dates, 1000-draw seeded
  bootstrap, IQR-dimmed) vs Arm D (mirrored P90 entry — trend-vs-reversion sanity check), on two
  stated capital bases (allocated ≠ comparable across arms by construction; per-pound-year
  deployed is the bootstrap basis). Stamp duty (UK purchases only) + commission itemised per arm.
  Full history and trailing 24 months side by side; pooled/family/per-name with calendar-week
  episode dedup; sensitivity grid (P×TP, both modes) reported never selected, pre-registered cell
  outlined; any overridden parameter flags the run EXPLORATORY. Per-name results local-currency;
  pooled arms GBP via daily Frankfurter FX. See the Range section below.

- **Earnings** — `GET /earnings` → JSON, rendered as per-day tables under the Tools group. A rolling
  earnings calendar (default 2 days back, 3 forward) from Nasdaq's public JSON feed: each day lists
  every company scheduled to report with its session (BMO/AMC/TBD), market cap, consensus EPS
  estimate + estimate count and last year's same-quarter actual. A market-cap floor filters to the
  "major" names; the user's own holdings (the `held_symbols` KV set from the last /sync) are
  highlighted and pulled into a summary strip regardless of size. Forward-looking with estimates —
  the half the EDGAR filing-date proxy (Swing §D) can't serve. Rosters are cached and degrade to
  last-known on a feed outage. See the Earnings section below.

Primary tab groups are **derived from the DOM** — `PANEL_TO_GROUP` / `GROUP_DEFAULT_PANEL` are
built by scanning `.tabs-secondary[data-group]` strips, so moving a panel between groups is an
HTML-only edit (no JS to keep in sync). A saved tab whose group changed still restores correctly:
the per-group memory is validated with `PANEL_TO_GROUP[last] === g` before use. Groups are
Holdings / Performance / Risk / Quality / **Strategies** (the five backtests: Swing stats, Trend
filter, Momentum, Mom. signals, Range floor) / Tools (Reconcile, Journal, Watchlist, Earnings).

Charts load lazily on first tab click, each behind a `xLoaded` latch set *before* the fetch so
it runs once per page. **Every such loader must end in a `.catch`** that releases its own latch
and calls `panelLoadFailed(panelId, err, retryFn)` (`dashboard.js`) — without one, a single
transient failure left the latch set, so the tab stayed blank with no message and re-clicking it
was a silent no-op until a full reload. `panelLoadFailed` destroys any half-built charts on the
panel first (Chart.js refuses to re-bind a live canvas, so a partly-failed loader would otherwise
throw "Canvas is already in use" on every retry), then renders a `.panel-load-error` box with a
Retry button; the paired `clearPanelError(panelId)` goes right after the latch. Because loaders
can now re-run, any `addEventListener` inside one must go through `bindOnce` or it stacks a
duplicate handler per retry. Loaders that own a sub-view (allocation's concentration/currency)
pass the **sub-view** id, not the panel id — `panelLoadFailed` queries descendants, so a
panel-scoped call would clear a sibling sub-view's box.

Logging is SLF4J + Logback (`logback-spring.xml`);
no `System.out` in production code.

| Action                | Endpoint                   | Service                   |
|-----------------------|----------------------------|---------------------------|
| Sync portfolio        | `POST /sync`               | `SyncPortfolioService`    |
| Sync from cash ledger | `POST /sync-from-cash`     | `SyncFromCashService`     |
| Open orders           | `GET /orders`              | `OpenOrdersService`       |
| Open orders → Excel   | `POST /orders/export`      | `OpenOrdersService.export` (→ ~/Documents) |
| Export Excel          | `POST /export`             | `ExportExcelService`      |
| Import cash statement | `POST /import-cash`        | `ImportCashService`       |
| Import gilt prices    | `POST /import-gilt-prices` | `ImportGiltPricesService` |
| Contributions chart   | `GET /contributions`       | `ContributionService`     |
| Value-over-time chart | `GET /portfolio-value`     | `PortfolioValueService`   |
| Allocation            | `GET /allocation`          | `AllocationService`       |
| Sector breakdown      | `GET /allocation/sectors`  | `SectorBreakdownService`  |
| Style breakdown       | `GET /allocation/styles`   | `StyleBreakdownService`   |
| Attribution           | `GET /attribution`         | `AttributionService`      |
| Period diff           | `GET /period-diff`         | `PortfolioReturnService.periodDiff` |
| Watchlist             | `GET /watchlist`           | `WatchlistService`        |
| Watchlist mutate      | `POST /watchlist/{add,remove,threshold,move}` | `WatchlistRepository` |
| Watchlist popup chart | `GET /watchlist/series`    | `WatchlistService`        |
| Watchlist vol horizons| `GET /watchlist/vol`       | `WatchlistService`        |
| Fundamentals snapshot | `GET /portfolio-fundamentals` (+ `/add`, `/remove`, `/refresh`) | `PortfolioFundamentalsService` |
| Swing stats           | `GET /swing`               | `SwingAnalysisService`    |
| Swing mutate/backfill | `POST /swing/{symbols,backfill}` | `SwingAnalysisService` |
| Trend filter          | `GET /trend`               | `TrendAnalysisService`    |
| Trend backfill        | `POST /trend/backfill`     | `TrendAnalysisService`    |
| Momentum              | `GET /momentum`            | `MomentumAnalysisService` |
| Momentum mutate/backfill | `POST /momentum/{symbols,backfill}` | `MomentumAnalysisService` |
| Momentum signals      | `GET /momentum-signals`    | `MomentumSignalsService`  |
| Momentum signals backfill | `POST /momentum-signals/backfill` | `MomentumSignalsService` |
| Range floor           | `GET /range`               | `RangeAnalysisService`    |
| Range backfill        | `POST /range/backfill`     | `RangeAnalysisService`    |
| Earnings calendar     | `GET /earnings`            | `EarningsCalendarService` |

## Holding fields

- `currentMarketValue` — native currency amount.
- `currentMarketValueGbp` — pre-converted GBP from the source (AJ Bell does); `null` → convert via FX.
- `costBasisGbp` — GBP cost from the source (AJ Bell only); `null` → compute from `avgPricePaid` via FX.

**FX convention:** rates are *units of foreign currency per 1 GBP* (e.g. `{"USD": 1.3621}`).
To convert native → GBP, **divide** by the rate.

## Persistence

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
- `KeyValueStore` — file-backed scalar settings, one `<key>.txt` per key under `<db-dir>`:
  - `ii_sipp_cash_last` — last II SIPP **GBP** cash balance entered on the dashboard.
  - `ii_sipp_cash_last_usd` — last II SIPP **USD** cash balance entered on the dashboard.
    Service converts via current FX before summing into the holdings view's totals.
  - `roth_balance_brought_forward` — RothIRA opening balance in **USD**, used to seed the
    running balance on first import. Charts must convert to GBP via historical FX
    (see `ContributionService.rothSeedAsGbp`, `PortfolioValueService.timeline`) — treating
    the file as GBP would understate the gap substantially.
  - `held_symbols` — newline-separated set of currently-held symbols, written by
    `SyncPortfolioService` after each `/sync`. Unioned with the cash-ledger symbol set in
    `PriceFetchSupport.tickersToFetch` so freshly bought names get an intraday quote before
    their cash statement is imported.

**Account / TransactionType persistence:** the enums map to TEXT columns via `Account.dbValue()`
("AJBell"/"RothIRA"/"II") and `TransactionType.name()`. The SQLite CHECK constraints use the
same literal strings — don't rename enum constants without a migration.

**Write paths throw, reads degrade.** `SnapshotRepository.saveSnapshot` and the three
`CashTransactionRepository.save*` methods throw `IllegalStateException` on failure
(`@ExceptionHandler` renders `fragments/error`). Query methods and background-job inserts
(`savePriceBars`, `saveIntradayBars`) log + return empty/0 — they must not crash the scheduler.

**Migrations:** see `CashTransactionRepository`'s constructor for the idempotent pattern
(`CREATE TABLE IF NOT EXISTS` + `try { ALTER TABLE … } catch (SQLException ignored) {}`).

## Bond identification

`Instruments.isBond(securityId)` checks for `%` or `GILT` prefix. Single source of truth used to
(1) sort bonds into their own section at the bottom of the Portfolio sheet, (2) skip Yahoo price
fetches (no coverage there), (3) switch the per-£100-nominal value formula (`qty × price / 100`)
in `PortfolioAggregator.realtime`, `SyncFromCashService.realtime` (cash-ledger view), and
`PortfolioValueService.positionGbp` (value chart) — keep these in sync if the bond price model
changes.

Bonds in AJBell exports are normalised to `"GILT {coupon}% {year}"` by `AJBellSippParser.extractBondId`.
The same string is the symbol under which `GiltPriceFetchJob` writes to `price_intraday`, so the
aggregator joins holding → RT price without any ticker translation.

## Dividends

Source: `cash_transactions WHERE type = 'DIVIDEND'` — no separate table, no manual-entry screen.
`DividendService.dividendsBySymbol` runs `DividendAttributor` over the cash ledger (FIFO lot
tracking — see the class doc), reconciles the reconstructed share count against the holdings
file, and logs WARN on disagreement >0.01 shares. Per-symbol amounts flow into `AggHolding`'s
`dividendGbp` / `totalGainGbp` / `totalGainPct`. Portfolio-level totals (`PortfolioMetrics`) are
price-appreciation only — dividends are **not** folded into the saved snapshot.

## Open orders

Read-only view of resting orders across brokers (Open orders tab, under the Holdings group).
`OpenOrdersService` locates the newest order-status export per broker in the input dir (reusing
`HoldingFileLocator.findMostRecent(Predicate<Path>)` — same "newest file wins" rule as holdings),
parses each, and keeps only non-terminal statuses. Otherwise a mostly-disk read — the non-file
dependencies are a current FX snapshot (`FxRateProvider`, fills each order's GBP amount) and the
stored price feeds (`IntradayPriceRepository` + `PriceHistoryRepository`, fill the live-price
columns); all degrade to null + a warning rather than failing the view. Everything is normalised
into one framework-free `domain.model.OpenOrder` record (broker, symbol, name, side, orderType,
quantity, price, priceEstimated, amount, currency, dateEntered, expiry, duration, status, reference,
amountGbp, currentPrice, distancePct, dailyVol10, dailyVol30, sigmaToLimit, touchProb5d,
touchProb20d); absent fields are null → "—". Parsers build enrichment-free orders; the service adds `amountGbp` via
`withAmountGbp` (`toGbp` divides by the rate — units of foreign ccy per GBP), the two live-price
columns via `withCurrentPrice`, and the four volatility columns via `withVolatility`.

- **Live-price columns (`Current` + `vs limit`).** `enrichWithCurrentPrice` bulk-loads the latest
  intraday quote for every order symbol (one query; daily-close fallback within `DAILY_FALLBACK_DAYS`),
  resolves the symbol to its Yahoo ticker via `YahooTickerMap` (bonds map to themselves), and expresses
  the quote in the **order's own currency** through `priceInCurrency` — the same GBp-pence→GBP-pounds
  shift and cross-currency-via-GBP conversion the rest of the app uses (a listing quoted differently
  from the order is FX-crossed; a missing rate yields null, never a wrong number). `distancePct` =
  signed `(current − limit) / price` (positive = market above the resting limit), rendered as a
  green/red % and percent-formatted in Excel. Unenriched orders (no symbol, no quote) keep "—".

- **Volatility columns (`RV10` / `RV30` / `σ→lim` / `P(5d)` / `P(20d)`).** Same `enrichWithCurrentPrice`
  pass. `loadDailyVols` reads each symbol's daily series once (`VOL_LOOKBACK_DAYS` = 90 calendar days) and
  computes **realized daily volatility, not annualised** over the last `RV_SHORT`=10 and `RV_LONG`=30
  sessions via `PriceStats.dailyVol` (sample stdev of daily log returns; `annualizedVol` is now just
  this × √252, sharing one `dailyVolRaw` engine). Vol is a return **ratio**, so it's currency-free — a
  `.L` pence series needs no FX. `PriceStats.sigmaDistance(current, limit, dailyVol, 5)` = signed
  `ln(limit/current) ÷ (dailyVol×√5)` — how many 5-session σ the limit sits from the market (+ above,
  − below), using RV10 as the near-term σ (falls back to RV30 when the 10-session window is too thin).
  `PriceStats.touchProbability` = the driftless first-passage `2·Φ(−|d|)` (standard-normal CDF via a
  Numerical-Recipes `erfc`), the probability the market touches the limit at least once within the
  horizon; capped to [0,1], = 1 at the barrier. Two horizons — `TOUCH_HORIZON_SESSIONS` 5 (also the
  σ→lim column) and `..._LONG` 20 — share **one** base vol, so P(20d) ≥ P(5d) always (same barrier,
  more time; only the √horizon scaling differs). σ/touch need a limit **and** a live price; RV10/RV30
  need only the daily series, so a name with no live quote still shows its RVs. Excel formats:
  RV10/RV30 `0.00%`, σ→lim `+0.00;-0.00`, both P(touch) `0.0%`.

- `parser/OrderParser` — interface (`sourceName`, `supports`, `parse`). Parsers return **all** rows;
  the open/closed filter lives in `OpenOrdersService.isOpen` (a denylist of terminal statuses —
  dealt/executed/filled/cancelled/expired/…; anything else, incl. an unseen "still working" status,
  is shown rather than dropped).
- `AJBellOrdersParser` — `activeorders*.csv`. The export names the instrument but carries no
  ticker, so `symbol` is mapped from the name by keyword (`symbolOrName` over `NAME_TO_SYMBOL`:
  EQQQ / LSEG / REL — the same internal symbols used elsewhere; add a line when a new AJ Bell name
  appears). An **unmapped** name falls straight through as the `symbol` (the table has no Name
  column), so a new instrument shows its full name in the Symbol column — the cue to add a mapping —
  rather than "—"; it just won't resolve to a live quote until mapped. `name` still carries the full
  text on the record (unused by the view). Prices are London pence ("53800p") → normalised to pounds
  so the row is single-currency (GBP). Statuses: "In progress" (open), "Dealt" (filled).
- `IIOrdersParser` — UUID `.csv`, **header-sniffed** (`Submitted` + `Settlement Date` + `Status`) to
  distinguish from II holdings/cash UUID CSVs. `Type` bundles side+ordertype ("SELL/LIMIT"); currency
  from the `Amount` £/$ prefix; no expiry. The resting **limit price isn't exported**, so it's
  estimated as `amount ÷ quantity` with `priceEstimated=true` — the UI prefixes "~" and shows a
  footnote (the ~£2.99 dealing fee, plus an FX charge in GBP on USD orders, is folded into the
  amount, so an exact price can't be recovered). Open = "PENDING".
- `RothOrdersParser` — Citi `Order+Status*.xlsx` (POI, `DataFormatter`). Header row located by
  scanning (metadata preamble above it). All USD + GTC (`duration` = "GTC", `expiry` null). Open =
  "Open". The order value isn't exported but the (exact) limit price is, so amount = price × qty.

Wiring: three parsers are listed in the `openOrdersService` bean (`BeanConfiguration`); the order of
that list is the broker grouping in the table. `OrdersController` (`GET /orders`) renders
`fragments/orders :: result`; the table is a sticky-head sortable table (sort attached via the
`htmx:afterSettle` handler, which now also watches `#orders-result`; `parseVal` strips "~" so
estimated prices still sort numerically). No parser registration in `PortfolioGatherer` — the order
pipeline is independent of the holdings/cash pipelines (and the II sniff keeps them from
cross-matching).

**Excel download.** The panel's "Download Excel" button (`POST /orders/export`) calls
`OpenOrdersService.export()`, which re-parses (so it's always current) and writes a single-sheet
`Open Orders-YYYYMMDD.xlsx` to the **output dir (~/Documents)** — same place the portfolio exports
land, not the browser's download folder — then reuses the shared `fragments/export` result fragment
to report the path. Sheet built by `ExcelReportWriter.ordersWorkbook(List<OpenOrder>)`: Price /
Current / Amount / Amount GBP are real numbers formatted with the row's currency symbol via a
per-cell number format (mixed £/$ reads naturally, stays sortable); II estimated prices carry a
literal "~" in the format (`"~$"#,##0.00`), mirroring the screen while keeping the numeric value; the
`Vs limit` column is a signed percent (`+0.00%;-0.00%`); the `RV10`/`RV30` daily-vol columns are
`0.00%`, `σ→lim (5d)` a signed plain number (`+0.00;-0.00`) and `P(touch 5d)`/`P(touch 20d)` a `0.0%`
percent (all via `setStyled`, raw fraction/number in the cell). (The Files-tab portfolio button was
renamed to "Download Excel" too — both write to ~/Documents.)

## Adding a new parser

1. Implement `AccountParser` (`parse`, `supports`, `sourceName`).
2. Register in `PortfolioGatherer` (parsers are built per run, after rates are fetched, because
   `AJBellSippParser` needs the live rates).
3. If the source provides GBP values directly, set `currentMarketValueGbp` + `costBasisGbp` on
   the `Holding`; otherwise leave them null and `PortfolioAggregator` will convert via FX.

File-detection: `Holdings*.xlsx` (Roth), `portfolio*.csv` (AJBell SIPP), UUID `.csv` (II SIPP
holdings; distinguished from II cash by header sniff), `cashstatements*.csv` (AJBell cash),
`History*.xlsx` (Roth cash), UUID `.csv` with `Debit,Credit,Running Balance` header (II cash).

## Adding a new cash source (broker)

1. Implement `CashTransactionParser` (`account()` → `Account` enum, `supports`, `parse`).
2. Add a `CashTransactionRepository.save…` method if the dedup rules differ, else reuse one.
3. Implement `CashImporter` by extending `AbstractCashImporter` (provides `matchingFiles`,
   `mostRecent`, `parse`, `archiveOrDelete`).
4. Register the importer as a bean in `BeanConfiguration`. Spring injects
   `List<CashImporter>` into `ImportCashService` in bean-declaration order, which is the order
   the dashboard shows results.

**Cash dedup keys (one per broker, all cross-file):**
- AJBell: `(transaction_date, type, symbol, amount_gbp)`. AJ Bell can retroactively insert a
  dividend mid-file, which shifts every later row's running balance — so identity is the row
  itself, not its position. Re-importing UPDATEs `cash_balance_gbp` on matching rows.
  Integrity check: if the file's earliest row is newer than every stored AJBell row, the
  import **throws** as a gap (a window has been lost between exports). Legacy duplicates
  from prior (date, balance)-keyed imports are collapsed on next load — highest-rowid wins.
- RothIRA: `(date, symbol, quantity, type, amount)` — native USD amount, FX-stable across
  imports. Balance continues from latest stored `cash_balance`, or the
  `roth_balance_brought_forward` KV seed (USD) on first import.
- II: `(date, type, symbol, amount, currency)` within `account='II'`. The currency component
  isolates the GBP and USD ledgers within the shared account so dividend FIFO still groups by
  `(account, symbol)` across currencies.

## Price history

Two tables, three jobs. `PriceFetchSupport` is shared by the two Yahoo jobs:

- `PriceFetchJob` → `price_history` (daily). First run backfills ~10 years per ticker; later runs
  fetch the gap to today. Cron 22:00 Europe/London + startup.
- `IntradayPriceFetchJob` → `price_intraday` (1-minute closes). **15-day** retention
  (`RETENTION_DAYS`), but the per-tick fetch lookback is capped at `FETCH_LOOKBACK_DAYS` = 7 —
  Yahoo only serves ~7 days of 1-minute history per request, so the 15-day window *accumulates*
  as ticks append rather than backfilling in one shot. Feeds the watchlist popup's 1D–15D
  intraday charts. Pruned after each fetch tick. Every minute + startup.
- `GiltPriceFetchJob` → also `price_intraday`, one HTTP GET to
  `hl.co.uk/shares/corporate-bonds-gilts/bond-prices/uk-gilts` (HL; conventional gilts only —
  index-linked are on a separate page, not fetched). Previously scraped dividenddata.co.uk,
  which went behind a Cloudflare JS challenge in July 2026 (no header combination passes a
  managed challenge — if HL 403s someday, check for the same before fiddling with UAs).
  Hourly + startup. Symbols are `"GILT {coupon}% {year}"` (no Yahoo ticker map; HL's
  zero-padded coupons "4.750" are trimmed to the broker form "4.75"). Currency `"GBP"`.
  Rides the intraday job's prune for retention. `GiltPriceFetcher` sends a browser UA.
  Each tick also **upserts** today's row into `price_history` so the daily series
  self-accumulates even when the app isn't running all day — last-write-wins.
- `ImportGiltPricesService` → bulk historical import for gilts from manually downloaded
  `~/Downloads/Tradeweb_FTSE_ClosePrices*.csv`. Runs on startup (daemon) and on demand via
  `POST /import-gilt-prices`. Upserts into `price_history`, archives consumed files to the
  DB dir as `Tradeweb_{symbol}_{from}_to_{to}.csv` (range comes from the file's own dates,
  not today's). **Never clobbers**: if a target name already exists, appends `_2`, `_3`, ...
  Tradeweb files are manually sourced and irreplaceable, so `REPLACE_EXISTING` is forbidden
  here.

**Gilt price history sources:** two independent feeds writing the same table via the same
upsert. Either can fill or overwrite the other's day. The intraday rollup gives continuous
coverage while the app runs; Tradeweb batch imports give authoritative closes for whatever
range the user downloads — typically the source of truth when both are present.

`PriceFetchSupport.tickersToFetch` builds the universe: every symbol from
`CashTransactionRepository.distinctTradedSymbols()` plus the `held_symbols` KV set persisted by
the most recent `/sync` (covers freshly bought names whose cash statement isn't imported yet —
otherwise their first intraday quote would wait until import). Minus bonds (`Instruments.isBond`),
mapped to Yahoo tickers via `YahooTickerMap`. 500ms throttle between requests
(`PriceFetchSupport.sleep`).

`PriceFetchScheduler` is the only Spring class in the price layer; `@EnableScheduling` is on
`PortfolioBenchApplication`. Startup runs are on daemon threads so first-run backfill doesn't
block the web UI.

**Adding a Yahoo ticker mapping:** add `internal=YAHOO` to `resources/yahoo-tickers.properties`.
US listings map to themselves; non-US need an exchange suffix (`.L` London, `.PA` Paris, `.AS`
Amsterdam, `.DE` Frankfurt).

**A missing mapping fails in two different ways**, which is why the identity fallback is warned
about rather than trusted. Unmapped `PHP` (Primary Health Properties, LSE) resolved to a US mutual
fund delisted in 2019 — no data, so the position simply lost its RT value; unmapped `BLND` (British
Land, LSE, ~440p) resolves to a *live* NYSE ticker near $1.50, which would have priced the holding
wrongly instead of not at all. Two signals cover the first case, one covers the second:
- `PriceFetchJob` warns on a **first-ever** fetch that comes back empty (`PriceFetchSupport.warnNoPriceData`,
  the one place that wording lives) — restricted to first-ever because a quiet fetch is routine for
  an established ticker. The message branches: an unmapped symbol gets the copy-paste line to add,
  a mapped one is told to correct its existing entry. The intraday job deliberately stays silent
  (it runs every minute; the same warning would be log spam).
- On a first-ever fetch that *does* return data through the identity fallback,
  `PriceFetchSupport.noteIdentityTicker` logs at INFO what was assumed. That is the only signal that
  catches the BLND shape, and it can't be a warning — every US name is legitimately unmapped.
- Both sync views surface `SyncResult.unpricedSymbols` (`SyncPortfolioService.unpricedSymbols`,
  shared static): held symbols with **no intraday quote and no daily close ever stored**, rendered
  as their own panel naming the ticker actually tried. A missing intraday quote alone stays in the
  existing "Missing intraday prices" panel — that's an ordinary stale feed. Bonds are excluded (the
  gilt scraper, not Yahoo, prices them).

**Three name→symbol maps, all needed for one new instrument** — a name is only fully wired when it
appears in all three, and PHP (Primary Health Properties, Aug 2026) is the worked example of what
each one costs when missed:
1. `AJBellCashStatementParser.SYMBOL_RULES` — cash-ledger description → internal symbol. Unmapped,
   `resolveSymbol` falls back to the cleaned investment name, so the ledger stored
   `PRIMARY HEALTH PROPERTIES PLC` while the holdings file called the same position `PHP`: the
   ledger view lost the position, and the reconciliation panel reported the whole £10k as a diff.
   Now warns at parse time naming the string it stored.
2. `AJBellOrdersParser.NAME_TO_SYMBOL` — order-export investment name → internal symbol. Unmapped,
   the order still lists (under its full name) but resolves to no ticker, so Current / vs limit /
   σ→lim / P(touch) are all blank. Now warns via `symbolOrName`.
3. `yahoo-tickers.properties` — internal symbol → Yahoo ticker (above).

A stale ledger row left by (1) has to be corrected in place —
`UPDATE cash_transactions SET symbol='PHP' WHERE symbol='PRIMARY HEALTH PROPERTIES PLC'` — because
the AJBell dedup key is `(transaction_date, type, symbol, amount_gbp)`: leave it and the next
import of the same statement inserts a *second* row under the newly-mapped symbol and double-counts
the position.

**`close` vs `adj_close`:** use `close` for "market value of the position on date X", `adj_close`
for total-return calculations. **`YahooPriceFetcher` re-derives `adj_close` from the
`events.dividends` and `events.splits` stream on every fetch** — Yahoo's bundled `adjclose`
field is only split-adjusted (not dividend-adjusted) for many UK listings, so trusting it
silently understates total return. The backward-walk handles both dividends and splits and
yields a true total-return adj_close. The daily incremental run only refreshes the latest
day, so a new dividend on a held name leaves historical `adj_close` stale — trigger
**Rebuild adj_close** on the dashboard (or `POST /rebuild-prices`) to re-fetch the full
window per ticker and refresh every row via `upsertPriceBars`. The gilt paths (intraday
rollup, Tradeweb batch import) also use `upsertPriceBars`, so latest write wins for
`(symbol, date)` — clean price feeds both `close` and `adj_close` (no total-return math
for bonds).

**Currency is stored verbatim** from Yahoo. `.L` (London) tickers report `"GBp"` (pence);
`PortfolioAggregator.realtime` divides by 100 before producing the RT market value.

**II SIPP cash field (holdings view + Excel export only):** II's holdings CSV export omits the
cash balance, so the dashboard form supplies it as **two separate inputs** — `iiSippCash`
(GBP) and `iiSippCashUsd` (USD). `SyncPortfolioService.combineIiCashAsGbp` converts the USD
portion at the live FX rate it already fetched and sums into the single GBP figure that
flows into `PortfolioMetrics` and the Excel sheet. Both inputs persist in `KeyValueStore`
(`ii_sipp_cash_last`, `ii_sipp_cash_last_usd`). The cash-ledger view, contributions chart,
and value-over-time chart don't use these inputs — they derive II cash from the latest
stored `cash_balance_gbp` per `(account='II', currency)` directly.

## Charts (Contributions, Value-over-time)

Two read-only JSON endpoints feed Chart.js panels:

- **`GET /contributions`** (`ContributionService`) — cumulative GBP per account plus Total.
  Source: `cash_transactions WHERE type = 'CONTRIBUTION'`. RothIRA has no contribution rows;
  the brought-forward seed (USD) is converted to GBP at the FX rate on Roth's earliest
  ledger date and emitted as a single step.
- **`GET /portfolio-value`** (`PortfolioValueService`) — month-end portfolio GBP value.
  Forward-replays the full ledger once, valuing surviving positions at the most recent
  `price_history` close ≤ each sample (with **ceil-fill to the earliest known close** as
  fallback for samples that predate all stored prices). Bonds use the per-£100 formula;
  `GBp` (pence) divides by 100. Roth USD seed folded in on its earliest ledger date.
  Cash FX'd via `HistoricalFxRateProvider` (Frankfurter).

`ValueTimeline.missingPrices` lists symbols ever held with zero rows in `price_history`,
including the date range they were held. The dashboard renders them as a yellow warning
panel above the chart; each one also logs WARN. Dropping any close for the symbol clears
the warning and fills in the dip.

**Yahoo testing:** JSON parsing is unit-tested against `yahoo-nvda-sample.json`; a live-API test
(`YahooPriceFetcherIntegrationTest`) is `@Tag("integration")` and excluded by the surefire
`excludedGroups` property. Run with `mvn test -Dgroups=integration -DexcludedGroups=`.

## Watchlist & alerts (volatility trading)

Monitor-and-nudge tool for opportunistic trimming/rebuying — it emails alerts and never
touches an order.

- `WatchlistRepository` — `watchlist` table (symbol PK + two thresholds + added_at). The
  symbol set is **mirrored** to `KeyValueStore` under `watchlist_symbols` on every mutation,
  so `PriceFetchSupport.tickersToFetch` and `PortfolioFundamentalsService` union watchlist
  names into their fetch universes with no constructor churn — a freshly added symbol gets
  daily + intraday prices and a fundamentals row on the next tick. `WatchlistController.add`
  also kicks an immediate `PriceFetchJob.fetchSingle` + fundamentals refresh in the background.
- `WatchlistService` — assembles each row from one snapshot (single FX fetch, one bulk
  latest-intraday lookup, one ledger read). Position/gain reuse `PositionDetailService`'s FIFO
  engine (same package) rather than re-implementing it. Momentum (Today/2d/3d/5d/10d/30d) is
  **session-anchored** via `PriceStats.closeNSessionsBefore` — `k` "days" = `k` trading sessions,
  so "2d" is the last two sessions (today + the prior session) and the short windows stay distinct
  over weekends (a calendar anchor collapsed Today/2d/3d onto the same Friday close on Mondays).
  The full row and the near-real-time `/watchlist/quotes` poll share one `computeLive` calc so
  they can't diverge; realized vol is `PriceStats.annualizedVol` (30-session sample
  stdev × √252, on split-adjusted `close`, same convention as the Risk tab). 52-wk high/low,
  P/E and beta come from the fundamentals cache. `series()` serves the popup chart: intraday
  for 1D–15D (downsampled to ~5-min beyond 5 days), daily `close×splitFactor` for 30D.
- **Triggers** are pure domain logic (`WatchlistAlerts.evaluate` → `Flags`): (a) within
  `highThresholdPct`% of the 52-week high (a fresh high always fires); (b) `|today's move|` ≥
  `moveThresholdPct`%, either direction; (c) price ≥ `priceAbove` level; (d) price ≤ `priceBelow`
  level. The two price levels are absolute, in the symbol's **native price currency** (compared
  against the same native `currentPrice` the row shows — no FX), at-or-through counts. Screen and
  alert job evaluate identical `Row`s so they never disagree. **No 52-wk-low trigger** — the low is
  displayed but never alerts (watched names have run up too far for it to matter). A **blank/0
  threshold/level disables that trigger** (`WatchlistRepository` stores 0; the columns are
  `price_above`/`price_below`, NULL on rows predating them → read as 0 = disabled), so a symbol can
  be watched with no alerts at all. `both()` (the pulse) stays near-high + big-move only; the alert
  job dedups the price levels under `SYMBOL|ABOVE|date` / `SYMBOL|BELOW|date`.
- **Popup extras:** `GET /watchlist/vol` returns a realized-vol **term structure** (5D/10D from
  intraday 1-min returns via `PriceStats.annualizedVolIntraday`, 30D/1Y from daily closes) shown
  in the popup stats. `GET /watchlist/series` for `1D` returns only the latest session's bars
  (convention: today only); the popup chart uses a **category x-axis** (evenly-spaced) so
  overnight/weekend gaps don't draw diagonal lines — 1D labels are times, multi-day/30D labels
  are dates. Popup fundamentals reuse the Snapshot-diff renderer (`fundamentalsHtml` /
  `fundamentalsFieldGroups` in `dashboard.js`) laid out in columns.
- **Manual order:** `watchlist.display_order` + `POST /watchlist/move?dir=up|down` swap
  neighbours; rows render in that order (↑/↓ buttons per row). Add-backfills run on a
  single-thread executor in `WatchlistController` so two quick adds queue rather than racing
  SQLite writes.
- `WatchlistAlertJob` — runs every 5 min (`PriceFetchScheduler`). Dedups **once per day per
  symbol per trigger** via a KV set (`watchlist_alerts_fired`, keys `SYMBOL|TRIGGER|date`),
  batches a tick's new firings into one email, and only persists the fired-set after a
  successful send (so a mail failure retries). No-op unless SMTP is configured.
- **Email:** `AlertNotifier` port + `SmtpAlertNotifier` adapter (Jakarta Mail directly, kept
  Spring-free like the other adapters; `spring-boot-starter-mail` supplies the library).
  Disabled by default. **Put the Gmail app password in `~/.portfolio-bench.properties`** (home
  dir, outside the repo — `application.properties` loads it via
  `spring.config.import=optional:file:${user.home}/.portfolio-bench.properties`, so it can never
  be committed). Set `portfolio.alert.mail.{enabled=true,username,password,from,to}` there
  (Gmail: `smtp.gmail.com:587`, STARTTLS). The app must be running during market hours for
  alerts to fire — keep the lid open + `caffeinate` (closing the lid sleeps the JVM even under
  caffeinate).

## Swing-strategy validation statistics

Backtesting/statistics tool (Swing stats tab) for validating a short-term mean-reversion
strategy — dip-buy after 1–2 day flushes, exit at a fixed take-profit / wide stop / time stop —
*before* trading it. Read-only: it never touches the ledger or places an order.

- **Pure engine** lives in `com.portfolio.domain.swing` (framework-free, unit-tested like
  `PriceStats`). `SwingConfig` holds every knob (spec defaults); `SwingSeries` pre-computes the
  aligned `close/adjClose/high/low/logRet/simpleRet` arrays, a **look-ahead-free trailing-30d daily σ**
  (σ over returns strictly *before* each bar, so a σ-trigger judges a day against vol known going in),
  and a trailing **`sma[]`** (200d MA, `maWindow`) for the §A/§E regime flag. `preWindowSigma(i,days)`
  = the σ strictly before a flush window (`trailSigma[i-days+1]`), so the flush can't inflate its own σ.
  Engines: `RealizedVol` (§1), `ForwardReturns` (§2/§3/§4), `Flushes` (§5 profile + §6 barrier sim),
  `Placebo` (§6 controls), `ReturnCorrelation` (§7), `FlushEvents` (§A event log), `AnnualBreakdown`
  (§B per-year), `MaSplit` (§E 200d-MA split). `Stats` provides mean/median/quartiles/%pos + a public
  `quantile`. All engines take a `List<SwingSeries>` so the same code serves per-symbol (one) and
  pooled-cluster (concatenate observations across the cluster, equal-weight by observation).
- **Trigger families (§C).** `FlushTrigger` abstracts "does a flush fire at bar `i`" with two impls —
  `AbsoluteFlush` (cum ret ≤ −threshold) and `SigmaFlush` (cum ret ≤ −k×σ×√N, σ = `preWindowSigma`).
  `Flushes` is parameterised by it, so the whole §5/§6/placebo/bootstrap/§A/§B/§E machinery runs for
  the absolute and σ-scaled families **in parallel** over each regime. The σ family fires at each
  regime's own scale, so a low-vol bear (2022) generates events the absolute trigger misses.
  `Flushes.overlapCount` reports the dates both barrier-seed triggers fire (never double-pooled).
- **Conventions:** returns off `close` (split-adjusted), log for vol, simple for anything P&L-like.
  σ-triggers compare the day's simple return to −k×(trailing daily σ); absolute triggers to −p%.
  A 1e-9 epsilon makes round thresholds (a "−10%" day stored as −0.0999999998) register. Small-N
  cells are flagged, never silently averaged (`smallNFloor`); **pooled** cells also report distinct
  event *dates* (episodes) beside the raw event count and flag `episodes < episodeFloor` (cluster
  names flush on shared dates, so 10 symbols × one crash ≈ 1 episode, not 10).
- **§6 event accounting** (the calibration core). Barrier touches are read from **intraday OHLC**
  (TP if session `high ≥ entry×(1+tp)`, SL if `low ≤ entry×(1−sl)`; fills at the barrier, time-stop
  exits at the close). `Flushes.simulateTrade`/`aggregate` are the shared single-trade engine (reused
  by the placebo). Five-way reconciliation holds in every table: **events = TP + SL + Time +
  Ambiguous + Open**. *Ambiguous* = both barriers touched in one session (excluded from the standard
  mean return; a `meanReturnConservative` books each as a stop for a pessimistic bound). *Open* = the
  outcome window runs past the last data date (censored — never dropped, never counted as an
  outcome). §6 events are **non-overlapping until the prior simulated trade resolves** (so the count
  legitimately shrinks as the time stop lengthens); §5 keeps its `flushCooldown` dedup for its
  descriptive profile. `Stats.Dist` and any nullable figure never emit a bare `NaN` — `config/JacksonConfig`
  serializes non-finite doubles as JSON `null` app-wide (empty distributions would otherwise corrupt
  the payload).
- **§6 controls (`Placebo`)** answer "does the flush trigger beat drift?": an **all-days placebo**
  (entry at every fully-evaluable close, overlaps allowed, `open == 0` by construction) and a
  **matched random bootstrap** (draw N = flush-event count random entry dates from that pool,
  `bootstrapIterations` times, seeded by `randomSeed`; reports the %TP / mean-return distribution and
  the percentile rank of the actual flush result — a rank inside the IQR means the trigger adds
  nothing). The bootstrap samples the precomputed placebo pool, so 1,000 iterations are cheap.
- **§A/§B/§E engines.** `FlushEvents` emits one diagnostic row per **cooldown-deduped** event (every
  down-leg, not the §6 non-overlap subset), resolved at `eventLogTimeStop` — cum return, σ-multiple,
  above/below-200d-MA + distance, outcome/exit/return, further drawdown, sessions-to-target, and the
  §D earnings flag; the row set is CSV-exportable in the UI. `AnnualBreakdown` groups events by
  calendar year (outcome counts + mean/median trade return) beside the name's own `adjClose` calendar
  return (pooled = member mean). `MaSplit` partitions §6 outcomes and the placebo pool by entry MA
  state and runs a within-state bootstrap when powered (pool ≥ 100, draws ≥ 5), else flags
  `underpowered`.
- **`SwingAnalysisService`** loads bars, runs every symbol + the pooled cluster over both §9
  regimes (full history + trailing 6 months, rendered **side by side**), and adds §7 correlation.
  Each `SymbolReport` reports its actual data span (`from`/`to`/`yearsAvailable`) and a `shortHistory`
  flag when the first bar post-dates the full-history cutoff. Each `Regime` carries `families` (abs +
  σ) + `familyOverlapCount`; each `FlushFamily` carries two `EarningsVariant`s ("all"/"ex-earnings",
  §D) of the §5/§6 tables (each §6 stop still a `BarrierComparison` of flush + placebo + bootstrap),
  plus the §A event log, §B annual and §E MA split. No FX — statistics are unit-less return ratios, so
  `.L` pence listings need no conversion. `SwingReport` (in `application`) is the JSON DTO;
  `earningsHygiene` is now a real §D coverage summary.
- **Config persistence:** the symbol universe + cluster live in `KeyValueStore` (`swing_symbols` /
  `swing_cluster`, seeded from spec defaults on first read). `swing_symbols` is unioned into
  `PriceFetchSupport.tickersToFetch`, so the nightly 30-year daily job keeps the swing universe
  backfilled automatically; `POST /swing/backfill` (and `POST /swing/symbols` after an edit) covers
  the first-run gap via `PriceFetchJob.fetchSingle` on a background executor. `GET /swing` echoes
  the effective `SwingConfig`; every knob is also an optional query param (percents in, e.g.
  `dropPcts=5,8,10`; σ-family knobs `flushSigmas`/`barrierSigmaK`, plus `maWindow`/`eventWindow`/
  `eventLogTimeStop`) so parameters can be swept without changing stored config.
- **§D earnings hygiene (active).** Earnings dates are proxied by **SEC EDGAR 10-Q/10-K filing dates**
  (submissions API), which land within a few days of the release — good enough for a ±window flag, and
  the only free multi-year source. `port/EarningsCalendarProvider` → `adapter/EdgarEarningsFetcher`
  resolves a ticker's CIK **dynamically** via `adapter/CikRegistry` (SEC's `company_tickers.json`, disk-
  cached at `<db-dir>/sec_company_tickers.json`, 30-day TTL, lazy load), so a new swing name needs no
  source edit; a small `CIK_OVERRIDES` map is kept only as an offline fallback and is consulted first.
  Dates are cached in `persistence/EarningsDateRepository` (`earnings_dates` table) so the report never
  re-hits SEC; `application/EarningsService` composes them and builds a per-series `EarningsLookup`
  (trading-days-to-nearest-earnings per bar). **Verification is data-driven** — a symbol is verified
  iff we actually obtained ≥1 date (`EarningsService.isVerifiable == repo.hasAny`), *not* CIK-presence.
  This is load-bearing: ASML resolves a CIK from `company_tickers.json` but files 20-F/6-K (zero
  10-Q/10-K), so it yields no dates and stays **unverified**; SEMI.L (UK, absent from the file) is
  unresolvable and likewise unverified — neither is ever treated as clean. To cap SEC traffic, every
  attempted symbol is recorded in a KV set (`earnings_checked`); a non-forced warm skips already-
  attempted names, so ASML-type "resolves-but-empty" names aren't re-fetched each report. The
  ex-earnings variant removes events within ±`eventWindow` sessions of an earnings date and drops those
  dates from the placebo/bootstrap pool too, via a per-`(symbol,date)` `EventFilter` (date-only would
  over-exclude ~10× when pooling a cluster). Report reads the cache; `report()` warms it best-effort
  (non-forced), while `POST /swing/backfill` **force**-warms — it refreshes the CIK registry and retries
  even previously-attempted names.

## Trend-filter backtest

Backtesting tool (Trend filter tab) answering one question: does an MA trend filter on the core
Nasdaq-100 holding (held as EQQQ) cut catastrophic drawdowns enough to justify its whipsaw and
lag costs — and which signal clock does it best? Read-only; the decision is made in the
named-episode table (for the filter: 2000–02/2008/2022; against: 2015–16 and COVID — both always
visible). No champion cell is ever chosen: the full sweep grid ships and where variants disagree,
the disagreement is the finding.

- **Pure engine** in `com.portfolio.domain.trend` (framework-free; reuses `domain.swing.Stats`).
  `TrendConfig` (record + Builder, every knob a query param); `TrendSeries` — spliced daily
  arrays: `close` (signal, split-adjusted), `adjClose` (all P&L), `adjOpen` (`open×adjClose/close`,
  fallback `adjClose` when the source open is missing) and a look-ahead-free `cashDaily` (prior
  cash-series close /100/252, flat-rate fallback before its first bar; discount-yield≈short-rate
  and /252 stated as approximations). `Signals` — five `TrendSignal` clocks: `monthAnchored`
  (checks every ~21 sessions, offset-shifted), `firstTouch`, `bufferedPct` (hysteresis band),
  `bufferedCount` (N consecutive closes), `monthlyClose` (classic Faber; completed month ends
  only). Equality counts as *in* everywhere; initial state at `firstValid` is the plain
  comparison.
- **`Backtester`** is the single accounting path for every cell, control and bootstrap iteration.
  Decision at close `i` fills at that close (`CLOSE`) or the next open (`NEXT_OPEN`, the headline;
  overnight leg stays at the old weight). Cost = `costPerSide × |Δweight|`; out-weight earns the
  cash leg; the initial entry at the common start is cost-free (buy-and-hold pays the same); a
  decision on the final bar stays unfilled (surfaced by live state, never executed). **Common
  analysis window**: every variant and B&H start at the *latest* `firstValid` of any configured
  signal, so a 150d cell never gets a head start on a 250d one.
- **Splice honesty.** ^NDX bars before QQQ's first bar are scaled to match at the splice; the
  pre-1999 segment is a **price-only TR proxy** (no dividends) — `spliceIndex`/`proxiedFrom/To`
  and `dotComInSample` (relative to the *analysis* start) are surfaced in the data panel, never
  hidden. `ensureBackfilled` deepens **shallow** coverage, not just missing coverage — the
  startup/nightly price job unions `trend_symbols` and can win the race with its standard 30-year
  window, which would leave ^NDX starting mid-90s; anything whose earliest bar is inside that
  window gets the full 45-year refetch (`PriceFetchJob.fetchSingle(symbol, years)` overload).
  `YahooPriceFetcher.urlTicker` percent-encodes `^` (URI.create rejects it raw) — that fix is what
  makes index tickers fetchable at all.
- **Cost side explicit.** `Whipsaw` compounds per-trip factors
  `[(1+cash while out)(1−c)²]/(reentryAdj/exitAdj)`: bought-back-higher count, whipsaw-only drag,
  net timing effect (= strategy/B&H at zero cost/cash — asserted in tests), per decade. `TradeLog`
  is the exportable row set (display prices raw, deltas total-return; leading/trailing half-trips
  kept as censored rows). Trailing open exits and unrecovered drawdowns are censored-not-dropped
  throughout (same discipline as Swing §6 Open).
- **Controls.** Buy-and-hold in every table; `ExposureBootstrap` scatters the strategy's own
  out-segment lengths uniformly (random composition of in-gaps — preserves time-in fraction and
  switch count, destroys timing; seeded), reporting CAGR/maxDD distributions + the actual rule's
  percentile. `null` when a variant never exits — the UI says so rather than showing an empty
  distribution. Anchor sweep = the identical month-anchored rule at all `anchorEvery` offsets
  (min/q1/median/q3/max of CAGR and maxDD): a wide band is anchor-luck, not signal.
- **`TrendAnalysisService`** (application) assembles `TrendReport`: data panel, B&H, per-cell
  `VariantResult` (headline + cost sweep + close-fill + half-switch + 0%-cash + whipsaw +
  bootstrap + annual + episodes + trades), anchor sweeps, live state (QQQ + EQQQ.L close-vs-MA
  per length, per-variant state/pending-switch/action note). Sharpe always uses the historical
  cash leg, including in the 0%-cash sensitivity (comparable across tables). `trend_symbols` KV
  is written by the constructor seed and `ensureBackfilled` **only — never by `report()`**, so an
  exploratory `?extension=` sweep can't drop ^NDX from the nightly fetch set. Episodes are fixed
  named windows in `Episodes.NAMED`; uncovered/partial ones are flagged, never dropped. No FX —
  unit-less return ratios (EQQQ's GBp readout included).

## Momentum-rotation backtest

Backtesting tool (Momentum tab) for a **cross-sectional 12-1 momentum rotation** over a fixed
~33-name US large-cap universe. The deliverable is not the raw CAGR — the universe is hand-picked
from today's survivors, so absolute momentum returns are inflated (a red survivorship banner says
so in the header) — but whether momentum beats its **controls**. Read-only; never touches the
ledger.

- **Pure engine** in `com.portfolio.domain.momentum` (framework-free; reuses `domain.swing.Stats`).
  `MomentumConfig` (record + Builder, every knob a query param). `MomentumSeries` — the aligned
  multi-symbol daily panel: master calendar = union of all symbols' dates, per-symbol `close`
  (split-adjusted, regime/drawdown) and `adj` (total-return adj_close, all P&L). A missing day is
  forward-filled ≤ `maxFfillSessions` (5); a longer gap is left NaN (ineligible) and logged as a
  `Gap` — never silently filled. `monthEndIdx` = completed-month last trading days (the rebalance
  clock; the final partial month is excluded, same convention as `TrendSeries`).
- **`Momentum`** (ranker) — score at bar `i` = `adj[i−skip]/adj[i−lookback] − 1` (defaults
  252/21). Eligibility needs a price at `i` and at both window endpoints; ties break alphabetically
  so every downstream result is deterministic.
- **`RotationBacktester`** — THE single accounting path for every variant, control and bootstrap
  iteration (same discipline as trend `Backtester`), differing only by a `Selector` (`topN` /
  `bottomN` / `allEligible` / `randomN`). Monthly: rank → select → target `1/slots` per name
  (shortfall in cash at rf; `slots=null` = EW baseline's `1/chosen`); kept names re-equalise so
  they pay only drift-correction cost; `cost = costPerSide × Σ|Δweight|`; turnover `= Σ|Δw|/2`.
  Between rebalances weights drift with total return, the cash slot earns `rfDaily`; a name that
  goes NaN mid-hold is frozen (visible in the gap log), never dropped. **Common window:**
  `analysisStart` = first month-end with ≥ `minEligibleFactor×max(topNs)` eligible names, and every
  variant/control/iteration starts there — expanding universe, names enter as history accrues.
  The initial entry's cost folds into daily-return bar 0, so fit/holdout slices partition exactly.
- **`MomentumStats`** — CAGR (365.25-day), ann vol (√252), Sharpe (flat rf), maxDD with peak/trough
  dates, Calmar, monthly returns (compounded daily slice, "YYYY-MM"), hit rate vs the EW baseline,
  best/worst month. Computed over a `[fromK,toK]` slice of one full run, so fit and holdout come
  from the same accounting.
- **Controls (the point of the exercise).** `RotationBootstrap` — `bootstrapIterations` (1000)
  random-N draws from the same eligible set through the same path, seeded `randomSeed + N`
  (reported); reports `Stats.Dist` of CAGR/Sharpe/maxDD + the actual variant's percentile
  (`cagrInsideIqr` ⇒ UI dims the cell = indistinguishable from random selection). **Fit window
  only** — the holdout never feeds a control. Plus the EW-universe baseline and the **reversed
  signal** (bottom-N matched over the same windows: if worst-N beats top-N the sign/data is wrong)
  and QQQ/SPY buy-and-hold.
- **`RegimeSplit`** — §E-convention split of held months by whether the regime index (QQQ) sat
  above/below its 200d MA at the *positioning* month-end (look-ahead-free); thin buckets flagged,
  pre-MA months bucketed "unknown". **`CrashDiagnostics`** — worst 3 strategy months with QQQ's
  same-month return, plus the strategy's compounded return over the `crashWindowMonths` after each
  month-end where QQQ closed ≥ `crashDrawdownPct`% below its running high (contiguous month-ends =
  one *episode*; windows past the section end are censored, counted, never truncated).
- **`MomentumAnalysisService`** (application) assembles `MomentumReport`: survivorship caveat,
  reproducible seed, expanding-window start + reason, **fit vs holdout** boundaries (recent
  `holdoutMonths` reserved; the split is leak-free because selection at `t` only reads data ≤ `t`),
  per-symbol spans, missing-symbol + gap logs, per-variant fit section (stats + turnover + cost
  drag [CAGR at 0 bps − at configured bps] + regime + crash + bootstrap), lean holdout stats,
  QQQ/SPY benchmarks, and an exportable rebalance log. **Two KV keys:** `momentum_universe` (the
  ranked names, user-editable) and `momentum_symbols` (the fetch set = universe ∪ benchmarks ∪
  regime index, unioned into `PriceFetchSupport.tickersToFetch`) — kept distinct so benchmarks
  never leak into the ranked universe. Both written by the constructor seed and `ensureBackfilled`
  **only — never by `report()`** (an exploratory `?universe=` sweep can't drop names from nightly
  maintenance); `ensureBackfilled` deep-fetches missing/shallow coverage (45y, same rationale as
  Trend). No FX — unit-less return ratios throughout.

## Momentum-signals comparison

Five-variant signal-speed/overlay comparison (Mom. signals tab) on the pinned 17-ETF asset-class
universe, judged by a pre-registered adoption rule. Extends the momentum harness — no second
accounting path.

- **Ranker abstraction.** `domain.momentum.Ranker` is the only thing variants may differ in
  (plus the two overlays); `Ranker.incumbent(cfg)` delegates to `Momentum.rank` verbatim, so the
  generalized `RotationBacktester.run(…, Ranker, …, trailingStopPct)` overload with the incumbent
  ranker is **bit-identical** to the original path (asserted at domain and service level — the V1
  reproduction requirement; the old 6-arg `run` delegates, all existing callers unchanged).
  `SignalRankers.lookbackSkip` (V2: 126/21 scoring, `eligibilityLookback` 252 held constant so a
  faster signal doesn't also get a broader early universe) and `SignalRankers.compositeZ` (V3:
  population-σ cross-sectional z per component over the eligible set, degenerate component → 0,
  composite = mean of z's; hand-checked in `SignalVariantsTest`).
- **Overlays.** V4 = `RotationBacktester.gatedTopN(n, hurdle)` with `hurdle = (1+rfDaily)^(lookback−skip)−1`
  (compounded-daily rf accrual, matching the cash leg) and `slots = n` so a gated slot stays in
  cash; gate re-evaluates held names (no grandfathering); binds derived from events as
  `min(n, eligible) − held`. V5 = `trailingStopPct` inside the daily loop: peaks track
  split-adjusted `close` (what a real stop order watches — P&L stays in adj space), include the
  entry close, absorb every session incl. rebalance days; the trigger check is **skipped on
  rebalance days** (the ranking supersedes); at-or-through fills at that close, cost on the
  weight sold, `StopEvent`s surface in `Result.stops` (censoring/turnover conventions unchanged).
  Whipsaw = stop then re-entry within 2 rebalances; reported with txn cost (bps) + TR gap
  stop→re-entry (negative = re-entered cheaper). V5 turnover includes stop sells (`weightSold/2`).
- **Shared control.** `RotationBootstrap.samples`/`of` split lets one random-N distribution per N
  (seed `randomSeed+N`, fit window only) rank all five variants — the control is
  selection-agnostic (ranking orders the eligible set, never changes membership), and overlays are
  deliberately not applied inside draws: the bootstrap prices selection skill on one yardstick.
- **`MomentumSignalsService`** (application) pins `SIGNALS_UNIVERSE` (17 ETFs incl. REIT, listed
  2021 — enters as history accrues) and assembles `MomentumSignalsReport`: context + 3-bullet
  pre-registration + ≤10-sentence §0 all **verbatim constants**; per-N `VariantRow`s (fit/holdout
  stats, turnover, cost drag, shared-bootstrap percentiles, regime, crash, 2026-YTD, logs); the
  **adoption verdict** (challenger must beat V1 on Sharpe AND Calmar in fit AND holdout, same N;
  NaN comparisons fail conservatively — undefined holdout Calmar keeps the incumbent); the
  **POST-HOC flag** recomputes the holdout leg on the ex-2026 slice (`CONTAMINATED_FROM`
  2026-01-01; contiguous tail, so the same stats path serves it) — an edge that only exists with
  2026 included is flagged whatever the rule says; five **predictions** scored per N with stated
  thresholds (`SHARPE_NOISE` 0.10, |ΔCAGR| ≤ 1pp, YTD ∈ (0,10%)); the **episode panel**
  (5 fixed windows × 5 variants at `episodeN`, default middle N) lists positioning + in-window
  holdings and GLD derate/gate/stop dates, labelled non-decisional. Helpers (`logRows`, `spans`,
  `indexMonths`) shared from `MomentumAnalysisService` (package-private statics). KV
  `momentum_signals_symbols` (universe ∪ benchmarks ∪ regime) unioned into
  `PriceFetchSupport.tickersToFetch`; written by constructor seed + `ensureBackfilled` only
  (standard 30y fetch — the cohort lists post-1993). No universe editing endpoint — the pin is
  part of the pre-registration. First adoption run (2026-07): all challengers REJECT at every N;
  the incumbent stands.

## Range-floor backtest

Pre-registered backtest (Range floor tab) of a percentile-floor range strategy on the fixed
information-infrastructure cohort. The deliverable is the Arm A/B/C/D comparison — the report is
built to be able to answer "no, just buy and hold", and prints that verdict prominently when the
data says so. Read-only; never touches the ledger.

- **Pure engine** in `com.portfolio.domain.range` (framework-free; reuses `domain.swing.Stats`).
  `RangeConfig` (record + Builder, every knob a query param; the defaults *are* the pre-registered
  parameters — any override flags the run EXPLORATORY in the report). `RangeSeries` — aligned
  arrays + **look-ahead-free trailing percentile bands** (`trailingLevels` shares one sorted
  window per bar across all requested percentiles; the window is strictly-prior closes so a crash
  day can't drag its own floor down) + a >40%-daily-move data-error scan (flagged, never
  repaired). `RangeBacktester` — the single trade path: arm after `aboveRunSessions` consecutive
  closes beyond the (moving) band, enter at the close on touch, exits via the swing OHLC-touch
  convention (TP fills at barrier; trailing-P75 variant can legitimately exit *below* entry after
  a long hold; AMBIGUOUS carries no resolved return; OPEN is censored but carries the end-of-data
  TR mark so capital arms can value it). **Signals/barriers/MAE on split-adjusted price** (what a
  real limit order sees); **P&L in total-return space** — fills map via `fill × adjClose/close`
  on the fill bar (Trend's `adjOpen` convention), so dividends accrue to multi-year holds and
  the A-vs-B comparison is dividend-fair.
- **`RangeArms`** — one accounting path for A (cash-ledger walk, fixed £-notional per entry, rf on
  idle cash, symmetric on negative balances), B (buy at A's first signal, end-marked without exit
  cost — cost symmetry: every arm pays purchase costs, only realized exits pay the exit side),
  C (`randomArm`: same purchase count at random distinct sessions in the same window, held to
  end, precomputed hold-to-end growth per session so 1000 draws are cheap; seeded, seed logged;
  per-cell seeds offset like Momentum) and D (mirrored ceiling entries through the A path).
  **Two capital bases, both reported**: return-on-allocated (A/B commit one notional, C commits
  K×notional — bases differ by construction, the report says so) and return per pound-year
  deployed (normalises recycling vs parallel deployment; the bootstrap-percentile basis).
  `RangeEpisodes` — greedy calendar-day clustering (default 7d ≈ one trading week, cross-calendar
  safe) for pooled significance; cells under `episodeFloor` dimmed. `RangeTradeStats` — five-way
  accounting **events = TP + Pctl + SL + Ambiguous + Open** in every table; MAE/underwater include
  open trades ("hold until it bounces" includes the positions still waiting).
- **`RangeAnalysisService`** (application) assembles `RangeReport`: pre-registration block
  (verbatim, at top), exploratory flag + changed-param list, data panel (spans, currencies,
  hygiene flags), scopes = Pooled / Family A / Family B (GBP basis) + per-name (local), each with
  full-history and trailing-24m windows side by side (trailing = the same full-history simulation
  filtered by entry date — re-arm state is path-dependent, restarting on a slice would invent
  trades), verdict lines requiring A > B **and** A > C-median on both bases, sensitivity grid
  (P ∈ {5,10,15} × TP ∈ {8,10,12} × both modes × three scopes; pre-registered cell outlined,
  inside-IQR dimmed, report-never-select), CSV-exportable event log, and the featured LSEG.L
  chart (thinned closes + entry/exit/open markers). **FX**: per-name local-currency; pooled/family
  arms convert at daily Frankfurter rates on entry/exit/end dates (ceil-fill before 1999); FX
  failure degrades to per-name-only with a warning. `range_symbols` KV unioned into
  `PriceFetchSupport.tickersToFetch`; written by constructor seed + `ensureBackfilled` **only —
  never by `report()`**. Standard 30y fetch suffices (no deep-lookback special case — the cohort
  all lists post-1996).

## Investment-style breakdown (growth vs value/income)

Current-snapshot composition by **investment style**, rendered at the very **top of Allocation →
Breakdown**, above the sector block. `GET /allocation/styles` → `StyleBreakdownService`. Answers
what the sector split cannot: how much of the book is cheap, how much is growing, how much is
both, and how much of it pays an income.

- **Style is a 2×2, not a spectrum.** Cheapness and growth are scored independently, so
  `VALUE_GROWTH` (a cheap compounder) and `NEITHER` (expensive without the growth to justify it)
  are real cells rather than rounding errors. Collapsing them onto one value↔growth line forces a
  third of the book into whichever end it sits nearer — a loss-making biotech on a *negative*
  earnings multiple came out as "value" in the one-dimensional prototype.
- **Values** come from `AllocationService.currentSnapshot()` — the same replay the sector block and
  the charts below use, so the three can never disagree about what is held.
- **Zero new fetching.** Every input was already in `fundamentals_snapshot`. The one addition is
  fund-level valuation, parsed from `topHoldings.equityHoldings` — a sub-object of a module
  `YahooQuoteSummaryFetcher` **already requested** for sector look-through. **Yahoo ships those
  inverted**: `priceToEarnings` arrives as `0.03396` for a Nasdaq-100 tracker (the earnings
  *yield*, 1/29.4), so `putFundValuation` reciprocates once at the edge — storing them verbatim
  would put every fund under a P/E of 1 and read as the cheapest thing in the portfolio. They go
  into `extra` under `fund*` keys, which is free-form JSON on the way to SQLite, so **no
  migration**.
- **`domain.StyleBucket`** — the 2×2 (`VALUE`/`GROWTH`/`VALUE_GROWTH`/`NEITHER`) plus structural
  buckets `FUND_VALUE`/`FUND_BLEND`/`FUND_GROWTH`, `FIXED_INCOME`, `NON_EQUITY` (bullion: no
  earnings, no sales, no yield — a property of the asset, not a data gap), `CASH` and `UNKNOWN`.
  `scored()` marks which came out of the score; the UI dims the rest so the growth-vs-value
  comparison is read off comparable rows only.
- **`domain.StyleClassifier`** (framework-free, unit-tested). Resolution order:
  `Instruments.isBond` → `override.<SYMBOL>` → fund → equity 2×2. Each axis scores
  **achieved ÷ available** over weighted flags, so an absent metric abstains rather than counting
  against the position; an axis with fewer than `min.metrics` (2) inputs is not scored at all and
  the position lands in `UNKNOWN`, surfaced by name (same discipline as Attribution's `excluded`).

**Four data landmines, all found by running the thing against the real portfolio first:**
1. **Thresholds are absolute, not percentile ranks within the portfolio.** Ranking within a book
   holding both deep-value European banks and 85%-growth semis put the mega-caps mid-pack on
   *both* axes: GOOG, MSFT, AMZN, V and MA all came out "neither". That is an artefact of the peer
   set, not a judgement. Absolute anchors put them in Growth, which is obviously right.
2. **`priceToBook` is corrupt for non-US listings** — a currency-scale artefact reporting AZN at
   479.7 (really ≈4.8), REL at 3676.6 (≈36.8), ASML at 1563 (≈15.6). It would have marked every UK
   and European holding as ruinously expensive, exactly backwards for an income book. **Not used
   for direct holdings**; P/E, P/S and EV/EBITDA carry the value axis. The fund-level P/B from
   `equityHoldings` is unaffected and is used.
3. **`earningsGrowth` is single-quarter YoY and wild** (MU +1368%, AV. +979%, LGEN +507%), and
   **P/E compression fires for any cyclical whose earnings are recovering**. At their first weights
   (0.5 + 0.7 of a 2.2 denominator) the pair outvoted flat revenue and scored a recovering bank at
   0.545 — a growth label for a value stock. Now 0.4 and 0.5 against revenue growth's 1.0, so real
   top-line growth is close to necessary. Guarded by
   `StyleClassifierTest.cyclicalWithFlatRevenueIsNotGrowth`.
4. **A missing `dividendYield` means "pays none", not "unknown".** Yahoo omits the field entirely
   for a non-payer, so it is read as a genuine zero whenever any other metric is present —
   otherwise the denominator shrinks and every growth name gets a free pass on the value axis.
   `fcfGrowthYoy` has **zero coverage** in practice (Yahoo isn't returning 8 quarters); it is still
   wired and simply abstains.

**Funds sit outside the 2×2 on purpose.** Yahoo publishes a fund's aggregate price multiples but
returns null for `threeYearEarningsGrowth` on every fund held here, so there is no growth axis to
measure. Inferring "expensive therefore growth" would convert an absence of data into a finding,
so they get their own three buckets and the gap stays visible. `threeYearEarningsGrowth` is
deliberately **not stored** — its scale (fraction or percent, inverted or not) is unverifiable
when no value has ever been observed.

**Income is an overlay, not a fifth bucket** — the same relationship the AI/Tech tile has to the
sector table. Income names already sit in whichever cell they scored into (mostly Value, but BP.
and LGEN are in Value+Growth), so **the tile must never be added to the table**. `portfolioYield`
is value-weighted over scored non-fund holdings and is a **lower bound**: funds pay distributions
Yahoo does not publish, so they are in neither the numerator nor the base, and a warning says so.

**`resources/style.properties`** holds only *judgements* — thresholds, weights, and `override.*`
for instruments no multiple can describe (mirroring `sectors.properties`: PHGP/GBS/SGBX bullion →
`NON_EQUITY`, VGOV → `FIXED_INCOME`). No per-symbol multiples, no as-of dates, nothing that drifts.

**UI**: basis toggle (% of total / % of invested, both computed server-side so it toggles without
a refetch), an income tile with the yield and annual figure, the **2×2 grid** (`.style-quadrant`,
left column cheap / top row growing), a doughnut, and a table whose Basis column separates
"scored on both axes" from "structural" and "not scored — see below". Clicking a table row, a
doughnut segment **or a quadrant cell** opens `#style-detail-modal` listing every holding in that
bucket with its two scores and **the individual tests it passed** — a placement you can argue with
beats one you can only accept. The row handler is delegated from the `<table>`, since the basis
toggle replaces its innerHTML. The block lives in its own **`#style-block`** container and passes
that id to `panelLoadFailed`/`clearPanelError`, so a styles failure cannot destroy the sector
doughnut or the allocation canvases below it; `loadBreakdownSubview` now drives **three**
independent latches.

## Sector & AI/Tech breakdown

Current-snapshot composition by sector, rendered at the **top of Allocation → Breakdown** (the
existing charts shift down). `GET /allocation/sectors` → `SectorBreakdownService`. Two
**orthogonal** answers from one pass, which is the whole design: the sector split is a partition
(buckets sum to the portfolio total), while **AI/Tech cuts across buckets** — GICS files
GOOG/META/NFLX under Communication Services and AMZN/TSLA under Consumer Cyclical, so a
sector-only rollup reads Tech at ~11% when the real AI/Tech figure is ~34% of total / ~44% of
invested.

- **Values** come from `AllocationService.currentSnapshot()` — the single-sample form of
  `timeline()` (same idiom as `PortfolioValueService.dailyValuesIn`), sharing one `replay()` so
  the block cannot disagree with the charts beneath it. It returns the point **plus the
  per-`(account, currency)` cash detail** the replay tracks and otherwise discards; that detail is
  deliberately **not** on `AllocationPoint`, which `GET /allocation` serialises ~550 times.
  `currentPoint()` remains as the thin accessor (`EarningsCalendarService` uses it). The ledger still replays from its first
  row, but none of the ~550 weekly snapshots are valued. **Don't use `SyncFromCashService.sync()`**
  here — it hits the network for FX on every call, throws on failure, and drops equities with no
  intraday quote.
- **`domain.SectorBucket`** — 14 buckets incl. `FIXED_INCOME`/`CASH`/`COMMODITIES` as top-level
  (so equity percentages aren't diluted by gilts or idle cash) and `UNCLASSIFIED`.
  `fromYahooSector` accepts **both** Yahoo spellings — display form from `summaryProfile`
  ("Financial Services") and snake_case from `topHoldings` ("financial_services") — which is what
  lets a fund's constituent sectors and a directly-held stock's sector be compared at all.
- **`domain.SectorClassifier`** (framework-free, unit-tested). Resolution order:
  `Instruments.isBond` → `override.<SYMBOL>` → **fund look-through** → Yahoo sector →
  `UNCLASSIFIED`. Anything unclassifiable is surfaced by name in an amber strip, never absorbed
  into a neighbouring bucket (same discipline as Attribution's `excluded`).
- **Fund look-through is fetched, never maintained.** Yahoo's `topHoldings` module (added to the
  existing `YahooQuoteSummaryFetcher` module list — **zero extra HTTP calls**, those ETFs were
  already being fetched) returns `sectorWeightings` + top-10 `holdings`; both persist as JSON
  columns on `fundamentals_snapshot` and refresh with `FundamentalsFetchJob`. A hand-written
  weight table was rejected because it would have been **wrong on day one**: MCTS reads as a
  semis fund by name but is 29.8% tech / 23.4% consumer / 18.6% comms (Tencent, Alibaba, CATL —
  it's China tech), and EQQQ is 57.8% tech, not the ~100% an eyeball guess suggests. A fund that
  stops returning weights falls to `UNCLASSIFIED` and is surfaced — never served stale.
  Weights are normalised by their own sum (Yahoo's total ~1.0001) and the rounding remainder is
  pushed onto the largest bucket, so **look-through never creates or destroys value** — asserted
  in `SectorClassifierTest`.
- **Two different measures — do not add them.** The sector table is a **partition** (every
  position in exactly one bucket, buckets sum to the portfolio); the AI/Tech tile is an
  **overlay** that cuts across buckets. The `TECH_AI` bucket's label is plain **"Technology"**
  (it *is* the GICS Technology sector; the constant name stays `TECH_AI` because it's the JSON
  key and the `SECTOR_COLORS` key in `dashboard.js`), and the tile reads **"AI/Tech theme
  (cross-sector)"**. They were briefly "Tech/AI" and "AI/Tech" — the same two words reversed,
  inches apart — which reliably read as a bug. **The AI/Tech figure is not a row in the table
  and must never be summed with it**: it already contains the whole Technology bucket.
  The bridge is exact and printed on screen:
  `aiTechGbp = fromTechSectorGbp + crossSectorGbp`, with `fromTechSectorGbp` **equal to the
  Technology row**. Currently £443,186 = £296,877 + £146,309 (£112,805 held directly —
  GOOG/AMZN/META/BABA/TSLA/NFLX — plus £33,504 of the same names inside the ETFs). Both parts are
  computed in `SectorClassifier` rather than subtracted by the caller, so a later change to the
  AI/Tech rule can't silently break the bridge; `SectorClassifierTest` asserts the identity and
  that `fromTechSectorGbp` equals the bucket. For funds the split is taken against the *bucket*
  value (`min`, then subtract), because that bucket may have absorbed a rounding penny from
  `absorbRounding` and the printed arithmetic has to hold to the penny.
- **AI/Tech share.** Direct: sector is Technology, **or** the symbol is in `aitech.include`.
  Fund: the `technology` weight in full, **plus** each named top-10 constituent on the list whose
  own sector is *not* Technology — the **double-count guard** (NVDA/AAPL/MSFT are already inside
  the technology weight). A constituent with no cached sector is **skipped**, keeping the result
  a genuine **lower bound**; Yahoo names only the top 10 (~46% of EQQQ), so the UI renders it as
  `≥` with the name-coverage %.
- **`resources/sectors.properties`** holds only *judgements*, nothing that drifts: `override.*`
  for bullion ETCs Yahoo has no sector for, and `aitech.include` (GOOG/GOOGL/META/AMZN/NFLX +
  TSLA/BABA; V/MA and LSEG/REL deliberately excluded). No weights, no as-of dates.
- **UI**: basis toggle (% of total / % of invested, both computed server-side so it toggles
  without a refetch), AI/Tech tile showing the **bridge** (Technology sector + AI names filed in
  other sectors), doughnut, table with a **direct / via-fund** column keeping the inferred part
  visible, plus two collapsible sections — per-fund look-through provenance, and a per-position
  breakdown of the AI/Tech figure (`AiContributor.source` = `TECHNOLOGY_SECTOR` / `TAGGED` /
  `FUND`; a `TAGGED` row names the sector it's actually filed under, which is the whole
  explanation in one cell).
- **Sector drill-down.** Clicking a table row **or its doughnut segment** opens
  `#sector-detail-modal` (standard `.modal-backdrop` + `open` class; Close / backdrop / Escape all
  dismiss) listing every position feeding that bucket — `Row.constituents`, riding on the same
  payload, no extra fetch. The unit is the **position, not the underlying company**: a fund is one
  row carrying its slice ("EQQQ — via fund (17.8% of EQQQ)"). Name-level decomposition was
  rejected on the data — Yahoo publishes only a fund's top 10 and the foreign lines aren't in the
  fundamentals cache, so resolvable coverage runs EQQQ 46.3% / SPX5 36.4% / **RBTX 5.9% / MCTS
  0%**; a popup built on that would look like a full decomposition while showing a third of one.
  Positions keep every penny accounted for — constituents sum to the bucket and `pctOfBucket` sums
  to 1. The `CASH` bucket's constituents are the per-`(account, currency)` balances. The row
  click-handler is **delegated from the `<table>`**, since `renderSectorTable` replaces its
  innerHTML on every basis toggle.

  The block lives in its **own `#sector-block` container**, and `loadSectors`
  passes that id to `panelLoadFailed`/`clearPanelError` — passing the sub-view id would destroy
  the allocation charts' canvases on a sectors failure. Two independent latches
  (`loadBreakdownSubview` calls both) so one endpoint's blip doesn't take the other down.

## Earnings calendar

Read-only earnings calendar (Earnings tab, under Tools). `GET /earnings` → JSON, rendered client-side
as one table per day over a `[today−back, today+forward]` window (default 2/3). Unlike the EDGAR
filing-date proxy behind Swing §D (`EarningsCalendarProvider`, backward-looking "which past dates did
symbol X file"), this is a **forward-looking, market-wide** calendar with consensus EPS estimates —
deliberately a **separate port** (`port/EarningsCalendarFeed`, `forDate(LocalDate)`).

- `port/EarningsCalendarFeed` — `forDate(date)` returns `List<EarningsEvent>`; **three-valued**: a
  non-null (possibly empty) list is a successful read (`[]` = no reports, e.g. a weekend), `null` =
  fetch failure. The service needs that distinction to avoid caching an empty roster over good data.
- `adapter/NasdaqEarningsFetcher` — one GET per date to `api.nasdaq.com/api/calendar/earnings?date=`.
  Browser-UA (default Java UA gets 403, same as `GiltPriceFetcher`). Jackson;
  `parse(JsonNode,date)` is a static, unit-tested pure function. All feed fields are display strings
  (`"$665,246,306,497"`, `"$7.98"`, `"($0.11)"`) → parsed to real numbers (`parseNumber` treats
  `(x)` as −x, strips `$ , %`; `"$0"` is a real zero, blank/`N/A` → null). `time` normalises
  `time-pre-market`→`BMO` / `time-after-hours`→`AMC` / else `TBD`. Rows without a symbol are dropped.
- `domain.model.EarningsEvent` — pure record (date, symbol, companyName, time, marketCap [whole USD],
  epsForecast, numEstimates, lastYearEps). `marketCap`/EPS are native USD (US-market calendar, no FX).
  "Held" is **not** a field — the report ships the held-symbol set and the client tags rows, keeping
  the record a pure feed row.
- `persistence/EarningsCalendarRepository` — cache. Two tables: `earnings_calendar` (row per
  `(date,symbol)`) + `earnings_calendar_fetch` (per-date `fetched_at` + count — the bookkeeping that
  lets a **zero-report day** be cached distinctly from "never fetched"). `save` replaces-per-date in
  one transaction (a company dropped from the roster disappears). Reads degrade (log + empty).
- `application/EarningsCalendarService` — window walk with cache policy: **past** dates cache forever
  (settled roster); **today/future** re-fetch once cache ages past `REFRESH_TTL` (3h — estimates/roster
  still move). `refresh=true` (the Refresh button) bypasses freshness. Live-fetch failure degrades to
  cached rows + a warning, never throws. Window clamped to `MAX_BACK/MAX_FORWARD` (14) so a stray param
  can't fan out. Market-cap floor filters the per-day tables (the "major" filter) but **never**
  `myHoldings`. Held set = `held_symbols` KV (the /sync-persisted holdings); US internal symbols equal
  their US tickers so a direct membership test suffices.
- `web/EarningsController` — `GET /earnings?back=&forward=&holdingsForward=&minCapB=&refresh=`.
  `minCapB` is `$` **billions** (UI select), converted to whole dollars. No backfill/mutation
  endpoints — a pure feed read, independent of the price/cash pipelines (like Open orders). Not
  wired into `PriceFetchSupport` (no Yahoo prices involved).

**Your-holdings section (ranked by position size).** Above the per-day tables, the tab renders
every held position ordered by GBP value, with its portfolio weight beside the date/session/EPS
estimate. `holdingsForward` is a **separate look-ahead** from the daily tables' `forward` (default
21d vs 3d) — browsing the market wants a few days, "when do my own positions report" wants a few
weeks; the day loop scans `max(forward, holdingsForward)` but only `forward` becomes a `Day`.
`MAX_FORWARD` was raised 14 → 45 for this (each extra date is one cached GET; past dates cache
forever, future share the 3h `REFRESH_TTL`).

Position sizes come from `AllocationService.currentPoint()` — the same snapshot the sector block
uses — and **degrade**: on failure the section still renders, unranked, plus a warning.
`HoldingRow.state` is the load-bearing part, separating three things a blank row conflates:
- `REPORTING` — a date was found;
- `NOT_COVERED` — a **non-US listing this US-only feed never carries**, detected by a dotted
  exchange suffix on `YahooTickerMap.tickerFor` (`.L/.PA/.AS/.DE`). This correctly leaves **ASML
  covered** (it maps to itself = the Nasdaq ADR line). Without this state, LGEN (5.0%) and BNP
  (4.6%) — the #2 and #3 single-name positions — were indistinguishable from names with nothing
  due. 12 held names currently land here.
- `NONE_SCHEDULED` — covered, nothing due in the window.

Funds and bonds are **dropped entirely** (no earnings to report). Funds are identified by a
non-empty cached `sectorWeights` (the same `topHoldings` data the sector block uses) — needed
because every held ETF has a `.L` ticker and would otherwise be mislabelled "not covered".
