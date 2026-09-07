# Strategy: swing validation statistics


Backtesting/statistics tool (Swing stats tab) for validating a short-term mean-reversion
strategy — dip-buy after 1–2 day flushes, exit at a fixed take-profit / wide stop / time stop —
*before* trading it. Read-only: it never touches the ledger or places an order.

- **Pure engine** lives in `com.portfolio.domain.swing` (framework-free, unit-tested like
  `PriceStats`). `SwingConfig` holds every knob (spec defaults); `SwingSeries` pre-computes the
  aligned `close/adjClose/high/low/logRet/simpleRet` arrays, a **look-ahead-free trailing-30d daily σ**
  (σ over returns strictly *before* each bar, so a σ-trigger judges a day against vol known going in),
  and a trailing **`sma[]`** (200d MA, `maWindow`) for the §A/§E regime flag. `preWindowSigma(i,days)`
  = the σ strictly before a flush window (`trailSigma[i-days+1]`), so the flush can't inflate its own σ.
  Engines: `RealizedVol` (§1), `ForwardReturns` (§2/§3/§4), `Flushes` (§5 profile + §6 barrier sim),
  `Placebo` (§6 controls), `ReturnCorrelation` (§7), `FlushEvents` (§A event log), `AnnualBreakdown`
  (§B per-year), `MaSplit` (§E 200d-MA split). `Stats` provides mean/median/quartiles/%pos + a public
  `quantile`. All engines take a `List<SwingSeries>` so the same code serves per-symbol (one) and
  pooled-cluster (concatenate observations across the cluster, equal-weight by observation).
- **Trigger families (§C).** `FlushTrigger` abstracts "does a flush fire at bar `i`" with two impls —
  `AbsoluteFlush` (cum ret ≤ −threshold) and `SigmaFlush` (cum ret ≤ −k×σ×√N, σ = `preWindowSigma`).
  `Flushes` is parameterised by it, so the whole §5/§6/placebo/bootstrap/§A/§B/§E machinery runs for
  the absolute and σ-scaled families **in parallel** over each regime. The σ family fires at each
  regime's own scale, so a low-vol bear (2022) generates events the absolute trigger misses.
  `Flushes.overlapCount` reports the dates both barrier-seed triggers fire (never double-pooled).
- **Conventions:** returns off `close` (split-adjusted), log for vol, simple for anything P&L-like.
  σ-triggers compare the day's simple return to −k×(trailing daily σ); absolute triggers to −p%.
  A 1e-9 epsilon makes round thresholds (a "−10%" day stored as −0.0999999998) register. Small-N
  cells are flagged, never silently averaged (`smallNFloor`); **pooled** cells also report distinct
  event *dates* (episodes) beside the raw event count and flag `episodes < episodeFloor` (cluster
  names flush on shared dates, so 10 symbols × one crash ≈ 1 episode, not 10).
- **§6 event accounting** (the calibration core). Barrier touches are read from **intraday OHLC**
  (TP if session `high ≥ entry×(1+tp)`, SL if `low ≤ entry×(1−sl)`; fills at the barrier, time-stop
  exits at the close). `Flushes.simulateTrade`/`aggregate` are the shared single-trade engine (reused
  by the placebo). Five-way reconciliation holds in every table: **events = TP + SL + Time +
  Ambiguous + Open**. *Ambiguous* = both barriers touched in one session (excluded from the standard
  mean return; a `meanReturnConservative` books each as a stop for a pessimistic bound). *Open* = the
  outcome window runs past the last data date (censored — never dropped, never counted as an
  outcome). §6 events are **non-overlapping until the prior simulated trade resolves** (so the count
  legitimately shrinks as the time stop lengthens); §5 keeps its `flushCooldown` dedup for its
  descriptive profile. `Stats.Dist` and any nullable figure never emit a bare `NaN` — `config/JacksonConfig`
  serializes non-finite doubles as JSON `null` app-wide (empty distributions would otherwise corrupt
  the payload).
- **§6 controls (`Placebo`)** answer "does the flush trigger beat drift?": an **all-days placebo**
  (entry at every fully-evaluable close, overlaps allowed, `open == 0` by construction) and a
  **matched random bootstrap** (draw N = flush-event count random entry dates from that pool,
  `bootstrapIterations` times, seeded by `randomSeed`; reports the %TP / mean-return distribution and
  the percentile rank of the actual flush result — a rank inside the IQR means the trigger adds
  nothing). The bootstrap samples the precomputed placebo pool, so 1,000 iterations are cheap.
- **§A/§B/§E engines.** `FlushEvents` emits one diagnostic row per **cooldown-deduped** event (every
  down-leg, not the §6 non-overlap subset), resolved at `eventLogTimeStop` — cum return, σ-multiple,
  above/below-200d-MA + distance, outcome/exit/return, further drawdown, sessions-to-target, and the
  §D earnings flag; the row set is CSV-exportable in the UI. `AnnualBreakdown` groups events by
  calendar year (outcome counts + mean/median trade return) beside the name's own `adjClose` calendar
  return (pooled = member mean). `MaSplit` partitions §6 outcomes and the placebo pool by entry MA
  state and runs a within-state bootstrap when powered (pool ≥ 100, draws ≥ 5), else flags
  `underpowered`.
