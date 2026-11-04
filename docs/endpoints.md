# Endpoints, controllers and services

The lookup table for "where does this live". Read on demand — it is a map, not an invariant, so it
is here rather than in the CLAUDE.md index.

Two tables: every HTTP endpoint with the controller and service behind it, and the areas that ship
with no `docs/` file of their own. For the second, **the source is the specification** — there is no
recorded design intent beyond the code, so read the service before changing it and do not infer
intent from the endpoint name.

## Areas with no docs/ file

These ship and have no `docs/` file. Read the service; there is no recorded design intent
beyond the code, so treat the source as the specification.

| Area | Tab | Entry point |
|---|---|---|
| Risk metrics (Sharpe/Sortino/Calmar, drawdown, underwater) | Risk | `PortfolioRiskService` — `GET /risk`, `POST /risk/rate` |
| Correlation matrix + diversification ratio | Correlations | `CorrelationService` — `GET /correlations` |
| TWR / money-weighted returns, annual bars, benchmarks | Returns | `PortfolioReturnService`, `BenchmarkReturnService` — `GET /returns{,/mwr,/benchmark,/symbols}` |
| Dividend history + audit | Dividends | `DividendService` — `GET /dividends`, `/dividends/audit` |
| Concentration | Allocation | `ConcentrationService` — `GET /concentration` |
| Currency exposure | Allocation | `CurrencyExposureService` — `GET /currency` |
| Target-allocation drift | Allocation | `GET /allocation/targets` (+ `POST`) |
| What-if planner | What-if | `WhatIfService` — `POST /whatif` |
| Historical scenario replay | Scenario | `HistoricalScenarioService` — `GET /scenario`, `/scenario/substitutes` |
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
| `GET /position` | Charts | `PositionDetailService` — the transactions list, the FIFO lots it builds up to, and the chart markers, which are built from that list and index back into it |
| `GET /fundamentals`, `/fundamentals/tickers` | Analysis | `FundamentalsService` — the P/E-history series (EDGAR EPS ÷ price) and the tickers it can draw, derived from the universe rather than listed; the EPS term is split a second time by the filer's own operating and net income, or the split is withheld with its reason; a base window the filer has since restated is reported beside it |
| `GET /portfolio-fundamentals` (+ `/add`, `/remove`, `/refresh`) | Analysis | `PortfolioFundamentalsService` |
| `GET /reconciliation` | Analysis | holdings-vs-ledger reconciliation |
| `GET /scenario`, `GET`/`POST /scenario/substitutes` | Analysis | `HistoricalScenarioService` |
| `POST /whatif` | Analysis | `WhatIfService` |
| `GET /trades`, `POST /trades/{rowid}/note` | Analysis | `TradeNotesService` |
| `GET /health` | Analysis | liveness |
| `GET /mappings`, `POST /mappings/{verify,save,delete}` | Mappings | `InstrumentMappingService` |
| `GET /watchlist` (`?list=`), `/watchlist/{row,series,vol,quotes,returns,gilts,lists}` | Watchlist | `WatchlistService` — `quotes`/`returns` take `?list=`; `row`/`series`/`vol`/`gilts` are symbol-scoped |
| `POST /watchlist/{add,remove,threshold,move}` (`&list=`) | Watchlist | `WatchlistRepository` — `remove` drops the symbol from *that* list, and outright only if it was its last |
| `POST /watchlist/horizon` | Watchlist | the N behind `Above P(N)` / `Below P(N)` — one **stored** app-wide setting (default 30 sessions), because a comparison grid whose horizon differed per row would be one header over many questions; out of range is clamped, not refused |
| `POST /watchlist/target` | Watchlist | the two **order targets** — where the order goes once a trigger fires, normally further out. Nothing alerts on one; it is the level that side's `%` and `P(N)` are priced to, and blank hands them back to the trigger. Its own endpoint, like the note, because it clears **no** fired flags — moving where you deal changes nothing about where the price has to get |
| `POST /watchlist/note` | Watchlist | the per-symbol plan for when its alert fires — free text, nothing parses it; **global to the symbol** like the triggers, but **not** a trigger, so unlike every other write here it clears no fired flags (rewording a plan must not re-mail a signal already sent for the same levels), and blank is absent, never `""` |
| `POST /watchlist/reorder` (`&list=`) | Watchlist | the drag-and-drop drop — saves the order **as displayed** (so a drag under an active sort is what makes that sort the manual order); `move` stays the ↑↓ single swap, and the only reorder a keyboard or touch screen can reach |
| `POST /watchlist/lists/{create,rename,delete,move}` | Watchlist | the named lists; the last one cannot be deleted |
| `GET`/`POST /watchlist/columns` (`?list=`) | Watchlist | the per-list column layout; **no rows = the default set**, so saving none is "reset to default" |
| `GET /earnings` | Earnings | `EarningsCalendarService` |
| `GET /reminders` | Reminders | `ReminderService` — stored reminders + the derived earnings ones + the few days just **reported**, in one payload; a reported row carries no due flag and the send job never walks that list; `deliveryEnabled` says whether anything is actually being mailed |
| `GET /reminders/reported` | Reminders | `EarningsPrintService` — what each recent print *did*: the move net of its market and in units of that name's usual one, the £ effect, the gap to each resting limit, revenue vs a pre-print consensus. **Separate** from `/reminders`, which the mail job reads half-hourly |
| `POST /reminders/reported/refresh` | Reminders | `EarningsPrintFetchJob` on demand; one attempt per print per day |
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
