# Earnings calendar


Read-only earnings calendar (Earnings tab, under Tools). `GET /earnings` → JSON, rendered client-side
as one table per day over a `[today−back, today+forward]` window (default 2/3). Unlike the EDGAR
filing-date proxy behind Swing §D (`EarningsCalendarProvider`, backward-looking "which past dates did
symbol X file"), this is a **forward-looking, market-wide** calendar with consensus EPS estimates —
deliberately a **separate port** (`port/EarningsCalendarFeed`, `forDate(LocalDate)`).

- `port/EarningsCalendarFeed` — `forDate(date)` returns `List<EarningsEvent>`; **three-valued**: a
  non-null (possibly empty) list is a successful read (`[]` = no reports, e.g. a weekend), `null` =
  fetch failure. The service needs that distinction to avoid caching an empty roster over good data.
- `adapter/NasdaqEarningsFetcher` — one GET per date to `api.nasdaq.com/api/calendar/earnings?date=`.
  Browser-UA (default Java UA gets 403, same as `GiltPriceFetcher`). Jackson;
  `parse(JsonNode,date)` is a static, unit-tested pure function. All feed fields are display strings
  (`"$665,246,306,497"`, `"$7.98"`, `"($0.11)"`) → parsed to real numbers (`parseNumber` treats
  `(x)` as −x, strips `$ , %`; `"$0"` is a real zero, blank/`N/A` → null). `time` normalises
  `time-pre-market`→`BMO` / `time-after-hours`→`AMC` / else `TBD`. Rows without a symbol are dropped.
  Reads **both row shapes** without being told which (see below): every field is taken where it
  appears and left null where it is not, so a settled row yields an `actualEps` and no session and a
  scheduled row the reverse. `surprise` is deliberately **not** parsed.
- `domain.model.EarningsEvent` — pure record (date, symbol, companyName, time, marketCap [whole USD],
  epsForecast, numEstimates, lastYearEps, actualEps). `marketCap`/EPS are native USD (US-market
  calendar, no FX). `actualEps` is what the company reported and exists **only once the date has
  passed**, so `reported()` is the test for "a report that has happened" rather than one booked.
  The **surprise is not a field** — it is a function of two others, and the feed's own figure was
  rejected (below). "Held" is **not** a field either — the report ships the held-symbol set and the
  client tags rows, keeping the record a pure feed row.
- `persistence/EarningsCalendarRepository` — cache. Two tables: `earnings_calendar` (row per
  `(date,symbol)`) + `earnings_calendar_fetch` (per-date `fetched_at` + count — the bookkeeping that
  lets a **zero-report day** be cached distinctly from "never fetched"). `save` replaces-per-date in
  one transaction (a company dropped from the roster disappears) — which is exactly why a settled
  re-fetch has to be merged before it is handed over. `actual_eps` was added by `ALTER TABLE`; every
  pre-existing row is null, which reads correctly as "scheduled, no actual known". `latestPerSymbol`
  is the backward mirror of `earliestPerSymbol`. Reads degrade (log + empty).
- `application/EarningsCalendarService` — window walk with cache policy: a **past** date caches
  forever once `settled` (its rows carry actuals, it has no rows, or it has already been asked after
  it passed — see below); **today/future** re-fetch once cache ages past `REFRESH_TTL` (3h —
  estimates/roster still move). `refresh=true` (the Refresh button) bypasses freshness. Live-fetch failure degrades to
  cached rows + a warning, never throws. Window clamped to `MAX_BACK/MAX_FORWARD` (14) so a stray param
  can't fan out. Market-cap floor filters the per-day tables (the "major" filter) but **never**
  `myHoldings`. Held set = `held_symbols` KV (the /sync-persisted holdings); US internal symbols equal
  their US tickers so a direct membership test suffices.
- `web/EarningsController` — `GET /earnings?back=&forward=&holdingsForward=&minCapB=&refresh=`.
  `minCapB` is `$` **billions** (UI select), converted to whole dollars. No backfill/mutation
  endpoints — a pure feed read, independent of the price/cash pipelines (like Open orders). Not
  wired into `PriceFetchSupport` (no Yahoo prices involved).

## The feed answers in two shapes, and flips per date

The same `?date=` GET returns a different row shape depending on whether that date has passed, and
it flips **the whole roster at once, not row by row** — a company that reported before this
morning's open still shows no actual until tomorrow.

| | A date still ahead (today included) | A date that has passed |
|---|---|---|
| `epsForecast`, `noOfEsts` | yes | yes |
| `time` | `time-pre-market` / `time-after-hours` | always `time-not-supplied` |
| `lastYearEPS`, `lastYearRptDt` | yes | **gone** |
| `eps` (what was reported), `surprise` | — | yes |

Three consequences, each of which is a rule somewhere in the code:

- **A past date is no longer cached forever unconditionally.** It used to be ("a settled roster
  cannot change"), and that was right about the roster and wrong about the figures: dates are
  normally first fetched *while still ahead* — that is what `warmForward` and the forward window do
  — so caching the first answer for good meant the actual figures could never arrive for exactly
  the dates whose reports had happened. `EarningsCalendarService.settled` replaces it: a past date
  is finished with when its rows **carry actuals**, or it has **no rows** (a weekend publishes none,
  now and forever — without this clause every past weekend in the back window re-fetches on every
  load), or **we already asked it after it passed**. That third clause is what bounds the retry: the
  feed's answer stands, including "still nothing", so a date it never settles is not re-fetched on
  every page load. "After it passed" is judged in **US Eastern**, the feed's own clock — 00:30 in
  London is still the previous evening in New York, and judging it locally would permanently mark a
  date asked-after-settlement and lose that day's figures for good.
- **A settled re-fetch is merged, never swapped in.** The cache replaces per date on purpose, so a
  company dropped from a roster disappears — which means a straight save of a settled roster would
  also erase the `BMO`/`AMC` marker from every row of a date already fetched. That is not cosmetic:
  the session is the difference between "reported before this morning's open" and "reports after
  tonight's close", which is the whole reason to look at a settled day. `mergeSettled` keeps the
  live roster authoritative about membership, the actual and the final consensus, and takes from the
  cached row only a field the live one leaves empty. It is keyed on **symbol, not position** —
  both rosters are ordered by market cap and a settled one can reorder.
- **The surprise is recomputed, never read.** The feed publishes its own `surprise` column and it
  cannot be used. Across 481 settled rows sampled it contradicted the two numbers printed beside it
  twice — **MAA** reported 2.10 against a 2.10 consensus and was labelled −65.71%, **VKTX** −1.55
  against 1.00 was labelled −55% where the arithmetic gives −255% — and on **31** more it was simply
  absent because the company had met consensus to the cent, publishing a real zero as a blank. Both
  are failures this codebase already has a rule for: a vendor ratio that disagrees with its own
  inputs is the [P/B case](fundamentals.md), and a measured zero rendered as "unknown" is the
  inverse of "absent is never zero". `EarningsCalendarService.surprisePct` divides, from the two
  figures the row shows, so the division is reproducible on the row.
  - **Divided by the magnitude of the consensus, not its signed value.** BA was expected to lose
    0.21 and lost 0.34 — worse, so negative. Dividing by −0.21 would report +61.9% and file a red
    quarter under "beat".
  - **Null when there is nothing to divide**: no actual, no consensus (a figure no analyst covers
    is not a beat of the whole amount), or a consensus of exactly zero, where the difference is
    real but no percentage of it exists.

`warmBack` is what asks the question nobody else would. The Earnings tab's own back window covers
the two days it shows, but the [reminders](reminders.md) screen has no fetch of its own — it is read
by a half-hourly job — so without it the figures would reach only someone who happened to browse the
calendar. It is **self-limiting rather than carefully scheduled**: `settled` is content-based, so the
whole pass costs at most a handful of requests once and nothing on every run after that, which is
what makes a plain six-hourly timer (`PriceFetchScheduler.earningsBackWarm`) the right shape instead
of something that has to know when earnings season is.

**Your-holdings section (ranked by position size).** Above the per-day tables, the tab renders
every held position ordered by GBP value, with its portfolio weight beside the date/session/EPS
estimate. `holdingsForward` is a **separate look-ahead** from the daily tables' `forward` (default
21d vs 3d) — browsing the market wants a few days, "when do my own positions report" wants a few
weeks; the day loop scans `max(forward, holdingsForward)` but only `forward` becomes a `Day`.
`MAX_FORWARD` was raised 14 → 45 for this (each extra date is one cached GET; past dates cache
forever, future share the 3h `REFRESH_TTL`).

Position sizes come from `AllocationService.currentPoint()` — the same snapshot the sector block
uses — and **degrade**: on failure the section still renders, unranked, plus a warning.

**Two sources, in order of strength.** The market calendar is asked first — it is the only one
carrying a session and a consensus estimate, and the only one authoritative about the window. When
it has nothing, the **fundamentals snapshot's cached `calendarEvents` date** answers instead (see
the section below), read straight out of the `loadAll()` map `holdingRows` already loads, so the
fallback costs no query and no fetch. `EarningsCalendarService.holdingState` is that precedence,
pure and unit-tested; `HoldingRow.state` is the load-bearing part, separating four things a blank
row conflates:
- `REPORTING` — the calendar has it inside the window, with session and estimate;
- `EXPECTED` — the calendar has nothing, the snapshot has a date still ahead. Deliberately not a
  second kind of `REPORTING`: it is usually **past the window**, and it comes with no session and
  no estimate, so the table leaves those cells blank rather than letting the weaker source borrow
  the stronger one's geometry;
