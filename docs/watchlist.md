# Watchlist & alerts


Monitor-and-nudge tool for opportunistic trimming/rebuying — it emails alerts and never
touches an order.

- **Named lists.** `watchlist_list` (id + name + display_order) and `watchlist_member`
  (`(list_id, symbol)` PK + per-list display_order) turn the one watchlist into any number of
  them — chips, banks — each its own tab at the head of the Tools strip. `watchlist` itself is
  unchanged and stays the symbol universe: it is the **only** home of the alert settings, one
  row per symbol however many lists hold it.
  - **Alerts are global to the symbol, membership is not.** A name in both `chips` and `ai` has
    one set of triggers; editing Move % on either tab edits the one row. That is what keeps
    `WatchlistAlertJob` — which dedups on `SYMBOL|TRIGGER|date` and knows nothing about lists —
    from mailing the same signal twice, and what leaves `/watchlist/row` (a linear scan for the
    first match, serving five other tables) with a single answer to whose thresholds to show.
    `WatchlistService.view()` stays list-agnostic for the job; `view(listId)` serves the screen.
  - **Two invariants tie the tables together**, enforced in the repository rather than by a
    foreign key: every member names a symbol with a `watchlist` row, and every `watchlist` row
    is in at least one list. So `×` removes from *this* list, and deletes the symbol outright
    only when it was the last — an orphan would otherwise keep drawing daily prices and a
    fundamentals refresh for ever, invisible to the screen that could stop it. Deleting a list
    does the same sweep. **The last list cannot be deleted**: the tab strip has nowhere to send
    the user, and emptying it reaches the same place without discarding every alert setting.
  - A pre-lists database is promoted on first start: the flat watchlist becomes the first list,
    keeping its manual order. The backfill is guarded on *having just created* that list, not on
    the member table being empty — otherwise a deliberately emptied list would be resurrected by
    the next restart.
- **Drag to reorder, rows and columns.** A grip (⠿) in each row's Symbol cell drags the row; a
  chip in the column picker drags the column. The ↑↓ buttons stay beside both — they are the
  keyboard path and the touch path (HTML5 drag events never fire on a touch screen), and a
  one-place nudge is still quicker by button. Drag is the addition for a name that has to travel.
  - **A drop saves the order it shows.** `POST /watchlist/reorder` takes the whole visible order
    and `WatchlistRepository.reorder` renumbers the list `0..n-1` from it; `move` (the buttons)
    swaps a pair in memory and hands the result to the **same** renumbering writer, so the two
    can never leave the table in an order neither of them meant. The table is also sortable by
    header, and a sort is not the stored order — posting what is displayed is what keeps the
    screen and the database from disagreeing, so dragging under a sort is how that arrangement
    *becomes* the manual order.
  - **The post is not the membership.** `reorder` numbers the names it was given and then keeps
    every member it wasn't, after them; names that aren't members are ignored. A drop that raced
    an add from another tab therefore reorders what the user saw without un-listing the name they
    never had — it writes order, and only order, so the KV mirror is untouched.
  - **No drop, no save.** `dragover` reflows the rows live under the cursor, which means a drag
    cancelled with Escape has already moved them; `dragend` restores the order captured at
    `dragstart` unless a real `drop` landed. A failed save re-loads from the server rather than
    leaving the screen showing an order that was never stored. The picker's chips follow the same
    rule against the *draft* — nothing there reaches the server until Save, which already posts
    the whole ordered key list, so columns needed no new endpoint at all.
  - Only the grip is `draggable`, never the `<tr>`: a draggable ancestor stops text being selected
    inside the threshold inputs in WebKit, and the row's own click opens the price popup (the grip
    is a `<button>`, which that handler already ignores). It sits in the **Symbol** cell because
    that is the sticky-left column — a handle out at the actions end would need a horizontal
    scroll to reach. The name is patched through its own `.wl-sym-name` span so the 30s poll
    rewriting the cell can't discard the grip's listener, and a render landing mid-drag places
    only rows that aren't in the table yet.

