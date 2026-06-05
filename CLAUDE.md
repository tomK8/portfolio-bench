# CLAUDE.md

Guidance for Claude Code in this repository. Prefer reading the code; this file covers the
cross-cutting bits that aren't obvious from any single file.

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
  `DividendAttributor` (FIFO), `Instruments.isBond` (single home of the bond/gilt predicate).
  `domain.model`: `Holding` (Builder; many nullable fields), `AggHolding`, `CashTransaction`,
  `Account` + `TransactionType` enums, `PriceBar`, `IntradayBar`, `IntradayPrice`.
- `port` — `FxRateProvider`, `HistoricalFxRateProvider` (interfaces, faked in tests).
- `adapter` — `FrankfurterFxClient` (Jackson + shared HttpClient with timeouts),
  `HoldingFileLocator`, `YahooPriceFetcher`, `YahooTickerMap`.
- `parser` — `AccountParser` / `CashTransactionParser` interfaces + impls.
- `persistence` — `JdbcConnectionFactory` + 4 repositories + `KeyValueStore`. See below.
- `application` — `PortfolioGatherer`, `SyncPortfolioService`, `ExportExcelService`,
  `DividendService`, `ImportCashService` (composes `List<CashImporter>`), `PriceFetchJob` +
  `IntradayPriceFetchJob` (share `PriceFetchSupport`).
- `web` — `DashboardController` (thin; `@ExceptionHandler(IllegalStateException)` renders
  `fragments/error` instead of HTTP 500), `PriceFetchScheduler`.
- `config` — `BeanConfiguration`.
- Root — `ExcelReportWriter`, `PortfolioBenchApplication`.

UI: server-rendered Thymeleaf + htmx (CDN) + Chart.js. Actions are synchronous; result fragment
swaps into `#result`. Logging is SLF4J + Logback (`logback-spring.xml`); no `System.out` in production code.

| Action                | Endpoint                   | Service                   |
|-----------------------|----------------------------|---------------------------|
| Sync portfolio        | `POST /sync`               | `SyncPortfolioService`    |
| Export Excel          | `POST /export`             | `ExportExcelService`      |
| Import cash statement | `POST /import-cash`        | `ImportCashService`       |
| Import gilt prices    | `POST /import-gilt-prices` | `ImportGiltPricesService` |

## Holding fields

- `currentMarketValue` — native currency amount.
- `currentMarketValueGbp` — pre-converted GBP from the source (AJ Bell does); `null` → convert via FX.
- `costBasisGbp` — GBP cost from the source (AJ Bell only); `null` → compute from `avgPricePaid` via FX.

**FX convention:** rates are *units of foreign currency per 1 GBP* (e.g. `{"USD": 1.3621}`).
To convert native → GBP, **divide** by the rate.

## Persistence

`com.portfolio.persistence` is the single owner of JDBC traffic. All repos route through
`JdbcConnectionFactory`; each repo runs its DDL once in its constructor (not on every read/write).

- `SnapshotRepository` — `portfolio_snapshots` (one row per snapshot date; re-run same day overwrites).
- `CashTransactionRepository` — `cash_transactions`; per-broker save methods (`saveAjBell`,
  `saveRothIra`, `saveII`) hold each broker's dedup + balance-derivation rules. Plus reads:
  `loadDividendTransactions`, `distinctTradedSymbols`.
- `PriceHistoryRepository` — `price_history` (daily OHLCV).
- `IntradayPriceRepository` — `price_intraday` (1-min closes). `loadLatestIntradayPrices` is a bulk
  query, not N round-trips.
- `KeyValueStore` — file-backed scalar settings, one `<key>.txt` per key under `<db-dir>`:
  - `ii_sipp_cash_last` — last II SIPP cash balance entered on the dashboard.
  - `roth_balance_brought_forward` — RothIRA opening balance, persisted on first import for traceability.

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
fetches (no coverage there), (3) switch `PortfolioAggregator.realtime` to the per-£100-nominal
formula (`qty × price / 100`) when scoring the cached dividenddata clean price.

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
- AJBell: `(transaction_date, cash_balance_gbp)`. A known key reappearing *after* a new row
  **throws** as a data-integrity gap, so the file stays in place rather than being silently
  deleted as a "duplicate".
