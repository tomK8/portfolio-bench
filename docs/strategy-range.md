# Strategy: range floor


Pre-registered backtest (Range floor tab) of a percentile-floor range strategy on the fixed
information-infrastructure cohort. The deliverable is the Arm A/B/C/D comparison — the report is
built to be able to answer "no, just buy and hold", and prints that verdict prominently when the
data says so. Read-only; never touches the ledger.

- **Pure engine** in `com.portfolio.domain.range` (framework-free; reuses `domain.swing.Stats`).
  `RangeConfig` (record + Builder, every knob a query param; the defaults *are* the pre-registered
  parameters — any override flags the run EXPLORATORY in the report). `RangeSeries` — aligned
  arrays + **look-ahead-free trailing percentile bands** (`trailingLevels` shares one sorted
  window per bar across all requested percentiles; the window is strictly-prior closes so a crash
  day can't drag its own floor down) + a >40%-daily-move data-error scan (flagged, never
  repaired). `RangeBacktester` — the single trade path: arm after `aboveRunSessions` consecutive
  closes beyond the (moving) band, enter at the close on touch, exits via the swing OHLC-touch
  convention (TP fills at barrier; trailing-P75 variant can legitimately exit *below* entry after
  a long hold; AMBIGUOUS carries no resolved return; OPEN is censored but carries the end-of-data
  TR mark so capital arms can value it). **Signals/barriers/MAE on split-adjusted price** (what a
  real limit order sees); **P&L in total-return space** — fills map via `fill × adjClose/close`
  on the fill bar (Trend's `adjOpen` convention), so dividends accrue to multi-year holds and
  the A-vs-B comparison is dividend-fair.
- **`RangeArms`** — one accounting path for A (cash-ledger walk, fixed £-notional per entry, rf on
  idle cash, symmetric on negative balances), B (buy at A's first signal, end-marked without exit
  cost — cost symmetry: every arm pays purchase costs, only realized exits pay the exit side),
  C (`randomArm`: same purchase count at random distinct sessions in the same window, held to
  end, precomputed hold-to-end growth per session so 1000 draws are cheap; seeded, seed logged;
  per-cell seeds offset like Momentum) and D (mirrored ceiling entries through the A path).
  **Two capital bases, both reported**: return-on-allocated (A/B commit one notional, C commits
  K×notional — bases differ by construction, the report says so) and return per pound-year
  deployed (normalises recycling vs parallel deployment; the bootstrap-percentile basis).
  `RangeEpisodes` — greedy calendar-day clustering (default 7d ≈ one trading week, cross-calendar
  safe) for pooled significance; cells under `episodeFloor` dimmed. `RangeTradeStats` — five-way
  accounting **events = TP + Pctl + SL + Ambiguous + Open** in every table; MAE/underwater include
  open trades ("hold until it bounces" includes the positions still waiting).
- **`RangeAnalysisService`** (application) assembles `RangeReport`: pre-registration block
  (verbatim, at top), exploratory flag + changed-param list, data panel (spans, currencies,
  hygiene flags), scopes = Pooled / Family A / Family B (GBP basis) + per-name (local), each with
  full-history and trailing-24m windows side by side (trailing = the same full-history simulation
  filtered by entry date — re-arm state is path-dependent, restarting on a slice would invent
  trades), verdict lines requiring A > B **and** A > C-median on both bases, sensitivity grid
  (P ∈ {5,10,15} × TP ∈ {8,10,12} × both modes × three scopes; pre-registered cell outlined,
  inside-IQR dimmed, report-never-select), CSV-exportable event log, and the featured LSEG.L
  chart (thinned closes + entry/exit/open markers). **FX**: per-name local-currency; pooled/family
  arms convert at daily Frankfurter rates on entry/exit/end dates (ceil-fill before 1999); FX
  failure degrades to per-name-only with a warning. `range_symbols` KV unioned into
  `PriceFetchSupport.tickersToFetch`; written by constructor seed + `ensureBackfilled` **only —
  never by `report()`**. Standard 30y fetch suffices (no deep-lookback special case — the cohort
  all lists post-1996).


## Panel overview

- **Range floor** — `GET /range` → JSON, rendered as tables + an annotated chart under the
  Strategies group. Pre-registered backtest of a percentile-floor range strategy on a fixed 12-name
  information-infrastructure cohort (Family A exchanges: LSEG.L, DB1.DE, ICE, CME, NDAQ; Family B
  data/publishing: MSCI, SPGI, MCO, FDS, REL.L, WKL.AS, TRI; LSEG.L featured): buy when the close
  hits the trailing-252-session P10 (look-ahead-free, strictly-prior window) after ≥10 consecutive
  closes above it (buys dips from above; same run must recur after every exit — the
  anti-machine-gun rule), sell +10% via the flush harness's OHLC-touch convention. Accumulation
  mode (no stop; MAE + time-underwater per trade, open positions censored) is primary; trading
  mode (−15% stop, ambiguous-as-stop conservative column) secondary; a trailing-P75 exit variant
  rides along. The verdict is Arm A (strategy, £10k/entry, idle cash at rf) vs Arm B (buy-and-hold
  from A's first signal) vs Arm C (same purchase count at random dates, 1000-draw seeded
  bootstrap, IQR-dimmed) vs Arm D (mirrored P90 entry — trend-vs-reversion sanity check), on two
  stated capital bases (allocated ≠ comparable across arms by construction; per-pound-year
  deployed is the bootstrap basis). Stamp duty (UK purchases only) + commission itemised per arm.
  Full history and trailing 24 months side by side; pooled/family/per-name with calendar-week
  episode dedup; sensitivity grid (P×TP, both modes) reported never selected, pre-registered cell
  outlined; any overridden parameter flags the run EXPLORATORY. Per-name results local-currency;
  pooled arms GBP via daily Frankfurter FX. 

