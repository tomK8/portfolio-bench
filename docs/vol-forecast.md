# Volatility forecast, drift & the touch model

Consumed by the Open orders panel — see [open-orders.md](open-orders.md).

- **The volatility forecast — `domain/VolForecast`.** The single resolution point for σ_eff, and the
  reason the touch columns are not scaled by a trailing window. Realized vol mean-reverts, so
  pricing a 90-session horizon off the last 90 sessions reads a name fresh from a violent fortnight
  as though the next quarter stays violent, and one asleep for a month as though it stays asleep.
  What a first passage over N sessions needs is the expected **average** variance over those N
  sessions, so current state is blended toward a long-run anchor with the weight decaying at a
  tunable half-life:
  `κ = ln2/halfLifeDays` (default 20), `var_now = RV20²`, `var_LR = RV250²`,
  `decay = (1 − e^{−κN})/(κN)`, `var_N = var_LR + (var_now − var_LR)·decay`, `σ_eff = √var_N`.
  `decay` is the mean of `e^{−κt}` over `[0,N]`: → 1 as N → 0 (guarded as a genuine 0/0 with the
  series expansion below `SMALL_KN`), → 0 as N grows. Every input is a **daily-return stdev**,
  matching σ_eff's units — nothing here is annualised.
  **Total variance still grows with N** even when the blend pulls σ_eff *down*:
  `N·var_N = N·var_LR + (var_now − var_LR)(1 − e^{−κN})/κ`, whose derivative is bounded below by
  `min(var_now, var_LR) > 0` — so P(touch) can never go backwards as the horizon lengthens, which a
  test pins over N ∈ [1, 1250] on a reverting series.
  **Degrades rather than failing**, and says which: `Source` = `BLEND` / `CURRENT_ONLY` (no anchor)
  / `ANCHOR_ONLY` / `NONE`, recorded per row and per calibration line. The anchor carries a quality
  bar — `MIN_ANCHOR_RETURNS` (120): `PriceStats.dailyVol` shortens its window to whatever the series
  offers rather than returning null, so a name listed four months ago would hand back a 60-session
  "RV250" that is really current state under another name, and the blend would revert toward the
  very thing it exists to correct.
  **Event variance (`useEventVar`) has shipped; only `useImpliedVol` is still a reserved seam.**
  Diffusive vol cannot see the calendar: a name quiet for twenty sessions that reports next month is
  not a quiet name, and no blend of RV20 toward RV250 will discover that. `VolForecast.withEvents`
  adds `Σ move²` for every scheduled print inside the horizon —
  `σ_eff = √((σ_diffusive²·N + Σ move²) / N)` — on **every** branch including the degraded ones,
  since a short-history name with a print next week is exactly where the calendar carries most of
  the information. Dates come from `application/OrderEventVariance` (the cached Nasdaq calendar and
  nowhere else); sizes from `domain/EventMoves` (`event-moves.properties` — 6% default for a
  single-name equity, per-symbol overrides, zero for anything without a print), per class with
  overrides for the same reason drift is: a per-name estimate of "how much does this one jump" is a
  forecast of that name, and a wrong one is worse than a stated convention.
  **Nothing is inferred.** A date we don't have produces no event and a stated reason, never an
  estimate — a quarterly cadence extrapolated from the last print would land within a week of the
  truth most of the time and be indistinguishable from a real date on screen, which is the exact
  failure mode this panel exists to avoid. Coverage is therefore reported in three states a blank
  would conflate: `COMPLETE`, `PARTIAL` (the roster was fetched only part of the way out — a
  never-fetched market-wide roster makes *every* symbol look event-free) and `NOT_COVERED` (a
  US-only feed, so a `.L` or `.PA` line is never in it). `MAX_LOOKAHEAD_DAYS` (400) caps the scan
  however long a horizon the reader types.
  The switch is stored app-wide exactly like the drift one — `POST /orders/event-var?enabled=` →
  `OpenOrdersService.setUseEventVar`, resolved through the same `effectiveConfig` precedence
  (explicit query param → stored setting → shipped default).
  `useImpliedVol` remains declared and unused: `resolve` carries an explicit no-op branch for it so
  that when an implied-vol term structure arrives, it arrives *in that method* and nowhere else.
  `halfLifeDays`, `gtcHorizonSessions`, `useDrift` and `useEventVar` are parameters, not constants,
  and are query params on `GET /orders?halfLife=&gtcSessions=&useDrift=&useEventVar=` (and on
  `/orders/touch`), the same "every knob is a query param" convention the strategy tabs use.
