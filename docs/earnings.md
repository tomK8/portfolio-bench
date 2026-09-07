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

