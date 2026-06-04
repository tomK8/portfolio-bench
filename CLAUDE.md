# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build, test & run

```bash
mvn compile                            # compile
mvn test                               # run all tests (excludes @Tag("integration"))
mvn test -Dtest=AJBellSippParserTest   # run a single test class
mvn package -DskipTests                # build jar without tests
mvn spring-boot:run                    # run in the foreground (dev)
```

The server binds to loopback only, no auth — it is a single-user local tool. Override the
default directories (e.g. for a throwaway run that doesn't touch real data):

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--portfolio.input-dir=/tmp/in --portfolio.db-dir=/tmp/db --portfolio.output-dir=/tmp/out"
```

| Property             | Default                 | Purpose                                                                |
|----------------------|-------------------------|------------------------------------------------------------------------|
| `portfolio.input-dir`  | `~/Downloads`           | where broker exports + `cashstatements.csv` are read                 |
| `portfolio.db-dir`     | `~/Documents/Investing` | SQLite `portfolio.db`, archived cash files, KV setting files         |
| `portfolio.output-dir` | `~/Documents`           | generated Excel workbooks                                            |

### Daemon mode

```bash
scripts/start.sh   # backgrounds the jar, writes PID file, returns immediately
scripts/stop.sh    # SIGTERM the PID (or: kill $(cat portfolio-bench.pid))
```

Both scripts assume the jar at `target/portfolio-bench-1.0-SNAPSHOT.jar`; run
`mvn package -DskipTests` first. Logback writes to `~/log/portfolio-bench.log` — rotated
on each restart (previous log renamed with a timestamp; latest is always `portfolio-bench.log`).
The JVM's stdout/stderr is redirected into the same log, so any non-Logback output (early
classloader errors, OOM dumps, third-party `System.out`) is captured too. `spring.main.banner-mode=log`
routes the Spring banner through SLF4J so it lands in the file rather than disappearing.

### Test fixtures

`src/test/resources/` **must contain only synthetic data — no real brokerage exports.** The
fixtures are committed (the directory is not gitignored) so every checkout runs the same tests,
including the parser integration tests that load files from disk:

- `00000000-0000-0000-0000-000000000001.csv` — synthetic II SIPP holdings (UUID-named so
  `IISippParser.supports` matches); mixed USD/GBP rows, includes a `GOOGL` row.
- `00000000-0000-0000-0000-000000000002.csv` / `…0003.csv` — synthetic II cash statements (GBP
  and USD respectively); UUID-named with a `Debit,Credit,Running Balance` header so
  `IICashStatementParser.supports` matches and `IISippParser.supports` rejects.
- `Holdings.xlsx` — synthetic RothIRA holdings (sheet `Holdings`, headers at row index 11);
  includes a `GOOGL` row and `BDP` + `USD999997` cash rows to exercise the merge-to-`CASH` path.
- `yahoo-nvda-sample.json` — synthetic Yahoo chart response for `YahooPriceFetcherTest`.

The parser integration tests (`RothIraParserTest`, `IISippParserTest`) assert *structural*
properties (normalisation, currency mix, positive quantities), not exact values, so the synthetic
numbers can change freely. Real exports were never committed — they only ever existed as local,
untracked files; keep it that way. Regenerate the xlsx with openpyxl if its shape needs to change.

## Architecture

A Spring Boot web app. The user issues actions from a dashboard; each action is an
application service that wraps the framework-agnostic domain core. (It began as a one-shot
batch `Main`, since removed — see git history for the migration.)

Layered, ports-and-adapters style. **Spring is confined to the `web` and `config` packages**;
`domain`, `application`, `adapter`, `parser` and `persistence` carry no Spring annotations.

- `com.portfolio.domain` — pure logic, no IO/framework:
  - `PortfolioAggregator` (two named passes: `groupAccumulators` folds raw `Holding`s into a
    private `Accumulator` per `(securityId, currency)`, `buildAggHolding` derives the immutable
    `AggHolding`); owns `toGbp` / `costInGbp`.
  - `PortfolioMetrics` — portfolio-level totals (`totalGbp`, `rtTotalGbp` for the dashboard's
    intraday valuation, `returnPct`, `totalReturn`). Price appreciation only — dividends excluded.
  - `Instruments.isBond(String)` — the single home of the bond/gilt predicate.
  - `DividendAttributor` — FIFO dividend attribution.
  - `domain.model`: `Holding` (JavaBean + Builder; many nullable fields), `AggHolding`,
    `CashTransaction`, `Account` + `TransactionType` enums, `PriceBar`, `IntradayBar`, `IntradayPrice`.
- `com.portfolio.port` — `FxRateProvider` (latest rates) and `HistoricalFxRateProvider`
  (rates by date); interfaces, justified by faking the network in tests.
- `com.portfolio.adapter` — `FrankfurterFxClient` (implements both FX ports; Jackson JSON +
  shared `HttpClient` with connect/read timeouts), `HoldingFileLocator` (finds the most recent
  supported file per parser), `YahooPriceFetcher` (daily + intraday), `YahooTickerMap` (internal
  symbol → Yahoo ticker lookup).
- `com.portfolio.parser` — `AccountParser` / `CashTransactionParser` interfaces + impls. The
  cash parsers produce `CashTransaction` with `Account`/`TransactionType` enums.
- `com.portfolio.persistence` — the SQLite + KV layer (see "Persistence" below).
- `com.portfolio.application` — operations, plain classes wired as beans:
  - `PortfolioGatherer` (shared fetch-rates + parse → `GatheredPortfolio`),
  - `SyncPortfolioService`, `ExportExcelService`, `DividendService`,
  - `ImportCashService` composes a `List<CashImporter>` (one per broker — `AjBellCashImporter`,
    `RothIraCashImporter`, `IiCashImporter`) sharing `AbstractCashImporter`,
  - `PriceFetchJob` + `IntradayPriceFetchJob` share `PriceFetchSupport.tickersToFetch()` + `sleep()`.
- `com.portfolio.web` — `DashboardController` (thin; HTTP ↔ service only, with an
  `@ExceptionHandler(IllegalStateException)` that renders `fragments/error` instead of HTTP 500),
  Thymeleaf templates + htmx fragments, `PriceFetchScheduler` (the only price-layer Spring class).
- `com.portfolio.config` — `BeanConfiguration` wires adapters, repositories and services as `@Bean`s.
- `com.portfolio` — `ExcelReportWriter` (Apache POI), `PortfolioBenchApplication` (entry point).

**UI:** server-rendered Thymeleaf + htmx (CDN) + Chart.js. Actions run synchronously and swap
a result fragment into `#result`.

**Logging:** SLF4J + Logback throughout — no `System.out`/`System.err` in production code.
Config in `src/main/resources/logback-spring.xml`. The repository constructors run their own
DDL; that's the only thing that runs at application start besides Spring wiring.

### Web operations

| Action (button)       | Endpoint              | Service                  | Effect                                                                                              |
|-----------------------|-----------------------|--------------------------|-----------------------------------------------------------------------------------------------------|
| Sync portfolio        | `POST /sync`          | `SyncPortfolioService`   | fetch rates → parse → aggregate → save snapshot → show table                                        |
| Export Excel          | `POST /export`        | `ExportExcelService`     | write `portfolio<date>.xlsx` + `Portfolio Summary-<date>.xlsx`                                      |
| Import cash statement | `POST /import-cash`   | `ImportCashService`      | dispatches each `CashImporter`; per importer parse → dedup-save → archive/delete                    |

These are independent operations; Sync no longer auto-writes Excel or imports cash. Import
cash returns one result per source (`List<ImportCashResult>`, each carrying a `source` label).

**Sync data flow:** `PortfolioGatherer` fetches rates (`FxRateProvider`) and parses the most
recent supported file per parser → `PortfolioAggregator` aggregates by `(securityId, currency)`
→ `PortfolioMetrics` computes totals (with the II SIPP cash from the form) →
`SnapshotRepository.saveSnapshot()` records to SQLite → aggregated view returned for display.

**`Holding` fields:**

- `currentMarketValue` — native currency amount
- `currentMarketValueGbp` — pre-converted GBP if the source provides it (AJ Bell does); `null` means convert via FX
- `costBasisGbp` — GBP cost from the source (AJ Bell only); `null` means compute from `avgPricePaid` via FX

**FX convention:** rates are stored as *units of foreign currency per 1 GBP* (e.g. `{"USD": 1.3621}`). To convert
native → GBP, divide by the rate.

## Persistence

The `com.portfolio.persistence` package is the single owner of JDBC traffic. All repositories
go through `JdbcConnectionFactory`, which holds the on-disk path and ensures the directory
exists. Each repository runs its DDL once at construction (Spring startup), so reads/writes
don't re-execute `CREATE TABLE IF NOT EXISTS` on every call.

- `JdbcConnectionFactory` — owns `dbDir` + `dbPath`, hands out `Connection`s.
- `SnapshotRepository` — `portfolio_snapshots` table (one row per snapshot date; re-running same
  day overwrites).
- `CashTransactionRepository` — `cash_transactions` table. Per-broker save methods (`saveAjBell`,
  `saveRothIra`, `saveII`) each encapsulate the dedup + balance-derivation rules specific to that
  broker. Plus read methods: `loadDividendTransactions`, `distinctTradedSymbols`.
- `PriceHistoryRepository` — `price_history` table (daily OHLCV).
- `IntradayPriceRepository` — `price_intraday` table (1-min closes); `loadLatestIntradayPrices`
  is a single bulk query feeding the dashboard's RT columns, not N round-trips.
- `KeyValueStore` — file-backed scalar settings, one `<key>.txt` per key under `<db-dir>`:
  - `ii_sipp_cash_last` — last II SIPP cash balance entered on the dashboard.
  - `roth_balance_brought_forward` — RothIRA opening balance, persisted on first import for traceability.

**Write-path errors throw, reads degrade.** `SnapshotRepository.saveSnapshot` and the three
`CashTransactionRepository.save*` methods throw `IllegalStateException` on failure — the
`@ExceptionHandler` in `DashboardController` catches and renders `fragments/error` so the user
sees what went wrong. Query methods (e.g. `distinctTradedSymbols`, `loadDividendTransactions`)
and background-job inserts (`savePriceBars`, `saveIntradayBars`) log + return empty/0 — they
must not crash the scheduler thread.

**Adding a column to a pre-existing table:** `CashTransactionRepository`'s constructor demonstrates
the pattern — `CREATE TABLE IF NOT EXISTS`, then a `try { ALTER TABLE … } catch (SQLException ignored) { }`
block. The catch is the migration's idempotency guard: it fires on second startup once the column exists.

## Adding a new parser

1. Implement `AccountParser` (`parse`, `supports`, `sourceName`)
2. Register it in `PortfolioGatherer` alongside the existing parsers (the parser list is built
   there per run, after rates are fetched, since `AJBellSippParser` needs the live rates)
3. If the source provides GBP values directly, set `currentMarketValueGbp` and `costBasisGbp` on the `Holding`;
   otherwise leave them null and `PortfolioAggregator` will convert via FX

**Parser file-detection conventions:**

- `RothIraParser` — `Holdings*.xlsx` (holdings)
- `AJBellSippParser` — `portfolio*.csv` (takes live FX rates in constructor for native-value calculation)
- `IISippParser` — UUID-named `.csv` (e.g. `1f072c5f-....csv`); infers currency from `$`/`£` prefix on Market Value
- `AJBellCashStatementParser` — `cashstatements*.csv` (cash flows, GBP)
- `RothIraCashStatementParser` — `History*.xlsx` (cash flows, native USD; takes a `HistoricalFxRateProvider` in
  constructor for per-date GBP conversion)
- `IICashStatementParser` — UUID-named `.csv` with `Debit,Credit,Running Balance` header (one file per
  currency: GBP, USD, …); takes a `HistoricalFxRateProvider` for listing-currency detection and per-date GBP
  conversion. Distinguished from `IISippParser` (holdings) by header sniff.

## Adding a new cash source (broker)

1. Implement `CashTransactionParser` (`account()` → `Account` enum, `supports`, `parse`).
2. Add a per-broker save method to `CashTransactionRepository` if the dedup rules differ; or
   reuse one of the existing three.
3. Write a `CashImporter` impl extending `AbstractCashImporter` (provides `matchingFiles`,
   `mostRecent`, `parse`, `archiveOrDelete`). Implement `importFrom(Path inputDir)`.
4. Register the importer as a bean in `BeanConfiguration`. Spring injects `List<CashImporter>`
   into `ImportCashService` in bean-declaration order, so place the new bean wherever you want
   it to appear in the dashboard's result list.

## II SIPP cash handling

II SIPP's CSV export does not include a cash balance. The dashboard has an II SIPP cash field
that prefills from the last saved value and is remembered on sync/export;
`KeyValueStore.getBigDecimal("ii_sipp_cash_last", ZERO)` / `putBigDecimal(...)` persist it to
`<db-dir>/ii_sipp_cash_last.txt`. In a generated workbook the value is written into a yellow
input cell (`E` column) on the Portfolio sheet; a formula chain links the Portfolio Raw sheet's
II SIPP CASH row back to that cell so editing the Excel file directly also works.

## Bond identification

Bonds in AJ Bell exports carry `(SEDOL:...)` in the Investment description. `AJBellSippParser.extractBondId`
converts these to `"GILT {coupon}% {year}"` format. `Instruments.isBond(securityId)` (in `com.portfolio.domain`)
checks for `%` presence or `GILT` prefix and is the single source of truth used to:
- sort bonds into their own section at the bottom of the Portfolio sheet;
- skip them when fetching prices from Yahoo (no coverage);
- skip the real-time market-value column (broker price stays source of truth).

## Dividends

Dividend data comes **exclusively** from `cash_transactions WHERE type = 'DIVIDEND'` — there is no
separate dividend table or manual-entry screen (both were removed).

`DividendService.dividendsBySymbol(holdings)` drives the pipeline:

1. `CashTransactionRepository.loadDividendTransactions()` returns all `TRANSACTION` + `DIVIDEND` rows
   oldest-first (buys/sells are needed to reconstruct the share timeline).
2. `DividendAttributor.attributeBySymbol(rows)` replays per `(account, symbol)` with FIFO lot tracking:
   - **Buy** (`amount < 0`): opens a lot at the current cumulative-per-share baseline.
   - **Dividend**: raises cumulative-per-share by `amountGbp / sharesHeld`.
   - **Sell** (`amount > 0`): removes shares oldest-first (FIFO); accrued dividends leave with them.
   - **Split** (`amount == 0`): scales lot quantities up and per-share figures down by the split ratio,
     keeping each lot's total attributed dividend invariant.
   - Dividends on shares that were subsequently sold drop out — only currently-held shares count.
3. `DividendService` reconciles the reconstructed share count against the holdings file and logs
   a WARN (SLF4J) if they disagree by more than 0.01 shares (incomplete transaction history).

`PortfolioAggregator.aggregate(holdings, rates, dividendsBySymbol)` then sets per holding:

- `dividendGbp` — FIFO-attributed dividends for the symbol (£; ZERO when none)
- `totalGainGbp` — price-appreciation `gainGbp` + `dividendGbp` (null when cost basis is unknown)
- `totalGainPct` — `totalGainGbp / cost` (null when cost is unknown/zero)

These surface as **Dividends (£)**, **Total Gain (£)** and **Total Gain %** columns on the web table and
both Excel sheets (aggregated Portfolio + Portfolio Raw), all computed in code — no Excel formulas. The
raw sheet, which writes per-`Holding` rows, still receives the dividends map directly. Portfolio-level
totals/returns (`PortfolioMetrics`, the saved snapshot) remain price-appreciation only and do **not** fold
in dividends.

## Cash transaction ingestion

`CashTransactionParser` is a separate interface from `AccountParser` — cash flows and holdings are different concerns.
Three impls feed the `cash_transactions` table: `AJBellCashStatementParser` (`cashstatements*.csv`, GBP),
`RothIraCashStatementParser` (`History*.xlsx`, native USD) and `IICashStatementParser` (UUID-named `.csv`, one
file per currency). All emit `CashTransaction` with `Account` + `TransactionType` enums; on-disk the columns
remain TEXT (`account.dbValue()` / `type.name()`) so the SQLite CHECK constraint and existing data are unchanged.

The table stores both a native-currency running balance (`cash_balance`) and its GBP equivalent (`cash_balance_gbp`).
For AJBell these are equal (GBP); for RothIRA `cash_balance` is USD; for II each row's `currency` column distinguishes
the per-currency ledger within the shared `account='II'`. `CashTransactionRepository`'s constructor performs the
one-time `ALTER TABLE … ADD COLUMN cash_balance` + backfill for pre-existing GBP rows.

**Ingestion flow:** `ImportCashService.importCash()` iterates a Spring-injected `List<CashImporter>` and
concatenates each importer's results. Per importer (see `AbstractCashImporter` for the shared mechanics):

1. Match files in input dir (AJBell + RothIRA pick the newest of a glob; II picks every UUID-named CSV whose
   header carries `Debit,Credit,Running Balance`).
2. Parse → save via the matching `CashTransactionRepository.save*` method.
3. If new rows were written: archive the file to `<db-dir>/<prefix>_<date>.<ext>` (move, not copy).
4. If no new rows (duplicate): delete it.
5. If the save throws (data-integrity gap, JDBC failure): the file stays in place, the controller's
   `@ExceptionHandler` shows the error in `fragments/error`.

**AJBell dedup** (in `CashTransactionRepository.saveAjBell`): loads existing `(transaction_date, cash_balance_gbp)`
pairs into a `Set`; known rows are skipped; a known key reappearing *after* a new row **throws** as a
data-integrity gap (previously logged and silently returned 0, which caused the importer to delete the
file as a duplicate — fixed).

**RothIRA dedup + balance** (in `CashTransactionRepository.saveRothIra`): rows arrive with `fxToGbp`/`amountGbp`
already resolved per-date by the parser (historical GBP→USD via `HistoricalFxRateProvider`, falling back to
the nearest prior business day). Dedup key is `(date, symbol, quantity, type, amount)` — native USD amount,
FX-stable across imports — so overlapping re-imports skip known rows. The running native balance continues
from the latest stored `cash_balance`, or from the opening-balance *seed* when the account is empty. The seed
(`0`, the balance before the earliest transaction) is persisted to the `KeyValueStore` key
`roth_balance_brought_forward` (file: `<db-dir>/roth_balance_brought_forward.txt`) for traceability on first-run
import only. New rows are applied oldest-first; `cash_balance_gbp = cash_balance / fxToGbp` of the row's date.

**II dedup + balance** (in `CashTransactionRepository.saveII`): rows arrive with `cashBalance`/`cashBalanceGbp`
already set from the file's `Running Balance` column (per-currency). Dedup key is
`(date, type, symbol, amount, currency)` within `account='II'`; the currency component isolates the GBP and USD
ledgers within the shared account so dividend FIFO still groups by `(account, symbol)` across currencies
(a USD-listed stock bought from GBP cash and later sold to USD cash attributes correctly).

**Symbol resolution in `AJBellCashStatementParser`** (three tiers, in order):

1. GILT detection — standard `coupon% ... dd/mm/yyyy` format, or compressed names like `TREASURY2.75L24` /
   `HM TREA0.2525`; unresolvable redemptions (e.g. `TSY STK25`) borrow the symbol from a same-date DIVIDEND row in a
   second pass.
2. `SYMBOL_RULES` list — ~25 equity/ETF patterns mapped to tickers (longest/most-specific first).
3. `cleanName` fallback — strips share-class suffixes and currency codes.

**Row classification (AJBell):** `TRANSACTION` (purchase/sale/redemption), `DIVIDEND`, `INTEREST`, `CHARGE`,
`CONTRIBUTION`. Reversed-format redemption descriptions (`NAME Redemption`) are handled by `PAT_REVERSED_REDEMPTION`
before the fallback path.

**Row classification (RothIRA):** by Activity Description — Buy/Sell *and* Stock Split → `TRANSACTION` (splits move no
cash: `amount`/`amountGbp` = 0 but `quantity` = the extra shares); Cash/Foreign Security Dividend → `DIVIDEND`; Foreign
Tax Withheld → `CHARGE` (its own negative row, not netted into the dividend). Quantity keeps the file's sign (negative
for sells). Symbols pass through `RothIraParser.normaliseSecurityId` for consistency with holdings.

