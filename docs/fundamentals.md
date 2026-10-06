# Fundamentals snapshot — what each field means, and when it means nothing

The Fundamentals panel is a **cross-symbol comparison** screen. That single fact decides
everything below: a column is worth having only if it means the same thing in every row. One
that means different things for different symbols is worse than an absent column, because the
absent one cannot be read off and acted on by mistake.

Almost nothing here is computed. `YahooQuoteSummaryFetcher` is a passthrough of one
`v10/finance/quoteSummary` call, so "wrong field" nearly always means *Yahoo's field does not
mean what our column label says* — and the labels are ours.

## The feed states no period and no accounting basis

There is no field for either, and Yahoo is not internally consistent about them. Measured
against a live AVGO fetch on 2026-10-06, whose four quarters (Nov 2025 – Aug 2026) sum to
revenue 89.104B and net income 38.265B:

| Field | Module | Period | Evidence |
|---|---|---|---|
| `totalRevenue` | `financialData` | TTM | 89,103,998,976 = the four quarters, exactly |
| `ebitda` | `financialData` | TTM | 52.26B |
| `profitMargins` | `financialData` | TTM | 0.42944 = 38.265 / 89.104 |
| `grossMargins` | `financialData` | TTM | 0.75515 |
| `ebitdaMargins` | `financialData` | TTM | 0.58651 × 89.104 = 52.26B |
| `operatingMargins` | `financialData` | **latest quarter** | 0.54307 × 29.591B (Q revenue) = 16.070B, that quarter's operating income, to five decimals |
| `returnOnEquity` | `financialData` | TTM income / **unidentified** equity base | 0.44245 implies equity 86.48B; book value × shares = 99.68B → 38.39% |
| `trailingPE` / `trailingEps` | `summaryDetail` / `defaultKeyStatistics` | TTM, **GAAP diluted** | eps 7.83; 38.265B / 7.83 = 4.887B diluted shares; 362.51 / 7.83 = 46.298 = Yahoo's own `trailingPE` |
| `forwardPE` / `forwardEps` | `summaryDetail` / `defaultKeyStatistics` | next FY, **analyst consensus → non-GAAP** | forward eps 19.39 against trailing 7.83; GAAP EPS is not tripling, the amortisation and SBC are coming out |

**Four of these are in the same module and three of the four are TTM.** Nothing here can be
assumed TTM because its neighbour is, and the panel's group headings ("Income (TTM)", "Cash
Flow (TTM)") are *our* claim, not the feed's.

## GAAP vs non-GAAP

The feed never says which basis a figure is on. It is inferable, and the trailing side is even
checkable — see "GAAP against non-GAAP, named rather than judged" below for what the panel does
about it. One consequence worth stating here, because it is the usual cause of an apparent
disagreement with an outside source: **an independent source's per-quarter EPS line is usually
non-GAAP.** AVGO's sums to 9.76 against our 7.83, and ours is the one that reconciles to net
income, so the gap is the other source's basis rather than an error here.

## What was removed, and why not relabelled

**Operating margin.** MRQ for every symbol, so not inconsistent *across* rows — consistently
mislabelled. It cannot be repaired: the feed carries no TTM operating income anywhere now that
the quarterly statements are hollow (below). Relabelling it "latest qtr" was the alternative and
loses anyway, because a quarter's margin beside TTM revenue and TTM EBITDA compares against
neither its own row nor the same column on another symbol — and it is noisiest exactly where it
would be read hardest, on cyclicals.

**ROIC** and **FCF growth YoY.** Both derived from the three `*HistoryQuarterly` modules. Yahoo
still answers those modules but returns them **hollowed out**: the income statement keeps only
`totalRevenue` and `netIncome`, `grossProfit` / `ebit` / `incomeTaxExpense` come back as a
literal **0**, and the balance-sheet and cash-flow statements are entirely null. Verified on
AVGO, MSFT, KO and AZN.L; **0 of 80 stored snapshots** carried either field.

Those zeros were also a live hazard. `roicFromQuarterly` null-guarded `incomeBeforeTax` and
`totalStockholderEquity` but took `ebit` and `incomeTaxExpense` at face value, so it was one
non-null `incomeBeforeTax` away from computing `NOPAT = 0 × (1 − rate) = 0` and publishing
**ROIC = 0.00%** as a measured return — the "absent is never zero" invariant, inverted. A field
that can print a confident zero for a company earning billions is not a coverage gap, it is a
wrong answer waiting for the feed to shift again.

