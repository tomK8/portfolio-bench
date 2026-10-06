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
SEC EDGAR XBRL — `EdgarFundamentalsFetcher` and `CikRegistry` already exist (currently diluted
EPS only) — which is GAAP by construction and names its own periods. US filers only.

The glossary keeps both entries, with a note saying the panel does not carry the metric and why.
The idea still governs how to read what *is* shown; only our ability to measure it went away.

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
| Statement side | revenue, EBITDA, net income, debt, cash, OCF, FCF, capex, net debt | `financialCurrency` | `'capFiled'` → `fmtMarketCap(v, filedCurrency(row))` |

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
