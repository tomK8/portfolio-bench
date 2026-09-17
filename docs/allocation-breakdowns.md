# Allocation breakdowns (sector, style, AI/Tech)

**One row per holding, not per listing.** Everything below is keyed by the symbols
`AllocationService` produces, and those are folded by `domain/ShareClasses` first: two listings the
user has declared a share-class switch between are one position, counted under the line the switch is
leaving. Each listing is still valued on its own quote in its own currency — only the GBP results are
added — so the fold changes no total, and the same rule is applied to concentration and currency
exposure. Why, and what would otherwise go wrong: [share-class-switches.md](share-class-switches.md).

## Investment-style breakdown (growth vs value/income)

Current-snapshot composition by **investment style**, rendered at the very **top of Allocation →
Breakdown**, above the sector block. `GET /allocation/styles` → `StyleBreakdownService`. Answers
what the sector split cannot: how much of the book is cheap, how much is growing, how much is
both, and how much of it pays an income.

- **Style is a 2×2, not a spectrum.** Cheapness and growth are scored independently, so
  `VALUE_GROWTH` (a cheap compounder) and `NEITHER` (expensive without the growth to justify it)
  are real cells rather than rounding errors. Collapsing them onto one value↔growth line forces a
  third of the book into whichever end it sits nearer — a loss-making biotech on a *negative*
  earnings multiple came out as "value" in the one-dimensional prototype.
- **Values** come from `AllocationService.currentSnapshot()` — the same replay the sector block and
  the charts below use, so the three can never disagree about what is held.
- **Zero new fetching.** Every input was already in `fundamentals_snapshot`. The one addition is
  fund-level valuation, parsed from `topHoldings.equityHoldings` — a sub-object of a module
  `YahooQuoteSummaryFetcher` **already requested** for sector look-through. **Yahoo ships those
  inverted**: `priceToEarnings` arrives as `0.03396` for a Nasdaq-100 tracker (the earnings
  *yield*, 1/29.4), so `putFundValuation` reciprocates once at the edge — storing them verbatim
  would put every fund under a P/E of 1 and read as the cheapest thing in the portfolio. They go
  into `extra` under `fund*` keys, which is free-form JSON on the way to SQLite, so **no
  migration**.
- **`domain.StyleBucket`** — the 2×2 (`VALUE`/`GROWTH`/`VALUE_GROWTH`/`NEITHER`) plus structural
  buckets `FUND_VALUE`/`FUND_BLEND`/`FUND_GROWTH`, `FIXED_INCOME`, `NON_EQUITY` (bullion: no
  earnings, no sales, no yield — a property of the asset, not a data gap), `CASH` and `UNKNOWN`.
  `scored()` marks which came out of the score; the UI dims the rest so the growth-vs-value
  comparison is read off comparable rows only.
