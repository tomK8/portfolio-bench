# Strategies: momentum rotation & signal comparison

## Momentum-rotation backtest

Backtesting tool (Momentum tab) for a **cross-sectional 12-1 momentum rotation** over a fixed
~33-name US large-cap universe. The deliverable is not the raw CAGR — the universe is hand-picked
from today's survivors, so absolute momentum returns are inflated (a red survivorship banner says
so in the header) — but whether momentum beats its **controls**. Read-only; never touches the
ledger.

- **Pure engine** in `com.portfolio.domain.momentum` (framework-free; reuses `domain.swing.Stats`).
  `MomentumConfig` (record + Builder, every knob a query param). `MomentumSeries` — the aligned
  multi-symbol daily panel: master calendar = union of all symbols' dates, per-symbol `close`
  (split-adjusted, regime/drawdown) and `adj` (total-return adj_close, all P&L). A missing day is
  forward-filled ≤ `maxFfillSessions` (5); a longer gap is left NaN (ineligible) and logged as a
  `Gap` — never silently filled. `monthEndIdx` = completed-month last trading days (the rebalance
  clock; the final partial month is excluded, same convention as `TrendSeries`).
- **`Momentum`** (ranker) — score at bar `i` = `adj[i−skip]/adj[i−lookback] − 1` (defaults
  252/21). Eligibility needs a price at `i` and at both window endpoints; ties break alphabetically
  so every downstream result is deterministic.