- **Per-list columns.** `watchlist_column` (`(list_id, col_key)` PK + display_order) stores an
  ordered list of keys; **no rows means the default set**, which is what lets an untouched list
  and a brand-new one render identically without either owning a row. `col_key` is deliberately
  opaque to the server — a built-in (`price`, `vol30d`, `thrAbove`) or a fundamentals field
  prefixed `f:` — so adding a metric to the picker needs no migration, the same bargain
  `fundamentals_snapshot.extra_json` already strikes.
  - **The menu was already written.** `fundamentalsFieldGroups()` (`dashboard.js`) is the single
    home of the fundamentals grouping — label, format type, prose tooltip, glossary anchor, and
    which bag on the row holds the value — and the picker is generated from it, so a metric added
    there appears here fully described. `fmtSnapValue` formats the cell. The tuple's `source` slot
    is what lets a non-numeric field join: `f:earningsDate` reads the string `labels` blob rather
    than the numeric `extra` map, and its cell carries the day's own epoch as `data-sort-value`
    because `parseFloat` on an ISO day reads the year alone
    (see [earnings.md](earnings.md)). Built-ins get a parallel `WL_BUILTIN_GROUPS`; `Row` already
    carried `pct10d`, `beta` and `gainPct`, which the table never showed — three columns for free.
  - **Values are joined client-side** from the `/portfolio-fundamentals` snapshot the price popup
    already caches per session (`ensureWlFundamentals`), so an `f:` column costs no new endpoint
    and no change to `Row`. The grid is built from the server Row alone and those cells start
    `—`, then `wlPatchFundCells` fills them when the snapshot lands — patched, not re-rendered,
    so an in-progress threshold edit keeps its focus and caret.
  - **Resolution is `wl-columns.js`**, a DOM- and network-free UMD module beside `wl-poll.js` and
    `wl-edit-sync.js` (unit-tested under plain node). It drops keys this build no longer offers
    rather than rendering empty cells, and **falls back to the defaults when a layout resolves to
    nothing** — without that, one bad row leaves a list permanently blank with no picker reachable
    to fix it. Symbol and the ↑↓× cell are pinned either side and never stored, so no layout can
    lose the column that names the row or the one that edits it.
  - **`attachSort` is re-run by `wlSyncHeader`, not latched once.** It binds per `<th>` and closes
    over the header cells **by index**; the old one-shot `wlSortAttached` flag would have left the
    listeners on nodes a column change had already discarded.
  - The picker marks each fundamentals field with how many symbols **on this list** have a value
    (`13/18`, or "no data for this list"). Several metrics are empty for whole asset classes — an
    ETF has no FCF margin, a bank no EV/EBITDA — and picking one blind to find a column of dashes
    is a wasted trip. Two are currently empty for *everything*: `roic` and `fcfGrowthYoy` are
    computed from the `*HistoryQuarterly` quoteSummary modules, which Yahoo has stopped
    returning — 0 of 71 stored rows carry either, while every `financialData`-derived metric is
    fine. The computation is correct and will start working if the feed does; the coverage mark
    is what stops that being a surprise.
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
  for 1D–15D (downsampled to ~5-min beyond 5 days), daily `close×splitFactor` for 30D — and,
  alongside the points, the selected window's own traded high/low (`Series.range`).
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
- **The popup is shared with every table that names an instrument** — Live, Open orders (rows,
  ladder rungs, switch legs and the probe result), Holdings and the Cash ledger. Its markup
  (`#wl-modal`) sits at **body level**, not inside `panel-watchlist` — a modal nested in a hidden
  `.tab-panel` is hidden along with it, so the Live row click opened a popup nobody could see.
  Its listeners (close/Escape/backdrop, the window toggle, the Fundamentals disclosure) are bound
  by `ensureWlModalInited()`, separate from `setupWatchlist()`'s tab-specific bindings, so a user
  who never opens the Watchlist tab doesn't get a popup whose Close button is dead. Every one of
  those tables calls the same `openSymbolModal(symbol)`, which opens the modal immediately with a
  "Loading…" line and fills it from `GET /watchlist/row?symbol=` — none of those tables carries
  the popup's data (no 52-week levels, vol term structure or fundamentals), and `wlCurrentSymbol`
  doubles as the staleness latch so a response landing after a close/re-click is dropped.
  `WatchlistService.row` serves **any** symbol, watched or not: a watched one is assembled from
  its stored `Entry` so popup and watchlist row agree, an unwatched one gets a stand-in entry
  with every trigger **disabled** —
  looking at a holding must never arm an alert. The click is delegated from the Live `<tbody>`
  (rows are rebuilt and re-ordered on every SSE frame); the poll guard in `wlPollTick` already
  requires `panel-watchlist` to be active, so a popup opened from Live doesn't suppress anything.
