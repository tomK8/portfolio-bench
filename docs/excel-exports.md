# Excel exports


Every "Download Excel" button writes a date-stamped `.xlsx` to the **output dir (~/Documents)** and
reports the path through the shared `fragments/export :: result` fragment — nothing streams to the
browser's download folder. Six of them:

| Tab | Endpoint | File | Sheet writer |
|-----|----------|------|--------------|
| Holdings → From holdings files | `POST /export` | `portfolio{date}.xlsx` + `Portfolio Summary-{date}.xlsx` | `writeFullReport` / `writeSummaryReport` |
| Holdings → From cash ledger | `POST /sync-from-cash/export` | `Portfolio Ledger-{date}.xlsx` | `ledgerWorkbook` |
| Holdings → Live | `POST /live/export` | `Live Positions-{date}.xlsx` | `liveWorkbook` |
| Holdings → Open orders | `POST /orders/export` | `Open Orders-{date}.xlsx` | `ordersWorkbook` |
| Open orders → Ladders block | `POST /ladders/export` | `Ladders-{date}.xlsx` | `laddersWorkbook` |
| Open orders → Switches block | `POST /switches/export` | `Share-Class Switches-{date}.xlsx` | `switchesWorkbook` |

**The two group exports sit outside the "no order files found" guard**, which the orders export is
behind. A ladder can be made entirely of intended rungs and a switch's buy leg is normally an intent
for days, so both blocks have something real to write when `~/Downloads` holds no broker export at
all — and that is the day those records are the only account of what was decided. Their "nothing to
export" therefore means *no groups*, and they pass their own `emptyNote` to the shared fragment
rather than let its default sentence send the reader looking for missing files. The button is
rendered only when the block has a group in it: a Download that can only report nothing is worse
than no Download.

**Each export re-derives its view at the moment the button is pressed** (the ledger export re-runs
`sync()`, the Live export takes a fresh `snapshot()` rather than the last 15s SSE broadcast), so a
file is never a stale copy of what the panel happens to be showing.

**The Ledger and Live sheets mirror their tables rather than reusing the aggregated Portfolio
sheet**, for the same reasons the views differ on screen:
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

**The two group workbooks are several sheets each, because neither panel is a table.** A ladder is a
price axis plus a totals block plus cash broken out per account; a switch is a progress bar, a
two-sided yardstick, a sequence of steps and its own cash lines. Each block of the panel becomes a
sheet whose grain it states, and **the group's name is the leading column of every sheet** — that is
the join, and it is what lets a reader pivot the file back into the cards they saw. Nothing is summed
across groups anywhere, because neither panel sums across them either.

- **`Ladders-{date}.xlsx`** — `Rungs`, `Ladders`, `Cash`. The rungs sheet carries a **`Side of
  market`** column (`Above market` / `Below market`) because the on-screen axis is an *ordering*,
  and a spreadsheet loses an ordering the moment anyone sorts it: the fact the ordering encodes has
  to become a value or it is gone. Blank when the security has no live price — the panel draws no
  marker rather than inventing a position, and neither does the column. **`Lapses before P(30)`** is
  the sheet's version of the amber `~`: the caveat gets a column instead of a cell tint, since the
  probability beside it prices a window the order will not survive.
- **`Share-Class Switches-{date}.xlsx`** — `Switches`, `Yardstick`, `Legs`, `Sequence`, `Cash`. Five
  grains: per switch, per side of a comparison, per leg, per step, per account. The summary sheet
  keeps **three** progress columns, not two — `Sold, not bought back` is the exposure a half-finished
  switch actually carries and is invisible in any view that counts positions — and carries the
  **market cross rate** standing when the file was written, without which the basis reading cannot be
  judged months later. `Yardstick` is its own sheet rather than columns because both legs are
  converted to GBP there and nowhere else, and that conversion is the whole reason the panel can say
  whether the switch buys above where it sells; its `Net` line is written **only when both sides are
  present and priced**, since a net row under two absent sides reads as a comparison that was made.
- **Neither has a formula in it.** The older sheets carry them where a reader edits an input (the II
  SIPP cash cell) and the totals must follow. Nothing here is editable — and a `SUM` down a cash
  column is exactly the netting across accounts both panels exist to prevent, which is why `Cash` has
  `Raises`, `Needs` and a per-line `Net` but no total row.

Both of those set `wb.setForceFormulaRecalculation(true)` — POI writes formula cells with no cached
value, so without it every total opens blank. A view with **no rows** writes its totals as literals
instead of formulas (`SUM` over an empty range is a `#REF!`), and an empty Live snapshot writes
**nothing at all**: an empty sheet of a live view reads as "the portfolio is empty" rather than "no
quotes have arrived yet".

**Every sheet writer lives in `ExcelReportWriter` and returns bytes**; who calls it is whoever owns
the view's own load. `ExportExcelService` drives the holdings, ledger and Live files and takes the
view's result from the caller rather than re-deriving it, so a second implementation of each view's
valuation can't grow next to the first. The three Open-orders files (`export`, `exportLadders`,
`exportSwitches`) are driven by `OpenOrdersService`, which owns `load()` — and they re-load rather
than take a report, because the rungs' `P(10)`/`P(30)` are priced on the stored forecast settings and
a file written off a cached report could carry probabilities from a model the panel is no longer
using. All three share one `writeWorkbook` helper, so the output directory, the date stamp and the
failure message are stated once.

