# What a print meant

The second half of the [reminders](reminders.md) tab's *Just reported* section: not whether a
company beat, but whether the print **mattered**. `GET /reminders/reported` →
`EarningsPrintService.PrintReport`, rendered into the same table as the beat/miss columns.

Beat-or-miss is the smallest thing a print says. A 3% beat is routine for a name that beats by 3%
every quarter; a 2% miss that takes 6% off the price is a different event from one the market
ignored; and an EPS beat on revenue that came in short is a sentence about where the beat came
from. Those are the questions this answers.

## It is a separate request, and that is the safety property

`ReminderService.report()` is read by `ReminderJob` **every half hour** to decide what to mail.
None of this is a reminder — it fires nothing, mails nothing, and has no dedup key — so none of it
is on that payload. A second endpoint keeps price history, position valuation and the resting-order
read off the mail path entirely, and keeps the job's cost exactly what it was.

It is the same division the section itself already has ([reminders.md](reminders.md): *on the
screen, but not a reminder*), applied one level down. The tab degrades to the plain beat/miss
figures if the detail call fails, so a feed outage costs the extra columns and not the section.

## The price reaction

**One session, chosen by when the news could first be traded.** A print is a moment, not a day:

| Session | Window |
|---|---|
| `BMO` — before the open | previous close → the print day's close |
| `AMC` — after the close | the print day's close → the next close |

- **A session the feed did not state is not a session to guess.** A BMO window applied to an AMC
  print measures the day *before* the news. The row reads **no session** instead.
- **The anchors are trading days this instrument actually has.** `printDate.minusDays(1)` is a
  Sunday one week in five, and a London line and a New York one do not keep the same holidays, so
  the window is walked over the keys of the price series itself.
- **`not yet` is a state.** For an after-the-close print read the next morning — the ordinary case
  this whole section exists for — the session that will carry the reaction has not closed. That is
  not the same as having no prices, and it resolves itself.
- **It is measured on the listing you hold**, through `YahooTickerMap` like everything else. AZN
  reports on the US calendar and is held in London; the London close is what the position is valued
  at, and the session rule carries over unchanged (a US pre-market release is before the London
  close; one after the US close is after everything).

**Net of the market, over the identical two dates.** `vs mkt` subtracts an index's move over the
same pair of days, never a nearby pair — a market-relative figure built from two different windows
is a difference of windows, not of returns, and on an AMC print that is most of the number. Where
the index has no close on one of the two days the figure is withheld.

The index is chosen per listing from a **two-entry** map (US → `SPY`, `.L` → `ISF.L`) and every
other market is **withheld and named**. This is a definition, not data — no feed publishes "which
index is this listing's market" — and a wrong index would turn the column into a difference between
two unrelated markets while still printing a number. The index tickers join
`PriceFetchSupport.tickersToFetch` so the price job keeps them warm; leaving that to whichever
other feature happens to want `SPY` would make this column vanish the day that feature changed.

**`× usual` is a median of the name's own prints, and it is withheld when thin.** The multiple is
`|move| ÷ median(|move|)` over the same company's previous prints, each measured by the identical
rule. Median, not mean, so one quarter the company blew up does not set the scale. Below **three**
previous prints there is no baseline and the cell is withheld **with its reason** — a usual move
derived from one or two quarters is not a usual move, and `×2.4` beside an n of 1 dresses a single
comparison up as a distribution. The count and the median are always on the tooltip, so the cell
can say what it is divided by.

### Where a previous print's session comes from

The baseline needs each earlier print's **date and session**, and the date alone is not enough.

- **Dates** come from Nasdaq's per-company history (`/api/company/{sym}/earnings-surprise`), which
  states the day each of the last four quarters was reported. They accumulate in
  `earnings_print_history`, so the baseline grows a quarter at a time instead of being permanently
  four deep.
- **Sessions** come from Yahoo's `earningsChart.reportedDate`, which is an *instant*, read on the
  **market's clock**: before 09:30 New York is `BMO`, at or after 16:00 is `AMC`, and **inside the
  session is nothing** — a stamp between the bells is neither window, and far more often means the
  feed does not know the time.
