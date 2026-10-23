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


## Coming back after the laptop has been shut

Two separate things keep a reopened app from showing Wednesday's prices on Friday. One stops a
fetch outliving the machine; the other repairs what the sleep went through.

**A send is bounded by the caller, not by the request.** On 2026-10-22 the laptop slept at 00:27
with a Yahoo intraday fetch in flight. The connection died silently during the suspend and
`HttpClient.send` never came back: its thread was still parked there 35 hours later, its own
20-second request timeout long past. That timeout is enforced by a timer the client's selector
thread services, so it is not a deadline the caller holds — and the intraday job runs on a
`fixedDelay`, which re-arms only when the previous run *returns*. One parked call therefore stopped
every quote the Live tab had, took the thread the 22:00 cron needed with it, and nothing short of a
restart brought either back. `adapter/BoundedHttp` is now the only way this app sends: it runs the
exchange through `sendAsync` and waits on the calling thread for the request's own timeout plus
`GRACE`. That wait is driven by the monotonic clock, which stops with the machine and resumes with
it, so a call caught by a suspend returns within its own budget of *waking* time and the job
re-arms on its own. The grace is what keeps this a backstop — a healthy timeout still wins its
race and still reports itself as the `HttpTimeoutException` it always was, which is also what this
bound throws, because a connection that outlived its deadline is exactly "the source is
unreachable".

**The catch-up is demand-driven: no poller, no watchdog, no timer.** `CatchUpService.noteActivity()`
hangs off a `HandlerInterceptor` on every request and, in the ordinary case, reads one volatile
field and returns. Opening whichever tab you open first after a two-day sleep is what starts the
repair — the page still renders at once and fills in as the fetches land, and the Live tab draws a
strip meanwhile (`catchingUp` rides on every snapshot, the 15s SSE tick included, so it clears
itself).

- **The signal is the jobs' own liveness, not the age of the prices.** Asking it that way is what
  stops a closed market looking like a fault — out of hours the quotes are legitimately hours old
  while the pass ticks along fine — and it catches the hang above just as well as a sleep, because
  a pass that never returns is the same silence. The scheduler heartbeats at **both ends** of each
  intraday pass, which is what separates *slow* from *stuck*: a pass crawling through a Yahoo
  outage at a bounded 30s a ticker keeps marking its starts and keeps the gate shut, while one
  parked in a dead socket marks its start once and is never heard from again. Twenty minutes
  without either end is the threshold — headroom for the crawling case, instant for the two-day
  one. The closing heartbeat is in a `finally`: the question is whether the passes are still
  running, not whether the last one found anything, and a catch-up runs these same jobs, so it has
  nothing to offer a feed that is simply down.
- **The elapsed time is wall-clock on purpose.** `System.nanoTime` is the usual right answer for
  measuring an interval and is precisely the wrong one here — it stops while the machine is
  suspended, which is the interval being measured. A monotonic clock would report that no time had
  passed at all.
- **Daily bars first, then quotes, then gilts.** Day Δ is a quote over the close before that
  quote's *trading day*, so while `price_history` still ends on Wednesday every row would read a
  three-day move as today's — the MRNA +12.13% shape above. Repairing the anchor before the prices
  that are judged against it is the whole of the ordering; gilts come last because they are scraped
  hourly anyway and nothing waits on them.
- **No lock is shared with the scheduled pass.** The two can overlap after a hang and the bar
  writes are upserts, so the cost is a duplicated fetch. A lock would instead let a pass that is
  *stuck* keep every catch-up out, which is the failure the gate exists for.
- **A catch-up re-arms the clock even when every step failed**, so an unreachable feed means one
  refetch rather than a fresh full pass on every page load — and it says which it was, because a
  catch-up that reached none of its sources otherwise looks exactly like one that worked.
