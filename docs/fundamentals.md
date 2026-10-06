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

The feed never says. It is inferable, though, and checkable:

- The **trailing** side is GAAP and self-reconciling: `trailingEps × sharesOutstanding` ≈
  `netIncomeToCommon` (AVGO: 7.83 × 4.774B = 37.4B vs 38.26B, a 2% basic/diluted gap).
- The **forward** side is consensus, which is non-GAAP wherever the company guides non-GAAP.

So trailing P/E and forward P/E are **not comparable with each other**, and they sit in adjacent
columns, which reads as a re-rating when it is mostly a basis change. An independent source's
per-quarter EPS line will usually be non-GAAP too — AVGO's sums to 9.76 against our GAAP 7.83,
and ours is the one that reconciles to net income.

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

## Still open

- **`enterpriseToEbitda` is unreliable where the statement currency differs from the quote
  currency.** It divides `defaultKeyStatistics.enterpriseValue` by `financialData.ebitda`, and
  those are not guaranteed to be in the same currency. `financialData.financialCurrency` says
  which, and **we do not capture it**. Of 58 symbols showing a value, 8 mismatch (ASML USD/EUR,
  BABA USD/CNY, NVO USD/DKK, SKHY USD/KRW, TSM USD/TWD, AZN GBp/USD, BP. GBp/USD, HSBA GBp/USD);
  the error is the FX rate — BABA's 2.05 × 7.1 ≈ 14.6x, NVO's 1.50 × 6.4 ≈ 9.6x. ASML (EV 39.68T
  against its own 714.37B market cap) and SKHY (EV −65.17T on a positive market cap, net debt
  positive) are worse: `enterpriseValue` itself reconciles in no currency, so an FX correction
  cannot rescue them.
- **The comparison grid applies no sector filter.** `renderSnapshot` prints EV/EBITDA for every
  row; the `isFinancial` guard (`/financial|insurance|bank/i` on sector) runs only in the
  per-symbol detail view. So LGEN 22.21, AV. 1.76 and LSEG 16.74 appear where the metric is
  meaningless while HSBA and BARC are blank — financials inconsistent against each other.
- **Currency amounts are labelled with the wrong currency.** Yahoo's `marketCap` for a
  GBp-quoted line is in **GBP** while `price` is in pence — exactly 100.0× on BA.L, BATS.L and
  AZN.L. `fmtMarketCap` prefixes the quote symbol and `currencySymbol('GBp')` is `'p'`, so BAE's
  £54.96B renders "p54.96B". Every `financialData` amount (net debt, total cash/debt, OCF, FCF,
  capex, revenue, EBITDA) is in `financialCurrency` but labelled from the quote currency. 18 of
  80 rows are GBp; AZN/BP./HSBA are wrong twice over, USD figures under a pence label.
- **`mostRecentQuarter` and `lastFiscalYearEnd`** are in the response we already make and are
  discarded. Storing them is what would let a row say *as of when*, instead of a column heading
  claiming it for every row at once.

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
