- **Open orders** — `GET /orders` → `fragments/orders` (htmx, re-fetched on each tab click), plus
  a "Download Excel" button (`POST /orders/export`). Resting orders across brokers parsed on demand
  from the newest order-status export per broker in the input dir; a current FX snapshot fills the
  Amount GBP column, and a live intraday/daily quote fills the **Current** price + **vs limit** %
  columns (how far the market sits from each resting limit). A **P(N)** column prices the
  probability of the market touching each limit **before that order expires**, on a volatility
  forecast matched to that horizon; it is editable per row, and clicking a row opens the same price
  popup the Live and Watchlist tables use. See the Open-orders section below.
- **Ladders** — `GET /ladders` → `fragments/ladders :: list`, rendered inside the Open orders panel
  above the table. Named groups of rungs on one security that the user creates and assigns orders
  to, including rungs decided on but never placed. Rungs are laid out by price around the market and
  priced over two **fixed** horizons (`P(10)` / `P(30)`) so they can be read against each other, where
  the table's `P(N)` runs on each order's own deadline. An **intended** rung's price and quantity are
  editable in place (`POST /ladders/intents/update`) and committing one re-prices the block; a rung
  that came from a broker's export is fixed. See the Ladders section below.
- **Share-class switches** — `GET /switches` → `fragments/switches :: list`, rendered inside the Open
  orders panel beside the Ladders block. One holding moving between two listings of the *same* fund —
  same ISIN, different trading currency, exchange line and account. Its own shape because a ladder
  assumes one security and one price spine and these two lines' ratio *is* the exchange rate. See
  [share-class-switches.md](share-class-switches.md).


# Open orders

The volatility model behind the P(N)/P(5d) columns is in [vol-forecast.md](vol-forecast.md).

Read-only view of resting orders across brokers (Open orders tab, under the Holdings group).
`OpenOrdersService` locates the newest order-status export per broker in the input dir (reusing
`HoldingFileLocator.findMostRecent(Predicate<Path>)` — same "newest file wins" rule as holdings),
parses each, and keeps only non-terminal statuses. Otherwise a mostly-disk read — the non-file
dependencies are a current FX snapshot (`FxRateProvider`, fills each order's GBP amount) and the
stored price feeds (`IntradayPriceRepository` + `PriceHistoryRepository`, fill the live-price
columns); all degrade to null + a warning rather than failing the view. Everything is normalised
into one framework-free `domain.model.OpenOrder` record (broker, symbol, name, side, orderType,
quantity, price, priceEstimated, amount, currency, dateEntered, expiry, duration, status, reference,
amountGbp, currentPrice, distancePct, quoteSession, dailyVol10/20/30/250, sigmaEff, decayWeight,
volSource, sigmaToLimit, touchProb5d, horizonSessions, touchProbHorizon, touchProbLow,
touchProbHigh, sessionsToExpiry); absent fields are null → "—". Parsers build enrichment-free orders
via the **`OpenOrder.parsed(...)` factory** — it takes the fifteen broker-exported fields and nulls
the enrichment tail, so a parser never carries a dozen positional nulls that have to be recounted
whenever the enrichment set changes. The service then adds `amountGbp` via `withAmountGbp` (`toGbp`
divides by the rate — units of foreign ccy per GBP), the live-price columns via `withCurrentPrice`,
the RV windows + 5-session columns via `withVolatility`, and the whole P(N) block via
`withHorizonTouch(VolForecast.Touch, sessionsToExpiry)`.

- **Live-price columns (`Current` + `vs limit`).** `enrichWithCurrentPrice` bulk-loads the latest
  intraday quote of **any session** (`loadLatestQuotes`) for every order symbol (one query;
  daily-close fallback, both age-bounded by `MAX_PRICE_AGE_DAYS` = 6),
  resolves the symbol to its Yahoo ticker via `YahooTickerMap` (bonds map to themselves), and expresses
  the quote in the **order's own currency** through `priceInCurrency` — the same GBp-pence→GBP-pounds
  shift and cross-currency-via-GBP conversion the rest of the app uses (a listing quoted differently
  from the order is FX-crossed; a missing rate yields null, never a wrong number). `distancePct` =
  signed `(current − limit) / price` (positive = market above the resting limit), rendered as a
  green/red % and percent-formatted in Excel. Unenriched orders (no symbol, no quote) keep "—".

- **Volatility columns (`RV10` / `RV30` / `σ→lim` / `P(5d)`).** Same `enrichWithCurrentPrice`
  pass. `loadDailyVols` reads each symbol's daily series once (`VOL_LOOKBACK_DAYS` = 420 calendar
  days — sized for the RV250 anchor, ≈350 calendar days) and computes **realized daily volatility,
  not annualised**, over four windows via `PriceStats.dailyVol` (sample stdev of daily log returns;
  `annualizedVol` is now just this × √252, sharing one `dailyVolRaw` engine): RV10 and RV30 are
  displayed and bracket the touch probability as a range; **RV20 is current state and RV250 the
  long-run anchor** the forecast blends between (below). Vol is a return **ratio**, so it's
  currency-free — a `.L` pence series needs no FX. `PriceStats.sigmaDistance(current, limit, σ, N)`
  = signed `ln(limit/current) ÷ (σ×√N)` — how many N-session σ the limit sits from the market (+
  above, − below). `PriceStats.touchProbability` = the driftless first-passage `2·Φ(−|d|)`
  (standard-normal CDF via a Numerical-Recipes `erfc`), the probability the market touches the
  limit at least once within the horizon; capped to [0,1], = 1 at the barrier. `σ→lim` and `P(5d)`
  are one call at `TOUCH_HORIZON_SESSIONS` = 5, so `P(5d) = 2·Φ(−|σ→lim|)` stays checkable by eye
  on the row. Excel formats: RV10/RV30 `0.00%`, σ→lim `+0.00;-0.00`, P(touch) `0.0%`.