- **`VolForecast.sequence` — the same forecast asked about a *sequence* of barriers.** The Ladders
  block's round trip needs two touches in order and inside one window, and it is not a product of
  two `touch` calls: the second leg gets only what the first leaves of the horizon and starts from
  the first barrier rather than from today's price. Everything around the first passage is the
  single-barrier path unchanged and deliberately so — one `resolve` (blended for *this* horizon, not
  for a leg of it), one drift netting, one RV10/RV30 bracket. **One σ_eff across both legs**, because
  how the window splits between them is a random variable: there is no per-leg window to forecast
  for, and blending a second σ for "the rest of the horizon" would be forecasting off a quantity the
  model has not drawn yet. One barrier reduces to `touch` exactly, by calling it. The first passage
  itself is `PriceStats.sequentialTouchProbability` — see [open-orders.md](open-orders.md) for the
  convolution and why its quadrature grid is clustered toward zero. **No calibration line is
  written**, on the probe's argument: the log's grain is one order and one fill, and a round trip is
  neither.
- **The drift switch is one stored, app-wide setting**, not a per-row option and not browser state.
  A checkbox on the panel posts `POST /orders/drift?enabled=` → `orders_use_drift` in the
  `KeyValueStore`, and **everything resolves through `OpenOrdersService.effectiveConfig`**:
  the table, the pre-trade probe, the `P(N)` re-price box, the Excel export and therefore the
  calibration log. Precedence is **explicit query param → stored setting → shipped default
  (false)** — a null `useDrift` means "not stated", which is why the resolution can't live in
  `VolForecast.Config.of` (the domain has no store to read) and why a one-off `?useDrift=false`
  against a stored `true` works without moving the switch. Storing it server-side rather than in
  the page is what stops a downloaded spreadsheet being priced on a different model from the screen
  it came from; it also means **`dashboard.js` needs no drift logic at all** — the `/orders/touch`
  call omits the flag and inherits the setting. `kv` is null-guarded (several callers construct the
  service without a store, and a missing store is "nothing set", never a failure).
  `OrdersReport.config` echoes the config actually used, so the checkbox renders from server state
  and can't show a position the numbers beside it don't reflect (same convention as `GET /swing`
  echoing its effective `SwingConfig`). When drift is on the panel **says so where the numbers
  are** — the P(N) footnote names the model and warns that `P(5d) = 2·Φ(−|σ→lim|)` no longer holds —
  and the Excel sheet gains a `Drift (annual)` column carrying the **netted per-row** drift the
  model consumed (`driftPerSession × 252` — an 8% class figure less that name's yield, so it varies
  row to row and is 0 for gilts and bullion), since which of the two models
  produced a probability is exactly what a reader months later cannot otherwise recover.