- RothIRA: `(date, symbol, quantity, type, amount)` — native USD amount, FX-stable across
  imports. Balance continues from latest stored `cash_balance`, or the
  `roth_balance_brought_forward` KV seed (~£(figure redacted)) on first import.
- II: `(date, type, symbol, amount, currency)` within `account='II'`. The currency component
  isolates the GBP and USD ledgers within the shared account so dividend FIFO still groups by
  `(account, symbol)` across currencies.

## Price history

Two tables, three jobs. `PriceFetchSupport` is shared by the two Yahoo jobs:

- `PriceFetchJob` → `price_history` (daily). First run backfills ~10 years per ticker; later runs
  fetch the gap to today. Cron 22:00 Europe/London + startup.
- `IntradayPriceFetchJob` → `price_intraday` (1-minute closes). 7-day retention; pruned after each
  fetch tick. Every 5 minutes + startup.
- `GiltPriceFetchJob` → also `price_intraday`, one HTTP GET to `dividenddata.co.uk/uk-gilts-prices-yields.py`.
  Hourly + startup. Symbols are `"GILT {coupon}% {year}"` (no Yahoo ticker map). Currency `"GBP"`.
  Rides the intraday job's prune for retention. Dividenddata returns 403 to the default Java
  `User-Agent`; `GiltPriceFetcher` sends a browser UA. Each tick also **upserts** today's row
  into `price_history` so the daily series self-accumulates even when the app isn't running
  all day — last-write-wins.
- `ImportGiltPricesService` → bulk historical import for gilts from manually downloaded
  `~/Downloads/Tradeweb_FTSE_ClosePrices*.csv`. Runs on startup (daemon) and on demand via
  `POST /import-gilt-prices`. Upserts into `price_history`, archives consumed files to the
  DB dir with a symbol-embedded name. One file = one gilt across a date range.

**Gilt price history sources:** two independent feeds writing the same table via the same
upsert. Either can fill or overwrite the other's day. The intraday rollup gives continuous
coverage while the app runs; Tradeweb batch imports give authoritative closes for whatever
range the user downloads — typically the source of truth when both are present.

`PriceFetchSupport.tickersToFetch` builds the universe: every symbol from
`CashTransactionRepository.distinctTradedSymbols()`, minus bonds (`Instruments.isBond`), mapped to
Yahoo tickers via `YahooTickerMap`. 500ms throttle between requests (`PriceFetchSupport.sleep`).

`PriceFetchScheduler` is the only Spring class in the price layer; `@EnableScheduling` is on
`PortfolioBenchApplication`. Startup runs are on daemon threads so first-run backfill doesn't
block the web UI.

**Adding a Yahoo ticker mapping:** add `internal=YAHOO` to `resources/yahoo-tickers.properties`.
US listings map to themselves; non-US need an exchange suffix (`.L` London, `.PA` Paris, `.AS`
Amsterdam, `.DE` Frankfurt).

**`close` vs `adj_close`:** use `close` for "market value of the position on date X", `adj_close`
for total-return calculations. Because Yahoo fetching is incremental + `INSERT OR IGNORE`,
historical `adj_close` is **not** refreshed as later dividends/splits accrue. The gilt paths
(intraday rollup, Tradeweb batch import) use `upsertPriceBars` instead, so the latest write
wins for `(symbol, date)` — clean price feeds both `close` and `adj_close` (no total-return
math for bonds).

**Currency is stored verbatim** from Yahoo. `.L` (London) tickers report `"GBp"` (pence);
`PortfolioAggregator.realtime` divides by 100 before producing the RT market value.

**II SIPP cash field:** II's CSV export omits the cash balance; the dashboard form supplies it
and `KeyValueStore.getBigDecimal("ii_sipp_cash_last", ZERO)` remembers it. In the Excel export
the value lands in a yellow input cell on the Portfolio sheet, and a formula chain links the
Portfolio Raw sheet's II SIPP CASH row back to that cell.

**Yahoo testing:** JSON parsing is unit-tested against `yahoo-nvda-sample.json`; a live-API test
(`YahooPriceFetcherIntegrationTest`) is `@Tag("integration")` and excluded by the surefire
`excludedGroups` property. Run with `mvn test -Dgroups=integration -DexcludedGroups=`.