- `NOT_COVERED` — neither source has a forward date **and** the listing is one this US-only feed
  never carries, detected by a dotted exchange suffix on `YahooTickerMap.tickerFor`
  (`.L/.PA/.AS/.DE`). This correctly leaves **ASML covered** (it maps to itself = the Nasdaq ADR
  line). Without this state a large UK/EU position with an unknown date is indistinguishable from
  one with genuinely nothing due;
- `NONE_SCHEDULED` — covered, nothing due in the window, and nothing cached either.

**The fallback applies to both silences, not only the non-US one that prompted it.** Before it,
39 of 42 rows here were blank — 22 US names the feed covers but has nothing due for inside the
look-ahead, 17 non-US listings it cannot see at all — and the snapshot has a forward date for 32
of the 42. Dating BARC at 2.5% while leaving GOOG at 5.7% blank would draw a line the data does
not: the cached date exists either way, and what keeps them apart is the `state`, not the absence
of a row.

**A passed snapshot date does not make a row `EXPECTED`.** Yahoo leaves the last date it knows in
place until it learns the next one, which is the ordinary state of a UK half-year reporter — six
held names on the day this shipped. That is where the rhythm was, not when the company next
reports. The date still travels on the row (`expectedDate`) and the strip prints it as
*last 2026-07-30*, because "last reported in July" is a different silence from "we have never had
a date" — the same reason the four states exist.