The modules are therefore **no longer requested**. If ROIC matters later, the honest source is
SEC EDGAR XBRL, which is GAAP by construction and names its own periods — and that path now
exists and is proven: see "The operating line comes from the filings" below, which does exactly
this for operating income. US filers only.

The glossary keeps both entries, with a note saying the panel does not carry the metric and why.
The idea still governs how to read what *is* shown; only our ability to measure it went away.

**Capex.** Derived as `operatingCashflow − freeCashflow`, which is not capital expenditure.
Yahoo's `freeCashflow` is its own modelled **levered** free cash flow — after interest and debt
service, on a formula it does not publish — not the cash-flow statement's OCF minus purchases of
property, plant and equipment. The residual of the two is therefore a quantity with no name, and
measurably so. On a live 68-symbol fetch (2026-10-06), 52 rows carried both sides and the
subtraction came out **negative on five of them**:

| Symbol | OCF | Yahoo `freeCashflow` | the "capex" we published |
|---|---|---|---|
| BRK.B | 46.63B | 71.98B | **−25.34B** |
| NFLX | 11.97B | 25.39B | **−13.42B** |
| LGEN | 2.22B | 7.69B | **−5.46B** |
| SNPS | 2.94B | 3.43B | −0.49B |
| MRVL | 2.20B | 2.41B | −0.21B |
| BABA | 78.49B | −82.64B | 161.13B — 2.05× OCF |
| ORCL | 46.94B | −45.85B | 92.79B — 1.98× OCF |

A negative capital expenditure is not a measurement, and no sign convention repairs it: the last
two rows are the same defect in the other direction, where a negative levered FCF against
strongly positive operating cash flow makes the residual exceed OCF outright. **Three of the five
negatives were on screen** — NFLX, SNPS and MRVL; BRK.B's and LGEN's were hidden only by accident
of `isFinancialRow`, which skips the whole cash-flow group for financials. NVDA's 92.55B against a
fabless business was the same arithmetic (134.36 − 41.81), and it is ours, not Yahoo's.

This is not the hollow-module failure: it is a **label claiming something the feed never said**,
the P/B defect's sibling — two figures that do not belong in one subtraction. There is no repair
available, because the feed carries no capital expenditure anywhere now that the quarterly
cash-flow statement comes back null, so the column is **removed** rather than relabelled. Were it
needed later, EDGAR XBRL's `PaymentsToAcquirePropertyPlantAndEquipment` is the honest source, on
the same terms as ROIC above.