- **`P(N)` — will this order fill before it expires?** Three decisions, all load-bearing.
  **(1) N is each order's own trading sessions to expiry**, or `VolForecast.Config.gtcHorizonSessions`
  (90, ≈two quarters) for a GTC order, which has no deadline of its own. The column briefly ran on
  one standing horizon for every row, on the argument that a common horizon is what makes rows
  comparable — true, but it made the column answer nobody's question about any particular order,
  which is whether *this* limit fills before *this* deadline. The comparison is not lost: P(5d) is
  still the same window on every row, and each cell's box still re-prices that row over anything
  typed into it. **The unit is trading sessions**, not calendar days: `sigmaDistance` scales σ by
  `√sessions` off bars that exist only on trading days, and the rest of the app counts sessions
  everywhere. The calendar→session conversion happens once, in `sessionsToExpiry`;
  `sessions=5` reproduces the row's own `P(5d)` exactly, which is the check a reader can make on
  screen and `theHorizonIsInSessions_theSameUnitAsTheFixedTouchColumn` makes in a test.
  **(2) σ is forecast for the horizon, not measured over a trailing window of the same length** —
  see `domain/VolForecast` in [vol-forecast.md](vol-forecast.md), the single place σ_eff is resolved.
  **(3) The headline is bracketed by a range**: the same probability recomputed off RV10 and off
  RV30 (flat trailing windows), printed small under the cell and spelled out on hover. When the low
  end already clears the threshold you are deciding against, the call survives the vol input being
  wrong; when only the high end does, it doesn't. The bracket does **not** always contain the
  headline, and that is the point — a reverting forecast sits below it after a violent stretch and
  above it after a calm one, which is exactly what the flat windows get wrong.
  Enriched into `OpenOrder.horizonSessions/touchProbHorizon/touchProbLow/touchProbHigh/sigmaEff/
  decayWeight/volSource/sessionsToExpiry` via `withHorizonTouch`, which takes a whole
  `VolForecast.Touch`. The **box is editable per row**: typing re-prices via
  `GET /orders/touch?current=&limit=&rv10=&rv20=&rv30=&rv250=&sessions=` →
  `OpenOrdersService.touchEstimate`. It takes the row's four RV windows rather than one σ **because
  a new horizon re-blends the forecast rather than rescaling a fixed number by √N** — and rather
  than a symbol, which keeps it near-pure arithmetic (no re-parse, no FX call; one primary-key read
  for the dividend yield, a deliberate exception that keeps the plausibility guard in a single place
  rather than mirrored into the browser, and keeps a modelling parameter off the query string) and keeps the
  first-passage maths in **one** implementation instead of mirroring `erfc` into `dashboard.js`.
  Debounced 250ms with a per-cell `_ordSeq` latch (typing "1", "12", "120" fires three requests; a
  slow early one landing last would otherwise repaint a horizon the box no longer shows — the same
  guard Attribution's presets use). The printed range repaints with the headline; left behind it
  would read as the range for a horizon the cell no longer shows. Clamped to
  `[1, MAX_HORIZON_SESSIONS]` (1250, ≈5 years). **Past `OpenOrder.LONG_HORIZON_SESSIONS` (130, ≈6
  months) the figure is marked amber with a `~`** — reserved for a horizon a reader has pushed past
  any real order deadline, so it never fires on the GTC assumption itself (a test pins 90 < 130).
  The driftless caveat that applies to the whole column is stated once, in the panel footnote. The
  threshold lives on `OpenOrder.longHorizon()` so the server-rendered cell and the JS re-price can't
  disagree. Excel gains `Horizon (sessions)`, `Horizon from` (expiry / GTC default), `P(touch
  horizon)`, `P(low)`, `P(high)`, `σ eff` and `Vol source`; the export re-parses, so an on-screen
  edit stays a what-if rather than state.
- **Annotations, and the orders a broker has stopped listing.** A free-text **Note** column on
  every row, and a collapsed **Closed orders** block at the foot of the panel — the Open-orders
  counterpart to the Journal tab's `trade_notes`, and the reason the orders pipeline now persists
  anything at all (it was a pure disk read before). `persistence/OrderHistoryRepository` owns both:
  one `order_history` table holding **every order the tool has seen**, live ones included, with
  `closed_at` marking the ones that have gone.
  - **Live orders are in the table on purpose.** An annotation has to be attachable the moment an
    order is placed and still be there after it fills, so the row must predate the closing. Writing
    history only when an order disappears would leave the note nowhere to live until then and
    nowhere to come from afterwards.
  - **`OpenOrder.identity()` is the key**, and it is the broker's own reference whenever the export
    carries one (AJ Bell and II both do; **Citi exports none**, so a Roth order falls back to a
    composite of broker + symbol + side + qty + price + date entered). The reference is the right
    identity *because* a broker issues a new one when you modify an order: the modified order is a
    different order, the note you wrote stays attached to the version that was actually cancelled,
    and the two can be read against each other — which is the whole point of keeping the history.
    The Roth composite has the same property by construction. Broker is always in the key, so two
    brokers cannot collide on a reference of the same shape; `stripTrailingZeros` means a quantity
    restated as `10.00` is not a new order.
  - **An order closes by absence as much as by status.** A broker either restates it with a
    terminal status (Dealt / Cancelled / Executed / Expired — all four spellings appear on the live
    book) or simply stops listing it. `record` treats both the same. It is called **per broker and
    only from inside the parse `try`**: absence is meaningful only when the file actually parsed,
    and a broker whose export failed must not have its orders closed by the absence of rows nobody
    read. The **unfiltered** parse is passed in, not the open subset — an order restated as "Dealt"
    would otherwise look like a disappearance and lose the status that says what happened to it.
    An order the broker restates as open again is **reopened**, so it never sits in the history
    block and the table at once.
  - **One status vocabulary, in `domain.model.OrderStatus`.** Three brokers wrote six spellings of
    three outcomes into the Status column — AJ Bell "Dealt", Citi "Executed" and the American
    "Canceled", II "EXECUTED" and "CANCELLED" — so the same outcome appeared under several names
    and the column could not be read, or sorted, as a state. `OrderStatus.of(raw)` classifies
    (case, padding and the broker's choice of separator are noise) and `label()` is what the panel
    prints; the broker's own word stays in `order_history.status` verbatim and is shown on hover.
    It is **evidence**, not a display string: a broker can write something tomorrow that this map
    has never seen.
    - **The terminal set is byte-for-byte the one `isOpen` already used**, and `isOpen` is now
      `OrderStatus.of(status).open()` — the open/closed filter and the history column read one
      table instead of two. Teaching the map a new terminal word *removes orders from the panel*,
      so it is a decision to take on a real export, not a guess: an unrecognised status is
      `UNKNOWN`, which counts as open, keeping the denylist's "show an unseen 'still working'
      wording rather than drop it".
    - **The third state is the one no broker writes.** A row that reaches the history still
      carrying an open status is one the broker stopped listing without restating — which is what
      *every* modified Roth order looks like from outside, the composite identity changing when
      the quantity or price does. The table printed its last-seen "Open" in a list of closed
      orders, i.e. the opposite of the truth. It now reads **No longer listed**, greyed, with the
      last-seen word on hover: it is not reported as filled or as cancelled, because which of the
      two it was is exactly what the export declined to say. `HistoricalOrder.stateInferred()`
      is what separates our inference from the broker's statement, and drives the muting.

### Settling a "No longer listed" order

An order export is a snapshot of what is **resting**, so a fill appears in it only on the day it
happens. Download it a week later and the order has simply vanished — which looks exactly like a
cancellation, and leaves a ladder unable to see the filled half of its round trip (an MRVL sell at
$269.10 filled while the market ran to $271.84; the panel showed a buy with "no sell rung left to
close it"). Two things answer that silence, and both are **stored** rather than re-derived.

- **The verdict lives in its own columns**, never in `status`. `status` is the broker's word,
  rewritten by every re-parse; `resolved_status` / `resolved_source` / `resolved_at` /
  `resolved_trade` are ours and the `ON CONFLICT` update does not list them — the same bargain
  `note` has, and the only reason a verdict survives tomorrow's download.
- **The broker's own word always wins.** `HistoricalOrder.state()` ranks three sources: a terminal
  broker status, then a verdict, then absence. `saveResolution` refuses a row whose stored status
  `OrderStatus.of` reads as terminal — that is the account statement, and this is not the place to
  disagree with it. The guard is read in Java rather than expressed in the `WHERE` clause because
  what counts as terminal is `OrderStatus`'s judgement and nowhere else's (it reads `Part-Executed`
  and `PART EXECUTED` as one word; no SQL comparison on the stored text would).
- **A verdict reaches the ladder, not just the table.** `GroupRungs.stateOf` reads `state()`, so a
  settled sell becomes a `FILLED` rung and the round trip it completes becomes visible. That is the
  whole reason for recording one.

#### Matched from the cash ledger — `OrderFills` + `OrderFillReconciler`

A fill moves money and the money is in the statement, which is downloaded on its own schedule. So an
unexplained order is matched against the ledger's `TRANSACTION` rows. **Only `FILLED` is ever
written**: a match is evidence of a fill, but the absence of one is not evidence of a cancellation —
only of a ledger that may not have been downloaded yet.

The rule, every clause of which earns its place against the live data:

| Clause | Why |
|---|---|
| same account, same instrument (both sides through `Instruments.matchKey`) | the two exports punctuate a name differently |
| **side from the sign of the cash**, never the quantity | AJ Bell writes "Sale 7,605" and II "100 INTEL" — both positive. Quantity's sign is a Roth convention, not a ledger one |
| quantity equal, exactly | a partial fill is not this order, and claiming it would put a full rung's size on a ladder |
| trade on or after `date_entered`, on or before `closed_at` | the upper bound is what stops a *modified* order claiming the fill of the order that replaced it — the replacement's trade lands after the original disappeared (NVDA: three limits gone 09-11, the replacement filled 09-14) |
| fill no more than **2%** through the limit (`OrderFills.LIMIT_TOLERANCE`), unfavourable side only | a guard, not a reconciliation. Fees are *inside* the ledger amount: 50 LSEG at an £85.05 limit booked £4,276.26, an implied £85.53, because 0.5% stamp duty and a flat commission are in it. A test that cannot be run — no limit, no currency, two different currencies — is **not** evidence against the match |
| the trade is not already claimed | see below |

- **One trade, one order.** Citi exports no reference, so a Roth order's identity is a composite
  *including its price* — and Citi restates that price to the fill price when the order trades. The
  same order therefore arrives as **two** rows: a ghost at the old limit, indistinguishable from an
  order that vanished, and the real row carrying "Executed" (AMD 534.87 → 534.93; TSM 439.59 →
  442.20). There is one trade and two candidates for it. So trades are claimed in two passes: orders
  the broker *did* account for go first, and only then do the unexplained ones look at what is left.
  Without that pass the ghost and its twin both read as fills and a ladder shows two sells where one
  was placed. Claims already written (`resolved_trade`) are honoured the same way across runs.
- **Correction pairs are removed first**, through `PositionDetailService.pruneCorrections` rather
  than a second rule here. A reversal books as cash *in* on a purchase, so left in it reads as a sale
  of exactly the size that was bought — the shape most likely to be mistaken for a resting sell
  order's fill.
- **It is asked once per ledger, not once per page load.** Both callers run it — `OpenOrdersService`
  (where an order is first filed as gone) and `ImportCashService` (where the trade that explains one
  arrives) — because either half of the evidence can come first. Each attempt stamps the account's
  latest ledger date on the row (`matched_through`), and the next call skips every row whose account
  has not been imported past it. An order the ledger could not explain has had its question asked of
  the data that exists; the answer cannot change until the data does. In the steady state the pass
  reads one small table, finds nothing due, and never touches the ledger at all.

#### Said by hand — `POST /orders/status`

The Status cell of a "No longer listed" row is a dropdown (Filled / Cancelled / Expired, or back to
unanswered); a row the broker accounted for is plain text and carries no control at all. This is the
hand for what the ledger cannot do: a **cancellation leaves no trade**, and a fill whose statement
has not been imported leaves none yet either. Stored exactly like a matched verdict and equally
durable, and marked as the user's word (`yours` vs `ledger` beside the cell) so the column never
reads as though the export had said it. Withdrawing a verdict also clears `matched_through`, so the
row is asked about again rather than waiting on a date it was checked against under a verdict that
no longer stands.

Not an htmx post: re-rendering the panel would re-parse three broker exports and re-read the price
feed to change one cell, and it would collapse the `<details>` the row is inside — losing the user's
place in the very list they are working down. The verdict goes over `fetch`, and only the two things
that depend on it are redrawn: the cell, and the group blocks.

  - **A re-parse never touches the note.** The `ON CONFLICT` update lists status, price, quantity,
    amount and `last_seen` and deliberately omits `note` and `first_seen`: the text belongs to the
    user, not the export. `saveNote` likewise only ever UPDATEs — an annotation is *about* an order
    the tool has seen, so a key it does not know is rejected rather than inserted as a bare row
    with no broker and no price (the realistic cause is a stale tab whose order has been pruned).
  - **Retention is 365 days from `closed_at`, and applies to closed rows only.** An order resting
    for months is not history and is never pruned — pruning it would delete a live annotation.
    Later imports do not refresh an already-closed row's date, so the clock runs from when the
    order actually went rather than from the last time anything was read. `prune` runs inside
    `load()` because this panel is the only writer and so the only thing that can leave the table
    growing. On the **first** run every terminal row in the exports is filed as closed *today* —
    there is no record of when they really went, so that cohort's year starts from the first load.
    - **Why a year and not the month it started as.** The month served the original purpose —
      reading an annotation against the order that replaced it — but two things arrived after it.
      A ladder's round trip is a sell and the buy that closes it, which can be a season apart; and
      the order picker can only offer what is still stored, so retention is also the window in
      which a user may decide an old fill belongs on a ladder. An order already in a group is
      exempt from pruning entirely, so this governs only the loose ones — a few hundred rows a year.
  - The history rides on the **same payload** as the table (`OrdersReport.history`) rather than a
    second endpoint: a year of it is still small, and the panel renders it collapsed with no
    extra round trip. Notes ride alongside as `OrdersReport.notes` keyed by identity, deliberately
    **off `OpenOrder`** — that record already carries thirty-odd broker and model fields, and this
    keeps the parsers and every `with*` enrichment untouched by a feature none of them are part of.
  - **UI**: the Note cell is a borderless `<input>` that shows its border on hover and focus, saved
    on blur (and on Enter, which blurs) against the value it was rendered with, so tabbing through
    a row of notes writes nothing. Both handlers are delegated from `document.body` — the fragment
    is htmx-swapped on every tab click. An `<input>` is already excluded from the row click that
    opens the price popup, so typing a note cannot open a chart. Closed rows carry an editable note
    too: same input, same endpoint, no second code path.

- **Pre-trade limit probe (`Test a limit before you place it`).** The touch probability is the
  number that says whether a limit is ambitious or lazy, and until now it was only readable *after*
  the order existed at a broker — the wrong way round for calibrating one. A card at the top of the
  panel takes a symbol + limit (+ side + horizon) and reports the **same seven statistics** the
  table's columns do — Current, vs limit, RV10, RV30, σ→lim, P(5d), P(20d) — plus P(N).
  `GET /orders/probe` → `OpenOrdersService.probe` → `fragments/orders :: probe`. A symbol
    with no stored currency has never priced, and the probe used to answer that by warming both
    feeds and saying "reload in a few seconds" — every time, so a wrong ticker read as a slow one
    forever. It now checks `UnresolvedSymbols` **first**: once a fetch has come back empty the
    verdict is returned instead (naming the candidates Yahoo's own search offers, and the mappings
    editor), and no further warm is queued. Only an unjudged symbol still gets the warm-and-wait.
  - **It is the same object, not a parallel calculation.** A hypothetical order is built as an
    `OpenOrder` (broker "What-if", no qty/date/status) and pushed through the *same*
    `enrichWithCurrentPrice` pass the parsed rows take, so the quote-age bound, the pre/post
    labelling, the RV10→RV30 vol fallback and the first-passage maths are one implementation.
    `probeReportsExactlyWhatTheTableWouldForTheSameLimit` asserts a probe of a resting order's own
    limit reproduces that row's eight columns exactly — if it ever stops, a second implementation
    has grown between the two panels.
  - **No FX, ever.** The limit is read in the instrument's **own quote currency** (`GBp` folded to
    pounds, as an AJ Bell row already prints), detected from the stored quote rather than asked
    for. Target currency = the quote's currency ⇒ `priceInCurrency` never needs a rate, so the
    probe is a pure disk read and the form can safely re-fire as the limit box is typed into
    (asserted with an FX provider that throws).
  - **A marketable limit is flagged, loudly, above the numbers.** First passage is symmetric in
    |σ|, so a buy limit 1.26σ *above* the market prints the same modest 20.6% as one 1.26σ below —
    but that figure measures the market travelling *up* to a price the order would already have
    filled at. Unlabelled it reads as a one-in-five chance, the exact inverse of the truth.
    `LimitProbe.immediateFill()` (deliberately **not** `OpenOrder.throughLimit()`, which allows an
    extended-hours and fee-estimate margin for a different question) drives an amber banner stating
    execution is ~100% and that the columns below are not execution odds.
  - **Cold feeds are warmed, both of them.** A symbol nobody holds, orders or watches is exactly
    what a pre-trade probe reaches for, and `load()`'s `order_symbols` publication never covered
    it. Quotes go to the existing intraday warmer; the **daily** series — which every probability
    is scaled by — goes to a new `historyWarmer` (`PriceFetchJob.fetchSingleIfStale`, so a covered
    name costs no HTTP). Both share `lastWarmed`'s 5-minute cooldown under distinct key prefixes,
    so typing into the limit box is one fetch and not one per keystroke. The render never waits.
- **Row click → the shared price popup.** Same `openSymbolModal` the Live and Watchlist tables use
  (chart + momentum + vol term structure + fundamentals, served by `GET /watchlist/row` for any
  symbol, watched or not — looking at an order never arms an alert). Delegated from `document.body`,
  since the fragment is htmx-swapped on every tab click; clicks on `input, button, a, select, label`
  are excluded so the P(N) days box doesn't open a popup. **Only rows the service could resolve to
  a ticker are clickable** — `OrdersReport.chartableSymbols` / `chartable(o)`. An unmapped AJ Bell
  row's "symbol" is its entire description, so charting it would fetch nothing and read as a broken
  feature rather than as the missing mapping the panel already offers to add a few inches above.
- **Stale prices, and the symbols nobody else fetches.** A resting order is routinely on something
  neither held nor ever traded, so no other source (`held_symbols`, the ledger, the watchlist) named
  it and *nothing kept its feed warm*: a WMT buy limit printed a two-week-old $111.79 against a $115
  market, with `vs limit`, `σ→lim` and both touch probabilities derived from it (2026-08-19). Two
  halves to the fix, both needed. (1) `load()` publishes the resolved order symbols to the
  `order_symbols` KV set, which `PriceFetchSupport.tickersToFetch` unions in — so the intraday job
  picks them up on the next tick. Skipped when no source parsed, or an unreadable export would read
  as "no orders any more" and empty the set. (2) Both price sources are age-bounded by
  `MAX_PRICE_AGE_DAYS` (6 — a long weekend plus holidays); past it the row shows "—" and the symbol
  is named in a warning. The daily fallback's old `for`-loop bound was a no-op: `getPriceOn` is
  on-or-before, so walking back a day at a time only re-asked the same question and returned the
  newest bar however old — the bound has to be applied to the answer.
- **Quote age is visible, and a lagging feed is refetched on load.** The panel reads stored prices
  and never fetches inline, so the residual staleness is the polling cycle: the intraday pass walks
  ~106 tickers at a 500ms throttle (~70s) and then waits 60s, i.e. any one symbol refreshes about
  every two minutes — and on a cold start, or the first visit after an order appears on a new name,
  it can be a whole pass behind. Two mitigations. A **"Live prices as of HH:mm"** line reports the
  newest quote in the table, and any row priced off something older carries a muted `as of …` /
  `close …` note (`OrdersReport.priceNotes`), so a close is never silently dressed as a quote.
  Those same symbols are handed to `IntradayPriceFetchJob.fetchNow` on a background daemon thread
  (`BeanConfiguration.warmOnBackgroundThread`, 5-min per-symbol cooldown since the tab re-fetches on
  every click) — the render never waits, and a reload a few seconds later shows the live price.
  **Lag is judged against the newest quote from the same market, not the clock**: out of hours every
  US quote is hours old and none is stale. The tolerance is a generous 60 min because Yahoo writes a
  1-minute bar only when something trades — XWHS.L last printed at 16:13 against a 16:30 LSE close
  and tripped a 15-minute rule on a perfectly current price.
- **Pre/post-market quotes.** A resting order is priced off an extended-hours print when that's the
  newest price there is — at 08:00 London a US limit is far better served by a live pre-market bid
  than by last night's close — but never silently. Three consequences, all load-bearing:
  - The row carries a `pre-market` / `after-hours` note (`OrdersReport.priceNotes`, same span the
    lag note uses; `sessionNote` composes the two when both apply), and the Excel sheet gains a
    **`Quote`** column beside `Current` rather than folding the caveat into the price — the price
    stays a real number the sheet can sort and chart.
  - **The through-limit flag is suppressed** for an extended-hours quote (`OpenOrder.quoteSession`
    → `extendedHoursQuote()`). A resting regular-hours order doesn't fill outside regular hours, so
    "this should already have gone" isn't what a thin pre-market print through the limit means; the
    amber row would otherwise fire on every overnight gap.
  - **An extended-hours row always states its quote time** — "pre-market 13:58" — and there is
    deliberately **no staleness threshold** for these sessions. The peer-lag rule that works in
    regular hours cannot work here: pre/post volume is a fraction of regular volume, so sparse
    prints are the norm and any threshold is wrong in one direction or the other. At 60 min, MDLZ's
    perfectly ordinary 72-minute pre-market gap read as a stalled feed; at 3h the panel showed an
    88-minute-old $63.74 against Yahoo's $64.15 and said nothing at all (2026-08-20). Stating the
    time needs no tuning, never false-alarms, and answers the only question the reader has.
    `EXTENDED_HOURS_REFRESH_AFTER` (10 min) governs only how eagerly the warmer goes looking for
    something newer — Yahoo publishes extended-hours minutes with a lag of its own, so a quote can
    be current *for us* and behind the site.
  - **The peer-lag buckets are keyed by `(market, session)`**, not market alone, and the peer check
    now applies to regular-hours quotes only. Only the liquid US names print pre-market, so a
    perfectly current regular close would otherwise read as fifteen hours behind its pre-market
    peers and be flagged and re-fetched every morning.
- **Through-limit flag.** `OpenOrder.throughLimit()` (derived, no new field) marks a resting order
  sitting on the wrong side of its own limit — a buy at or above the market, a sell at or below.
  That state shouldn't persist: a buy limit fills at the limit *or better*, so it means the quote is
  stale, the export pre-dates the fill, or the order is about to go. Rendered as an amber left-bar
  row (`.order-through`, same idiom as the watchlist's price-target rows) plus a footnote. An
  `priceEstimated` II limit needs 0.5% of margin first — the dealing fee inside `amount ÷ quantity`
  inflates a buy's implied limit and deflates a sell's, biasing both toward a false flag.
- `parser/OrderParser` — interface (`sourceName`, `supports`, `parse`). Parsers return **all** rows;
  the open/closed filter lives in `OpenOrdersService.isOpen` (a denylist of terminal statuses —
  dealt/executed/filled/cancelled/expired/…; anything else, incl. an unseen "still working" status,
  is shown rather than dropped).
- `AJBellOrdersParser` — `activeorders*.csv`. The export names the instrument but carries no
  ticker, so `symbol` is mapped from the name by keyword (`symbolOrName` over the user's
  `instrument_aliases` first, then the committed `NAME_TO_SYMBOL`: EQQQ / LSEG / REL — the same
  internal symbols used elsewhere). An **unmapped** name falls straight through as the `symbol` (the
  table has no Name column), so a new instrument shows its full name in the Symbol column — and the
  panel offers the mapping inline (see [instrument-mappings.md](instrument-mappings.md)); it just won't resolve
  to a live quote until mapped. `name` still carries the full text on the record, and
  `symbol.equals(name)` is what flags the row as needing an alias. Prices are London pence ("53800p") → normalised to pounds
  so the row is single-currency (GBP). Statuses: "In progress" (open), "Dealt" (filled).
- `IIOrdersParser` — UUID `.csv`, **header-sniffed** (`Submitted` + `Settlement Date` + `Status`) to
  distinguish from II holdings/cash UUID CSVs. `Type` bundles side+ordertype ("SELL/LIMIT"); currency
  from the `Amount` £/$/**€** prefix; no expiry. The resting **limit price isn't exported**, so it's
  estimated as `amount ÷ quantity` with `priceEstimated=true` — the UI prefixes "~" and shows a
  footnote (the ~£2.99 dealing fee, plus an FX charge in GBP on non-GBP orders, is folded into the
  amount, so an exact price can't be recovered). Open = "PENDING".

  **The euro was the same gap that bug #1 of the transfers section describes, in the other II
  parser.** `IISippParser.parseCurrency`/`parseCurrencyAmount` were taught `€` when the euro names
  transferred into II (2026-08-27); nothing propagated it here, because II carried no euro *order*
  until a BNP sell limit on 2026-09-03. Both readings of the amount missed it independently:
  `currency()` tested `$` and `£` only, and `decimal()` stripped `[£$,\s]`, so `"€23,171.33"` threw
  and came back null. **The row's failure shape is worth recognising** — a null amount takes the
  limit price with it (`amount ÷ quantity`), a null currency makes `priceInCurrency`'s target null,
  and between them Price, Current, vs limit, σ→lim, P(5d), P(N) and both Amount columns were blank
  **while RV10/RV30 rendered normally**, those being read off the symbol's own daily series and
  needing no currency at all. Two live vol figures beside eight blanks reads as a broken instrument
  or a dead feed; it was an unparsed currency symbol. Everything downstream already handled EUR
  (`OpenOrder.currencySymbol`, `ExcelReportWriter.currencySymbolFor`, `toGbp` via the Frankfurter
  snapshot), so the fix was contained to the parser.

  **The two readings now share one `CURRENCIES` table**, since a symbol known to one but not the
  other yields half an order — a number under no currency, or a currency with no number. An
  unrecognised symbol resolves to **no currency**, deliberately the opposite of `IISippParser`'s
  GBP fallback: a holdings row has II's own `Market Value £` column to fall back on, an order has
  nothing, so a wrong currency would price the row against the wrong quote rather than leave it
  visibly unpriced. Quantity parses through the same helper and is unaffected either way.
- `RothOrdersParser` — Citi `Order+Status*.xlsx` (POI, `DataFormatter`). Header row located by
  scanning (metadata preamble above it). All USD + GTC (`duration` = "GTC", `expiry` null). Open =
  "Open". The order value isn't exported but the (exact) limit price is, so amount = price × qty.

Wiring: three parsers are listed in the `openOrdersService` bean (`BeanConfiguration`); the order of
that list is the broker grouping in the table. `OrdersController` (`GET /orders`) renders
`fragments/orders :: result`; the table is a sticky-head sortable table (sort attached via the
`htmx:afterSettle` handler, which now also watches `#orders-result`; `parseVal` strips "~" so
estimated prices still sort numerically). No parser registration in `PortfolioGatherer` — the order
pipeline is independent of the holdings/cash pipelines (and the II sniff keeps them from
cross-matching).

**Both tables sort, and the closed one needed no JS of its own.** The `htmx:afterSettle` handler
already calls `attachSort` on *every* table inside `#orders-result` (guarded by
`data-sortAttached`), so `#orders-history-table` only had to declare `data-sort` on its headers to
become sortable — Qty and Price as `num`, the rest as `text`. **Both date columns are ISO
`yyyy-MM-dd`, where a lexical sort is already chronological**, so there is no date parser here and
therefore none that could disagree with the one the rest of the panel uses. Sorting by Status is
the reason the vocabulary was normalised first: on the brokers' own spellings it grouped
`CANCELLED` away from `Canceled` and `Dealt` away from `Executed`, i.e. it sorted the brokers, not
the outcomes.

- **The Note column sorts on `data-sort-value`, not on the cell's text.** The note lives in an
  `<input>`, so `cell.textContent` is empty for every row and the column compared equal
  everywhere — declared sortable and silently inert, which the open table had been since the Note
  column shipped. The server renders the annotation onto the `<td>` as well, and the save handler
  writes `cell.dataset.sortValue` on a successful POST: without that, sorting after typing a note
  would order the table by what was on screen when it rendered. An absent note renders no
  attribute at all and an emptied one an empty string; `parseVal` treats both as "fall back to the
  cell text", so they behave identically.
- **The closed table's ordering is deliberately not remembered across a re-render**, unlike
  `ordersSort` above. The block is a `<details>` that a swap returns to collapsed, so there is no
  ordering on screen to preserve — remembering it would restore a sort into something the reader
  can't see.

**The chosen sort survives a re-render**, which matters because both model checkboxes swap the
whole fragment: ticking or unticking drift / earnings variance is a request to re-price the
numbers, never to re-order the rows, and the table used to come back in the server's default
broker order. The state can't live in the DOM — the `<thead>` carrying `data-sort-dir` is exactly
what the swap destroys — so `dashboard.js` keeps `ordersSort` (`{idx, dir}`) for the page session,
captured by a **body-delegated** header click (it bubbles past `attachSort`'s own handler, so the
marker is already written by the time it reads it) and re-applied by `restoreOrdersSort()` at the
head of the `htmx:afterSettle` handler, *before* the sort is re-attached. `attachSort` **seeds its
`sortCol`/`sortAsc` closure from any marker it finds** — without that, the first click on the
restored column would restart at ascending instead of flipping to descending. Same mechanism
covers the tab-click re-fetch, so leaving the panel and coming back keeps the ordering too.

**Excel download.** The panel's "Download Excel" button (`POST /orders/export`) calls
`OpenOrdersService.export()`, which re-parses (so it's always current) and writes a single-sheet
`Open Orders-YYYYMMDD.xlsx` to the **output dir (~/Documents)** — same place the portfolio exports
land, not the browser's download folder — then reuses the shared `fragments/export` result fragment
to report the path. Sheet built by `ExcelReportWriter.ordersWorkbook(List<OpenOrder>)`: Price /
Current / Amount / Amount GBP are real numbers formatted with the row's currency symbol via a
per-cell number format (mixed £/$ reads naturally, stays sortable); II estimated prices carry a
literal "~" in the format (`"~$"#,##0.00`), mirroring the screen while keeping the numeric value; the
`Vs limit` column is a signed percent (`+0.00%;-0.00%`); the `RV10`/`RV30`/`σ eff` daily-vol columns
are `0.00%`, `σ→lim (5d)` a signed plain number (`+0.00;-0.00`), `P(touch 5d)` / `P(touch horizon)`
/ `P(low)` / `P(high)` a `0.0%` percent, and `Drift (annual)` a `0.00%` percent (all via
`setStyled`, raw fraction/number in the cell). (The Files-tab portfolio button was
renamed to "Download Excel" too — both write to ~/Documents.)


# Ladders

The block above the orders table on the Open orders tab. `LadderService` +
`persistence/OrderGroupRepository` + `domain/Ladders` (pure arithmetic) +
`web/LaddersController`.

**What it is for.** Some orders belong together and the table cannot say so. Three NVDA buy rungs at
three prices are one intention, not three rows; a sell ladder and the buy ladder meant to pair with
it are one round trip. The table can sort but not group, and sorting by symbol still does not say
which rungs sit above the market and which below. Separately, a rung decided on but *not placeable*
— the cash isn't in that account, or the broker won't take a limit on that instrument — is a real
commitment that appeared nowhere at all, which is how the cash commitments got added up wrong.

**A ladder is a named group of rungs on one security.** Created by the user, assigned to by the
user. A rung is either a real order from a broker feed or an **intended** rung recorded here.

- **Nothing is ever inferred.** Symbol, date and price proximity are all to hand and all deliberately
  unused. Grouping by resemblance would attach the reasoning written on a ladder to orders that never
  carried it, and the panel's entire value is that it records an intention the feed cannot see.
- **An intended rung is never auto-promoted.** When a real order appears that matches one in every
  visible way — same symbol, side, quantity and price — nothing happens. `markPlaced` is the only
  path across and the user drives it: they pick the order the rung became. A feed match would
  sometimes be wrong, and being wrong here means one decision's reasoning ends up on a different
  order. `aMatchingRealOrderNeverAbsorbsTheIntendedRung` pins it.
- **A ladder spans brokers on purpose.** The sells in II SIPP and the paired buys in the Roth are one
  intention; nothing in the model ties a group to an account.
- **An intended rung never reaches the orders table or the closed-orders block.** Those are broker
  truth. It exists only here.

## The price axis

Rungs are ordered **strictly by price, highest first**, with the market's own price drawn as a row
between them — so sells sit above buys and the lean of the ladder is the shape of the list. Strictly
by price rather than "sells block, then buys block" because the two pictures differ exactly when a
rung is on the wrong side of the market (a sell below the bid, a buy above the offer), and that is
the state worth seeing: it is the same fact the table flags in amber, and a layout that always drew
sells on top would hide it behind a tidy picture of a ladder leaning the way it is supposed to.
`aRungOnTheWrongSideOfTheMarketStaysWhereItsPricePutsIt` is the test.

A rung with no price sorts last — it cannot be placed on the axis and guessing would misstate which
side of the market it is on. No live quote means **no marker at all** (`markerIndex` returns -1)
rather than one at an invented position; the marker's whole job is to say which rungs are above the
market.

## Two fixed horizons, not the table's P(N)

The fill-probability columns are **`P(10)` and `P(30)`** — 10 and 30 trading sessions, named in the
headings — where the table's column is `P(N)` on each order's own deadline.

That is a different question, deliberately. `P(N)` answers *will this limit fill before this order
expires*, which is the right question for one order sitting on its own and is still answered one inch
below. On a ladder the rungs are a **single decision** and the reader is comparing them with each
other: which are live now, which are the patient ones, which way the thing leans. A column whose
horizon silently changes from row to row cannot be compared down its own length, and an "N" on screen
does not say what it is.

**10 and 30 rather than the probe's 5 and 20, on the evidence.** Ladder rungs are deliberately placed
away from the market, and over 5 sessions a real NVDA ladder priced 1.3% / 42.5% / 11.0% / 0.1% —
three rungs reading "nothing happens", which says nothing about the ladder's shape. The same rungs at
10 and 30 spread to 7.8–57.3% and 17.9–74.6%, where the ordering is legible and the near/far pair
separates the rungs in play from the ones waiting. (These are *horizons*. RV10 and RV30 sharing the
numbers is a coincidence, not a reason: σ is forecast for a horizon, never a trailing window of the
same length — [vol-forecast.md](vol-forecast.md).)

**What a fixed horizon costs is paid openly.** A probability over 30 sessions for an order that
expires in eight prices a window the order will not survive. `Rung.lapsesBefore(sessions)` catches
that and the cell is marked amber with a `~` — the same idiom the table uses for a horizon pushed
past any real deadline — naming the expiry on hover and pointing at the table for the per-order
figure. Only AJ Bell exports a dated expiry; II, Roth and intended rungs carry none, so nothing runs
out for them and nothing is marked.

`OpenOrdersService.touchAt` prices both columns, and deliberately **not** through `touchEstimate`:
that serves the browser's editable P(N) box off the four vol windows on the rendered row, so it
cannot see the scheduled-event term. These cells are server-rendered from the same order the table
row was built from and re-resolve the event set, so ticking "Add earnings variance" moves the table's
numbers and the ladder's together. The calendar read is cached — no extra HTTP. A closed rung is
priced at neither horizon: nothing is resting, so there is no fill to put odds on.

## The same analytics for an intended rung, by construction

`vs mkt`, `P(10)` and `P(30)` on an intended rung are the table's own machinery, not a second
calculation.
`LadderService.intendedOrders` turns each intent into a synthetic `OpenOrder` — the chosen account as
the broker, `intent:<id>` as the reference (a namespace no broker reference can collide with),
`Intended` as the status (which `OrderStatus` reads as UNKNOWN, i.e. open, which is what it is) and
`GTC` as the duration (an intent has no deadline, so it prices over the same 90-session horizon a GTC
order gets). `OpenOrdersService.load` pushes those through **the same `enrichWithCurrentPrice`** the
table's rows take. Nothing in `LadderService` computes a statistic.

Two details in that pass:
- **Two calls, not one list.** Real orders are enriched with `recordCalibration=true` and intended
  rungs with `false`. The calibration log is a record of probabilities that can be scored against
  actual fills, and an intended rung cannot fill — it is not at a broker. Same argument, and the same
  flag, that keeps the pre-trade probe out of that log.
- **Intended symbols are published to the fetch universe.** An intended rung is by definition on
  something not yet traded, so nothing else in the app keeps its feed warm — the same gap that let a
  resting WMT limit price off a two-week-old quote (2026-08-19). `publishOrderSymbols` is handed the
  union.

**No currency is stored on an intent.** The instrument's quote publishes it, so storing it would be a
second and staler answer; `intendedOrders` asks the caller for it (`quoteCurrency(tickerFor(symbol))`,
the same helper the probe uses). An instrument that has never priced yields no currency, and the rung
is then visibly left out of the cash lines by name rather than summed under a guessed one.

## What a ladder comes to

`domain/Ladders.totals` — framework-free, and it takes `Rung`s rather than orders because an intended
rung and a resting one are the same input to every figure. What state a rung is in decides only
*which* totals it enters, once, in `Rung.remaining()` / `Rung.filled()`.

- **Net position if every remaining rung fills** — buys positive, sells negative, over resting and
  intended rungs. Filled rungs already moved the position; cancelled ones never will. Null (not zero)
  when a remaining rung has no quantity.
- **Cash per account, and never netted across accounts.** Two figures per account — raises and needs.
  A single number would call a ladder that raises £10k in the SIPP and needs £10k in the Roth
  cash-neutral, which is the precise error the panel exists to stop: the money is in the wrong
  wrapper and cannot move. Keyed by `(account, currency)`, since one wrapper can hold more than one.
- **The round trip**, once *both* sides have filled: matched quantity (the smaller side — the excess
  on the longer one is a position change, not a completed trip), quantity-weighted average buy and
  sell, and the difference. One side alone is null, not a number: calling that a round trip would
  answer a question the ladder has not finished asking. `complete` additionally requires nothing left
  resting.
- **Prices are the last ones the exports carried.** No broker export here has an execution price
  column — AJ Bell's `activeorders*.csv` has one `Price`, II's limit is `amount ÷ quantity`, Citi's is
  the limit. For a limit order that is the price it filled at or better, and the panel says "on export
  prices" beside the figure rather than letting it read as a settled P&L.
- **Anything that cannot be computed is dropped from the figure it would have corrupted and named**
  in `Totals.excluded`, which the panel prints underneath — the app-wide rule.

## The round trip: will this ladder come back round, and is it worth it

`domain/RoundTrips` (the path) + `VolForecast.sequence` / `PriceStats.sequentialTouchProbability`
(the odds) + `LadderService.RoundTripView` (the two joined, with the money on them). One block in
each ladder's summary, and one stored horizon control in the panel's head.

**The question the rung columns cannot answer.** `P(10)` and `P(30)` say how likely each rung is to
fill. They do not say whether the *ladder* is likely to come back round — a sell followed by a buy
back, or a buy followed by a sell out — which is the thing being decided when an order is left
resting. **Two rungs at 60% and 80% are not a 70% round trip, and not a 48% one either.** The naive
product assumes each leg gets the whole window; in a round trip the second leg gets only what the
first leaves of it, and starts from the first barrier rather than from today's price. Both
corrections run the same way, against the reader's optimism, which is exactly why the figure is
computed rather than eyeballed off two columns that are each individually correct.

**The next round trip, not every rung.** Three rungs a side is a sequence nobody waits out — the
ladder is repriced long before it plays through — so a probability covering all of them answers a
question that cannot come true.

**Matched by quantity, never by pairs.** A round trip is a quantity sold and the same quantity
bought back, or the reverse. Which rung closes which is not a question this can answer or needs to:
rungs fill out of order, and selling at one level and buying back at another is a completed trip
whatever the two were recorded as. So the opening leg is the **nearest** remaining rung on its side
(the first one the market reaches — not the one the reader would have picked), and the closing leg
is however many rungs on the other side it takes to match that quantity, priced at their
quantity-weighted average with the last taken part-way if that is all the match needs. The excess on
the longer side is a position change and is deliberately not priced as part of a trip.

**One barrier stands for a set of closing rungs, and it is the furthest of them.** Price is
continuous: reaching the last rung the match needs means the nearer ones were passed on the way. So
three buys at 98/96/94 closing a sell of 30 collapse to the single level 94, bought back at the
average 96.

**Direction is reported, both ways when both apply.** `SELL_THEN_BUY` is shares already held sold
high and bought back lower; `BUY_THEN_SELL` is shares not yet owned bought low and sold out higher.
They are different paths over the same two rungs and have different probabilities, so both are shown
and the likelier is listed first — the reader should be able to see which way round the ladder is
more likely to complete without doing the comparison. **A filled leg settles the direction by
arithmetic rather than by a rule:** matching is by quantity, so the two filled sides cancel and only
the excess is open, which means there can never be an open leg on both sides at once.

**A part-executed ladder shows the odds of what remains.** When a leg fills the ladder needs *one*
event rather than two, so the probability rises — and it is conditioned on the **current price**,
never the price the ladder was created at. `Rung.filled()` is the whole input; nothing is
remembered. The panel marks that row `underway`, because one leg left and two legs left are
different questions and a reader comparing two figures has to be able to see that they were not
asked the same one.

**A rung already through the market is not something to wait for.** A sell below the bid is
marketable: it fills, it is not a barrier. It therefore contributes no leg, and the next leg starts
from the **market price** rather than from that rung's limit — the market is where the market is.
This is the same fact the price axis keeps visible by ordering strictly by price, read as a path.

### The maths

Exact under the same geometric Brownian motion the single-barrier columns assume, not an
approximation of it. By the strong Markov property the process restarts at the first barrier the
moment it is touched, so the second passage is independent of the first and distributed as a fresh
first passage from that level:

```
P(T₁ + T₂ ≤ N) = ∫₀^N f(t; b₁) · F(N − t; b₂) dt
```

with `b₁ = ln(barrier₁/start)`, `b₂ = ln(barrier₂/barrier₁)`, `f` the inverse-Gaussian first-passage
density and `F` the first-passage CDF that `touchProbabilityDrifted` already is. One leg is that CDF
itself — literally the same call, so a part-executed ladder is priced by the model the rung above it
is priced by rather than by a second implementation that agrees today. Zero legs is 1.

**The quadrature's grid is in `t = N·u⁴`, and that substitution is load-bearing.** For a barrier
close in volatility units the density puts nearly all its mass in the first fraction of a session,
peaking near `b²/3σ²`. An evenly spaced Simpson grid over a quarter steps straight over that spike —
on the worst case tested it lost most of the probability outright, silently, in the direction of
"this ladder will never complete". Clustering the abscissae toward zero costs nothing and holds the
error below 2e-4 across every barrier, volatility and horizon the panel can produce, which is inside
the 4 dp the figure is rounded to. `sequentialTouch_reproducesTheClosedFormWhenTheSecondBarrierIsWhereTheFirstIs`
pins it: put the second barrier on the first and `F ≡ 1`, so the convolution must reproduce the
closed-form single-barrier probability.

**Three or more legs returns null, not a number.** Each further leg is another nested convolution,
`O(Mᵏ)` and less accurate with it; nothing here asks for one, and a wrong number would be
indistinguishable from a right one.

**One σ_eff for the whole horizon**, resolved by the same `VolForecast.resolve` the rungs use, with
the same drift netting and the same RV10/RV30 bracket. How the window splits between the two legs is
a random variable — there is no per-leg horizon to forecast for, and blending a second σ for "the
rest of the window" would be forecasting off a quantity the model has not drawn yet. Drift, when it
is on, cannot help both legs: it makes the sell leg likelier and the buy-back leg less so, and the
round trip lands on the balance.

### The horizon is its own, and selectable

**`POST /ladders/horizon?sessions=` → `ladders_round_trip_sessions` in the `KeyValueStore`**, default
**63** (a quarter, on the same 252-session clock the drift and the vol scaling use). Choices are
10 / 21 / 42 / 63 / 126 / 252, named in the calendar terms the decision is made in.

The rungs' fixed 10 and 30 are the wrong window here and that is the whole reason this one is
separate: **thirty sessions asked of two legs is a far tighter constraint than thirty asked of one
rung**, so the same number would read the same and mean something else. Stored app-wide rather than
left in the `<select>`, on the same argument as the drift switch: the panel and the Excel download
have to be asked the same question, and a downloaded sheet must never be priced over a window the
screen it came from never showed. Every ladder's line names its horizon beside the figure for the
same reason. Out of range is clamped rather than refused — this is a view control with a fixed list,
so anything else is a stale tab, and the useful answer is the nearest priceable horizon rather than
an error page over a panel that was working.

### Expected value

**Probability × spread × matched quantity**, in the ladder's own currency, with the GBP figure beside
it. A wide spread that never completes and a narrow one that always does come to the same number,
which is the point of multiplying them; the GBP conversion is what makes two ladders on different
listings comparable when the decision is where to leave resting orders across several symbols.

**Never clamped.** A ladder whose buy-back sits above its sell really does lose on completion, and a
floor at zero would hide the one arrangement most worth seeing. **Null, never zero**, when the
probability did not resolve: an unpriceable ladder and a worthless one are different answers.

Anything dropped from the matching — a rung with no price, no quantity or no side — is named in
`Next.excluded` and printed under the block, and a ladder with no round trip available says **why**
in the reader's terms rather than showing an empty line.

### In the workbook

A fourth sheet, `Round trips`, **one row per ladder and direction** — the grain is the direction,
because two directions folded onto one row would need a column pair each and would still lose which
one the ladder is in. The horizon is a column rather than a title (it is a stored setting that can
move between downloads, and a probability that does not say what window it is over cannot be
compared with last month's file), and `Legs left` carries what the panel's `underway` badge does. A
ladder with no trip to price still gets a row carrying why: dropping it would leave the sheet's
silence ambiguous between "no round trip is possible here" and "this ladder was not in the file".

## Editable intended rungs: the price and quantity cells

An intended rung's price and quantity are inputs, and committing one — leave the box, or press Enter
— redraws the block. `application/IntentEdits` + `GroupRungs.updateIntent` + `fragments/rung-edit` +
`POST /{ladders,switches}/intents/update`.

**Intended rungs, and nothing else.** A rung that came from a broker's CSV prints as text and
carries no input at all. That is not a judgement about how useful editing one would be — it is not
ours to restate. The panel's whole claim is that it shows what is resting at the broker, and this
tool re-parses the export on every load while `order_history`'s upsert restates price and quantity
from it: a typed figure would either be reverted on the next read or, worse, survive and have the
app assert a limit the broker has never been told about. To move a resting rung, move it at the
broker; the next export brings it here. An intended rung is the opposite case in every respect — it
exists nowhere but this panel, nothing else writes it, and editing it is the only way it can ever
change.

The rule is enforced twice over rather than just in the rendering. The panel draws an input only on
an intended rung, and the key a cell posts is `intent:<id>`; `IntentEdits.of` drops anything else
outright, so a stale tab or a hand-made request reaches nothing. `onlyAnIntendedRungCanBeAddressed`
pins it.

**What it replaced.** Before this, moving a rung you had decided on but not placed meant deleting it
and recording it again — which threw away the note, the one part of a rung that says *why* it is
there and the reason intended rungs exist at all. Side, listing and note are untouched by an edit: a
figure being tuned is not a change of any of them. On a switch that is load-bearing twice over — the
side decides which of the two tables the leg is in, and the listing which of the pair's two markets
it is priced against, so a re-price that could move either would silently be a different leg.

**Nothing downstream is special-cased**, because a rung's figures were never stored anywhere but the
intent. `LadderService.intendedOrders` rebuilds the synthetic order from what was just written and it
goes through the same `enrichWithCurrentPrice` pass it always did, so `vs mkt`, both fixed horizons,
the price-axis ordering and the market marker, the net position, the cash each account raises or
needs — and on a switch the GBP yardstick, all three terms of the split, the implied cross rate, the
progress and the shortfall — all move together.

**Two details are load-bearing.**

- **The panel posts every editable cell, and the server decides which ones are edits.** Tracking
  "touched" in the browser would make the client the authority on what the user meant; instead a
  posted figure counts only when it differs from the one the cell was rendered with, at
  `IntentEdits.EDIT_SCALE` (6 dp, the precision the cell prints). That is what stops a commit
  anywhere in the block from rewriting every intent in it.
- **Blank is "no opinion", never zero** — the app-wide rule. Tabbing through a row writes nothing, a
  half-typed figure leaves the cell as it was rather than emptying a commitment against cash, and a
  quantity of zero is refused outright by `OrderGroupRepository.updateIntent`, on the same guard
  `addIntent` has always had. A price may be null: a rung decided on before its level is is a real
  state, and the panel prices it at nothing rather than at zero.

The commit is on `change` and not per keystroke, and the panel is redrawn rather than the cell
patched: a commit is a whole `load()` — three broker exports re-parsed, the price feed re-read — and
the box being typed in is inside the fragment that gets replaced. `dashboard.js` puts the caret back
in the same cell afterwards, matching on `dataset.rungKey` by walking the inputs rather than by an
attribute selector. The commit itself is a **hidden button** carrying the `hx-post` / `hx-include` /
`hx-target`, not `hx-*` on each input: htmx posts the triggering element's own value *and* everything
`hx-include` names, and the server reads three index-aligned lists — one duplicated entry and every
rung after it takes the figure belonging to the one before.

## Storage, and the seam for the group type that is coming

Three tables. `order_group` is the user's intention; `order_group_member` is a *pointer* into
`order_history`, which the broker feed owns; `order_group_intent` is an order that exists nowhere
else and so carries its own side, quantity and price.

- **Membership points at an order key** (`OpenOrder.identity()` — the broker's own reference where
  there is one), the same key the annotations use. A rung therefore keeps its ladder when it fills,
  and a *modified* order — a new reference, by every broker's rules — does not silently inherit the
  old one's place: it is a different order and the user assigns it, which is the whole point.
- **One order, one group.** `order_key` is the member table's primary key, not a pair, so assigning an
  order elsewhere moves it. A rung counted in two ladders would be counted twice in the cash each
  needs.
- **A rung is never pruned.** `OrderHistoryRepository.prune` takes a `keep` set and
  `OpenOrdersService` passes `ladders.rungOrderKeys()` — every order in *any* group, since the member
  table is shared across group types. The second leg of a round trip can fill months
  after the first — a sell in August and the buy that closes it in October are one trip, and any
  finite retention would eventually delete half of it before the half that completes it arrived. The set
  is passed in rather than read from the group table inside the query, so the history repository goes
  on knowing nothing about ladders and the rule is visible where its reason is.
- **`GroupType` now has two constants, and the seam held.** The share-class switch that this column
  was left here for arrived as one new constant, one nullable `symbol_to` column and a second
  assembler — no change to a table already holding the user's groups. What "one group, one security"
  costs is paid in exactly three places, all of them this type's assembler and none of them the
  storage: the symbol lives on `order_group`, `LadderReport.assignableFor` offers same-symbol orders
  only, and the view lays rungs on one price axis. `LadderService` reads `GroupType.LADDER` groups
  and nothing else; a group whose `type` this build does not know is still dropped with a warning,
  never read as a ladder. What the two types genuinely share — building a rung, pricing an intent,
  the unassigned list, the two fixed horizons — moved to `GroupRungs`, which holds no rule about
  which orders belong in which group. See [share-class-switches.md](share-class-switches.md).

## The panel

- **Empty-looking when there are no ladders**: one line and a `+ New ladder` form, not a wrapper
  around everything. Most orders are not rungs — standalone trims and exits belong in the table only.
- **Rendered from the same `OrdersReport` the table is** (`OrdersReport.ladders`), so a rung's numbers
  are the row's numbers. It sits **outside** the "no order files found" guard: a ladder can be made
  entirely of intended rungs, and hiding those whenever no export happens to be in `~/Downloads`
  would hide them on the day they matter most.
- **Mutations re-render the block alone** (`hx-target="#ladders-result"`), never the table. The one
  exception is `markPlaced`, which targets `#orders-result`: linking can move the intent's note onto
  the order, and that note is a cell of the table below.
- **The picker offers closed orders as well as resting ones.** The first ladder anyone builds is
  usually a sell that has already filled paired with a buy still resting; a picker of live orders only
  could never assemble the completed round trip the panel exists to show. What it can offer is bounded
  by retention — a rung already assigned is kept forever, but an order *not yet* on a ladder is only
  assignable while it is still stored, which is the second reason that window is a year.
- **A rung row opens the shared price popup**, on the same terms the table's rows do — only when the
  ladder's security resolved to a ticker. An `<input>` is excluded from that row click, so typing in
  a price cell cannot open a chart — the same exclusion the table's Note and P(N) cells rely on.
- **`Download Excel` writes every ladder** to `Ladders-{date}.xlsx` (`POST /ladders/export`) — four
  sheets: the rungs, the per-ladder totals, the round-trip forecast (one row per ladder *and
  direction*) and cash by account. It re-loads on the press like the
  orders export, which matters more here because `P(10)`/`P(30)` are priced on the stored forecast
  settings. The button appears only once a ladder exists, and the export sits outside the order-files
  guard for the same reason the block does. Sheet design and the columns that exist only because a
  sheet has no colour or position to lean on: [excel-exports.md](excel-exports.md).
- Removing a rung takes it out of the ladder and touches the order not at all. Deleting a ladder takes
  its memberships and its intended rungs (which exist nowhere else) and, again, no orders.