**Row classification (II):** by `Description` — `Div N <name>` → `DIVIDEND`; `Gross interest…` → `INTEREST`;
`Total Monthly Fee` → `CHARGE`; `Trf from… / PBB NET CONTRIBUTION / Basic rate tax relief…` → `CONTRIBUTION`;
`N POUNDS STERLING NoTf…` (currency exchange) → `CONTRIBUTION` (paired across the GBP/USD files); buy/sell
descriptions `N <name> Del|Bal <price> S Date dd/mm/yy` → `TRANSACTION`. Every trade row produces a `TRANSACTION`
plus a `CHARGE` for the commission/FX markup so the cost is broken out rather than buried in the position cost basis.
Listing currency is detected heuristically (`price·qty` vs file debit/credit, trying file ccy first, then USD/EUR via
historical FX); the residual becomes the markup. Running balance per row is verified against the file's
`Running Balance` column — a mismatch >1p/1c aborts the import.

## Price history

Daily OHLCV prices for every instrument ever held are fetched from Yahoo Finance into the `price_history`
table; 1-minute closes go into `price_intraday`. Equities only — **bonds/gilts are skipped**
(Yahoo lacks coverage; the broker price stays source of truth, identified by `Instruments.isBond`).

**Schema** (`price_history`, DDL in `PriceHistoryRepository`): PK `(symbol, date)`,
both `close` (unadjusted) and `adj_close` (split/dividend-adjusted), plus `open/high/low/volume`, the listing
`currency`, and a `fetched_at` timestamp. Rows are keyed by the **resolved Yahoo ticker** (e.g. `EQQQ.L`),
not the internal symbol.