**No warning for the `NOT_COVERED` rows.** The section names every one of them in its own strip,
with weight and last-known date, which is strictly more than a count could say. A second banner
repeating it would blunt the warnings area, where everything else is an actual failure.

**`companyName` comes from whichever source dated the row** — `EarningsEvent.companyName` for a
calendar row, `labels.companyName` (Yahoo `price.longName`, `shortName` as fallback) for a snapshot
one. Without the second the Company column was 32 dashes: the market calendar is not there to
supply a name for a row it never dated.

Funds and bonds are **dropped entirely** (no earnings to report). Funds are identified by a
non-empty cached `sectorWeights` (the same `topHoldings` data the sector block uses) — needed
because every held ETF has a `.L` ticker and would otherwise be mislabelled "not covered".

## Panel overview

- **Earnings** — `GET /earnings` → JSON, rendered as per-day tables under the Tools group. A rolling
  earnings calendar (default 2 days back, 3 forward) from Nasdaq's public JSON feed: each day lists
  every company scheduled to report with its session (BMO/AMC/TBD), market cap, consensus EPS
  estimate + estimate count and last year's same-quarter actual. A market-cap floor filters to the
  "major" names; the user's own holdings (the `held_symbols` KV set from the last /sync) are
  highlighted and pulled into a summary strip regardless of size. Forward-looking with estimates —
  the half the EDGAR filing-date proxy (Swing §D) can't serve. Rosters are cached and degrade to
  last-known on a feed outage. The **Your holdings** table above the calendar ranks every position
  by size and dates it from the calendar where it can and from the cached fundamentals snapshot
  where it cannot, in two labelled groups that share one column geometry. 