- **`RotationBacktester`** — THE single accounting path for every variant, control and bootstrap
  iteration (same discipline as trend `Backtester`), differing only by a `Selector` (`topN` /
  `bottomN` / `allEligible` / `randomN`). Monthly: rank → select → target `1/slots` per name
  (shortfall in cash at rf; `slots=null` = EW baseline's `1/chosen`); kept names re-equalise so
  they pay only drift-correction cost; `cost = costPerSide × Σ|Δweight|`; turnover `= Σ|Δw|/2`.
  Between rebalances weights drift with total return, the cash slot earns `rfDaily`; a name that
  goes NaN mid-hold is frozen (visible in the gap log), never dropped. **Common window:**
  `analysisStart` = first month-end with ≥ `minEligibleFactor×max(topNs)` eligible names, and every
  variant/control/iteration starts there — expanding universe, names enter as history accrues.
  The initial entry's cost folds into daily-return bar 0, so fit/holdout slices partition exactly.
- **`MomentumStats`** — CAGR (365.25-day), ann vol (√252), Sharpe (flat rf), maxDD with peak/trough
  dates, Calmar, monthly returns (compounded daily slice, "YYYY-MM"), hit rate vs the EW baseline,
  best/worst month. Computed over a `[fromK,toK]` slice of one full run, so fit and holdout come
  from the same accounting.
- **Controls (the point of the exercise).** `RotationBootstrap` — `bootstrapIterations` (1000)
  random-N draws from the same eligible set through the same path, seeded `randomSeed + N`
  (reported); reports `Stats.Dist` of CAGR/Sharpe/maxDD + the actual variant's percentile
  (`cagrInsideIqr` ⇒ UI dims the cell = indistinguishable from random selection). **Fit window
  only** — the holdout never feeds a control. Plus the EW-universe baseline and the **reversed
  signal** (bottom-N matched over the same windows: if worst-N beats top-N the sign/data is wrong)
  and QQQ/SPY buy-and-hold.
- **`RegimeSplit`** — §E-convention split of held months by whether the regime index (QQQ) sat
  above/below its 200d MA at the *positioning* month-end (look-ahead-free); thin buckets flagged,
  pre-MA months bucketed "unknown". **`CrashDiagnostics`** — worst 3 strategy months with QQQ's
  same-month return, plus the strategy's compounded return over the `crashWindowMonths` after each
  month-end where QQQ closed ≥ `crashDrawdownPct`% below its running high (contiguous month-ends =
  one *episode*; windows past the section end are censored, counted, never truncated).
- **`MomentumAnalysisService`** (application) assembles `MomentumReport`: survivorship caveat,
  reproducible seed, expanding-window start + reason, **fit vs holdout** boundaries (recent
  `holdoutMonths` reserved; the split is leak-free because selection at `t` only reads data ≤ `t`),
  per-symbol spans, missing-symbol + gap logs, per-variant fit section (stats + turnover + cost
  drag [CAGR at 0 bps − at configured bps] + regime + crash + bootstrap), lean holdout stats,
  QQQ/SPY benchmarks, and an exportable rebalance log. **Two KV keys:** `momentum_universe` (the
  ranked names, user-editable) and `momentum_symbols` (the fetch set = universe ∪ benchmarks ∪
  regime index, unioned into `PriceFetchSupport.tickersToFetch`) — kept distinct so benchmarks
  never leak into the ranked universe. Both written by the constructor seed and `ensureBackfilled`
  **only — never by `report()`** (an exploratory `?universe=` sweep can't drop names from nightly
  maintenance); `ensureBackfilled` deep-fetches missing/shallow coverage (45y, same rationale as
  Trend). No FX — unit-less return ratios throughout.


## Momentum-signals comparison

Five-variant signal-speed/overlay comparison (Mom. signals tab) on the pinned 17-ETF asset-class
universe, judged by a pre-registered adoption rule. Extends the momentum harness — no second
accounting path.

- **Ranker abstraction.** `domain.momentum.Ranker` is the only thing variants may differ in
  (plus the two overlays); `Ranker.incumbent(cfg)` delegates to `Momentum.rank` verbatim, so the
  generalized `RotationBacktester.run(…, Ranker, …, trailingStopPct)` overload with the incumbent
  ranker is **bit-identical** to the original path (asserted at domain and service level — the V1
  reproduction requirement; the old 6-arg `run` delegates, all existing callers unchanged).
  `SignalRankers.lookbackSkip` (V2: 126/21 scoring, `eligibilityLookback` 252 held constant so a
  faster signal doesn't also get a broader early universe) and `SignalRankers.compositeZ` (V3:
  population-σ cross-sectional z per component over the eligible set, degenerate component → 0,
  composite = mean of z's; hand-checked in `SignalVariantsTest`).
- **Overlays.** V4 = `RotationBacktester.gatedTopN(n, hurdle)` with `hurdle = (1+rfDaily)^(lookback−skip)−1`
  (compounded-daily rf accrual, matching the cash leg) and `slots = n` so a gated slot stays in
  cash; gate re-evaluates held names (no grandfathering); binds derived from events as
  `min(n, eligible) − held`. V5 = `trailingStopPct` inside the daily loop: peaks track
  split-adjusted `close` (what a real stop order watches — P&L stays in adj space), include the
  entry close, absorb every session incl. rebalance days; the trigger check is **skipped on
  rebalance days** (the ranking supersedes); at-or-through fills at that close, cost on the
  weight sold, `StopEvent`s surface in `Result.stops` (censoring/turnover conventions unchanged).
  Whipsaw = stop then re-entry within 2 rebalances; reported with txn cost (bps) + TR gap
  stop→re-entry (negative = re-entered cheaper). V5 turnover includes stop sells (`weightSold/2`).
- **Shared control.** `RotationBootstrap.samples`/`of` split lets one random-N distribution per N
  (seed `randomSeed+N`, fit window only) rank all five variants — the control is
  selection-agnostic (ranking orders the eligible set, never changes membership), and overlays are
  deliberately not applied inside draws: the bootstrap prices selection skill on one yardstick.
- **`MomentumSignalsService`** (application) pins `SIGNALS_UNIVERSE` (17 ETFs incl. REIT, listed
  2021 — enters as history accrues) and assembles `MomentumSignalsReport`: context + 3-bullet
  pre-registration + ≤10-sentence §0 all **verbatim constants**; per-N `VariantRow`s (fit/holdout
  stats, turnover, cost drag, shared-bootstrap percentiles, regime, crash, 2026-YTD, logs); the
  **adoption verdict** (challenger must beat V1 on Sharpe AND Calmar in fit AND holdout, same N;
  NaN comparisons fail conservatively — undefined holdout Calmar keeps the incumbent); the
  **POST-HOC flag** recomputes the holdout leg on the ex-2026 slice (`CONTAMINATED_FROM`
  2026-01-01; contiguous tail, so the same stats path serves it) — an edge that only exists with
  2026 included is flagged whatever the rule says; five **predictions** scored per N with stated
  thresholds (`SHARPE_NOISE` 0.10, |ΔCAGR| ≤ 1pp, YTD ∈ (0,10%)); the **episode panel**
  (5 fixed windows × 5 variants at `episodeN`, default middle N) lists positioning + in-window
  holdings and GLD derate/gate/stop dates, labelled non-decisional. Helpers (`logRows`, `spans`,
  `indexMonths`) shared from `MomentumAnalysisService` (package-private statics). KV
  `momentum_signals_symbols` (universe ∪ benchmarks ∪ regime) unioned into
  `PriceFetchSupport.tickersToFetch`; written by constructor seed + `ensureBackfilled` only
  (standard 30y fetch — the cohort lists post-1993). No universe editing endpoint — the pin is
  part of the pre-registration. First adoption run (2026-07): all challengers REJECT at every N;
  the incumbent stands.


## Panel overviews

- **Momentum** — `GET /momentum` → JSON, rendered as tables + a monthly grid under the Strategies
  group.
  Cross-sectional 12-1 momentum rotation over a fixed ~33-name US large-cap universe: rank monthly
  by trailing 12-month return skipping the last month, hold top N (3/4/5) equal-weighted. The point
  is the comparison against controls, not raw CAGR (the universe is survivorship-biased, flagged in
  a red header banner): equal-weight baseline, matched random-selection bootstrap (dimmed inside the
  IQR), reversed-signal (bottom-N) check, QQQ/SPY. Recent 12 months held out as a labelled holdout;
  turnover + cost drag, a 200d-MA regime split and momentum-crash diagnostics per variant; expanding
  window; exportable rebalance log. See the Momentum section below.

- **Mom. signals** — `GET /momentum-signals` → JSON, rendered as tables under the Strategies group.
  Five-variant signal-speed/overlay comparison on a **pinned 17-ETF asset-class universe**
  (sector SPDRs + EFA/EEM/TLT/IEF/GLD/REIT): V1 incumbent 12-1 (must reproduce the Momentum tab
  bit-for-bit — asserted in tests), V2 6-1 (same 252-session *eligibility*, so signal speed isn't
  confounded with universe breadth), V3 equal-weight 3-1/6-1/12-1 cross-sectional z-blend, V4
  12-1 + absolute rf gate (a top-N slot idles in cash when the name's own 12-1 return ≤ the rf
  accrual over the window), V5 12-1 + 15% trailing stop off the peak close since entry (sells at
  that close, cash to next rebalance, free re-entry — whipsaw cost is part of what's measured).
  Verdicts follow a **pre-registered adoption rule** (beat V1 on Sharpe AND Calmar, in fit AND
  holdout, at the same N) printed verbatim, with a contamination disclosure (variants designed
  after peeking at 2025-26) and an ex-2026 POST-HOC re-check of any holdout edge; five
  pre-recorded predictions get per-N pass/fail marks. Episode panel (2008/2011/2020/2022/
  2026-gold, with GLD derate/gate/stop dates) is explicitly **non-decisional**. One shared
  random-N bootstrap per N ranks all five variants (the control is selection-agnostic). See the
  