**Schema** (`price_intraday`, DDL in `IntradayPriceRepository`): PK `(symbol, ts)`, `close`, `volume`, `currency`,
`fetched_at`. Rows older than 7 days are pruned at the end of each fetch tick.

**Pipeline** (shared between `PriceFetchJob` and `IntradayPriceFetchJob` via `PriceFetchSupport`):

1. `CashTransactionRepository.distinctTradedSymbols()` → every symbol from `cash_transactions WHERE type IN
   ('TRANSACTION','DIVIDEND')` (excludes INTEREST/CHARGE/CONTRIBUTION placeholder symbols).
2. `PriceFetchSupport.tickersToFetch()` filters out bonds (`Instruments.isBond`) and maps the rest to Yahoo
   tickers via `YahooTickerMap.tickerFor`, deduplicating.
3. Daily job: per ticker, `PriceHistoryRepository.getLatestPriceDate` → fetch `[latest+1 .. today]`, or a
   ~10-year backfill on first run. Intraday job: per ticker, `IntradayPriceRepository.getLatestIntradayTs` →
   fetch the gap, clamped to the 7-day retention window on first run.
4. `YahooPriceFetcher.fetch` / `.fetchIntraday` (java.net.http + Jackson, browser User-Agent, retry-once) →
   `savePriceBars` / `saveIntradayBars` (`INSERT OR IGNORE`, idempotent). A 500ms throttle (via
   `PriceFetchSupport.sleep`) separates requests.

