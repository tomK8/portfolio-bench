# P/E history — a price over a window, and which window it is

The *P/E history (per ticker)* sub-tab decomposes a stock's price move into **earnings growth ×
multiple expansion**, using the identity `priceMult ≡ epsMult × peMult`. Both inputs are
historical: price from `price_history.close`, trailing-twelve-month diluted EPS assembled out of
the company's own 10-K/10-Q XBRL at SEC EDGAR, as it stood on each chart date.

Two things decide everything here. **The denominator is a window, not a figure** — so the window
has to be assembled honestly and named. And **the universe is derived, not listed** — so every US
filer the portfolio touches is offered, rather than seven names someone typed once.

## The twelve months are assembled, not taken as the last four records

The original implementation summed the four most recently filed quarters. That is wrong for most
filers, and wrong in a direction that flatters nothing: **a 10-K rarely tags its own fourth
quarter.** The annual figure is filed; the Oct–Dec (or Nov–Jan, or Apr–Jun) quarter is not.

Measured against EDGAR on 2026-10-08, over `us-gaap:EarningsPerShareDiluted`:

| Symbol | quarter-length facts | annual gaps in the series | what the old rule summed |
|---|---|---|---|
| GOOGL | 34 | 12 — one a year, every fiscal Q4 | Jun'25, Sep'25, Mar'26, Jun'26 — a 15-month span with Dec'25 missing |
| NVDA | 67 | 7 — every Q4 (Nov–Jan) | Jul'25, Oct'25, Apr'26, Jul'26 |
| INTC, ORCL, PEP, BAC | 55 each | 18 each | the same shape, every year |
| LRCX | 51 | 16 | " |
| KLAC | 63 | 4 (recent years) | " |

So for roughly half of each year the chart divided the price by **four quarters spanning five
quarters of calendar time with one missing from the middle** — a TTM that is too small, and a P/E
correspondingly too high, on the tab's own flagship tickers. For KLAC today that was 3.443 against
a true 3.661, printing a P/E of 58.1 where it is 54.7.

`FiledPeriods` is the one place this repo assembles a trailing twelve months out of filed XBRL
periods, and it already solves exactly this for the operating line:

- facts are bucketed **by the length of their own window**, so a 10-Q's year-to-date is never
  added to the quarter inside it;
- four quarters must **tile a year contiguously** (within three days, since fiscal quarters end on
  weekdays) or nothing is published;
- the quarter a 10-K leaves implicit is **recovered by subtraction** — the year minus the
  nine-month year-to-date that shares its start, or minus the three contiguous quarters that open
  it. That is arithmetic on two filed figures, not an estimate, and the report marks the window as
  carrying a derived quarter rather than presenting it as filed.

The adapter therefore returns **every** period the filer tagged — quarters, year-to-dates and the
annual — and the domain class decides what may be added. Where even the subtraction is
unavailable (no 10-K yet, or a middle quarter genuinely missing) the series simply has no point
there. A gap is an answer; a figure spanning the wrong months is not.

### A step function over filing dates, not a sum per bar

The TTM changes only when a filing lands, so it is built once per **distinct filing date** — the
facts visible on that date, assembled by `FiledPeriods` — and then read off per price bar. ~80
assemblies rather than ~2500, and it is also what keeps the series look-ahead-free: a bar sees the
twelve months the market could see.

Two filing dates per fact make that work, and they answer different questions:

- **`availableFrom`** — the *earliest* filing carrying the window. When the figure became public,
  which is what gates it into a past date's TTM.
- **`valueFiled`** — the *latest* filing, whose value is the one kept (restatements and post-split
  comparatives win). Which per-share basis the number is in, which is the pivot for the split
  rescaling.

Using the restatement date for both would hide a two-year-old quarter from every chart point in
between, and the line would dip through a window where the market plainly knew the number.

### Splits are normalised before anything is added

Stored prices are split-adjusted to today's basis; EDGAR EPS is as-filed. Every fact is divided by
the split factor in effect **at its own `valueFiled` date** *before* the assembly runs — so the
subtraction that recovers a fourth quarter is a subtraction of two figures on one footing, and the
P/E is the multiple the market actually saw.

## The universe is derived from the portfolio

What the picker offers is: every symbol in `fundamentals_snapshot` (the held ∪ watched ∪
hand-added universe, the same table `OperatingLineService` reads for the same reason) that passes
two local, free gates —