- **A row opts in by carrying `data-symbol`; there is no other test.** Every caller delegates from
  the body (each of these tables is htmx-swapped or rebuilt under it) and matches
  `tr[data-symbol]`, so the rows that name *no* instrument are inert by construction rather than
  by a special case in the handler: the two sync tables' `CASH` rows (a currency balance is not a
  security), a grouping header in the Cash-ledger or Live table (it names no *single* symbol and
  keeps its own expand/collapse click — it carries no `data-symbol`, so the two handlers can never
  both fire on one click), and an Open-orders row whose description resolved to no ticker. Held
  names go through unfiltered, gilts included: `WatchlistService.tickerFor` maps a bond to itself
  and the HL scrape has priced it. A held symbol Yahoo has never answered to still opens — and
  says so, in the same amber `(no Yahoo data)` sentence the watchlist row uses, with the mapping
  editor sitting a few inches above it in the same panel.
- **Popup extras:** `GET /watchlist/vol` returns a realized-vol **term structure** (5D/10D from
  intraday 1-min returns via `PriceStats.annualizedVolIntraday`, 30D/1Y from daily closes) shown
  in the popup stats. `GET /watchlist/series` for `1D` returns only the latest session's bars
  (convention: today only); the popup chart uses a **category x-axis** (evenly-spaced) so
  overnight/weekend gaps don't draw diagonal lines — 1D labels are times, multi-day/30D labels
  are dates. Popup fundamentals reuse the Snapshot-diff renderer (`fundamentalsHtml` /
  `fundamentalsFieldGroups` in `dashboard.js`) laid out in columns.
- **The window's own high/low** sits beside the fixed 52-week pair and is relabelled as the window
  changes (`5D high`, `1Y low`, …), so the same two cells answer whichever term is selected. The
  52-week pair stays put: it is the near-high alert's yardstick and comes from the fundamentals
  cache, not from here.
  - **It is the traded range, not the range of the drawn line.** `price_history` has stored
    `high`/`low` since the first fetch (827k of 836k bars, back to 1981), so no window needs a
    "close-only, approximate" disclaimer and nothing new is fetched — the accuracy worry that
    prompted the feature turned out to be unfounded. One rule serves all fifteen windows: the
    highest and lowest price actually traded over the span the *label* claims, auctions included.
  - **The stated high can sit visibly above the chart.** The line is closes (daily, or 1-minute),
    the range is the session's true extreme, and our minute capture is gappy by construction — the
    app is only running when the laptop is open. BARC.L on 2026-09-22 closed a session whose daily
    bar high was 483.60 while our 446 stored minutes topped out at 475.30: a 1.7% gap, and the
    daily bar is the one to believe. Reading the high off the plotted points would have under-
    reported it.
  - **Every window sees the session in progress.** Today's daily bar does not land until the
    evening fetch, so `addLiveSession` folds the current session's minute bars into the daily
    windows too. Without it a high made this morning showed in `5D high` (which reads minute bars)
    but not `30D high` — a longer window reporting a lower high than a shorter one inside it.
    Windows nest, and a test asserts it.
  - **Zero is not a price.** A bar contributes `max(high, close)` / `min(low, close)`, and a stored
    `0.0` is treated as absent in all three fields. Both zeroes are real: Yahoo writes a whole OHLC
    row as `0.0` now and then (24 rows across 16 `.L` listings on one 2026 date) and a failed HL
    scrape has written a `0.0` close (6 rows, the `GILT 0%` strips) — either one taken at face
    value is the window's low, for ever. Only an *exact* zero is rejected: `^IRX` is a yield and
    really did print below zero in March 2020. Folding the close in also fixes the opposite
    glitch — ~0.4% of LSE ETF bars close just *outside* their own reported range (the closing
    auction prints against a different book), which would otherwise draw a line poking above its
    own stated high.
  - **The `*` is judged per extreme, at the bar that set it** (`highCloseOnly`/`lowCloseOnly`), not
    over the window: it says *this* figure is a bare closing price, with nothing stored about how
    that day traded. That is the gilts — the HL scrape stores a close and no OHLC, so a gilt's
    `1Y high` is the highest *close* and can sit below the year's real high. Judging it over the
    window instead was wrong twice over: folding the live session into every window (above) then
    let one day of scraped intraday vouch for the other 364 and the gilt silently stopped
    flagging, while an equity's 1D mid-session — a few hundred minute samples, a sound figure —
    would have been marked all day, training the reader to ignore the one case that matters. A
    close on a day whose range *is* known (the LSE ETF quirk) is not marked either; it is a real
    print on a bar we can see. A gilt's popup previously had no high/low at all — its 52-week
    cells come from fundamentals, which don't cover gilts — so these two cells are the only ones
    it gets, which is the more reason to say what they are.
  - Ordering is latched (`wlSeriesToken`): clicking 5D then 1Y must not let the slower 5D reply
    repaint the chart, nor label its high `1Y high`.