## The next-earnings date on the fundamentals snapshot

The Fundamentals tab, the price popup's Fundamentals block and the watchlist column picker all
show **one** date: the company's next scheduled results day. It is a third earnings source and
deliberately not either of the two above — here is the whole map, because the three answer
different questions and are wired to different feeds:

| Source | Direction | Universe | Lives in |
|---|---|---|---|
| `EarningsCalendarProvider` (EDGAR) | backward — "which past dates did X file?" | SEC filers | `earnings_dates`, Swing §D |
| `EarningsCalendarFeed` (Nasdaq) | forward — "who reports on day D?" | US market-wide | `earnings_calendar`, Earnings tab |
| `calendarEvents` (Yahoo quoteSummary) | forward — "when does X next report?" | whatever Yahoo lists | `fundamentals_snapshot.labels_json` |

- **It costs no request.** `calendarEvents` is one more module on the quoteSummary URL the
  fundamentals refresh already fetches per symbol, so the date arrives with the P/E and the margins
  and lands in the same cached row. Every reader is a SQLite read; nothing on a render path waits
  on Yahoo for a date, which is the whole reason it is not wired to the Nasdaq feed instead.
- **It rides in `labels`, not a column.** `earningsDate`, `earningsDateEnd`, `earningsDateEstimate` —
  three keys in the flat string blob `sector` and `industry` already share, so there was no
  migration and `col_key` stayed opaque to the server (the bargain `extra_json` already strikes,
  see [watchlist.md](watchlist.md)). Nothing queries on the date, so a column would buy nothing.
- **Its coverage is wider than the Nasdaq feed's**, which is why the two do not merge and why the
  holdings section above reads both: LGEN.L, BNP.PA and TSCO.L all answer here, and the US-only
  feed reports them `NOT_COVERED`. Funds and bonds answer with nothing, and nothing is what gets
  stored — **absent, not a zero date**.
- **Estimated is not announced, and the display says which.** Yahoo's `isEarningsDateEstimate`
  separates a day it inferred from the past reporting rhythm from one the company published; an
  estimated day can move by weeks. The cell carries a trailing `~` for it — the same
  "not confirmed" marker the popup's high/low cells use for a close-only extreme — and a
  two-element `earningsDate` array is a window Yahoo has not narrowed to a day at all, printed as
  both ends. Showing either as a plain fixed date would be the same mistake as absorbing a
  figure we could not value.
