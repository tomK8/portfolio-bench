# Watchlist & alerts


Monitor-and-nudge tool for opportunistic trimming/rebuying — it emails alerts and never
touches an order.

- `WatchlistRepository` — `watchlist` table (symbol PK + two thresholds + added_at). The
  symbol set is **mirrored** to `KeyValueStore` under `watchlist_symbols` on every mutation,
  so `PriceFetchSupport.tickersToFetch` and `PortfolioFundamentalsService` union watchlist
  names into their fetch universes with no constructor churn — a freshly added symbol gets
  daily + intraday prices and a fundamentals row on the next tick. `WatchlistController.add`
  also kicks an immediate `PriceFetchJob.fetchSingle` + fundamentals refresh in the background.
- `WatchlistService` — assembles each row from one snapshot (single FX fetch, one bulk
  latest-intraday lookup, one ledger read). Position/gain reuse `PositionDetailService`'s FIFO
  engine (same package) rather than re-implementing it. Momentum (Today/2d/3d/5d/10d/30d) is
  **session-anchored** via `PriceStats.closeNSessionsBefore` — `k` "days" = `k` trading sessions,
  so "2d" is the last two sessions (today + the prior session) and the short windows stay distinct
  over weekends (a calendar anchor collapsed Today/2d/3d onto the same Friday close on Mondays).
  The full row and the near-real-time `/watchlist/quotes` poll share one `computeLive` calc so
  they can't diverge; realized vol is `PriceStats.annualizedVol` (30-session sample
  stdev × √252, on split-adjusted `close`, same convention as the Risk tab). 52-wk high/low,
  P/E and beta come from the fundamentals cache. `series()` serves the popup chart: intraday
  for 1D–15D (downsampled to ~5-min beyond 5 days), daily `close×splitFactor` for 30D.
- **Triggers** are pure domain logic (`WatchlistAlerts.evaluate` → `Flags`): (a) within
  `highThresholdPct`% of the 52-week high (a fresh high always fires); (b) `|today's move|` ≥
  `moveThresholdPct`%, either direction; (c) price ≥ `priceAbove` level; (d) price ≤ `priceBelow`
  level. The two price levels are absolute, in the symbol's **native price currency** (compared
  against the same native `currentPrice` the row shows — no FX), at-or-through counts. Screen and
  alert job evaluate identical `Row`s so they never disagree. **No 52-wk-low trigger** — the low is
  displayed but never alerts (watched names have run up too far for it to matter). A **blank/0
  threshold/level disables that trigger** (`WatchlistRepository` stores 0; the columns are
  `price_above`/`price_below`, NULL on rows predating them → read as 0 = disabled), so a symbol can
  be watched with no alerts at all. `both()` (the pulse) stays near-high + big-move only; the alert
  job dedups the price levels under `SYMBOL|ABOVE|date` / `SYMBOL|BELOW|date`.
- **A row with no prices says which kind of nothing it is.** `Row.missingData` marks the absence;
  `Row.missingReason` (from `UnresolvedSymbols`, see
  [price-history.md](price-history.md)) is non-null only once Yahoo has actually refused the
  ticker. Grey `(fetching…)` now genuinely means a backfill is in flight; amber `(no Yahoo data)`
  means it never will be, and carries the candidates Yahoo's own search returned plus where to map
  them. Adding `EQQU` used to sit at `(fetching…)` forever — the add's backfill 404'd, logged, and
  told no one. The popup subtitle takes the same sentence.