- **Manual order:** `watchlist_member.display_order` + `POST /watchlist/move?dir=up|down&list=`
  swap neighbours; rows render in that order (↑/↓ buttons per row). The order is **per list**,
  so the same name can sit third in `chips` and first in `ai`. Add-backfills run on a
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

- **Watchlist** — `GET /watchlist?list=` → JSON, rendered as a table under the Tools group.
  One tab per named list, at the **head** of the strip with a `+` and a rule separating them
  from the fixed tools, so a list is one click from Tools rather than two. Every list tab points
  at the **same** `panel-watchlist` and is told apart by `data-list`: one table, one set of
  listeners, one poll — switching list re-fetches rather than building a second grid, which is
  also why `PANEL_TO_GROUP` needed no change (it keys on `data-panel`, which they share).
  `activatePanel` takes the list as a third argument and the active-marking loop compares
  `data-list` when present; a tab without the attribute — every other tab in the app — is
  unaffected. The active list is remembered under its own key, and an unknown one (deleted in
  another window) falls back to the first rather than erroring.
  A user-maintained set of symbols (owned or not — *not* unioned with holdings, kept lean by
  design) for volatility trading. Each row joins live price + today's move + trailing
  momentum (5d/30d) + a realized-vol term structure (5D/10D/30D/1Y annualised) + 52-wk
  high/low distances + P/E/beta
  + position/gain (blank when not held). Four per-symbol alert triggers with editable
  thresholds/levels: proximity to the 52-week high, |today's move|, and absolute price
  targets (≥ above and ≤ below, in the price's native currency). Row colour = alert state
  (green/red today-move by direction, blue near-high, amber ≥-target, purple ≤-target,
  pulsing when both near-high + big-move).
  - **The grid is its own scroll surface.** `#watchlist-wrap` is a centred `.full-bleed` — the
    same breakout the correlation heatmap uses — so the table escapes the 960px body cap and
    scrolls sideways only when even the full viewport can't hold it. The left-anchored variant
    Trend/Range use was tried first and leaves the table just `100vw - 320px`, which 21 columns
    already overflow. The wrapper also needs a height of its own: `overflow-x` makes it the
    nearest scroll container, so `.sticky-head`'s `top: 0` resolves against *it*, not the
    viewport, and the column labels silently stopped sticking the moment the table was wrapped
    (measured: thead 309px down the viewport after a 600px page scroll, not 0). With
    `max-height` + `overflow: auto` the header sticks again and the Symbol column pins left on
    the same footing — the corner cell outranks both axes or whichever slides under it wins.
  - **The header wraps; the data does not.** With twenty-odd columns the *label* is what sets
    the width — `% Held by Institutions` is 123px over a column of `15.48%` figures, and six such
    labels cost ~160px of screen between them. Each label therefore renders inside its own
    `<span class="wl-th">`: `max-width` on a `<th>` is ignored by an auto-layout table, so the cap
    has to sit on a box inside it. The span pairs that cap with `width: max-content`, which is
    what stops the column being squeezed *below* the cap into one word per line — short labels
    keep a single line, long ones take exactly two, and nothing lands tighter than the cap. Only
    the span relaxes `white-space`; the `th[data-sort]` nowrap stays, which is what keeps the sort
    arrow (an `::after` on the th, outside the span) on the label's last line instead of dropping
    to one of its own. `vertical-align: bottom` then sits every label on the same baseline.
  Click a row for a
  popup: same fields + a price chart with a 1D/3D/5D/10D/15D (intraday) + 30D…MAX (daily) window
  toggle, the **selected window's own high/low** beside the fixed 52-week pair, + a collapsible
  Fundamentals section. 

