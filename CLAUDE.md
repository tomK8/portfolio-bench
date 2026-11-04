# CLAUDE.md

Guidance for Claude Code in this repository. Prefer reading the code; this file covers the
cross-cutting bits that aren't obvious from any single file.

**This file is the index, not the manual.** It holds what applies everywhere — layering, the FX and
null conventions, the "how to add X" recipes — plus a routing table to `docs/`, which holds the
per-feature detail and the reasoning behind it. Read the relevant `docs/` file *before* changing
code in that area; each index line names the invariant it guards, so the guard fires even if you
never open the file.

**An index row names an invariant in one line. The moment it starts explaining one, it belongs in
the `docs/` file it points at** — see [Keeping this file small](#keeping-this-file-small). That is
enforced by `DocsIndexTest`, not by discipline: the file is on a size ratchet and no row may exceed
45 words.

Do not use `@docs/...` import syntax here. Claude Code inlines `@` imports at startup, which would
put every doc back into every session and defeat the split.

## Build, test & run

```bash
mvn compile                            # compile
mvn test                               # excludes @Tag("integration")
mvn test -Dtest=AJBellSippParserTest   # single class
mvn package -DskipTests                # jar
mvn spring-boot:run                    # foreground (dev)
scripts/start.sh / scripts/stop.sh     # daemon (writes ~/log/portfolio-bench.log)
node --test src/test/js/*.test.mjs     # JS unit tests
```

Server binds to loopback only, no auth — single-user local tool. Override directories with
`--portfolio.input-dir=…`, `--portfolio.db-dir=…`, `--portfolio.output-dir=…` (blank → defaults
`~/Downloads`, `~/Documents/Investing`, `~/Documents`).

Daemon mode: `start.sh` backgrounds `target/portfolio-bench-1.0-SNAPSHOT.jar`, writes a PID file,
rotates the previous log to a timestamped name. The JVM's stdout/stderr is redirected into the log
file, so non-Logback output (early classloader errors, OOM dumps) is captured too.
`spring.main.banner-mode=log` routes the Spring banner through SLF4J.

**`start.sh` runs a prebuilt jar and never rebuilds.** After any change: `mvn package` first, then
restart — otherwise you are testing the old build.

**Demo mode:** `python3 scripts/make-demo-data.py` then `scripts/demo.sh` (:8081, own PID/log, so
`start.sh` is unaffected). Figures come from a generator profile **outside this repository**
(`PB_DEMO_PROFILE`); there is no fallback and nothing builds without it. **How it produces them is
documented nowhere — in this repo, the code, the history or the UI — and must stay that way**:
`demo/` and the profile are uncommitted and this repo is public.
See [demo-mode.md](docs/demo-mode.md).

**Test fixtures:** `src/test/resources/` **must contain only synthetic data — no real brokerage
exports.** Fixtures are committed so parser tests can load files from disk. Real exports were never
committed; keep it that way. Regenerate `Holdings.xlsx` with openpyxl if its shape needs to change.

## Architecture

Spring Boot web app, layered. **Spring is confined to `web` and `config`**; `domain`, `application`,
`adapter`, `parser` and `persistence` are framework-free. One direction only:

| Layer | May import |
|---|---|
| `domain` | nothing outside `domain` — pure logic, no I/O |
| `port` | `domain` — interfaces, faked in tests |
| `parser` | `domain`, `port` |
| `persistence` | `domain` — all JDBC via `JdbcConnectionFactory`, the only owner of `java.sql` |
| `adapter` | `domain`, `port`, `parser` — outbound HTTP, files, SMTP |
| `application` | `domain`, `port`, `persistence`, `adapter`, `parser` — use-case orchestration |
| `web` | `application`, `domain` — thin controllers only |
| `config` | all of them; wiring only |

Logging is SLF4J + Logback (`logback-spring.xml`); no `System.out` in production code. UI is
server-rendered Thymeleaf + htmx + Chart.js, all **vendored**; 29 tab panels in six groups
(Holdings / Performance / Risk / Quality / Strategies / Tools).

The class inventory per package, the 13 known violations of the table above and why this is
ArchUnit's job rather than Spring Modulith's: [architecture.md](docs/architecture.md).

## Conventions that apply everywhere

These recur across unrelated features. Breaking one is how the recorded incidents happened.

- **FX convention:** rates are *units of foreign currency per 1 GBP* (e.g. `{"USD": 1.3621}`).
  To convert native → GBP, **divide** by the rate.
- **Write paths throw, reads degrade.** `saveSnapshot` and `CashTransactionRepository.save*` throw
  `IllegalStateException` (rendered as `fragments/error`). Query methods and background-job inserts
  log + return empty/0 — they must not crash the scheduler.
- **Absent is never zero.** A missing figure is `null` ("we don't know"), never `0` ("we measured
  zero"). A caller must be able to tell a non-payer from an unresolved yield, a cash row from a
  position with no cost basis, an unpriced holding from a worthless one. Zeroing instead is what
  manufactured a ~£299k phantom gain in Attribution (2026-08-09).
- **Excluded, and said so.** Anything that can't be valued or classified is dropped from the rows
  *and every total* and reported by name in the payload (`AttributionResult.excluded`,
  `SyncResult.unpricedSymbols`, `ValueTimeline.missingPrices`, the breakdown services' contributor
  `state`), which the UI renders as a warning strip. Never absorbed silently.
- **Censored, not dropped.** A trade whose outcome window runs past the data end is `OPEN` and
  counted as such; every backtest table reconciles `events = TP + SL + Time + Ambiguous + Open`.
- **Look-ahead-free by construction.** Trailing windows use closes *strictly prior* to the bar
  being judged, so a signal can't see its own move.
- **One accounting path per backtest.** `Backtester` / `RotationBacktester` / `RangeBacktester` each
  serve every variant, control and bootstrap iteration; variants differ by a parameter (a
  `Selector`, a `Ranker`, a `FlushTrigger`), never by a second code path.
- **Exports re-derive at the moment the button is pressed** — never a stale copy of what the panel
  is showing.
- **Repos run their DDL once in the constructor**, not per read/write.
- **Every lazy chart loader ends in a `.catch`** that releases its own latch and calls
  `panelLoadFailed(id, err, retryFn)`; the paired `clearPanelError(id)` goes right after the latch.
  `addEventListener` inside a loader must go through `bindOnce`.
- **Self-regulating over maintained.** Reject hand-written data tables when a live source publishes
  the same fact (gilt SEDOLs from HL, fund sector weights from Yahoo, CIKs from SEC). A maintained
  list can only ever be wrong, never right.

## Holding fields

- `currentMarketValue` — native currency amount.
- `currentMarketValueGbp` — pre-converted GBP from the source (AJ Bell does); `null` → convert via FX.
- `costBasisGbp` — GBP cost from the source (AJ Bell only); `null` → compute from `avgPricePaid` via FX.

## Dividends

Source: `cash_transactions WHERE type = 'DIVIDEND'` — no separate table, no manual-entry screen.
`DividendService.dividendsBySymbol` runs `DividendAttributor` over the cash ledger (FIFO lot
tracking — see the class doc), reconciles the reconstructed share count against the holdings file,
and logs WARN on disagreement >0.01 shares. Per-symbol amounts flow into `AggHolding`'s
`dividendGbp` / `totalGainGbp` / `totalGainPct`. Portfolio-level totals (`PortfolioMetrics`) are
price-appreciation only — dividends are **not** folded into the saved snapshot.

## Adding a new parser

1. Implement `AccountParser` (`parse`, `supports`, `sourceName`).
2. Register in `PortfolioGatherer` (parsers are built per run, after rates are fetched, because
   `AJBellSippParser` needs the live rates).
3. If the source provides GBP values directly, set `currentMarketValueGbp` + `costBasisGbp` on the
   `Holding`; otherwise leave them null and `PortfolioAggregator` will convert via FX.

Per-broker rules the recipe does not imply — zero-quantity rows, what counts as cash, file
detection: [parsers.md](docs/parsers.md).

## Adding a new cash source (broker)

1. Implement `CashTransactionParser` (`account()` → `Account` enum, `supports`, `parse`).
2. Add a `CashTransactionRepository.save…` method if the dedup rules differ, else reuse one.
3. Implement `CashImporter` by extending `AbstractCashImporter` (provides `matchingFiles`,
   `mostRecent`, `parse`, `archiveOrDelete`).
4. Register the importer as a bean in `BeanConfiguration`. Spring injects `List<CashImporter>` into
   `ImportCashService` in bean-declaration order, which is the order the dashboard shows results.

Per-broker dedup keys and their rationale: [persistence.md](docs/persistence.md).

## Adding an account or a transaction type

One enum constant, no SQL. The CHECK constraints are **generated from the enums** and
`migrateConstraints` rebuilds an existing table whenever the stored DDL no longer lists every
constant. Renaming a constant still needs a data migration; adding one is free. Every read filter is
an **allowlist**, so a new type reaches nothing until you add it deliberately — see
[persistence.md](docs/persistence.md).

## docs/ — read before changing the area

Each row names the one invariant that must fire even if you never open the file. The detail, the
evidence and the reasoning are in the file.

| Read this | Before you | The invariant it guards |
|---|---|---|
| [architecture.md](docs/architecture.md) | add a class, a service or a layer dependency | The layer table above is one-directional; `domain` imports nothing outside itself; the 13 known violations are a burn-down list, not precedent |
| [endpoints.md](docs/endpoints.md) | look for an endpoint, controller or service by name | The endpoint → controller → service map, plus the areas shipping with no doc — there, the source *is* the specification |
| [parsers.md](docs/parsers.md) | add or change a broker parser, or touch file detection | A zero-quantity row is absent, not worthless; cash is the broker's own classification, never our ticker list; a short is a real position |
| [persistence.md](docs/persistence.md) | touch any repository, dedup rule or import path | Broker dedup keys are composite and cross-file; `cash_balance_gbp` is in the PK on purpose; read filters are allowlists |
| [gilts-and-bonds.md](docs/gilts-and-bonds.md) | touch a gilt parser, price source or bond valuation | `Instruments.giltId` is the **only** place a gilt id is built; bonds use the per-£100-nominal formula in four places |
| [instrument-mappings.md](docs/instrument-mappings.md) | change alias/ticker resolution, or the II SIPP cash boxes | Both sides key through `Instruments.matchKey`; a rename re-keys ledger rows and is not undoable; a blank cash box keeps the stored balance, never zeroes it |
| [sync-views.md](docs/sync-views.md) | change either sync tab or the Portfolio % column | Ledger view drops unpriced equities but not bonds; II SIPP cash is per-currency and blank ≠ zero; `cashPct` is **null, never 0%** |
| [cash-ledger-irr.md](docs/cash-ledger-irr.md) | touch the Annualised return column or `Irr` | IRR not CAGR; current episode only; unsolvable writes **no** cell, never a zero; two day-count bases are deliberate |
| [table-grouping.md](docs/table-grouping.md) | add a grouping key to either table | Exactly one group per holding; Gain % is `Σgain ÷ Σcost`, never the mean of members; a new key is a resolver class and nothing else |
| [position.md](docs/position.md) | change the Position tab, its transactions table or markers | The transactions table is the ledger **as dealt**, the lot tables what FIFO makes of it; the price is derived; markers are built from the trade list and index into it |
| [open-orders.md](docs/open-orders.md) | change the orders panel, a parser or the probe | **Open and resting are two questions**; the broker's own word outranks any derived verdict; ladder membership is never inferred and a rung is never pruned |
| [share-class-switches.md](docs/share-class-switches.md) | change the switches block, or any exposure view | A switch is **declared, never detected**, and declaring it folds two listings into one holding in every exposure view; both legs go on one GBP yardstick first |
| [vol-forecast.md](docs/vol-forecast.md) | change P(N), σ_eff, drift or the calibration log | σ is *forecast* for the horizon, never a trailing window of that length; drift is a **price** drift; one σ_eff serves a whole barrier sequence |
| [live-tab.md](docs/live-tab.md) | change the Live tab, intraday sessions, or anything that sends HTTP | Day Δ is anchored on the quote's **trading day**; **every send goes through `BoundedHttp`**; the dormancy catch-up fires on the jobs' silence, never the prices' age |
| [attribution.md](docs/attribution.md) | change the Attribution window or its totals | A boundary that can't be valued is excluded from rows *and* totals; annualising under 30 days is blank |
| [period-diff.md](docs/period-diff.md) | change Period diff or the TWR | Contributions and brought-forward are two figures, never one; flows count over `(from, to]` |
| [charts.md](docs/charts.md) | change Contributions or Value over time | Roth's USD seed converts at the FX rate on its earliest ledger date, not today's |
| [allocation-breakdowns.md](docs/allocation-breakdowns.md) | change sector, style or AI/Tech | The sector table is a partition, the AI/Tech tile an **overlay** — never sum them; the wrapper step runs **before** look-through; cash equivalents are an adjacent row, never merged into Cash |
| [excel-exports.md](docs/excel-exports.md) | touch any Download Excel button | `setForceFormulaRecalculation(true)` or every formula opens blank; an empty view writes literals, not `SUM` over an empty range |
| [watchlist.md](docs/watchlist.md) | change the watchlist, alerts or the shared price popup | Alerts are **global to the symbol** and lists are membership only; each side carries a trigger *and* an order target nothing evaluates; extended hours are shown and read by nothing |
| [reminders.md](docs/reminders.md) | change the Reminders tab, a rule or the send job | Nothing here is evaluated — a reminder fires when the **calendar** says so; earnings reminders are derived, never stored; *Just reported* is on screen but is not a reminder |
| [print-reaction.md](docs/print-reaction.md) | change the *Just reported* detail | A print's move is **one session**, chosen by when the news could first be traded; the revenue consensus is captured **before** the print; EPS is Nasdaq and revenue Yahoo, never mixed |
| [fundamentals.md](docs/fundamentals.md) | add or relabel a field, or touch a ratio | The feed states **no period and no accounting basis**; a column earns its place only if it means the same thing in every row; division needs a shared currency and scale |
| [pe-history.md](docs/pe-history.md) | change the P/E-history chart, its tickers or its EPS source | The TTM is **assembled, never the last four records** — four quarters must tile a year contiguously; the picker is derived, not listed; figures share one split basis |
| [earnings.md](docs/earnings.md) | change the earnings calendar, the holdings section or the reported figures | `[]` is a successful empty read, `null` a failure; the feed flips **per date** and serves four shapes, told apart structurally; the surprise is **recomputed, never read** |
| [manual-positions.md](docs/manual-positions.md) | change hand-entered positions | Each acquisition is **two** rows; `OPENING_BALANCE`, not `CONTRIBUTION`; balances are recomputed, never supplied |
| [aj-bell-accounts.md](docs/aj-bell-accounts.md) | touch any AJ Bell parser, or add a second account at one broker | Only the holdings export names its account, so cash and orders are routed by the instruments they name; an unplaceable file is reported, never guessed |
| [transfers.md](docs/transfers.md) | change in-specie transfers, or any per-account lot engine | Both legs or neither; the magnitude is the source account's **FIFO cost**; **every per-`(account, symbol)` lot engine must replay `TRANSFER`** |
| [strategy-swing.md](docs/strategy-swing.md) | change the swing lab | Every flush family reports its placebo and matched bootstrap beside it; pooled cells report episodes, not just events |
| [strategy-trend.md](docs/strategy-trend.md) | change the trend filter | Every variant starts at the latest `firstValid` of any configured signal; the pre-1999 splice is a price-only proxy and is labelled |
| [strategy-momentum.md](docs/strategy-momentum.md) | change momentum or the signal comparison | V1 must reproduce the Momentum tab bit-for-bit; the adoption rule is pre-registered and printed verbatim; controls run on the fit window only |
| [strategy-range.md](docs/strategy-range.md) | change the range-floor backtest | Defaults *are* the pre-registration — any override flags the run EXPLORATORY; signals on split-adjusted price, P&L in total-return space |
| [price-history.md](docs/price-history.md) | change a fetch job, ticker mapping, `adj_close` or anything split-related | `close` for market value, `adj_close` for total return; a ticker Yahoo refuses is *recorded*, never left "fetching"; **a split is a ledger row, never a price repair** |
| [ui-conventions.md](docs/ui-conventions.md) | add a tab, chart loader, sortable table or vendored library | Loaders need `.catch` + `panelLoadFailed`; tab groups are derived from the DOM; a sort lives in `data-sort-dir` and is re-applied after anything that re-renders rows |
| [demo-mode.md](docs/demo-mode.md) | run the app on demo data, or change the generator | The generator profile lives **outside the repo** and its method is documented nowhere — never add a fallback or explain it; `order_key` must be rebuilt or every ladder rung orphans |
| [backup.md](docs/backup.md) | change what is backed up, or the schedule | Only the rows that cannot be re-fetched, plus the `KeyValueStore` files a SQL dump would miss; due by elapsed time, not a calendar cron, because the laptop is usually shut |
| [postmortems.md](docs/postmortems.md) | investigate a reconciliation gap between views | Resolved incidents kept for the invariants they justify; a rule about what the **ledger means** lives in `domain`, not in whichever view first needed it |

## Keeping this file small

This file is loaded in full on every session, so its size is a running cost paid by every task.
It was split out of a 228k-character original in September 2026 and regrew from 23k to 60k in seven
weeks — because the cheapest place to record a new invariant, in the moment, is a line here rather
than the `docs/` file it belongs in. Three mechanical guards in `DocsIndexTest` invert that:

- **A size ratchet, not a ceiling.** The budget tracks the file within a small slack, so the test
  fails both when the file grows past it *and* when the file shrinks well under it. The previous
  cap was a round 60k with 36k of headroom, and the file grew into every byte of it. A raise is a
  deliberate one-line diff, not ambient drift.
- **No routing row over 45 words.** This is the rule that was actually broken: two rows had reached
  979 and 815 words, 20% of the file between them, restating the docs they pointed at.
- **Every `docs/` file reachable from the index, every index link resolving.** A doc nothing points
  at is a doc nobody reads.

So: **detail goes in `docs/`; the row here names the invariant and stops.** When an invariant needs
evidence, a worked example or a count, that is the signal it belongs in the file, not the row.
Anything the build can check — a layer rule, a confinement — belongs in a test, and then needs no
paragraph here at all.
