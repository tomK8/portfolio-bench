# Live tab: sessions, market state, Day Δ


`GET /live` + `GET /live/stream` (SSE) → `LivePricesService.snapshot()`. Per-position latest quote,
day change, trailing 5d…6M columns and totals. `POST /live/export` writes the same table to Excel
(see [excel-exports.md](excel-exports.md)). A **Group by** dropdown collapses the rows by sector — see [table-grouping.md](table-grouping.md)
above; the groups ride on every snapshot because the SSE stream is one broadcast for all clients.

**Extended hours are stored, tagged, and opt-in.** `YahooPriceFetcher`'s intraday URL now carries
`includePrePost=true`, so a US name has a live pre-market quote at 07:00 London instead of freezing
on last night's close. The extra minutes are **labelled, never blended**:
- `price_intraday` gained `session` (`PRE`/`REGULAR`/`POST`) and `session_date` columns. Rows
  written before them are NULL and read as `REGULAR` by `MarketSession.fromDb` — everything stored
  up to then *was* regular-session, so that is the truthful reading, not a default.
- **`loadLatestIntradayPrices` filters to regular only**, so every pre-existing reader (portfolio
  valuation, both sync views, reconciliation, watchlist alerts) keeps the semantics it was written
  against. A thin 200-share pre-market print is a fine thing to look at and a bad thing to value a
  portfolio or fire an alert on. `loadIntradaySeries` filters too — pre/post minutes
  would inflate `PriceStats.annualizedVolIntraday` with spread noise and draw the overnight gap as
  a run of intraday moves on the popup chart.
- **Two panels opt in via `loadLatestQuotes`** (newest bar of any session): the Live tab, which
  renders a `PRE` / `AH` badge beside the price, and **Open orders**, where a resting limit is
  judged against the newest price there is — see [open-orders.md](open-orders.md) for the quote-age
  bound that pairs with it. Both label the session rather than blending it in.
- Classification comes from the feed's own `meta.tradingPeriods` — three arrays **zipped by day
  index**, which is the only way to give a pre-market minute the trading day of the session it
  *precedes* once a US after-hours session crosses midnight UTC. Half-days and holidays are right
  for free. The trading day is the local date of the regular open, derived from that window's own
  `gmtoffset` — no timezone database consulted.
- **The regular window is tested first and inclusive of both ends.** The closing auction prints at
  exactly the second the regular window ends *and* the post window begins; labelling it `POST`
  would hide the official close from every regular-hours reader and stop the portfolio being able
  to value a position from its own closing price.

**Day Δ is anchored on the quote's trading day, not the calendar day** — this is the whole
correctness of the column. A US listing viewed from London at 09:00 has a newest quote belonging to
*yesterday's* US session, so "the close before calendar yesterday" is that same session's close and
the column compares a price against itself. Worse, `price_history` can hold a **partial** bar for
that session: `PriceFetchJob` re-fetches the latest stored day precisely because Yahoo reports an
in-progress session's current price as `close`, and it relies on the 22:00 cron to overwrite it. On
2026-08-20 that cron had not run, so MRNA — up 177% the previous session — read **+12.13%**: the
real 19 Aug close (174.38) over a 13:33 snapshot of the same day (155.51). `previousCloses()` groups
tickers by `IntradayPrice.tradingDay()` and asks for the close before *that*, which fixes both
halves at once. It is two or three bulk queries per tick (a London morning has US names on one
session and LSE names on another), not one per position.

**Market state per row** (`OPEN`/`PRE`/`POST`/`CLOSED`/`UNKNOWN`) drives the row shading — a closed
listing is dimmed so last night's close reads as settled data rather than a live quote that has
stopped moving. The authority is `MarketHoursCache`: `meta.currentTradingPeriod`, captured by
`IntradayPriceFetchJob` from a response it already makes (zero extra HTTP), keyed by ticker, held
**in memory on purpose** — a calendar that survived a restart would be a day stale, which is exactly
the input that makes a closed-market badge lie, and entries older than `STALE_AFTER` (6h) are
treated as absent so a laptop that slept through the weekend can't assert Friday's windows against
Monday's clock. With no usable calendar the service falls back to quote age, and the window is a
generous 60 min: Yahoo writes a 1-minute bar only when something trades, so a tight threshold keeps
declaring open markets shut (the same trap the Open-orders staleness rule hit with XWHS.L).
`renderQuoteTs` skips its amber/red staleness colouring for a `CLOSED` row — out of hours every
quote is hours old and none of them is a problem.

**`spring.task.scheduling.pool.size=4`** is load-bearing, not tuning. Spring's default is **one**
thread shared by every `@Scheduled` method in `PriceFetchScheduler`; the intraday pass walks ~106
tickers at a 500ms throttle (~70s, far longer when Yahoo starts timing out at 20s a request)
against a 60s `fixedDelay`, so that thread is saturated essentially all the time. That is how the
22:00 cron got starved on 2026-08-19 and left the day's closes frozen at their mid-session
snapshots.

**Sorting:** `parseVal` honours an explicit `data-sort-value` before falling back to cell text —
needed because the Last cell now carries a badge as well as a number. `setPriceCell` writes the
number and badge into their own spans (a `textContent` swap would wipe the badge every frame and
break change detection by comparing `"174.38PRE"` against `"174.38"`), and sets `data-sort-value`.

