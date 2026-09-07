# Excel exports


Every "Download Excel" button writes a date-stamped `.xlsx` to the **output dir (~/Documents)** and
reports the path through the shared `fragments/export :: result` fragment — nothing streams to the
browser's download folder. Four of them:

| Tab | Endpoint | File | Sheet writer |
|-----|----------|------|--------------|
| Holdings → From holdings files | `POST /export` | `portfolio{date}.xlsx` + `Portfolio Summary-{date}.xlsx` | `writeFullReport` / `writeSummaryReport` |
| Holdings → From cash ledger | `POST /sync-from-cash/export` | `Portfolio Ledger-{date}.xlsx` | `ledgerWorkbook` |
| Holdings → Live | `POST /live/export` | `Live Positions-{date}.xlsx` | `liveWorkbook` |
| Holdings → Open orders | `POST /orders/export` | `Open Orders-{date}.xlsx` | `ordersWorkbook` |

**Each export re-derives its view at the moment the button is pressed** (the ledger export re-runs
`sync()`, the Live export takes a fresh `snapshot()` rather than the last 15s SSE broadcast), so a
file is never a stale copy of what the panel happens to be showing.

**The two new sheets mirror their tables rather than reusing the aggregated Portfolio sheet**, for
the same reasons the views differ on screen:
- **Ledger** — one combined value column, the four Annualised-return columns (see that section),
  and crucially **no II SIPP cash input cell**. Ledger cash
  comes from the stored per-`(account, currency)` balances and is already in the rows; the holdings
  workbook's yellow editable cell would be added on top of it. Totals are live formulas, and the
  cash line is a **`SUMIF` on the CASH symbol** because ledger cash rows are interleaved by currency
  section rather than contiguous — that is what keeps "Return on invested" (`gain ÷ (total − cash)`)
  honest when a reader filters rows.
- **Live** — the two things the screen says with colour become their own columns, since a sheet has
  nowhere else to put them: **`Quote`** carries the PRE / AH badge (same convention as the orders
  sheet — the caveat travels beside the price, never folded into it) and **`Market`** carries the
  state that greys a closed listing's row. `Last` stays a real number the sheet can sort and chart.
  `Quote ts` is rendered in Europe/London.

Both set `wb.setForceFormulaRecalculation(true)` — POI writes formula cells with no cached value, so
without it every total opens blank. A view with **no rows** writes its totals as literals instead of
formulas (`SUM` over an empty range is a `#REF!`), and an empty Live snapshot writes **nothing at
all**: an empty sheet of a live view reads as "the portfolio is empty" rather than "no quotes have
arrived yet". `ExportExcelService` owns all of it — the two new methods take the view's own result
rather than re-deriving it, so a second implementation of each view's valuation can't grow next to
the first.

