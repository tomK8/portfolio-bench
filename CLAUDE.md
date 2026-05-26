# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build, test & run

```bash
mvn compile                            # compile
mvn test                               # run all tests
mvn test -Dtest=AJBellSippParserTest   # run a single test class
mvn package -DskipTests                # build jar without tests
mvn spring-boot:run                    # start the web app at http://127.0.0.1:8080
```

The server binds to loopback only, no auth — it is a single-user local tool. Override the
default directories (e.g. for a throwaway run that doesn't touch real data):

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--pension.input-dir=/tmp/in --pension.db-dir=/tmp/db --pension.output-dir=/tmp/out"
```

| Property | Default | Purpose |
|---|---|---|
| `pension.input-dir`  | `~/Downloads`            | where broker exports + `cashstatements.csv` are read |
| `pension.db-dir`     | `~/Documents/Investing`  | SQLite `portfolio.db`, archived cash files, `ii_sipp_cash_last.txt` |
| `pension.output-dir` | `~/Documents`            | generated Excel workbooks |

## Architecture

A Spring Boot web app. The user issues actions from a dashboard; each action is an
application service that wraps the framework-agnostic domain core. (It began as a one-shot
batch `Main`, since removed — see git history for the migration.)

Layered, ports-and-adapters style. **Spring is confined to the `web` and `config` packages**;
`domain`, `application`, `adapter` and `parser` carry no Spring annotations.

- `com.pension.domain` — pure logic, no IO/framework: `PortfolioAggregator` (aggregates
  `Holding`s; owns `toGbp`/`costInGbp`/`isBond`), `PortfolioMetrics` (totals/return),
  `DividendCalculator` (dividend FX→GBP). `domain.model`: `Holding`, `AggHolding`,
  `CashTransaction`, `DividendEntry`.
- `com.pension.port` — `FxRateProvider`, the one interface (its only justification is faking
  the network in tests). Everything else is concrete by design.
- `com.pension.adapter` — `FrankfurterFxClient` (implements `FxRateProvider`, hits
  `api.frankfurter.dev`), `HoldingFileLocator` (finds the most recent supported file per parser).
- `com.pension.parser` — `AccountParser` / `CashTransactionParser` interfaces + impls.
- `com.pension.application` — the operations (plain classes, wired as beans):
  `PortfolioGatherer` (shared fetch-rates + parse step → `GatheredPortfolio`),
  `SyncPortfolioService`, `ExportExcelService`, `RecordDividendsService`, `ImportCashService`.
- `com.pension.web` — `DashboardController`, `DividendsController` (thin; HTTP ↔ service only),
  Thymeleaf templates + htmx fragments.
- `com.pension.config` — `BeanConfiguration` wires the adapters/services as `@Bean`s.
- `com.pension` — `PortfolioDatabase` (all SQLite + settings-file IO), `ExcelReportWriter`
  (Apache POI), `PensionAggregatorApplication` (entry point).

**UI:** server-rendered Thymeleaf + htmx (CDN) + Chart.js. Actions run synchronously and swap
a result fragment into `#result`.

**Persistence is routed through `PortfolioDatabase` alone** — nothing else opens JDBC
connections — so concurrency safety (a future price-poller) can be added in one place.

### Web operations

| Action (button) | Endpoint | Service | Effect |
|---|---|---|---|
| Sync portfolio        | `POST /sync`        | `SyncPortfolioService` | fetch rates → parse → aggregate → save snapshot → show table |
| Export Excel          | `POST /export`      | `ExportExcelService`   | write `portfolio<date>.xlsx` + `Portfolio Summary-<date>.xlsx` |
| Record dividends      | `GET/POST /dividends` | `RecordDividendsService` | validate + FX-convert + persist manual dividend rows |
| Import cash statement | `POST /import-cash` | `ImportCashService`    | parse → dedup-save → archive/delete `cashstatements.csv` |

These are independent operations; Sync no longer auto-writes Excel or imports cash.

**Sync data flow:** `PortfolioGatherer` fetches rates (`FxRateProvider`) and parses the most
recent supported file per parser → `PortfolioAggregator` aggregates by `(securityId, currency)`
→ `PortfolioMetrics` computes totals (with the II SIPP cash from the form) →
`PortfolioDatabase.saveSnapshot()` records to SQLite → aggregated view returned for display.

**`Holding` fields:**
- `currentMarketValue` — native currency amount
- `currentMarketValueGbp` — pre-converted GBP if the source provides it (AJ Bell does); `null` means convert via FX
- `costBasisGbp` — GBP cost from the source (AJ Bell only); `null` means compute from `avgPricePaid` via FX

**FX convention:** rates are stored as *units of foreign currency per 1 GBP* (e.g. `{"USD": 1.3621}`). To convert native → GBP, divide by the rate.

## Adding a new parser

1. Implement `AccountParser` (`parse`, `supports`, `sourceName`)
2. Register it in `PortfolioGatherer` alongside the existing parsers (the parser list is built
   there per run, after rates are fetched, since `AJBellSippParser` needs the live rates)
3. If the source provides GBP values directly, set `currentMarketValueGbp` and `costBasisGbp` on the `Holding`; otherwise leave them null and `PortfolioAggregator` will convert via FX

**Parser file-detection conventions:**
- `RothIraParser` — `Holdings*.xlsx`
- `AJBellSippParser` — `portfolio*.csv` (takes live FX rates in constructor for native-value calculation)
- `IISippParser` — UUID-named `.csv` (e.g. `1f072c5f-....csv`); infers currency from `$`/`£` prefix on Market Value

## II SIPP cash handling

II SIPP's CSV export does not include a cash balance. The dashboard has an II SIPP cash field
that prefills from the last saved value and is remembered on sync/export;
`PortfolioDatabase.loadLastIiSippCash()` / `saveLastIiSippCash()` persist it to
`<db-dir>/ii_sipp_cash_last.txt`. In a generated workbook the value is written into a yellow
input cell (`E` column) on the Portfolio sheet; a formula chain links the Portfolio Raw sheet's
II SIPP CASH row back to that cell so editing the Excel file directly also works.

## Bond identification

Bonds in AJ Bell exports carry `(SEDOL:...)` in the Investment description. `AJBellSippParser.extractBondId` converts these to `"GILT {coupon}% {year}"` format. `PortfolioAggregator.isBond` checks for `%` presence or `GILT` prefix to sort bonds into their own section at the bottom of the Portfolio sheet.

## Cash transaction ingestion

`CashTransactionParser` is a separate interface from `AccountParser` — cash flows and holdings are different concerns. `AJBellCashStatementParser` implements it for `cashstatements.csv`.

**Ingestion flow in `ImportCashService.importCash()`** (one cohesive operation):
1. If `cashstatements.csv` is absent from the input dir, return NOT_FOUND
2. Parse → classify rows → resolve symbols → `PortfolioDatabase.saveCashTransactions()` into the `cash_transactions` table
3. If new rows were written: archive the file to `<db-dir>/cashstatements_<date>.csv`
4. If no new rows (file is a duplicate): delete it

The parsed rows are passed through in file order, and the dedup/integrity logic stays inside
`saveCashTransactions` (it needs the existing-keys query and the inserts in one connection).

**Incremental dedup:** before inserting, `saveCashTransactions` loads all existing `(transaction_date, cash_balance_gbp)` pairs for the account into a `Set<String>`. Rows whose key is already present are skipped. If a known key reappears *after* a new row has been inserted, the import aborts with a data integrity error (gap detected in input file).

**Symbol resolution in `AJBellCashStatementParser`** (three tiers, in order):
1. GILT detection — standard `coupon% ... dd/mm/yyyy` format, or compressed names like `TREASURY2.75L24` / `HM TREA0.2525`; unresolvable redemptions (e.g. `TSY STK25`) borrow the symbol from a same-date DIVIDEND row in a second pass
2. `SYMBOL_RULES` list — ~25 equity/ETF patterns mapped to tickers (longest/most-specific first)
3. `cleanName` fallback — strips share-class suffixes and currency codes

**Row classification:** `TRANSACTION` (purchase/sale/redemption), `DIVIDEND`, `INTEREST`, `CHARGE`, `CONTRIBUTION`. Reversed-format redemption descriptions (`NAME Redemption`) are handled by `PAT_REVERSED_REDEMPTION` before the fallback path.
