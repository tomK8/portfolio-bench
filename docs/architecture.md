# Architecture

Spring Boot web app, layered. The CLAUDE.md index carries the layer rule and the allowed-dependency
table; this file carries the class inventory behind it and the reasoning.

## Layers, and what each may import

One direction only. Measured from the source, not aspirational — the counts are real imports of
`com.portfolio.*` as of 2026-11-04.

| Layer | May import | Actual |
|---|---|---|
| `domain` | nothing outside `domain` | `domain` 38 — clean |
| `port` | `domain` | `domain` 1 |
| `parser` | `domain`, `port` | `domain` 26, `port` 2 |
| `persistence` | `domain` | `domain` 18, **`adapter` 1** |
| `adapter` | `domain`, `port`, `parser` | `domain` 12, `port` 6, `parser` 4 |
| `application` | `domain`, `port`, `persistence`, `adapter`, `parser` | as listed |
| `web` | `application`, `domain` | `application` 120, `domain` 10, **`persistence` 8**, **`adapter` 4** |
| `config` | all of the above — wiring only | — |

Plus two rules that are not about layers:

- **Spring is confined to `web` and `config`.** One exception: `SmtpAlertNotifier` in `adapter`,
  which needs `JavaMailSender`.
- **All JDBC goes through `JdbcConnectionFactory`**, and `java.sql` is imported only by
  `com.portfolio.persistence`. Two exceptions: `HealthService` (a liveness ping) and
  `BackupService` (writes a plain SQL dump, deliberately independent of our own code).

### Known violations

13 files, listed so they are a burn-down list rather than a surprise:

| What | Files | Why it is wrong |
|---|---|---|
| `web` → `persistence` | `WebSupport`, `DemoController`, `OperationsController`, `RootController`, `ChartsController`, `WatchlistController` | A controller reaching a repository skips the use-case layer that owns the read filters |
| `web` → `adapter` | `DemoController`, `MappingsController`, `RootController`, `ManualPositionsController` | Same, for a fetcher |
| `persistence` → `adapter` | `FundamentalsRepository` imports `YahooQuoteSummaryFetcher.QuoteSummary` | A stored row typed by a feed DTO: the table's shape now follows Yahoo's. Wants a `domain.model` type |

Nothing enforces this table yet — it is prose, which is the weakest kind of guard. The intent is
ArchUnit rules in test scope, frozen at these 13 and burned down, which is also what lets the
convention paragraphs leave the index: a rule the build checks does not need restating.

## What each package holds