- **A date already behind us is set in muted italics, not labelled.** When Yahoo has no forward
  date it leaves the most recent one in place — 7 of the 58 held names on the day this shipped,
  UK half-year reporters especially — and printing that under "Next earnings" in the same face as
  a forward date states something false. The styling is the whole marker: the earlier `(passed)`
  spent a third of the column's width again on a word, in a table where the label already sets
  the width (see [watchlist.md](watchlist.md)). The date itself stays rather than being blanked,
  for the same reason the holdings strip prints *last 2026-07-30* — "reported in July, nothing
  scheduled since" and "this name never reports" are different silences, and a blank cell tells
  them apart from neither. It replaces the estimate `~` rather than joining it: a date already
  behind us is not a date whose firmness is still the question. `fmtEarningsDate` therefore
  returns HTML, and `earningsTd` sets it as `innerHTML`.
- **The day comes from Yahoo's `fmt`, not from `raw`.** The raw epoch is the scheduled *instant*:
  MRVL's `1796155200` is 20:00 UTC, an after-the-close US report on 1 December, and a report
  scheduled late enough in the US evening falls on the following UTC date. Yahoo's own label
  already names the trading day — the same anchor-on-the-trading-day rule the
  [Live tab](live-tab.md) applies to Day Δ. `raw` is only the fallback for a payload with no label.
- **It is as fresh as the snapshot**, i.e. the 4-hour `FundamentalsFetchJob` cycle. Between a
  company reporting and the next refresh the stored date is the one just passed, since Yahoo only
  rolls it forward on its side; it corrects itself, and **Refresh** on the Fundamentals tab forces
  it. That staleness is the price of never blocking a render on the network, and a date that
  moves on a scale of weeks can afford it.

## A third reader: the reminders tab

`EarningsCalendarService.nextEarnings(symbols, lookahead)` answers "when does each of these next
report" for the [reminders](reminders.md) job, over the union of held/watched/ordered symbols rather
than just the held ones. It is the **same precedence** as `holdingState` above — calendar first,
snapshot second, a passed snapshot date dropped — deliberately reusing this service rather than
growing a second answer to the same question elsewhere.

Two things make it different from `report()`. It **reads only the cache**: no fetch, no window walk,
because it is called from a half-hourly background job and a hundred symbols behind a live feed would
be a hundred HTTP calls for a fact that changes once a quarter. And it returns a `Lookup` of **two
lists**, `dated` and `undated`, instead of a map with holes — funds and bonds belong to neither, so
only this method can tell the silence that is an answer from the silence that is a gap, and handing
the caller both lists is what stops every gilt in the ledger being reported as an undated company.
`EarningsCalendarRepository.earliestPerSymbol` is the one-query window read behind it.

`recentlyReported(symbols, lookbackDays)` is its **backward** counterpart, behind the reminders tab's
*Just reported* section, and deliberately a second method rather than a fourth outcome of the
forward one: "when does this next report" and "did this just report" are two questions, the forward
lookup's contract is already a careful three-way split, and folding a past report into it would mean
a row that is neither `dated` nor `undated` for every existing caller to learn to ignore — the same
reason the orders panel keeps *open* and *resting* apart. It reads the cache only, for the same
reason. **Only the market calendar answers here**: the snapshot holds one date per symbol and leaves
the last one it knows in place, so it cannot tell "reported on Tuesday" from "last reported in July"
and carries no figures to judge either way. `latestPerSymbol` is its one-query read — latest, not
earliest, because a company that reported twice inside the window is being asked about the print just
gone. **A row with no actual is still returned**: inside a window of dates that have all passed, that
is a report that has happened whose figures have not reached us yet, which is a different thing from
not having reported, and only the screen can say which.

`fundamentalsFieldGroups()` in `dashboard.js` stays the single home: the field tuple grew a
seventh slot, `source`, naming which bag on the row holds the value (`'labels'` here, the numeric
`extra` map by default), and `fmtSnapValue` grew a `date` type. That is all the picker, the popup
and the Snapshot-diff modal needed — the watchlist column `f:earningsDate` came with them. Its
cell carries the day's own epoch as `data-sort-value`, because `parseFloat` on an ISO day reads
the year alone and would shuffle a whole quarter together.