- **`domain.StyleClassifier`** (framework-free, unit-tested). Resolution order:
  `Instruments.isBond` → `override.<SYMBOL>` → fund → equity 2×2. Each axis scores
  **achieved ÷ available** over weighted flags, so an absent metric abstains rather than counting
  against the position; an axis with fewer than `min.metrics` (2) inputs is not scored at all and
  the position lands in `UNKNOWN`, surfaced by name (same discipline as Attribution's `excluded`).

**Four data landmines, all found by running the thing against the real portfolio first:**
1. **Thresholds are absolute, not percentile ranks within the portfolio.** Ranking within a book
   holding both deep-value European banks and 85%-growth semis put the mega-caps mid-pack on
   *both* axes: GOOG, MSFT, AMZN, V and MA all came out "neither". That is an artefact of the peer
   set, not a judgement. Absolute anchors put them in Growth, which is obviously right.
2. **`priceToBook` is corrupt for non-US listings** — a currency-scale artefact reporting AZN at
   479.7 (really ≈4.8), REL at 3676.6 (≈36.8), ASML at 1563 (≈15.6). It would have marked every UK
   and European holding as ruinously expensive, exactly backwards for an income book. **Not used
   for direct holdings**; P/E, P/S and EV/EBITDA carry the value axis. The fund-level P/B from
   `equityHoldings` is unaffected and is used.
3. **`earningsGrowth` is single-quarter YoY and wild** (MU +1368%, AV. +979%, LGEN +507%), and
   **P/E compression fires for any cyclical whose earnings are recovering**. At their first weights
   (0.5 + 0.7 of a 2.2 denominator) the pair outvoted flat revenue and scored a recovering bank at
   0.545 — a growth label for a value stock. Now 0.4 and 0.5 against revenue growth's 1.0, so real
   top-line growth is close to necessary. Guarded by
   `StyleClassifierTest.cyclicalWithFlatRevenueIsNotGrowth`.
4. **A missing `dividendYield` means "pays none", not "unknown".** Yahoo omits the field entirely
   for a non-payer, so it is read as a genuine zero whenever any other metric is present —
   otherwise the denominator shrinks and every growth name gets a free pass on the value axis.
   `fcfGrowthYoy` has **zero coverage** in practice (Yahoo isn't returning 8 quarters); it is still
   wired and simply abstains.

**Funds sit outside the 2×2 on purpose.** Yahoo publishes a fund's aggregate price multiples but
returns null for `threeYearEarningsGrowth` on every fund held here, so there is no growth axis to
measure. Inferring "expensive therefore growth" would convert an absence of data into a finding,
so they get their own three buckets and the gap stays visible. `threeYearEarningsGrowth` is
deliberately **not stored** — its scale (fraction or percent, inverted or not) is unverifiable
when no value has ever been observed.

**A holding that publishes no yield now derives one from its own payment history.** Yahoo's
`summaryDetail` returns a near-empty payload for some listings — HICL.L, a London infrastructure
trust paying ~6%, has no sector, no `dividendYield` and no `dividendRate` — so `isIncome(null)` was
false and £10,220 sat in the Value row with no warning of any kind. The chart endpoint the price job
*already calls* ships that instrument's full ex-date/amount stream, so the yield costs **zero extra
HTTP**: see "Derived dividend yields" in [price-history.md](price-history.md). `StyleBreakdownService` prefers Yahoo's
published figure and falls back to the derived one, carrying an `IncomeContributor.yieldSource`
("Yahoo published yield" / "Derived — 4 payments to 2026-05-28 (trailing year clipped one …)") that
the income modal renders beside the number as a muted `derived` marker plus a hover. HICL now reports
**5.96%**.

**Funds are credited too, and the "lower bound" caveat is gone.** Every fund used to be excluded from
both yields because Yahoo publishes no distribution yield for any held here. With payment history the
distributing ones resolve real figures (EQQQ 0.24%, SPX5 0.91%) and — the part that matters — the
accumulating ones resolve a **genuine zero** rather than being assumed to pay nothing. A fund still
does **not** join the income *group*: that stays the scored equities plus fixed income, and a tracker
paying 0.9% is not an income holding. It contributes only to `annualIncomeGbp` / `yieldOnTotal`, so
`yieldOnIncomeHoldings` is unmoved (4.23% → 4.26%, the change being HICL) while `yieldOnTotal` runs
2.00% → 2.10%. The bridge still closes to the penny.

**Income is an overlay, not a fifth bucket** — the same relationship the AI/Tech tile has to the
sector table. Income names already sit in whichever cell they scored into (mostly Value, but BP.
and LGEN are in Value+Growth), so **the tile must never be added to the table**. It now spans the
**Fixed Income row too**, which is the one bucket that is an income holding *structurally* rather
than by clearing the yield bar: a gilt is what the income allocation **is**, and gating it on the
2.5% equity bar would have dropped `GILT 0.875% 2033` (£154k, bought for pull-to-par) out of a tile
whose entire job is to say how much of the book pays. Income holdings run £201k → **£501k** (38.5%
of total) as a result, and the basket yield honestly reflects the low-coupon line dragging it down.

**The gilt running yield is derived, never fetched: `nominal × coupon ÷ value`.** Both inputs are
exact — the coupon is in the id (`Instruments.couponOf`, a fraction: `"GILT 4.75% 2038"` → 0.0475)
and the nominal is in the ledger — so it needs **no price lookup and no FX**, and because `value`
came from the caller's own replay the quotient is precisely `coupon ÷ (clean price / 100)` against
the price the position was valued at. A second price read would be a second valuation path that
could disagree with the number printed beside it. The nominal reaches the classifier because
**`AllocationService.CurrentSnapshot` now carries `quantityBySymbol`** — kept off `AllocationPoint`
for exactly the reason `cashGbpByAccountCcy` is (that record is serialised ~550× by
`GET /allocation`). `Instruments.couponOf` is the single home of coupon-reading;
`HistoricalScenarioService.extractCoupon` delegates to it. Note reading a coupon and *building* an
id are different concerns — `giltId` still refuses to expose a trimming helper, because a caller
could then assemble the join key and forget to canonicalise it.

**Fixed income with no derivable yield counts by value and says so.** A bond fund overridden to
`FIXED_INCOME` (VGOV) has no coupon in its symbol, so it falls back to Yahoo's distribution yield;
with neither, the position is still an income holding — its value is real — but contributes
**null, never zero**, to the income figure and is named in a warning. A silent zero would
understate the portfolio yield with nothing on screen to say why.

**Two yields, because one is misreadable.** A single "portfolio yield" is either a strong-looking
number over a base most of the portfolio is not in, or a weak-looking one that understates what
the income holdings pay — and nothing said which. Both now ship with their denominators named:
`yieldOnIncomeHoldings` (= `incomeAnnualGbp ÷ income.gbp`, what the basket actually yields) and
`yieldOnTotal` (= `annualIncomeGbp ÷ totalGbp`, everything paid over everything held, cash and
funds included). **Their numerators differ**, so `otherAnnualIncomeGbp` carries the bridge — what
the holdings *below* the income bar pay — and `incomeAnnualGbp + otherAnnualIncomeGbp =
annualIncomeGbp` is printed in the drill-down so all three figures reconcile on screen rather than
looking like an inconsistency. Both remain a **lower bound**: funds pay distributions Yahoo does
not publish, so they are in neither numerator. `StyleBreakdownServiceTest` pins the two
denominators and the bridge closing.

**`resources/style.properties`** holds only *judgements* — thresholds, weights, and `override.*`
as a per-position escape hatch. No per-symbol multiples, no as-of dates, nothing that drifts.
Bullion ETCs and gilt funds are **not** listed: `StyleClassifier` defers to
`Instruments.assetClassOf` (`COMMODITY` → `NON_EQUITY`, `GILT` → `FIXED_INCOME`, the latter
routed through the same `fixedIncome()` path a directly-held gilt takes), so the wrapper sets have
one home — see the sector block's note for what the second copy cost.

**UI**: basis toggle (% of total / % of invested, both computed server-side so it toggles without
a refetch), an income tile carrying both yields ("Portfolio yield — 4.2% on income holdings · 1.8%
on total portfolio · £23,424/yr"), the **2×2 grid** (`.style-quadrant`,
left column cheap / top row growing), a doughnut, and a table whose Basis column separates
"scored on both axes" from "structural" and "not scored — see below". Clicking a table row, a
doughnut segment **or a quadrant cell** opens `#style-detail-modal` listing every holding in that
bucket with its two scores and **the individual tests it passed** — a placement you can argue with
beats one you can only accept. The row handler is delegated from the `<table>`, since the basis
toggle replaces its innerHTML. The block lives in its own **`#style-block`** container and passes
that id to `panelLoadFailed`/`clearPanelError`, so a styles failure cannot destroy the sector
doughnut or the allocation canvases below it; `loadBreakdownSubview` now drives **three**
independent latches.

Clicking the **income tile** opens `#income-detail-modal`: the headline split into a Fixed-income
section and an Equities section, each with its own subtotal row and yield, plus the bridge
sentence. They are two sections rather than one ranked list because they are not the same kind of
promise — a gilt's running yield is contractual and its capital is pulling to par, while a dividend
is a policy that can be cut, and one merged table invites reading a 4.99% gilt and a 4.6% bank as
the same thing. This **replaced** the inline `<details>` "What makes up the income figure" block
rather than joining it: two places listing the same holdings is one more than anybody reads. The
shared document-level Escape handler closes both style modals.


## Sector & AI/Tech breakdown

Current-snapshot composition by sector, rendered at the **top of Allocation → Breakdown** (the
existing charts shift down). `GET /allocation/sectors` → `SectorBreakdownService`. Two
**orthogonal** answers from one pass, which is the whole design: the sector split is a partition
(buckets sum to the portfolio total), while **AI/Tech cuts across buckets** — GICS files
GOOG/META/NFLX under Communication Services and AMZN/TSLA under Consumer Cyclical, so a
sector-only rollup reads Tech at ~11% when the real AI/Tech figure is ~34% of total / ~44% of
invested.

- **Values** come from `AllocationService.currentSnapshot()` — the single-sample form of
  `timeline()` (same idiom as `PortfolioValueService.dailyValuesIn`), sharing one `replay()` so
  the block cannot disagree with the charts beneath it. It returns the point **plus the
  per-`(account, currency)` cash detail** the replay tracks and otherwise discards; that detail is
  deliberately **not** on `AllocationPoint`, which `GET /allocation` serialises ~550 times.
  `currentPoint()` remains as the thin accessor (`EarningsCalendarService` uses it). The ledger still replays from its first
  row, but none of the ~550 weekly snapshots are valued. **Don't use `SyncFromCashService.sync()`**
  here — it hits the network for FX on every call, throws on failure, and drops equities with no
  intraday quote.
- **`domain.SectorBucket`** — 15 buckets incl. `FIXED_INCOME`/`CASH`/`COMMODITIES` as top-level
  (so equity percentages aren't diluted by gilts or idle cash) and `UNCLASSIFIED`.
  `fromYahooSector` accepts **both** Yahoo spellings — display form from `summaryProfile`
  ("Financial Services") and snake_case from `topHoldings` ("financial_services") — which is what
  lets a fund's constituent sectors and a directly-held stock's sector be compared at all.
  **The two Consumer sectors are separate buckets** (`CONSUMER_DISCRETIONARY` /
  `CONSUMER_STAPLES`, labelled with the GICS names). They were briefly merged into one "Consumer"
  row, which cost information for nothing: staples and discretionary are the defensive/cyclical
  pair and behave differently in a drawdown, and one row at ~6.8% hid a book that is two-thirds
  discretionary. The merge bought nothing either — Yahoo publishes the two separately in *both*
  shapes ("Consumer Cyclical"/"Consumer Defensive" on a stock, `consumer_cyclical`/
  `consumer_defensive` inside a fund's `sectorWeightings`) — so the split needed no extra data, no
  extra fetch and no look-through change, only a second enum constant and a `SECTOR_COLORS` entry.
  The book is roughly two-thirds discretionary, which the merged row hid.
- **`domain.SectorClassifier`** (framework-free, unit-tested). Resolution order:
  `Instruments.isBond` → `override.<SYMBOL>` → **wrapper asset class** → **fund look-through** →
  Yahoo sector → `UNCLASSIFIED`. Anything unclassifiable is surfaced by name in an amber strip,
  never absorbed into a neighbouring bucket (same discipline as Attribution's `excluded`).

  **The wrapper-asset-class step is what stops `sectors.properties` needing maintenance.** A
  bullion ETC has no Yahoo sector and never will, and a gilt ETF's equity sector-weightings would
  be meaningless if it published any — so both used to need an `override.*` line, duplicating a
  judgement `Instruments` already held. It listed **eleven** bullion ETCs and three bond funds;
  `sectors.properties` listed three and one. **SGLN — the gold ETC actually held — was among the
  eight in the gap**, sitting in the red "excluded from every sector" strip as though the feed had
  failed, when it is simply gold (2026-08-25); buying any of the other seven would have done the
  same, and CLAUDE.md had predicted exactly this two sections down. Both classifiers now defer to
  `Instruments.assetClassOf` (`COMMODITY` → `COMMODITIES`, `GILT` → `FIXED_INCOME`), so the
  wrapper sets are **one list rather than two partial ones** and a new metal ETC added to
  `Instruments` is placed in both blocks with no properties edit. Only those two classes are
  consulted: `EQUITY` is the catch-all `assetClassOf` returns for anything unrecognised, so acting
  on it would mean overriding the feed with a default. **The step sits after the override** (the
  escape hatch still wins) **and before look-through** — a wrapper that did publish sector weights
  must not be decomposed into equity sectors it does not hold.
- **"Unclassified" is three states, not one** — and only the last is a finding. A held name has no
  sector until `fundamentals_snapshot` has a row for it, and that cache is filled by
  `FundamentalsFetchJob` on a **4-hour** `fixedDelay` over the `held_symbols` set, so anything
  bought since the last tick is un-*fetched*, not un-classifiable. XWIS (Xtrackers MSCI World
  Industrials, bought 2026-08-20) sat in a red "excluded from every sector" strip for four hours
  though Yahoo had it all along at 94.8% Industrials; ISRG was in the same state at the same time.
  So both breakdown services now report a `state` on each un-placed contributor —
  `AWAITING_DATA` (no row at all), `FETCH_FAILED` (row written `missing=true`, so Yahoo may just
  have been unreachable), `NO_SECTOR` / `NOT_SCOREABLE` (fetched fine, genuinely unplaceable — the
  bullion-ETC case that wants an `override.*` entry). **Only the last raises a warning or the red
  strip**; the first two render as an amber `.value-warnings.pending` panel saying a fetch is
  queued.
- **`FundamentalsWarmer` closes the gap.** The two services hand their `AWAITING_DATA` /
  `FETCH_FAILED` symbols to it and it fetches them on a background daemon thread
  (`BeanConfiguration.fundamentalsWarmer` → `PortfolioFundamentalsService.refreshSymbols`, 500ms
  throttle). **The render never waits** — the sector appears on the next load, the same bargain
  the Open-orders panel strikes when warming a lagging quote feed. **One warmer serves both
  blocks on purpose**: they load together in Allocation → Breakdown and are un-placed by the same
  missing row, so a per-service cooldown would fetch each new symbol twice, concurrently, on the
  first paint. 5-minute per-symbol cooldown, because both panels re-fetch on every tab click and
  a `missing=true` row keeps asking to be retried.
- **Fund look-through is fetched, never maintained.** Yahoo's `topHoldings` module (added to the
  existing `YahooQuoteSummaryFetcher` module list — **zero extra HTTP calls**, those ETFs were
  already being fetched) returns `sectorWeightings` + top-10 `holdings`; both persist as JSON
  columns on `fundamentals_snapshot` and refresh with `FundamentalsFetchJob`. A hand-written
  weight table was rejected because it would have been **wrong on day one**: MCTS reads as a
  semis fund by name but is 29.8% tech / 23.4% consumer / 18.6% comms (Tencent, Alibaba, CATL —
  it's China tech), and EQQQ is 57.8% tech, not the ~100% an eyeball guess suggests. A fund that
  stops returning weights falls to `UNCLASSIFIED` and is surfaced — never served stale.
  Weights are normalised by their own sum (Yahoo's total ~1.0001) and the rounding remainder is
  pushed onto the largest bucket, so **look-through never creates or destroys value** — asserted
  in `SectorClassifierTest`.
- **Two different measures — do not add them.** The sector table is a **partition** (every
  position in exactly one bucket, buckets sum to the portfolio); the AI/Tech tile is an
  **overlay** that cuts across buckets. The `TECH_AI` bucket's label is plain **"Technology"**
  (it *is* the GICS Technology sector; the constant name stays `TECH_AI` because it's the JSON
  key and the `SECTOR_COLORS` key in `dashboard.js`), and the tile reads **"AI/Tech theme
  (cross-sector)"**. They were briefly "Tech/AI" and "AI/Tech" — the same two words reversed,
  inches apart — which reliably read as a bug. **The AI/Tech figure is not a row in the table
  and must never be summed with it**: it already contains the whole Technology bucket.
  The bridge is exact and printed on screen:
  `aiTechGbp = fromTechSectorGbp + crossSectorGbp`, with `fromTechSectorGbp` **equal to the
  Technology row**; the cross-sector part is the tagged names held directly
  (GOOG/AMZN/META/BABA/TSLA/NFLX) plus the same names inside the ETFs. Both parts are
  computed in `SectorClassifier` rather than subtracted by the caller, so a later change to the
  AI/Tech rule can't silently break the bridge; `SectorClassifierTest` asserts the identity and
  that `fromTechSectorGbp` equals the bucket. For funds the split is taken against the *bucket*
  value (`min`, then subtract), because that bucket may have absorbed a rounding penny from
  `absorbRounding` and the printed arithmetic has to hold to the penny.
- **AI/Tech share.** Direct: sector is Technology, **or** the symbol is in `aitech.include`.
  Fund: the `technology` weight in full, **plus** each named top-10 constituent on the list whose
  own sector is *not* Technology — the **double-count guard** (NVDA/AAPL/MSFT are already inside
  the technology weight). A constituent with no cached sector is **skipped**, keeping the result
  a genuine **lower bound**; Yahoo names only the top 10 (~46% of EQQQ), so the UI renders it as
  `≥` with the name-coverage %.
- **`resources/sectors.properties`** holds only *judgements*, nothing that drifts: `override.*`
  as a per-position escape hatch, and `aitech.include` (GOOG/GOOGL/META/AMZN/NFLX + TSLA/BABA;
  V/MA and LSEG/REL deliberately excluded). No weights, no as-of dates. The one live `override.*`
  is **HICL** → `UTILITIES_INFRA`: a London closed-end infrastructure trust is neither an operating
  company with a GICS sector nor an ETF that discloses its composition, so Yahoo returns no
  `summaryProfile` sector *and* no `topHoldings` weightings and the feed and look-through both come
  up empty. That is exactly what the escape hatch is for — the judgement is about what the trust
  owns (PPP/PFI projects, utilities, transport), which no source will ever return, and it does not
  drift. `SectorClassifierTest.hiclIsInfrastructureFromTheShippedProperties` pins it through the
  no-arg constructor so the properties entry itself is under test. **Bullion ETCs and gilt
  funds are deliberately absent and must not be re-added** — `Instruments` owns those sets and the
  classifier defers to it. `InstrumentsTest.neitherPropertiesFileRedeclaresAWrapperInstrumentsKnows`
  fails the build if an `override.*` line reappears for a symbol `assetClassOf` already places,
  since a shadowing copy is exactly how SGLN went missing.
- **UI**: basis toggle (% of total / % of invested, both computed server-side so it toggles
  without a refetch), AI/Tech tile showing the **bridge** (Technology sector + AI names filed in
  other sectors), doughnut, table with a **direct / via-fund** column keeping the inferred part
  visible, plus two collapsible sections — per-fund look-through provenance, and a per-position
  breakdown of the AI/Tech figure (`AiContributor.source` = `TECHNOLOGY_SECTOR` / `TAGGED` /
  `FUND`; a `TAGGED` row names the sector it's actually filed under, which is the whole
  explanation in one cell).
- **Sector drill-down.** Clicking a table row **or its doughnut segment** opens
  `#sector-detail-modal` (standard `.modal-backdrop` + `open` class; Close / backdrop / Escape all
  dismiss) listing every position feeding that bucket — `Row.constituents`, riding on the same
  payload, no extra fetch. The unit is the **position, not the underlying company**: a fund is one
  row carrying its slice ("EQQQ — via fund (17.8% of EQQQ)"). Name-level decomposition was
  rejected on the data — Yahoo publishes only a fund's top 10 and the foreign lines aren't in the
  fundamentals cache, so resolvable coverage runs EQQQ 46.3% / SPX5 36.4% / **RBTX 5.9% / MCTS
  0%**; a popup built on that would look like a full decomposition while showing a third of one.
  Positions keep every penny accounted for — constituents sum to the bucket and `pctOfBucket` sums
  to 1. The `CASH` bucket's constituents are the per-`(account, currency)` balances.
  **Constituents are grouped by industry with subtotals** (from the same cached quote the sector
  came from — no extra fetch). A GICS sector can hold businesses with quite different drivers:
  Financials is £168k of banks, insurers, payment networks and a data vendor in one row, and the
  split is what shows BNP and LGEN are 70% of it between them.

  **The heading is a maintained group, not Yahoo's raw industry** — `domain/IndustryGroups`, read
  from `sectors.properties`. Yahoo's taxonomy is finer than anyone reads a portfolio at: Financials
  arrives as six industries, splitting four banks and three insurers into groups of one and two.
  Two layers, and the order between them is the design. `industry.group.<slug>` maps a **Yahoo
  industry** to a group and is self-regulating — buy another regional bank and it lands in Banks
  with no edit, because the key is what the feed says rather than a list of symbols.
  `industry.override.<SYMBOL>` maps a **single holding** and wins, reserved for where the feed is
  wrong about the business: Yahoo files LGEN under "Asset Management", which describes what L&G
  does with the float rather than what it sells. Keys are slugs (lowercase, runs of
  non-alphanumerics → one hyphen), so one key matches "Banks - Diversified", "Banks—Diversified"
  and "Banks Diversified" — Yahoo has used the hyphen and the em dash for the same industry, and a
  key that broke on the difference would be a mapping that silently stopped applying.
  **An unmapped industry falls through to its own raw name, never to an "Other" bin**: a new
  industry then appears as its own one-row group the day it shows up, which is the signal that a
  mapping is missing. The map is currently scoped to Financials (Banks £74.8k / Insurance £80.1k /
  Payments £7.0k / Financial Data £4.3k); every other sector groups by its raw industry as before.

  **Yahoo's own value ships alongside in its own column** (`Constituent.industry` verbatim, beside
  `industryGroup`). A per-symbol override is the one layer an upstream change cannot dislodge — it
  goes on asserting its group whatever the feed later says — so those rows carry an amber
  `override` badge (`Constituent.industryOverride`) and the raw column is what makes a
  reclassification visible rather than silently overridden.

  The grouping is **drill-down only, never a top-level row**, and the distinction is the whole
  reason the Consumer split was possible and this one isn't: Cyclical/Defensive are two *sectors*
  Yahoo publishes separately in both shapes, including inside a fund's `sectorWeightings`, whereas
  industries exist only on direct holdings — `sectorWeightings` is sector-level, so a fund's slice
  has no industry at all. Promoting industries to table rows would mix two taxonomies in one
  partition and leave every fund slice homeless. Fund slices are instead pooled under a
  "via funds — sector-level only" heading, and a bucket whose holdings land in one group renders
  exactly as before (headings appear only when there is more than one group). The row
  click-handler is **delegated from the `<table>`**, since `renderSectorTable` replaces its
  innerHTML on every basis toggle.

  The block lives in its **own `#sector-block` container**, and `loadSectors`
  passes that id to `panelLoadFailed`/`clearPanelError` — passing the sub-view id would destroy
  the allocation charts' canvases on a sectors failure. Two independent latches
  (`loadBreakdownSubview` calls both) so one endpoint's blip doesn't take the other down.


## Allocation charts (the panels beneath the breakdown blocks)

- **Allocation** — `GET /allocation` → JSON, three Chart.js panels: cash-vs-invested
  stacked area, per-symbol stacked area (top 12 + "Other"), and a date-picker
  snapshot of the full breakdown on any chosen day. Weekly cadence (~550 samples
  over a 10-year history) via `AllocationService`; latest sample always pinned to
  today so the snapshot reflects current state even mid-week.
  The Breakdown sub-view carries two blocks above those charts, each with its own latch:
  the **Investment-style block** at the very top (`GET /allocation/styles` →
  `StyleBreakdownService`) and the **Sector & AI/Tech block** beneath it
  (`GET /allocation/sectors` → `SectorBreakdownService`) — see both sections below.