- **And a company whose surviving stamps claim both sessions is not trusted at all.** Novo
  Nordisk, which releases at 07:30 Copenhagen every quarter, is stamped 01:30, 12:47, 22:06 and
  17:23 New York across four of them. A real change of reporting habit exists and is rare; the cost
  of refusing one is a missing baseline on that name, and the cost of accepting Novo's is a measured
  "usual move" built from windows pointing the wrong way, which is the mistake that looks like a
  finding.
- The **current** print's session is the calendar's own `BMO`/`AMC`, never an inferred one, and it
  is written into the history so next quarter it is a previous print with the strongest marker
  available.

## The £ effect, and the resting orders

**£ effect** is the position's own GBP value times the price move — deliberately the price move and
not a revaluation between two days, because a day's currency move is not the print's doing. Blank
for a name you watch but do not hold, which the tooltip says.

**Limits** is how far each resting order sat from the price before the print and after it, signed as
**the move still needed to reach it** — the watchlist's convention, so a figure with the wrong sign
for its side is one the price has already gone through. The cell shows the nearest limit (by the
distance still to travel) and the rest are on the tooltip.

Resting orders are read straight from `order_history` (`closed_at IS NULL`) rather than through
`OpenOrdersService.load()`. That call re-parses the broker exports, fetches FX, prices every row —
and **records order history as a side effect**, which a different tab must not do.

## Revenue: captured before the print, because afterwards nobody publishes it

The EPS consensus a print was measured against survives in Nasdaq's per-company history, so the
beat/miss on the row is always reproducible. **Revenue is not like that.** No free source publishes
"what revenue was expected of the quarter just reported": Yahoo carries one set of estimates per
company for its *next* report and rolls them forward within hours of the print, after which the
number under that company's name belongs to the following quarter.

So it is taken while the date is still ahead and kept. `EarningsPrintFetchJob` runs two-hourly and
captures every print inside a **fortnight** window; `earnings_print` holds it per
`(symbol, print date)`.

**The one test that makes the figure safe is that Yahoo still names that date.** Measured on
2026-11-03: VRTX's block still said 2026-11-02 and carried the quarter just reported, while AAPL's
had moved to 2027-01-28 and PLTR's to the following January. Read without checking, PLTR's $2.68bn
would have been set against the $2.31bn it had just reported and published as a **14% revenue
miss** on a quarter it did not describe. Nothing is inferred from how plausible the numbers look
beside the reported ones — that is the heuristic [fundamentals.md](fundamentals.md) rejects for
BRK.B's P/B, and it would have to be wrong about PLTR, whose rolled-forward figure sat a believable
distance away.

**Four states, because one blank cell would say all of them**: the figures are in; the print has
happened and the revenue has not reached us (`awaiting`); the revenue is in but no consensus was
ever captured (`no consensus` — **not** a shortfall, and unrecoverable afterwards at any price); or
the print has not been looked up yet (`not fetched`). The surprise divides by the **magnitude** of
the consensus, like the EPS one beside it.

Revenue actual comes from Yahoo's `earnings.financialsChart`, joined to the quarter whose
`earningsChart.reportedDate` matches the print — never "the newest row", which would mislabel a
company that reported twice inside the window. Both sides are Yahoo's statement-side figures in
`financialCurrency`, so the ratio is within one currency; the currency travels with the figure
(RHM in EUR, NVO in DKK).

**EPS and revenue are deliberately not from the same source**, and never mixed. Nasdaq's per-company
EPS reproduces the calendar row exactly (AAPL 2.05/1.98, AZN 2.79/2.60, VRTX 4.21/4.36) while Yahoo
states 2.10, 2.79 and 4.76 on its own adjusted basis. A "surprise against the company's own last
four quarters" assembled from both would compare this quarter's beat with last quarter's measured a
different way. Revenue is Yahoo throughout; EPS is Nasdaq throughout.

## Consensus since the print

Nasdaq's `/api/analyst/{sym}/earnings-forecast` states the forward consensus **by named fiscal
period**, with the number of estimates and the analysts who revised up and down over its own
four-week window. It publishes no history of the level, so a percentage change needs two readings
and the earlier one has to be taken.