**Free cash flow and FCF margin were relabelled rather than removed**, which is the opposite call
to operating margin's and worth saying why. Yahoo's levered FCF *can* state what it is: one named
figure, TTM, the same model applied to every row — so it is comparable, which is what this screen
is for, and the headers now read **Levered FCF** and **Levered FCF Margin** with the model's
unpublished formula named in the tooltip. That is the ROE treatment (a base the feed will not
identify, disclosed and kept) rather than the operating-margin one (a period that differs from its
neighbours', deleted). The keys `freeCashflow` and `fcfMarginTtm` are **unchanged on purpose**: a
watchlist column layout stores `f:fcfMarginTtm`, one list uses it, and re-keying the field would
have dropped that column silently while the label is the only thing that was ever wrong.

## The operating line comes from the filings

Deleting Yahoo's single-quarter operating margin left the panel with **no operating figure at
all**, which matters most exactly where reported earnings diverge from the business: GOOG's net
margin reads 54.8% because a large non-operating equity gain runs through it, while the operating
business is at 33.1%. Nothing on the screen could show that, and nothing in `quoteSummary` can —
there is no TTM operating income in it, and `ebit` in the quarterly statements arrives as a
literal 0.

SEC XBRL can. `OperatingIncomeLoss` is tagged by the filer, GAAP by construction, and **states
its own window** — the one property the whole of this document is otherwise about working around.
`EdgarXbrlFetcher` reads one concept at a time from `companyconcept` (tens of KB, against
megabytes for a whole-company `companyfacts` blob), `FiledPeriods` assembles the twelve months,
`OperatingLineService` applies the two gates below, and `edgar_operating_line` caches the result.

### Assembling twelve months out of filed periods

Two things make this more than addition, and `FiledPeriods` refuses rather than approximates
either:

- **A 10-Q tags more than its own quarter.** The filing for a fiscal Q3 carries Q3 alone (~91
  days) *and* the nine-month year-to-date (~273); a Q2 filing carries the half-year beside the
  quarter. Adding everything on file counts the same months two and three times, so facts are
  bucketed by the length of their own window and only quarter-length ones are summed.
- **The final quarter is usually not filed as a quarter.** A 10-K presents the year. For
  `OperatingIncomeLoss` the Q4 window is simply absent, so it is recovered as *year minus the
  nine months that share its start* — arithmetic on two filed figures, not an estimate. NVDA
  FY2026: 130.387 − 86.088 = **44.299bn**, which lands in sequence between the 36.010 before it
  and the 53.536 after. The quarter is **marked derived** in the panel rather than shown as
  though the filer had tagged it.

A TTM is published only when four quarters **tile a year contiguously** (within three days, since
fiscal quarters end on weekdays). Three quarters summed and called a year is the manufactured
figure this repo keeps deleting.

### The revenue tag is chosen by reconciliation, not by a preference list

Filers do not agree on one revenue concept — `Revenues`, `RevenueFromContractWithCustomer…`
(two of them), `SalesRevenueNet` — and ranking them by hand would be the maintained list this
repo rejects. Instead the tag used is **whichever one reproduces the TTM revenue already on the
row**, within 0.5%. One test then does four jobs:

| It confirms | Because otherwise |
|---|---|
| the CIK is the same company | SEC keys bare tickers and so do we: **`BLND` is British Land here and Blend Labs there**, and `BA.` is BAE against Boeing's `BA` |
| the window is current | KLAC's latest tagged `OperatingIncomeLoss` window is **2015-03-31**, which would have published an eleven-year-old margin beside today's price |
| the revenue definition matches the column beside it | WMT's contract-revenue tag **excludes membership income** and came out 1.0% light; `Revenues` reproduces the row exactly, so the gate picks it |
| the margin's denominator is the panel's own revenue | a margin that cannot be checked against the Revenue cell on its own row is a second opinion, not a measurement |

This is a reconciliation between two independent sources of the *same* quantity — the device
`DividendService` already uses on share counts — not a plausibility band on a figure, which is
the thing rejected for BRK.B's P/B. The evidence that it is tight enough to trust: **26 of the 30
candidates reproduced Yahoo's TTM revenue to the dollar**, and all four that did not were
genuinely wrong to publish (the three above, plus MU, whose EDGAR window is a quarter behind
Yahoo's).

A symbol is also skipped before any request unless it is **its own Yahoo ticker** — `AAPL` maps
to `AAPL`, `BLND` maps to `BLND.L` — which is what keeps the 27 London lines and ETFs out of SEC
entirely and is the first line of defence against the ticker collision.

### Four silences, told apart

"No operating margin" is four different facts and the panel says which, because `excluded:` is
how this screen reports anything it will not show:

| Outcome | What the row says | Measured |
|---|---|---|
| `UNTAGGED` (404, or a unit with no facts) | this filer never tags an operating income — banks have no such subtotal | BAC, LLY (404); V (200, empty `USD`) |
| `NOT_DOMESTIC` | reports on Form 20-F, so the figures are on another basis and often another currency | ASML: 51 facts, every one a 20-F, in EUR |
| revenue will not reconcile | names the window it was looking at | KLAC (2015), MU (a quarter behind) |
| `UNAVAILABLE` | **nothing is stored** | an outage is not a verdict about the company — storing one would leave a refusal reading like a finding |

Current coverage: **30 of 68 symbols publish**, 16 are withheld with a reason, and the rest are
not US lines. GOOG at 33.1% against a 54.8% net margin, MRVL at 16.5% against 27.9%, AMZN at
12.1% against 17.4%, INTC at −0.1% against −19.8%.

### Why its own table, and its own cadence

`fundamentals_snapshot.extra_json` is rewritten whole every four hours, which is what lets a
removed Yahoo field migrate itself (below). Filings move quarterly. Folding EDGAR figures into
that blob would either re-read SEC every four hours for data that cannot have changed, or make
the blob depend on its own previous value — so `edgar_operating_line` is a separate table on a
daily job, and the two are merged **at read time** in `PortfolioFundamentalsService.snapshot()`.
The blob keeps its self-clearing property and each source keeps its own clock. The refresh skips
any row younger than 20 hours, so restarts cost nothing, and it runs *after* the Yahoo pass in
the same startup daemon — the reconciliation needs that row cached first, and two daemons would
race with the loser skipping rather than waiting.

## The unit gate: which ratios may be divided at all

A ratio is trustworthy only when its two sides share a currency **and** a unit scale. Yahoo
checks neither, and the two sides come from different halves of the payload:

- **market side** — `price`, `marketCap`, `enterpriseValue`, `bookValue`: the quote currency's
  **major** unit, except `price`, which is the quote currency as quoted (pence on a `GBp` line).
- **statement side** — `totalRevenue`, `ebitda`, `totalDebt`, `totalCash`: `financialCurrency`.

`financialCurrency` is now captured, and `comparable()` is the single test. `GBp` and `GBP` count
as the **same currency** — a London line quoted in pence files in pounds, which is a scale
difference handled by `minorUnitsPerMajor`, not a currency one. An **absent**
`financialCurrency` counts as comparable: funds and trusts file no statements, and the gated
fields are absent for them anyway, so refusing on absence would report an exclusion nobody asked
about.

| Ratio | Sides | Treatment |
|---|---|---|
| `priceToBook` | price (as quoted) ÷ book value (major) | **Recomputed**, never read |
| `priceToSalesTrailing12Months` | market cap (major quote) ÷ revenue (statement) | Gated on currency |
| `enterpriseToEbitda` | EV (major quote) ÷ EBITDA (statement) | Gated on currency **and** EBITDA > 0 |
| `netDebtToEbitda` | both from `financialData` | **Ungated** — one currency by construction |
| margins, ROE, ROA, FCF margin | both from `financialData` | Ungated, same reason |

### P/B was wrong by exactly 100× on every London line

Yahoo's `priceToBook` is literally `price / bookValue` with no unit handling — verified as an
exact identity across 64 payloads. On a `GBp` line that divides **pence by pounds**:

| Symbol | Published | Reality |
|---|---|---|
| REL | 3676.55 | 36.77 |
| LGEN | 753.37 | 7.53 |
| BA. | 455.26 | 4.55 |
| BARC | 92.64 | 0.93 |
| BLND | 66.61 | 0.67 |

All 13 GBp rows carrying a P/B were affected, and the metric is the *primary* yardstick for
exactly the banks and REITs it broke — a scan for cheap-on-book showed every UK name as
ruinously expensive. P/B is therefore **recomputed** from `price / minorUnitsPerMajor` ÷
`bookValue` rather than read. `bookValue` is present whenever `priceToBook` is (61 of 61
observed), so there is one path and no fallback, and the fixture publishes a `priceToBook` that
disagrees with its own `bookValue` so a test can only pass the recomputing way.

**The denominator is now shown**, for the same reason `netIncomeToCommon` sits beside the
trailing P/E: a recomputed ratio nobody can check is still taken on trust. Book value per share
is a *fifth* rendering — per-share, but in the **major** unit while the price beside it is
as-quoted — so it has its own type (`'priceMajor'`), because labelling it with the quote currency
would restate pounds as pence, the exact inverse of the defect being fixed. It also carries four
decimals under 10 rather than the usual two: LGEN's £0.3857 rounded to `£0.39` reproduces a P/B of
7.39 against the 7.47 published next to it, and a denominator that cannot reproduce the ratio it
is the denominator of is not an audit. Small book values are precisely the banks and REITs this
column is the primary yardstick for. Verified on both conventions: BARC 450.05p → £4.5005 ÷
£4.8720 = 0.92, AAPL $332.89 ÷ $7.3600 = 45.23, LGEN 288.20p → £2.8820 ÷ £0.3860 = 7.47.

### Withheld, with the reason attached

A gated field is **not** silently dropped. `putOrExclude` records one `excluded:<field>` label
carrying why, and the panel renders that cell as a muted *withheld* with the reason in its
tooltip. A bare absence cannot distinguish "we could not trust this ratio" from "the feed never
sent it", and this screen is read by someone deciding between two symbols.

Eight symbols currently mismatch: ASML (USD/EUR), BABA (USD/CNY), NVO (USD/DKK), SKHY (USD/KRW),
TSM (USD/TWD), AZN (GBp/USD), BP. (GBp/USD), HSBA (GBp/USD). Where the figures are merely
mis-denominated the error is the FX rate — BABA's 2.05 × 7.1 ≈ 14.6x, NVO's 1.50 × 6.4 ≈ 9.6x —
but ASML's and SKHY's `defaultKeyStatistics` is corrupt outright, not merely in the wrong
currency: ASML's EV is 39.68T against its own 714.37B market cap, its P/B 1615.80 and its
EV/Revenue 1123.25, all implausible by a similar large factor; SKHY's EV is −65.17T on a positive
market cap with positive net debt. **Converting rather than withholding would therefore not be
enough**, which is why no FX is wired in here — the gate refuses the comparison instead of
guessing at it.

### The sector guard now applies in the grid too

`renderSnapshot` printed EV/EBITDA for every row while the detail view suppressed it for
financials, so the same symbol disagreed with itself and financials disagreed with each other:
LGEN 22.21, AV. 1.76 and LSEG 16.74 were shown, while Yahoo's own silence blanked HSBA and BARC.
`isFinancialRow` is now the single home for the test, used by the grid and both detail views.

## Three unit classes, three labels

A currency amount's unit is not the row's quote currency. There are three classes, and the panel
had been labelling all of them with the quote currency:

| Class | Fields | Unit | Rendered as |
|---|---|---|---|
| As quoted | `price`, `targetMeanPrice`, 52-week high/low, moving averages | quote currency, as quoted — **pence** on a GBp line | `fmtPrice`, unchanged (it was already right) |
| Market side, bulk | `marketCap`, `enterpriseValue` | quote currency's **major** unit | `'cap'` → `fmtMarketCap`, which now normalises GBp → GBP |
| Statement side | revenue, EBITDA, net income, debt, cash, OCF, levered FCF, net debt | `financialCurrency` | `'capFiled'` → `fmtMarketCap(v, filedCurrency(row))` |
| Filing side | `operatingIncomeTtm` | the unit the **filing** states, which Yahoo does not supply | `'capEdgar'` → `fmtMarketCap(v, labels.operatingUnit)` |

The fourth class is not pedantry: EDGAR reports the unit per concept and it need not agree with
Yahoo's `financialCurrency` for the same company (ASML's `OperatingIncomeLoss` is filed in EUR on
a USD-quoted line). Taking the unit as filed is the same discipline as the other three — label a
figure in the currency it is actually in, never in the one its neighbour happens to use.

### Negative amounts were not being scaled at all

`fmtMarketCap` compared the **signed** value against its thresholds (`n >= 1e9`), so every
negative amount fell through to the last branch and printed unscaled: GOOG's net cash position
read `$-121,682,993,152` directly above a `$120.79B` total debt — the one figure it most needs to
be comparable with. It now scales on the magnitude and carries the sign, giving `-$121.68B`.

Negatives are ordinary on this screen, not an edge case: a net cash position, a levered FCF Yahoo
models below zero (ORCL −45.85B, BABA −82.64B), a corrupt enterprise value (SKHY), and an
operating loss (INTC's TTM operating income is −77.0M). Adding a field that can be negative is
what surfaced it.

Verified by measurement: for BA.L, `price × sharesOutstanding ÷ marketCap` is exactly **100.0**
(likewise BATS.L and AZN.L), so market cap is in pounds while the price is in pence. The
as-quoted class was confirmed separately — analyst targets and moving averages on a GBp line are
in pence, with target/price ratios of 1.04–1.70 across every London name.

The normalisation lives **inside** `fmtMarketCap` rather than at its three call sites, so a
caller handing over `row.currency` cannot reintroduce "p54.96B" for a figure that was never in
pence. And `currencySymbol` now falls back to the ISO code rather than an empty string: SK Hynix
files in KRW, so its revenue has to read `KRW 189.17T` and not a bare `189.17T` on a
dollar-quoted row.

The result on a mixed row is deliberately visible rather than hidden — AstraZeneca now reads
market cap `£187.41B` beside net debt `$27.38B` and revenue `$61.37B`, because that is what the
feed actually says.

## GAAP against non-GAAP, named rather than judged

Trailing and forward multiples are different accounting bases sitting in adjacent columns:

- `trailingEps` is **GAAP diluted TTM** and reconciles: AVGO's 7.83 into a `netIncomeToCommon`
  of 38.265B implies 4.887B diluted shares, and 362.51 ÷ 7.83 = 46.298 is Yahoo's own
  `trailingPE` to three decimals.
- `forwardEps` is **analyst consensus**, which is whatever basis the company guides — non-GAAP
  for most large caps. AVGO's forward 19.39 against a trailing 7.83 is not a 148% growth
  forecast; it is mostly the share-based compensation and VMware amortisation coming out.

`netIncomeToCommon` is now stored and shown beside revenue and EBITDA, which both completes the
income block and makes the trailing P/E auditable on the row itself. Both P/E tooltips and both
grid headers now name their basis.

**No divergence flag was added, and the reconciliation check first proposed for this does not
work.** `trailingEps × shares ≈ netIncomeToCommon` tests whether the *trailing* figure is GAAP —
it passes for AVGO — and says nothing about the forward one, which is the side that changes
basis. The only signal that would catch it is a threshold on `forwardEps / trailingEps`, and that
is the same magic number rejected for BRK.B's P/B: a recovering cyclical can genuinely triple
EPS, so any cut-off is wrong for someone. Naming the basis is honest; inferring a verdict from a
ratio is not.

## Still open

- **BRK.B's P/B is 0.000966**, because Yahoo's `bookValue` for it is 522,225.9 — the Class **A**
  book value per share, against a Class **B** price of 504.26 (the classes differ 1500:1).
  Berkshire's real P/B is about 1.45. This is Yahoo's own figure to the digit — it published
  0.00096559746 before any of this and the recompute reproduces it exactly, so it is not a
  regression — but it *is* the same defect class as the currency gate: a market-side figure
  divided by a statement-side one on a different scale. It was deliberately **not** fixed,
  because every available test is a magic number. A plausibility band would also suppress
  legitimate extremes (REL at 36.8 and MA at 88.3 are real, as is a distressed bank at 0.3),
  which is the maintained-list anti-pattern this repo rejects: such a threshold can only ever be
  wrong, never right. A principled fix needs a share-count basis the feed does not state.
- **ROE's equity base is still unidentified.** The tooltip now says so and gives the AVGO
  numbers, which is as far as the feed allows: Yahoo divides TTM income by a trailing equity
  base it does not publish. It is applied consistently across symbols — comparable, which is
  what this screen is for — but it will not reconcile against the P/B on its own row.
- **`enterpriseValue` is still shown for currency-mismatched rows** as a bulk amount, even where
  the EV/EBITDA built from it is withheld. It is labelled in the quote currency's major unit,
  which is right for the ordinary case, but ASML's is corrupt (39.68T against a 714.37B market
  cap) and no currency makes it true. Withholding an *amount* on the same evidence that
  withholds a *ratio* would need a cross-check against market cap that does not exist yet.

## Rows outside the fetch universe keep their stale blob

`fundamentals_snapshot` holds 80 rows while the fetch universe (held ∪ watchlist) is 68, so a
symbol that has left the universe is never refetched and keeps whatever its blob last said. REL
still carries the pre-gate `priceToBook` of 3676.6 and no `financialCurrency` for that reason.
Harmless — nothing reads a row outside the universe, and it corrects itself the moment the symbol
returns — but worth knowing before reading a figure straight out of the table.

## The stored blobs clear themselves

`fundamentals_snapshot.extra_json` is written whole per symbol, so rows written before this
change still contained `roic`, `fcfGrowthYoy` and `operatingMargins` immediately after the
deploy. Nothing read them — the panel builds its columns from `fundamentalsFieldGroups()`, so a
key with no field tuple is unreachable — and the `fundamentals-refresh-startup` job rewrote all
68 rows within a minute of the restart, taking the keys with it. Verified: 0 rows carry any of
the three, while `profitMargins` / `grossMargins` / `returnOnEquity` hold their previous
coverage of 57 and `ebitda` its 52.

No bulk `UPDATE` was run, and none should be: a removed field needs no migration here, because
the next scheduled fetch is the migration.