- **`SwingAnalysisService`** loads bars, runs every symbol + the pooled cluster over both §9
  regimes (full history + trailing 6 months, rendered **side by side**), and adds §7 correlation.
  Each `SymbolReport` reports its actual data span (`from`/`to`/`yearsAvailable`) and a `shortHistory`
  flag when the first bar post-dates the full-history cutoff. Each `Regime` carries `families` (abs +
  σ) + `familyOverlapCount`; each `FlushFamily` carries two `EarningsVariant`s ("all"/"ex-earnings",
  §D) of the §5/§6 tables (each §6 stop still a `BarrierComparison` of flush + placebo + bootstrap),
  plus the §A event log, §B annual and §E MA split. No FX — statistics are unit-less return ratios, so
  `.L` pence listings need no conversion. `SwingReport` (in `application`) is the JSON DTO;
  `earningsHygiene` is now a real §D coverage summary.
- **Config persistence:** the symbol universe + cluster live in `KeyValueStore` (`swing_symbols` /
  `swing_cluster`, seeded from spec defaults on first read). `swing_symbols` is unioned into
  `PriceFetchSupport.tickersToFetch`, so the nightly 30-year daily job keeps the swing universe
  backfilled automatically; `POST /swing/backfill` (and `POST /swing/symbols` after an edit) covers
  the first-run gap via `PriceFetchJob.fetchSingle` on a background executor. `GET /swing` echoes
  the effective `SwingConfig`; every knob is also an optional query param (percents in, e.g.
  `dropPcts=5,8,10`; σ-family knobs `flushSigmas`/`barrierSigmaK`, plus `maWindow`/`eventWindow`/
  `eventLogTimeStop`) so parameters can be swept without changing stored config.
- **§D earnings hygiene (active).** Earnings dates are proxied by **SEC EDGAR 10-Q/10-K filing dates**
  (submissions API), which land within a few days of the release — good enough for a ±window flag, and
  the only free multi-year source. `port/EarningsCalendarProvider` → `adapter/EdgarEarningsFetcher`
  resolves a ticker's CIK **dynamically** via `adapter/CikRegistry` (SEC's `company_tickers.json`, disk-
  cached at `<db-dir>/sec_company_tickers.json`, 30-day TTL, lazy load), so a new swing name needs no
  source edit; a small `CIK_OVERRIDES` map is kept only as an offline fallback and is consulted first.
  Dates are cached in `persistence/EarningsDateRepository` (`earnings_dates` table) so the report never
  re-hits SEC; `application/EarningsService` composes them and builds a per-series `EarningsLookup`
  (trading-days-to-nearest-earnings per bar). **Verification is data-driven** — a symbol is verified
  iff we actually obtained ≥1 date (`EarningsService.isVerifiable == repo.hasAny`), *not* CIK-presence.
  This is load-bearing: ASML resolves a CIK from `company_tickers.json` but files 20-F/6-K (zero
  10-Q/10-K), so it yields no dates and stays **unverified**; SEMI.L (UK, absent from the file) is
  unresolvable and likewise unverified — neither is ever treated as clean. To cap SEC traffic, every
  attempted symbol is recorded in a KV set (`earnings_checked`); a non-forced warm skips already-
  attempted names, so ASML-type "resolves-but-empty" names aren't re-fetched each report. The
  ex-earnings variant removes events within ±`eventWindow` sessions of an earnings date and drops those
  dates from the placebo/bootstrap pool too, via a per-`(symbol,date)` `EventFilter` (date-only would
  over-exclude ~10× when pooling a cluster). Report reads the cache; `report()` warms it best-effort
  (non-forced), while `POST /swing/backfill` **force**-warms — it refreshes the CIK registry and retries
  even previously-attempted names.


## Panel overview

- **Swing stats** — `GET /swing` → JSON, rendered as tables + a correlation heatmap under the
  Strategies group. Validation statistics for a short-term mean-reversion (dip-buy) strategy on a
  configurable high-vol semi/AI watchlist: realized-vol term structure; forward returns after
  down-day / flush triggers, always shown next to their unconditional baseline; an OHLC-touch
  barrier / time-stop trade simulation with full TP/SL/Time/Ambiguous/Open accounting, an all-days
  placebo and a matched-random bootstrap (does the flush trigger beat drift?); and cluster
  correlation (incl. tail correlation on down days). Every flush runs for **two trigger families in
  parallel** (§C) — an absolute −% drop and a σ-scaled −k×σ×√N flush that fires at each regime's own
  scale — each with an event log (§A), a per-year breakdown (§B) and a 200d-MA split (§E), in **all**
  and **ex-earnings** variants (§D). Everything is configurable and pooled across a "semi cluster"
  subset (pooled cells report distinct-date *episodes* alongside raw event counts); §9 regime split
  (full vs trailing 6 months) is shown side by side with each name's actual data span. §8 earnings
  hygiene is now active via an EDGAR filing-date proxy (US filers only; others flagged unverified).
  

