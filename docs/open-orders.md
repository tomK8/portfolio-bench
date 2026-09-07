- **Open orders** — `GET /orders` → `fragments/orders` (htmx, re-fetched on each tab click), plus
  a "Download Excel" button (`POST /orders/export`). Resting orders across brokers parsed on demand
  from the newest order-status export per broker in the input dir; a current FX snapshot fills the
  Amount GBP column, and a live intraday/daily quote fills the **Current** price + **vs limit** %
  columns (how far the market sits from each resting limit). A **P(N)** column prices the
  probability of the market touching each limit **before that order expires**, on a volatility
  forecast matched to that horizon; it is editable per row, and clicking a row opens the same price
  popup the Live and Watchlist tables use. See the Open-orders section below.

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
  - **A re-parse never touches the note.** The `ON CONFLICT` update lists status, price, quantity,
    amount and `last_seen` and deliberately omits `note` and `first_seen`: the text belongs to the
    user, not the export. `saveNote` likewise only ever UPDATEs — an annotation is *about* an order
    the tool has seen, so a key it does not know is rejected rather than inserted as a bare row
    with no broker and no price (the realistic cause is a stale tab whose order has been pruned).
  - **Retention is 31 days from `closed_at`, and applies to closed rows only.** An order resting
    for months is not history and is never pruned — pruning it would delete a live annotation.
    Later imports do not refresh an already-closed row's date, so the clock runs from when the
    order actually went rather than from the last time anything was read. `prune` runs inside
    `load()` because this panel is the only writer and so the only thing that can leave the table
    growing. On the **first** run every terminal row in the exports is filed as closed *today* —
    there is no record of when they really went, so that cohort's month starts from the first load.
  - The history rides on the **same payload** as the table (`OrdersReport.history`) rather than a
    second endpoint: capped at a month it is small, and the panel renders it collapsed with no
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
  `GET /orders/probe` → `OpenOrdersService.probe` → `fragments/orders :: probe`.
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

