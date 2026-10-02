# Sync views (Files & Cash ledger) + Portfolio %

- **From holdings files** — `POST /sync` → `fragments/portfolio`. Includes a cash
  reconciliation panel comparing the holdings-side cash (parsed CASH rows + form-supplied
  II SIPP £/$/€, each on its own row in its own currency) against the ledger-side latest stored
  cash balance, with red rows for |diff| > £50 (threshold in
  `SyncPortfolioService.CASH_DRIFT_WARN_GBP`).
- **From cash ledger** — `POST /sync-from-cash` → `fragments/portfolio-ledger`. Slimmer
  table (no separate "Market Value £" / "RT Market Value £" columns — single combined
  value column). Drops positions without an intraday price — except bonds, which fall back to
  the latest `price_history` close (surfaced in a "Daily-close fallback" panel with the close
  date) so a dead gilt feed doesn't drop gilts from the totals. Carries an **Annualised return**
  column the holdings view cannot have (see [cash-ledger-irr.md](cash-ledger-irr.md)) — it needs a
  flow history, not a snapshot.
  A **Download Excel** button (`POST /sync-from-cash/export`) writes
  `Portfolio Ledger-YYYYMMDD.xlsx` to ~/Documents — see [excel-exports.md](excel-exports.md). A **Group by**
  dropdown collapses the rows into expandable groups — see [table-grouping.md](table-grouping.md).
- **A row click opens the shared price popup** — chart + window toggle, momentum, the vol term
  structure, 52-week levels, position/gain and a Fundamentals disclosure — the same `#wl-modal`
  the Live, Watchlist and Open-orders tables open, filled from `GET /watchlist/row?symbol=`.
  Both tables (flat and grouped) mark an instrument row with `data-symbol` in the Thymeleaf
  fragment; the `CASH` rows deliberately carry none and stay inert, and a Cash-ledger **group
  header** carries none either, so it keeps its expand/collapse click. Gilts open like anything
  else. Details, and why `data-symbol` is the only test: [watchlist.md](watchlist.md).
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
