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
- `domain.model.EarningsEvent` — pure record (date, symbol, companyName, time, marketCap [whole USD],
  epsForecast, numEstimates, lastYearEps). `marketCap`/EPS are native USD (US-market calendar, no FX).
  "Held" is **not** a field — the report ships the held-symbol set and the client tags rows, keeping
  the record a pure feed row.
- `persistence/EarningsCalendarRepository` — cache. Two tables: `earnings_calendar` (row per
  `(date,symbol)`) + `earnings_calendar_fetch` (per-date `fetched_at` + count — the bookkeeping that
  lets a **zero-report day** be cached distinctly from "never fetched"). `save` replaces-per-date in
  one transaction (a company dropped from the roster disappears). Reads degrade (log + empty).
- `application/EarningsCalendarService` — window walk with cache policy: **past** dates cache forever
  (settled roster); **today/future** re-fetch once cache ages past `REFRESH_TTL` (3h — estimates/roster
  still move). `refresh=true` (the Refresh button) bypasses freshness. Live-fetch failure degrades to
  cached rows + a warning, never throws. Window clamped to `MAX_BACK/MAX_FORWARD` (14) so a stray param
  can't fan out. Market-cap floor filters the per-day tables (the "major" filter) but **never**
  `myHoldings`. Held set = `held_symbols` KV (the /sync-persisted holdings); US internal symbols equal
  their US tickers so a direct membership test suffices.
- `web/EarningsController` — `GET /earnings?back=&forward=&holdingsForward=&minCapB=&refresh=`.
  `minCapB` is `$` **billions** (UI select), converted to whole dollars. No backfill/mutation
  endpoints — a pure feed read, independent of the price/cash pipelines (like Open orders). Not
  wired into `PriceFetchSupport` (no Yahoo prices involved).

**Your-holdings section (ranked by position size).** Above the per-day tables, the tab renders
every held position ordered by GBP value, with its portfolio weight beside the date/session/EPS
estimate. `holdingsForward` is a **separate look-ahead** from the daily tables' `forward` (default
21d vs 3d) — browsing the market wants a few days, "when do my own positions report" wants a few
weeks; the day loop scans `max(forward, holdingsForward)` but only `forward` becomes a `Day`.
`MAX_FORWARD` was raised 14 → 45 for this (each extra date is one cached GET; past dates cache
forever, future share the 3h `REFRESH_TTL`).

Position sizes come from `AllocationService.currentPoint()` — the same snapshot the sector block
uses — and **degrade**: on failure the section still renders, unranked, plus a warning.
`HoldingRow.state` is the load-bearing part, separating three things a blank row conflates:
- `REPORTING` — a date was found;
- `NOT_COVERED` — a **non-US listing this US-only feed never carries**, detected by a dotted
  exchange suffix on `YahooTickerMap.tickerFor` (`.L/.PA/.AS/.DE`). This correctly leaves **ASML
  covered** (it maps to itself = the Nasdaq ADR line). Without this state, LGEN (5.0%) and BNP
  (4.6%) — two of the largest single-name positions — were indistinguishable from names with
  nothing due.
- `NONE_SCHEDULED` — covered, nothing due in the window.

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
  last-known on a feed outage. 


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
- **Its coverage is wider than the Earnings tab's**, which is why the two do not merge. LGEN.L,
  BNP.PA and TSCO.L all answer here; the US-only Nasdaq feed reports them `NOT_COVERED`, and the
  holdings section of the Earnings tab still does. Funds and bonds answer with nothing, and
  nothing is what gets stored — **absent, not a zero date**.
- **Estimated is not announced, and the display says which.** Yahoo's `isEarningsDateEstimate`
  separates a day it inferred from the past reporting rhythm from one the company published; an
  estimated day can move by weeks. The cell carries a trailing `~` for it — the same
  "not confirmed" marker the popup's high/low cells use for a close-only extreme — and a
  two-element `earningsDate` array is a window Yahoo has not narrowed to a day at all, printed as
  both ends. Showing either as a plain fixed date would be the same mistake as absorbing a
  figure we could not value.
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

`fundamentalsFieldGroups()` in `dashboard.js` stays the single home: the field tuple grew a
seventh slot, `source`, naming which bag on the row holds the value (`'labels'` here, the numeric
`extra` map by default), and `fmtSnapValue` grew a `date` type. That is all the picker, the popup
and the Snapshot-diff modal needed — the watchlist column `f:earningsDate` came with them. Its
cell carries the day's own epoch as `data-sort-value`, because `parseFloat` on an ISO day reads
the year alone and would shuffle a whole quarter together.