`earnings_consensus_capture` holds one dated reading per symbol per period. The change is against
**the last reading strictly before the print**, with that date on the row — never "a month ago",
which on a four-week revision window would be a different question answered in the same column.
Where there is no earlier reading the change is absent and the **counts still stand on their own**:
a count is not a difference. It fills in for prints from here on; it cannot be backfilled.

The row reports the **nearest fiscal quarter and the nearest fiscal year**, each named, so what the
percentage is about is on the screen rather than inferred.

**Yahoo answers all of this too, and was rejected for it.** Its `earningsTrend` module puts one
period's consensus in another period's block (PLTR's `0q`, labelled with the quarter just reported,
carries the *next* quarter's estimate), stamps a stale `endDate` on it, publishes a literal `0.0`
where it has no historical point (AZN's 30-, 60- and 90-day readings), and returns revision counts
that contradict their own arithmetic (PLTR: 13 upgrades in the last 7 days inside 1 in the last 30).
Every one is a failure this codebase already has a name for — a vendor figure that disagrees with
the figures beside it, and a measured zero standing in for "we do not know".

## Context

- **Fiscal quarter** — the feed's own label (`Sep 2026`), kept as text because it is one: the
  quarter a company calls its fourth is not derivable from the month it ends in. Taken from the
  history row the feed itself dates to this print.
- **Est.** — `EarningsEvent.numEstimates`, already cached and simply never shown before. A beat
  against three estimates and a beat against thirty are not the same statement.
- **EPS YoY** — the actual against the year-ago figure the calendar already carries. **Withheld
  where that quarter was a loss or a zero**: a move from −0.40 to +0.10 is a real and important
  change and there is no percentage of it. "Returned to profit" is a sentence, not a growth rate.
- **vs own** — the median of this company's own recent surprises, each **recomputed** from the two
  figures that quarter reported, never the vendor's own column ([earnings.md](earnings.md)).

## There is no guidance column

The brief asked for next-quarter and full-year guidance, against consensus and against prior
guidance. **It is not here, and the screen says so.**

What a company guides lives in its press release and on its call. No free, structured source
publishes it — the paid estimate vendors do, and reading the prose out of an 8-K exhibit is the
fragile scraping this was explicitly not to do. More decisive: a company that gave **no** guidance
and one whose guidance we failed to extract would look identical in that column, and a blank that
means two opposite things is the one thing this screen is built not to print. The brief itself
asked for "gave no guidance" to be explicit, and that is exactly the statement that cannot be made.

*Consensus since the print* is the closest honest substitute: it is what the analysts did after
hearing the guidance.

## Self-limiting by content

`EarningsPrintService.refresh()` skips every print whose detail it already holds, so a pass outside
results season is a few SQLite reads and nothing else — the same argument that lets
`earningsBackWarm` ride a dumb timer rather than knowing when earnings season is.

The one thing that is **not** content-bounded is a feed with nothing to say, which would otherwise
be asked on every pass forever. `earnings_print.attempted_on` bounds that to **once a day** — dated
rather than a flag, so a feed that catches up tomorrow is still tried tomorrow: "we asked and got
nothing" is a fact about today, not about the company.

**Writes merge, never replace.** The two halves of a print row arrive on opposite sides of the
event — the consensus days before, the revenue days after — so every update coalesces. A blind
`INSERT OR REPLACE` would discard the pre-print consensus the moment the revenue arrived, which is
the one figure that cannot be fetched again.

## Storage

Three tables, none of them in [BackupService.TABLES](backup.md) — every row is re-fetchable except
the captures, and those are a convenience that rebuilds itself over a quarter rather than something
a restore must carry.

| Table | Key | Why it exists |
|---|---|---|
| `earnings_print` | `(symbol, date)` | the fiscal quarter, revenue reported, and **the consensus that print was measured against** — perishable, captured while the date is still ahead |
| `earnings_print_history` | `(symbol, reported_date)` | previous prints, so the baseline grows past the four quarters any one fetch returns |
| `earnings_consensus_capture` | `(symbol, captured_on, period_kind, fiscal_end)` | dated readings of the forward consensus, so "since the print" has an earlier endpoint |