1. **it is its own US ticker**: `symbol.equals(tickerMap.tickerFor(symbol))`. SEC keys bare tickers
   and so do we, so they collide — `BLND` is British Land here and Blend Labs there, `BA.` is BAE
   against Boeing's `BA`. A symbol that needs a Yahoo mapping to reach its own listing is not the
   company whose filings that CIK holds.
2. **SEC's own registry knows it**: `CikRegistry`, the cached `company_tickers.json` that the
   earnings proxy and the operating line already resolve through.

That replaced a hard-coded map of seven hyperscalers — the maintained list this repo rejects on
principle, which in practice hid the entire semiconductor complex behind a dropdown that could not
offer it. The picker now carries **46** symbols, of which **35 publish a series**.

A symbol can be offered and still have nothing to draw; finding that out costs one request, which
is why it is reported when the chart is asked for rather than used to prune the list.

### Four silences, told apart

Same vocabulary as the operating line (`EdgarXbrlFetcher.Status`), because they are the same four
facts:

| Outcome | What the tab says | Seen on |
|---|---|---|
| no CIK | SEC's registry lists no filer under this ticker | — (the dropdown filters these out) |
| `UNTAGGED` | SEC's XBRL carries no diluted EPS for this filer — absent (404) or an empty array | V, KO, MDLZ, CDNS, GFS; also TSM, ASML, NVO and SKHY, which tag no us-gaap EPS at all |
| `NOT_DOMESTIC` | the concept is tagged, but only on 20-F/40-F/6-K, so the figures are on another basis | ARM, BABA |
| `UNAVAILABLE` | EDGAR did not answer — says nothing about the filer, so retry | an outage |

`UNTAGGED` is phrased as a fact about the *API*, not about the company, because that is what was
verified: Visa's whole `companyfacts` blob contains no diluted-EPS concept at all, though every
10-Q it files states the figure. A failure is also the one outcome **not cached** for the day — an
outage should not be repeated back at the user until tomorrow.

## Evidence it assembles the right window

The check is a reconciliation against an independent source of the same quantity — Yahoo's own
`trailingEps` — on 2026-10-08, with our figure built from filings only:

| Agreement | Symbols |
|---|---|
| to the cent | AMAT, AMZN, ANET, LRCX, META, NVDA, ORCL, TER, TXN, MA, NFLX, VRTX, ISRG, LLY, MSFT, AVGO, KLAC (3.661 vs 3.66), BAC, CRH, AAPL |
| within 1.3% | AMD, MPWR, MRVL, SNDK, QCOM, SNPS, NOW, WMT |
| wider, and explained | PEP +4.3%, MU −40.6% |

PEP is a 12/12/12/**16**-week filer: its recovered fourth quarter is 111 days long, and the four
that tile its year span 363 days of filed figures. The arithmetic checks out against its own
filings (6.00 year − 4.15 nine months = 1.85, plus 1.70 + 2.18 + 2.23 = 7.96); Yahoo's 7.63 is on
some other footing.

MU is the one that matters to read correctly: **EDGAR's latest filed quarter ends 2026-05-28 while
Yahoo already has the quarter to 2026-09-03.** With EPS running 4.60 → 12.07 → 24.67 a quarter,
one quarter of lag is a 40% difference in the TTM. Nothing is wrong — the 10-K has not been filed
— and this is exactly why the summary line now ends with **"Latest EPS window ends 2026-05-28"**
rather than letting the right-hand edge of the chart imply "as of today".

Two apparent outliers were not ours at all: GOOGL's stored fundamentals row is from 2026-07-15
(it has left the fetch universe — see `docs/fundamentals.md`), and TSLA's from 2026-09-11. Against
the *fresh* GOOG row, Yahoo says 19.93 to our 19.91.

## What is still open

- **The chart clips outliers** at a quantile and says how many points it dropped. With a P/E near
  the clip threshold the right-hand edge can sit on the boundary (KLAC: "57 points clipped,
  P/E > 54"). Pre-existing, disclosed, and untouched here.
- **Pre-2018 values can be thin** for some tickers — XBRL coverage was patchier, and a year with
  no annual filed is a year with no recoverable fourth quarter.
- **Dimensional facts are invisible.** SEC's company-concept API returns only undimensioned facts,
  which is the likeliest reason a filer like Visa shows nothing while its filings plainly state an
  EPS. Recovering those would mean parsing the filings themselves, not this API.
