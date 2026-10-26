# Position tab


Per-symbol drill-down — Holdings → **Position**. `PositionDetailService` + `GET /position`;
one payload drives the stat tiles, the marked-up price chart, the transactions table and the
FIFO lot tables beneath it.

## Two views of the same ledger, in this order

**"All transactions" is the ledger as dealt; the lot tables are what FIFO makes of it.** One
sell is one row in the first and as many `ClosedLot`s as it consumed in the second — a 70-share
exit against two earlier buys is one transaction and two closed lots. Both are true and neither
substitutes for the other, so the transaction list is **first**: it is the one that reconciles
line-for-line against the broker's own trade history, and checking a statement is what the
screen gets opened for. Every value in it matched AJ Bell's EQQQ export exactly, row for row,
when it was built.

**The price is derived, because the ledger has no price column.** `|amount| ÷ |quantity|` in
the row's own currency, so it carries whatever dealing costs the broker folded into the cash
movement — AJ Bell's £3.50 commission puts our buy price £0.07 above theirs on a 50-share fill
and our sell price £0.07 below. Same figure and same caveat as the trade journal's, which is
why both go through the one rule rather than two (`TradeNotesService.pricePerShare`). The
column's own tooltip says so; "effective price" is not a thing a reader infers from a header.

**Splits and in-specie transfer legs are listed, with no price and no value.** They move shares
without a trade, and the point of listing them is the **Held after** column: leave them out and
the position jumps between two adjacent rows with nothing on screen to explain it. A transfer's
`amount` is the FIFO cost that travelled with the shares (see [transfers.md](transfers.md)), not
cash paid at a market price — printing it under the same **Value** header as a real fill would
state a trade that never happened, so it is `null`, not a number. A split's share count is the
only one shown **signed**: there the sign is the whole fact, where every other row says which
way it went in the Type column.

**Held after is the position across every account**, and the last row's figure is the same
number as the Shares held tile. That is the table's audit: the two are computed by different
code over the same rows, so disagreement is a bug in one of them.

**"Value" in the listing currency is hidden on a sterling line.** It restates "Value (£)" there
— one fact under two headers. It appears the moment any row is in another currency, which on a
symbol held at two brokers means both appear side by side (INTC: `$10,887.00` at II, `£6,590.79`
at AJ Bell). The column is hidden, never removed, so every `cellIndex` the sort runs on stays
put.

## The chart

**A marker under the cursor beats the price line outright, and every marker under it comes
back.** `Chart.Interaction.modes.markerFirst`: any scatter point within its own radius plus
`POSITION_GRAB_PX` is a hit, all hits are returned nearest-first, and the price line is
consulted only when there are none — then by date alone, so running along the line still reads
prices off it. The default `nearest`/`axis: 'x'` handed almost every hover to the price line,
which has a point on *every day*, so the trade you were pointing at was the one thing you could
not get a tooltip for. Returning one marker instead of all of them would only move the problem:
a cluster of same-week fills is the normal case here, not the edge case.

**A marker sits at that day's close, not at the price it was dealt at.** The two differ, and the
tooltip showing the dealt price is what makes that visible, so the panel text says which is
which. On a London line they are also in different *units* — the axis is the listing currency
(`GBp`, pence) and the ledger books in `GBP` — which is a factor of 100 and is stated rather
than silently converted.

**Buy / sell markers are built from the trade list, not from the ledger a second time, and
carry the row index they came from.** Two passes over the same rows could drift apart by one
skipped row, and then a marker's tooltip would describe the trade next to the one it sits on —
and clicking it would scroll to that one too. A dividend marker carries `null`: it is a payment,
not a row in that table.

**Drag-to-zoom is the other half of the overlap fix** — markers that sit on top of each other
across ten years pull apart across ten weeks. The time axis carries **no fixed unit**: it was
pinned to `'year'`, which left a zoomed-in window showing a single tick, which is the one state
zooming exists for.
