# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & test commands

```bash
mvn compile                          # compile
mvn test                             # run all tests
mvn test -Dtest=AJBellSippParserTest # run a single test class
mvn package -DskipTests              # build jar without tests
mvn exec:java -Dexec.mainClass=com.pension.Main  # run (requires input files in ~/Downloads)
```

## Architecture

The app reads the most recently modified matching file from `~/Downloads` for each registered parser, aggregates all holdings, and writes two Excel files to `~/Documents`:
- `portfolio<yyyyMMdd>.xlsx` — full workbook with Portfolio, Portfolio Raw, and one raw-input tab per source (overwritten on re-run same day)
- `Portfolio Summary-<yyyyMMdd>.xlsx` — aggregated Portfolio tab only (for easy re-opening)

It also appends a row to `~/Documents/Investing/portfolio.db` (SQLite) on every run.

**Key classes (SRP split from original monolithic Main):**
- `FxRateClient` — fetches live GBP FX rates from `api.frankfurter.dev`
- `PortfolioAggregator` — aggregates raw `Holding` objects; also owns `toGbp`, `costInGbp`, `isBond`
- `ExcelReportWriter` — all Apache POI logic; `writeFullReport` and `writeSummaryReport`
- `UserInputDialogs` — Swing prompts (II SIPP cash input, dividend entry table)
- `PortfolioDatabase` — SQLite persistence (snapshots, dividends, cash transactions)
- `Main` — orchestrates the run (~180 lines)

**Data flow:**
1. `FxRateClient` fetches live GBP rates
2. Each `AccountParser` is asked `supports(file)` against files in `~/Downloads`; the most recently modified match is parsed
3. `UserInputDialogs.promptForIISippCash()` prompts for the II SIPP cash balance
4. `PortfolioAggregator` aggregates all `Holding` objects by `(securityId, currency)` key
5. `ExcelReportWriter` writes both Excel files
6. `PortfolioDatabase.saveSnapshot()` records totals to SQLite

**`Holding` fields:**
- `currentMarketValue` — native currency amount
- `currentMarketValueGbp` — pre-converted GBP if the source provides it (AJ Bell does); `null` means convert via FX
- `costBasisGbp` — GBP cost from the source (AJ Bell only); `null` means compute from `avgPricePaid` via FX

**FX convention:** rates are stored as *units of foreign currency per 1 GBP* (e.g. `{"USD": 1.3621}`). To convert native → GBP, divide by the rate.

## Adding a new parser

1. Implement `AccountParser` (`parse`, `supports`, `sourceName`)
2. Register it in `Main.main()` alongside the existing parsers
3. If the source provides GBP values directly, set `currentMarketValueGbp` and `costBasisGbp` on the `Holding`; otherwise leave them null and `PortfolioAggregator` will convert via FX

**Parser file-detection conventions:**
- `RothIraParser` — `Holdings*.xlsx`
- `AJBellSippParser` — `portfolio*.csv` (takes live FX rates in constructor for native-value calculation)
- `IISippParser` — UUID-named `.csv` (e.g. `1f072c5f-....csv`); infers currency from `$`/`£` prefix on Market Value

## II SIPP cash handling

II SIPP's CSV export does not include a cash balance. `UserInputDialogs.promptForIISippCash()` prompts for it at runtime and persists the last value to `~/Documents/Investing/ii_sipp_cash_last.txt`. The value is written into a yellow input cell (`E` column) on the Portfolio sheet; a formula chain links the Portfolio Raw sheet's II SIPP CASH row back to that cell so editing the Excel file directly also works.

## Bond identification

Bonds in AJ Bell exports carry `(SEDOL:...)` in the Investment description. `AJBellSippParser.extractBondId` converts these to `"GILT {coupon}% {year}"` format. `PortfolioAggregator.isBond` checks for `%` presence or `GILT` prefix to sort bonds into their own section at the bottom of the Portfolio sheet.

## Cash transaction ingestion

`CashTransactionParser` is a separate interface from `AccountParser` — cash flows and holdings are different concerns. `AJBellCashStatementParser` implements it for `~/Downloads/cashstatements.csv`.

**Ingestion flow in `Main.importCashTransactions()`:**
1. If `cashstatements.csv` is absent from `~/Downloads`, skip entirely
2. Parse → classify rows → resolve symbols → save to `cash_transactions` table in `portfolio.db`
3. If new rows were written: archive the file to `~/Documents/Investing/cashstatements_<date>.csv`
4. If no new rows (file is a duplicate): delete it

**Incremental dedup:** before inserting, `saveCashTransactions` loads all existing `(transaction_date, cash_balance_gbp)` pairs for the account into a `Set<String>`. Rows whose key is already present are skipped. If a known key reappears *after* a new row has been inserted, the import aborts with a data integrity error (gap detected in input file).

**Symbol resolution in `AJBellCashStatementParser`** (three tiers, in order):
1. GILT detection — standard `coupon% ... dd/mm/yyyy` format, or compressed names like `TREASURY2.75L24` / `HM TREA0.2525`; unresolvable redemptions (e.g. `TSY STK25`) borrow the symbol from a same-date DIVIDEND row in a second pass
2. `SYMBOL_RULES` list — ~25 equity/ETF patterns mapped to tickers (longest/most-specific first)
3. `cleanName` fallback — strips share-class suffixes and currency codes

**Row classification:** `TRANSACTION` (purchase/sale/redemption), `DIVIDEND`, `INTEREST`, `CHARGE`, `CONTRIBUTION`. Reversed-format redemption descriptions (`NAME Redemption`) are handled by `PAT_REVERSED_REDEMPTION` before the fallback path.
