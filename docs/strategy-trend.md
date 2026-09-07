# Strategy: trend filter


Backtesting tool (Trend filter tab) answering one question: does an MA trend filter on the core
Nasdaq-100 holding (held as EQQQ) cut catastrophic drawdowns enough to justify its whipsaw and
lag costs — and which signal clock does it best? Read-only; the decision is made in the
named-episode table (for the filter: 2000–02/2008/2022; against: 2015–16 and COVID — both always
visible). No champion cell is ever chosen: the full sweep grid ships and where variants disagree,
the disagreement is the finding.

- **Pure engine** in `com.portfolio.domain.trend` (framework-free; reuses `domain.swing.Stats`).
  `TrendConfig` (record + Builder, every knob a query param); `TrendSeries` — spliced daily
  arrays: `close` (signal, split-adjusted), `adjClose` (all P&L), `adjOpen` (`open×adjClose/close`,
  fallback `adjClose` when the source open is missing) and a look-ahead-free `cashDaily` (prior
  cash-series close /100/252, flat-rate fallback before its first bar; discount-yield≈short-rate
  and /252 stated as approximations). `Signals` — five `TrendSignal` clocks: `monthAnchored`
  (checks every ~21 sessions, offset-shifted), `firstTouch`, `bufferedPct` (hysteresis band),
  `bufferedCount` (N consecutive closes), `monthlyClose` (classic Faber; completed month ends
  only). Equality counts as *in* everywhere; initial state at `firstValid` is the plain
  comparison.
- **`Backtester`** is the single accounting path for every cell, control and bootstrap iteration.
  Decision at close `i` fills at that close (`CLOSE`) or the next open (`NEXT_OPEN`, the headline;
  overnight leg stays at the old weight). Cost = `costPerSide × |Δweight|`; out-weight earns the
  cash leg; the initial entry at the common start is cost-free (buy-and-hold pays the same); a
  decision on the final bar stays unfilled (surfaced by live state, never executed). **Common
  analysis window**: every variant and B&H start at the *latest* `firstValid` of any configured
  signal, so a 150d cell never gets a head start on a 250d one.
- **Splice honesty.** ^NDX bars before QQQ's first bar are scaled to match at the splice; the
  pre-1999 segment is a **price-only TR proxy** (no dividends) — `spliceIndex`/`proxiedFrom/To`
  and `dotComInSample` (relative to the *analysis* start) are surfaced in the data panel, never
  hidden. `ensureBackfilled` deepens **shallow** coverage, not just missing coverage — the
  startup/nightly price job unions `trend_symbols` and can win the race with its standard 30-year
  window, which would leave ^NDX starting mid-90s; anything whose earliest bar is inside that
  window gets the full 45-year refetch (`PriceFetchJob.fetchSingle(symbol, years)` overload).
  `YahooPriceFetcher.urlTicker` percent-encodes `^` (URI.create rejects it raw) — that fix is what
  makes index tickers fetchable at all.
- **Cost side explicit.** `Whipsaw` compounds per-trip factors
  `[(1+cash while out)(1−c)²]/(reentryAdj/exitAdj)`: bought-back-higher count, whipsaw-only drag,
  net timing effect (= strategy/B&H at zero cost/cash — asserted in tests), per decade. `TradeLog`
  is the exportable row set (display prices raw, deltas total-return; leading/trailing half-trips
  kept as censored rows). Trailing open exits and unrecovered drawdowns are censored-not-dropped
  throughout (same discipline as Swing §6 Open).
- **Controls.** Buy-and-hold in every table; `ExposureBootstrap` scatters the strategy's own
  out-segment lengths uniformly (random composition of in-gaps — preserves time-in fraction and
  switch count, destroys timing; seeded), reporting CAGR/maxDD distributions + the actual rule's
  percentile. `null` when a variant never exits — the UI says so rather than showing an empty
  distribution. Anchor sweep = the identical month-anchored rule at all `anchorEvery` offsets
  (min/q1/median/q3/max of CAGR and maxDD): a wide band is anchor-luck, not signal.
- **`TrendAnalysisService`** (application) assembles `TrendReport`: data panel, B&H, per-cell
  `VariantResult` (headline + cost sweep + close-fill + half-switch + 0%-cash + whipsaw +
  bootstrap + annual + episodes + trades), anchor sweeps, live state (QQQ + EQQQ.L close-vs-MA
  per length, per-variant state/pending-switch/action note). Sharpe always uses the historical
  cash leg, including in the 0%-cash sensitivity (comparable across tables). `trend_symbols` KV
  is written by the constructor seed and `ensureBackfilled` **only — never by `report()`**, so an
  exploratory `?extension=` sweep can't drop ^NDX from the nightly fetch set. Episodes are fixed
  named windows in `Episodes.NAMED`; uncovered/partial ones are flagged, never dropped. No FX —
  unit-less return ratios (EQQQ's GBp readout included).


## Panel overview

- **Trend filter** — `GET /trend` → JSON, rendered as tables under the Strategies group. Backtest of a
  moving-average trend filter on the core Nasdaq-100 holding (QQQ total-return, spliced back with
  the ^NDX price index, cash leg from ^IRX): five signal clocks (month-anchored / daily
  first-touch / buffered ±x% / buffered N-in-a-row / monthly-close cross-check) × MA-length sweep,
  each cell with cost/execution/half-switch/0%-cash sensitivities, whipsaw accounting, a matched
  random-exposure bootstrap, calendar years, a named-episode table and an exportable trade log,
  plus an anchor-offset sweep and a live QQQ/EQQQ readout. 