- **The drift branch (`useDrift`, default false).** `VolForecast.touch` picks between two models and
  is the only place that does:
  `useDrift && μ ≠ 0` → `PriceStats.touchProbabilityDrifted`, else the driftless
  `2·Φ(−|b|/(σ_eff·√N))`. **The two are mathematically identical at μ = 0**, so the short-circuit is
  a numerical safeguard, not a behaviour switch: the drifted form carries an `exp(2νb/σ²)` term that
  is `0/0` when σ is degenerate, and the driftless branch never evaluates it. Both formulas stay
  callable and both stay under test — `PriceStatsTest` asserts the reduction on the formulas
  themselves (to the known rounding bound of 1.4e-4, since the driftless path goes through
  `sigmaDistance`'s 4-dp intermediate), and `VolForecastTest` asserts it *through the branch with
  the flag on* against a zero-drift class, which is what shows the wiring didn't change the maths.
  **The drift term is signed by which side of the market the barrier is on** — a rising asset is
  less likely to come back to a limit below it and more likely to reach one above. The textbook
  upper-barrier statement fed `|b|` raises both, which for a buy limit (the common case here) is
  backwards. The RV10/RV30 range takes the same branch as the headline; a drifted headline inside
  driftless bounds would be a range around a different question. With drift on, `P(5d)` no longer
  equals `2·Φ(−|σ→lim|)` by construction — σ→lim is a distance, not a probability — which is one
  more reason the default is off.
- **`domain/AssetClass` — the class figure is a *total return*, and the model needs a *price*
  drift.** `EQUITY` 8%/yr, `ETF` 8%/yr, `GILT` 0, `COMMODITY` 0, `UNKNOWN` 0. That figure is not
  what the first passage consumes; two conversions happen in `VolForecast.touch`, in this order:
  `mu_price = dailyTotalReturn() − forwardYield/252`, then `ν = mu_price − σ_eff²/2`.
  **The first conversion is the load-bearing one.** A resting limit is touched by *price*, not by
  price plus reinvested dividends, and `PriceStats` deliberately measures σ off `close` rather than
  `adj_close` ("not total-return figures"). Feeding a total return in as the drift put the two legs
  of the same formula on different bases and over-drifted every dividend payer. It is not a uniform
  error that cancels: the dispersion inside "equity" runs ~7pp between an accumulating tracker and a
  high-yielding REIT like PHP, so a single assumed yield would just be the old scalar with a smaller
  constant on it. **The yield is therefore per symbol** — resolved by `OpenOrdersService` and passed
  in as a `VolForecast.Yield`, so the *class* figure stays a modelling convention while the netting
  gets the per-name dispersion that is the whole point. A per-name expected *return* would still be
  a forecast of that name wearing a parameter's clothing; a per-name yield is a published fact.
  `dailyTotalReturn()` divides by 252, and so does the yield, the same clock the vol scaling assumes
  (σ√N) — drift, yield and diffusion on one calendar.
  **`appliesYield()` is false for every zero-return class**, and that is arithmetic rather than
  tidiness: a gilt starts at zero, so any subtraction hands it a *negative* drift it never asked
  for, and a non-zero μ would also drop it off the driftless branch (`probability()` branches on
  `mu.signum() != 0`). Netting is likewise skipped entirely when `useDrift` is off — μ_total is
  already ZERO there, so subtracting would manufacture drift out of a model the reader switched off.
  **A negative `mu_price` is never clamped.** A name yielding more than its class's total return
  really is expected to drift down in price, and a floor would silently truncate exactly the case
  the netting exists to reveal. The guard is on the *input* instead:
  `OpenOrdersService.MAX_PLAUSIBLE_YIELD` (0.15) rejects a stale dividend rate over a collapsed
  price, or a value shipped percent-scaled into a field the codebase reads as a fraction, and logs
  it — the row falls back to its gross figure flagged as a fallback.
  `Instruments.assetClassOf` resolves the class: `isBond` → `GILT`, bond funds (VGOV/IGLT/GLTY) →
  `GILT` (a gilt in an ETF wrapper is still a gilt), bullion ETCs → `COMMODITY`, everything else →
  `EQUITY`. **`ETF` is declared but never produced**: it carries the same 8% as `EQUITY`, so
  detecting it would need a maintained fund-ticker list whose only possible effect is the label on a
  calibration line — a list that can only ever be wrong and never right. Bond and bullion wrappers
  *are* detected, because for those the underlying changes the number.
  `bullionEtcsInInstrumentsAreAllZeroDrift` pins the bullion set itself. **`Instruments` is now the
  only home of those sets**: the allocation view's two classifiers used to keep partial copies in
  `sectors.properties` / `style.properties` (three of eleven bullion ETCs, one of three bond
  funds), and the warning this paragraph carried — that holding one of the eight in the gap would
  drop it into the `UNCLASSIFIED` strip — is exactly what happened to SGLN. Both classifiers now
  defer to `assetClassOf`, and a guard test fails the build if an `override.*` line reappears for
  a symbol it already places. See [allocation-breakdowns.md](allocation-breakdowns.md).

- **The yield source — derived, because Yahoo publishes no forward yield.**
  `FundamentalsRepository.loadForwardYields(Set<String>)` reads `dividendRate ÷ price` from
  `fundamentals_snapshot`, falling back to the trailing `dividendYield`. `dividendRate` is the
  *indicated annual* dividend — declared and forward-looking — so the quotient responds the right
  way to a moving quote: the numerator is sticky, so a price that halves doubles the yield and
  halves the price drift. The divisor is the **stored** price from the same fetch, not a live quote:
  both come from one Yahoo response and are therefore in one currency, whereas dividing a
  listing-currency dividend by an order-currency quote would be a silent FX error. The cost is that
  the yield tracks the ~4h fundamentals refresh rather than the tick, well inside the rate at which
  yields move. **The two fields are not always in the same units**: Yahoo prices a London listing in
  `GBp` (pence) but quotes its dividend rate in pounds, so the raw quotient is 100× too small for
  every `.L` name — LGEN read 0.076% against a real 7.71%, LMP 0.067% against 6.53%. The price is
  shifted with `movePointLeft(2)` on the same `"GBp"` test the rest of the app uses. Note the
  plausibility bound could never have caught this: the error runs *small*, so an under-netted drift
  looks entirely ordinary, and it lands on precisely the high-yield UK names the netting exists for.
  Live check after the fix: LGEN nets to a 0.37%/yr price drift, LSEG 6.21%, NVDA 7.52% — under one
  flat 6% they were identical. **When Yahoo publishes neither field, the yield falls back to one derived
  from the name's own payment history** (`DividendYieldService` — see [price-history.md](price-history.md)) — `OpenOrdersService.forwardYields`
  merges the two, published first. The order matters: `dividendRate ÷ price` is the *indicated* annual
  dividend, declared and forward-looking, while a derived figure is necessarily trailing, so preferring
  the derived one would swap a forecast for a record of the past on every name that has both. The
  fallback only fills genuine holes. HICL is the live case — a ~6% infrastructure trust that published
  nothing, so it took the full 8% `EQUITY` drift with `fwdYield=0 yieldFallback=true`, and now nets
  5.96%. **A second, quieter gain: `yieldFallback` finally means what its doc claims.** It exists to
  separate an accumulating fund (genuinely zero, correctly full drift) from a name whose yield never
  resolved (also zero, wrongly full drift) — but before this *both* printed `true`. A scanned
  non-payer now resolves an explicit zero, so TSLA/NFLX/SNPS report `fwdYield=0 yieldFallback=false`
  and only a genuinely unknown name still flags. **A symbol with no usable yield is absent from the map, never present as zero** — the
  caller has to tell "pays nothing" from "we don't know", and Yahoo publishes no distribution yield
  for any fund held here, so the second case is the common one. It is a targeted read rather than
  `loadAll()`: that one scans the whole table (~100 rows) and deserializes four JSON columns per
  row, three of them irrelevant here, and `/orders` re-fetches on every tab click.
- **Calibration record.** Every priced order writes one line per snapshot to a dedicated logger
  (`com.portfolio.calibration.touch` → `~/log/portfolio-bench-touch-calibration.log`, monthly
  rolling, 36 kept, `additivity=false` so it stays out of the app log). The question it exists to
  answer — did orders shown at P=60% fill about 60% of the time? — can only be asked months later,
  and only against the model that produced the number, so each line carries the inputs as well as
  the output: `broker symbol ref side limit current entered expiry sessions fromExpiry
  sigmaEffDiffusive sigmaEff decay volSource assetClass driftUsed muTotal fwdYield yieldFallback
  muPrice nuUsed eventCount eventVarAdded eventCoverage rv10 rv20 rv30 rv250 p pLow pHigh` —
  `driftUsed` is per line, so snapshots taken either side of a switch flip stay analysable together,
  as `key=value` pairs (a later analysis is a grep and a split, not a parser). **The drift is three
  numbers and a flag, not one**: collapsed into a single figure, an over-optimistic probability
  cannot be attributed months later between a wrong vol forecast, a wrong return assumption and a
  yield that silently failed to resolve. `yieldFallback` is what separates an accumulating fund
  (genuinely zero, correctly full drift) from a name whose yield never resolved (also zero, wrongly
  full drift) — identical arithmetic, and nothing else tells them apart. The **probe path deliberately writes nothing** — a hypothetical
  in the record is indistinguishable from a real order that never filled. `src/test/resources/
  logback-test.xml` sets that logger to OFF for the same reason: a suite pricing fabricated orders
  would file fabricated records among the real ones.