**`close` vs `adj_close`:** use `close` for "market value of the position on date X", `adj_close` for total-return
calculations. Because fetching is incremental + `INSERT OR IGNORE`, historical `adj_close` is **not** refreshed as
later dividends/splits accrue; a future full re-pull (keyed off `fetched_at`) can fix that if needed.

**Currency is stored verbatim** from Yahoo — no GBP conversion in this layer. Note `.L` (London) tickers report
`"GBp"` (pence, not pounds); `PortfolioAggregator.realtime` divides by 100 before producing the RT market value.

**Scheduling:** `PriceFetchScheduler` (the only price-layer Spring class) runs both jobs on
`ApplicationReadyEvent` (on daemon threads, so a first-run backfill doesn't block the web UI), the daily job
via `@Scheduled` cron at 22:00 Europe/London, and the intraday job every 5 minutes. `@EnableScheduling` is on
`PortfolioBenchApplication`.

**Adding a Yahoo ticker mapping:** add an `internal=YAHOO` line to `resources/yahoo-tickers.properties` (the single
source of mappings — edited in code, no external/runtime override). US listings need no entry (they map to
themselves); non-US need an exchange suffix (`.L` London, `.PA` Paris, `.AS` Amsterdam, `.DE` Frankfurt).

**Testing:** JSON parsing is unit-tested against a saved sample (`yahoo-nvda-sample.json`); a live-API test
(`YahooPriceFetcherIntegrationTest`) is `@Tag("integration")` and excluded from the default `mvn test` by the
surefire `excludedGroups` property. Run it with `mvn test -Dgroups=integration -DexcludedGroups=`.