- `domain` — pure logic. `PortfolioAggregator` (two-pass: `groupAccumulators` →
  `buildAggHolding`; keyed on `(securityId, currency)`, **plus the account for cash** — a security
  pools across accounts, a cash balance never does), `PortfolioMetrics` (totals + `rtTotalGbp` for intraday valuation),
  `DividendAttributor` (FIFO), `CashLedgerReconstructor` (FIFO position-from-ledger
  reconstruction), `Irr` (the app's only XIRR solver) + `PositionIrr`,
  `FiledPeriods` (the one place a trailing twelve months is assembled out of filed XBRL periods, and the one place the filer's own annual is asked whether it contradicts them —
  quarter-length windows only, four that must tile a year contiguously, and a 10-K's implicit final
  quarter recovered by subtraction; serves both the operating line and the P/E-history EPS),
  `Instruments`
  (`isBond`, `giltId`, `couponOf`, `assetClassOf` — single home of all four), `DividendYield`,
  `Reminders` (when a reminder is due, and the once-a-day key),
  `OrderFills` (the rule that reads a vanished order's fill out of the cash ledger),
  `PrintReaction` (the one session a print's move is measured over, and its median baseline),
  `LedgerCorrections` (a broker's cancel-and-rebook pair is not a trade — the one home for that,
  reached by every lot engine, and it refuses to drop a pair whose removal would leave the
  timeline selling shares it has not bought),
  `AjBellPortfolios` (which AJ Bell account an export that does not name one belongs to),
  `VolForecast` + `AssetClass` (the open-orders touch model), `Ladders` (rung layout + per-account
  cash), `RoundTrips` (the path a ladder's next round trip has to walk), `Switches` (share-class switch: one yardstick, the index/FX split, progress in units),
  `ShareClasses` (the declared one-holding pairs), `PriceStats`, `StyleClassifier`,
  `SectorClassifier`, `IndustryGroups`. Sub-packages `domain.swing` / `domain.trend` /
  `domain.momentum` / `domain.range` hold the four backtest engines, `domain.grouping` the
  table-grouping keys. `domain.model`: `Holding` (Builder; many nullable fields), `AggHolding`,
  `CashTransaction`, `OpenOrder` + `OrderStatus`, `OrderGroup` + `GroupType` + `IntendedRung` +
  `Rung` (ladders and share-class switches), `EarningsEvent`, `Reminder`, `Account` + `TransactionType` enums,
  `PriceBar`, `IntradayBar`, `IntradayPrice`, `OperatingLine`.
- `port` — `FxRateProvider`, `HistoricalFxRateProvider`, `EarningsCalendarProvider`,
  `EarningsCalendarFeed`, `AlertNotifier` (interfaces, faked in tests).
- `adapter` — `FrankfurterFxClient`, `HoldingFileLocator`, `AjBellFileRouter` (which of the two AJ
  Bell accounts a nameless export belongs to), `YahooPriceFetcher` (incl. `probe` + `search`),
  `YahooQuoteSummaryFetcher`, `YahooTickerMap`, `GiltPriceFetcher` (HL),
  `TradewebGiltPriceParser`, `NasdaqEarningsFetcher` (reads the calendar's three row shapes — a date
  still ahead carries a consensus and a session, one the feed has flipped carries what was reported
  and no session, and in between it carries both, with `eps` holding **last year's** figure), `EdgarEarningsFetcher` + `CikRegistry`,
  `EdgarXbrlFetcher` (one us-gaap concept's filed history, 10-K/10-Q only — an empty answer
  carries *which* silence it was), `NasdaqCompanyEarningsFetcher` (the per-company halves of the
  calendar's feed — four reported quarters **with the date each was reported on**, and the forward
  consensus per fiscal period), `SmtpAlertNotifier`, `BoundedHttp` (the only way this app sends — the deadline is the caller's, because the request's own is a timer a dead connection takes with it).
- `parser` — `AccountParser` / `CashTransactionParser` / `OrderParser` interfaces + impls.
- `persistence` — `JdbcConnectionFactory` + its repositories + `KeyValueStore`.
  `EarningsPrintRepository` holds figures obtainable only once, so its writes **merge**.
- `application` — use-case orchestration; ~40 services. `PortfolioGatherer`,
  `SyncPortfolioService`, `SyncFromCashService`, `ManualPositionService`, `AssetTransferService`,
  `ExportExcelService`, `DividendService`, `AllocationService`, `PortfolioValueService`,
  `OrderFillReconciler` (settles a vanished order against its cash-ledger trade; run by both the
  orders panel and the cash import, and idle unless the ledger has moved),
  `PortfolioReturnService`, `AttributionService`, `OpenOrdersService`, `LadderService`,
  `SwitchService` + `GroupRungs` (what both group types share) + `ShareClassRegistry`
  + `IntentEdits` (the editable price/qty cells — intended rungs only),
  `LivePricesService`, `UnresolvedSymbols`,
  `OperatingLineService` + `OperatingLineFetchJob` (the filings-sourced operating income and
  margin — two gates, and a refusal is stored with its reason),
  `EarningsPrintService` + `EarningsPrintFetchJob` (what a print *meant* — its price reaction, and
  revenue against a consensus captured before it stopped existing),
  `WatchlistService`, `ReminderService` + `ReminderJob` (calendar reminders — hand-entered ones
  stored, earnings ones derived from the held/watched/ordered union),
  the five `*AnalysisService` backtests, `PriceFetchJob` +
  `IntradayPriceFetchJob` (share `PriceFetchSupport`), `CatchUpService` (the dormancy gate — what repairs the data
  when you reopen the app after the laptop has been shut, with no poller behind it).
- `web` — ~19 thin controllers (`RootController`, `ChartsController`, `AnalysisController`,
  `OperationsController`, `OrdersController`, `LaddersController`, `SwitchesController`,
  `LiveDashboardController`, `WatchlistController`, `RemindersController`,
  the five strategy controllers, …), plus `GlobalExceptionAdvice`
  (`@ExceptionHandler(IllegalStateException)` renders `fragments/error` instead of HTTP 500),
  `PriceFetchScheduler` and `WebSupport`.
- `config` — `BeanConfiguration`, `WebConfig` (the one MVC hook: the dormancy interceptor), `JacksonConfig` (serializes non-finite doubles as JSON `null`
  app-wide — empty distributions would otherwise emit bare `NaN` and corrupt the payload).
- Root — `ExcelReportWriter`, `PortfolioBenchApplication`.

Logging is SLF4J + Logback (`logback-spring.xml`); no `System.out` in production code.

UI is server-rendered Thymeleaf + htmx + Chart.js, all **vendored** — see
[ui-conventions.md](ui-conventions.md). 29 tab panels in six groups
(Holdings / Performance / Risk / Quality / Strategies / Tools). Tools also carries one tab per
named watchlist, all pointing at the single `panel-watchlist` and told apart by `data-list`.

## Why not Spring Modulith

Modulith defines a module as a direct sub-package of the application root. This tree is layer-first,
so Modulith would read `adapter`, `application`, `domain` … as the modules and verify only that
those eight do not cycle — which already holds, so it would certify the structure rather than
improve it. Getting value from it means inverting all ~275 files to `com.portfolio.<feature>.{…}`.

Its generated documentation describes *structure* (C4 component diagrams, a module canvas). The
weight in this project's docs is *semantics* — divide by the FX rate, absent is never zero, a split
is a ledger row. No generator emits those, so Modulith would not remove a line from the index. It
would also pull Spring-flavoured module metadata into the five packages deliberately kept
framework-free.

ArchUnit runs in test scope, needs no annotations, works on the layout that exists, and can encode
the two non-layer rules above as well as the layer table.