- **The popup is shared with the Live tab.** Its markup (`#wl-modal`) sits at **body level**, not
  inside `panel-watchlist` — a modal nested in a hidden `.tab-panel` is hidden along with it, so
  the Live row click opened a popup nobody could see. Its listeners (close/Escape/backdrop, the
  window toggle, the Fundamentals disclosure) are bound by `ensureWlModalInited()`, separate from
  `setupWatchlist()`'s tab-specific bindings, so a user who never opens the Watchlist tab doesn't
  get a popup whose Close button is dead. Clicking a Live row calls `openSymbolModal(symbol)`,
  which opens the modal immediately with a "Loading…" line and fills it from
  `GET /watchlist/row?symbol=` — the Live table carries none of the popup's data (no 52-week
  levels, vol term structure or fundamentals), and `wlCurrentSymbol` doubles as the staleness
  latch so a response landing after a close/re-click is dropped. `WatchlistService.row` serves
  **any** symbol, watched or not: a watched one is assembled from its stored `Entry` so popup and
  watchlist row agree, an unwatched one gets a stand-in entry with every trigger **disabled** —
  looking at a holding must never arm an alert. The click is delegated from the Live `<tbody>`
  (rows are rebuilt and re-ordered on every SSE frame); the poll guard in `wlPollTick` already
  requires `panel-watchlist` to be active, so a popup opened from Live doesn't suppress anything.
- **Popup extras:** `GET /watchlist/vol` returns a realized-vol **term structure** (5D/10D from
  intraday 1-min returns via `PriceStats.annualizedVolIntraday`, 30D/1Y from daily closes) shown
  in the popup stats. `GET /watchlist/series` for `1D` returns only the latest session's bars
  (convention: today only); the popup chart uses a **category x-axis** (evenly-spaced) so
  overnight/weekend gaps don't draw diagonal lines — 1D labels are times, multi-day/30D labels
  are dates. Popup fundamentals reuse the Snapshot-diff renderer (`fundamentalsHtml` /
  `fundamentalsFieldGroups` in `dashboard.js`) laid out in columns.
- **Manual order:** `watchlist.display_order` + `POST /watchlist/move?dir=up|down` swap
  neighbours; rows render in that order (↑/↓ buttons per row). Add-backfills run on a
  single-thread executor in `WatchlistController` so two quick adds queue rather than racing
  SQLite writes.
- `WatchlistAlertJob` — runs every 5 min (`PriceFetchScheduler`). Dedups **once per day per
  symbol per trigger** via a KV set (`watchlist_alerts_fired`, keys `SYMBOL|TRIGGER|date`),
  batches a tick's new firings into one email, and only persists the fired-set after a
  successful send (so a mail failure retries). No-op unless SMTP is configured.
- **Email:** `AlertNotifier` port + `SmtpAlertNotifier` adapter (Jakarta Mail directly, kept
  Spring-free like the other adapters; `spring-boot-starter-mail` supplies the library).
  Disabled by default. **Put the Gmail app password in `~/.portfolio-bench.properties`** (home
  dir, outside the repo — `application.properties` loads it via
  `spring.config.import=optional:file:${user.home}/.portfolio-bench.properties`, so it can never
  be committed). Set `portfolio.alert.mail.{enabled=true,username,password,from,to}` there
  (Gmail: `smtp.gmail.com:587`, STARTTLS). The app must be running during market hours for
  alerts to fire — keep the lid open + `caffeinate` (closing the lid sleeps the JVM even under
  caffeinate).


## Panel overview

- **Watchlist** — `GET /watchlist` → JSON, rendered as a table under the Tools group. A
  user-maintained set of symbols (owned or not — *not* unioned with holdings, kept lean by
  design) for volatility trading. Each row joins live price + today's move + trailing
  momentum (5d/30d) + a realized-vol term structure (5D/10D/30D/1Y annualised) + 52-wk
  high/low distances + P/E/beta
  + position/gain (blank when not held). Four per-symbol alert triggers with editable
  thresholds/levels: proximity to the 52-week high, |today's move|, and absolute price
  targets (≥ above and ≤ below, in the price's native currency). Row colour = alert state
  (green/red today-move by direction, blue near-high, amber ≥-target, purple ≤-target,
  pulsing when both near-high + big-move). Click a row for a
  popup: same fields + a price chart with a 1D/3D/5D/10D/15D (intraday) + 30D (daily) window
  toggle + a collapsible Fundamentals section. 

