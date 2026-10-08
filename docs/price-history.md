# Price history & feeds


Two tables, three jobs. `PriceFetchSupport` is shared by the two Yahoo jobs:

- `PriceFetchJob` → `price_history` (daily). First run backfills ~10 years per ticker; later runs
  fetch the gap to today. Cron 22:00 Europe/London + startup.
- `IntradayPriceFetchJob` → `price_intraday` (1-minute closes). **15-day** retention
  (`RETENTION_DAYS`), but the per-tick fetch lookback is capped at `FETCH_LOOKBACK_DAYS` = 7 —
  Yahoo only serves ~7 days of 1-minute history per request, so the 15-day window *accumulates*
  as ticks append rather than backfilling in one shot. Feeds the watchlist popup's 1D–15D
  intraday charts. Pruned after each fetch tick. Every minute + startup.
- `GiltPriceFetchJob` → also `price_intraday`, one HTTP GET to
  `hl.co.uk/shares/corporate-bonds-gilts/bond-prices/uk-gilts` (HL; conventional gilts only —
  index-linked are on a separate page, not fetched). Previously scraped dividenddata.co.uk,
  which went behind a Cloudflare JS challenge in July 2026 (no header combination passes a
  managed challenge — if HL 403s someday, check for the same before fiddling with UAs).
  Hourly + startup. Symbols are `"GILT {coupon}% {year}"` (no Yahoo ticker map; HL's
  zero-padded coupons "4.750" are trimmed to the broker form "4.75"). Currency `"GBP"`.
  Rides the intraday job's prune for retention. `GiltPriceFetcher` sends a browser UA.
  Each tick also publishes the page's **SEDOL → gilt-id map** to the `gilt_sedols` KV entry
  (see "Gilt SEDOLs" below).
  Each tick also **upserts** today's row into `price_history` so the daily series
  self-accumulates even when the app isn't running all day — last-write-wins. **That rollup is
  weekday-only** (`GiltPriceFetchJob.isTradingDay`, keyed off each bar's own timestamp so a tick
  either side of midnight lands on the right day). The job runs hourly seven days a week and HL
  serves Friday's prices all weekend, so without the guard every weekend the app happened to be
  up minted a full set of gilt rows carrying a stale close — a price that doesn't exist, the LSE
  being shut. 717 such rows over 15 weekend dates had accumulated by 2026-08-20, and because the
  Watchlist **Returns** grid draws its date axis from the union of every symbol's stored dates,
  three held gilts were enough to put weekend columns across the whole panel with every equity
  showing "—" in them. Gilts are the only symbols that could do this: Yahoo never returns a
  weekend bar. The `price_intraday` write is deliberately **not** guarded — the RT lookup wants
  the newest gilt price whatever day it is, and those rows are pruned after 15 days. UK bank
  holidays are a known remaining gap (~8 rows/yr): neither HL's table nor our parse of it carries
  a price date, and a hand-maintained holiday calendar would go stale unnoticed. A Tradeweb
  import overwrites any of them authoritatively. Cleanup of the historical rows was scoped
  `weekend AND date >= 2026-06-06`, which spares the 16 pre-2026-06 weekend rows — Tradeweb
  month/quarter-end valuations, 1–3 symbols each, irreplaceable.
- `ImportGiltPricesService` → bulk historical import for gilts from manually downloaded
  `~/Downloads/Tradeweb_FTSE_ClosePrices*.csv`. Runs on startup (daemon) and on demand via
  `POST /import-gilt-prices`. Upserts into `price_history`, archives consumed files to the
  DB dir as `Tradeweb_{symbol}_{from}_to_{to}.csv` (range comes from the file's own dates,
  not today's). **Never clobbers**: if a target name already exists, appends `_2`, `_3`, ...
  Tradeweb files are manually sourced and irreplaceable, so `REPLACE_EXISTING` is forbidden
  here.

**Gilt SEDOLs.** `GiltPriceFetcher.Snapshot` carries a `sedolToSymbol` map beside its bars —
HL's name cell reads `Treasury 4.375% 07/03/2030 GBP | GB00BSQNRD01 | BSQNRD0`, so the SEDOL comes
out of the response the job **already makes hourly**, at zero extra HTTP. `GiltPriceFetchJob`
merges it into the `gilt_sedols` KV entry (the whole conventional-gilt list) and `IiCashImporter` reads it on
every import, which is what lets `IICashStatementParser` recognise a gilt in a file that names it
only by a broker code. It is **merged, never replaced**: a gilt drops off HL's page the day it
matures, and a cash statement from before then is still importable. The alternative — a committed
`T30 = GILT 4.375% 2030` table — would need an edit for every gilt ever bought and could only ever
be wrong, never right; the mapping is a published fact and a free source publishes it.

**Gilt price history sources:** two independent feeds writing the same table via the same
upsert. Either can fill or overwrite the other's day. The intraday rollup gives continuous
coverage while the app runs; Tradeweb batch imports give authoritative closes for whatever
range the user downloads — typically the source of truth when both are present.

`PriceFetchSupport.tickersToFetch` builds the universe: every symbol from
`CashTransactionRepository.distinctTradedSymbols()` plus the `held_symbols` KV set persisted by
the most recent `/sync` (covers freshly bought names whose cash statement isn't imported yet —
otherwise their first intraday quote would wait until import), plus each strategy tab's cohort and
the `order_symbols` set published by the Open orders tab. Minus bonds (`Instruments.isBond`),
mapped to Yahoo tickers via `YahooTickerMap`. 500ms throttle between requests
(`PriceFetchSupport.sleep`).

`PriceFetchScheduler` is the only Spring class in the price layer; `@EnableScheduling` is on
`PortfolioBenchApplication`. Startup runs are on daemon threads so first-run backfill doesn't
block the web UI.

**Adding a Yahoo ticker mapping:** normally from the dashboard (Open orders or either sync tab
offers the field inline, with a live check) — it lands in `ticker_mappings` and overrides the file.
The committed baseline `resources/yahoo-tickers.properties` takes `internal=YAHOO` lines and is
still the right place for a mapping worth shipping. US listings map to themselves; non-US need an
exchange suffix (`.L` London, `.PA` Paris, `.AS` Amsterdam, `.DE` Frankfurt).

**A missing mapping fails in two different ways**, which is why the identity fallback is warned
about rather than trusted. Unmapped `PHP` (Primary Health Properties, LSE) resolved to a US mutual
fund delisted in 2019 — no data, so the position simply lost its RT value; unmapped `BLND` (British
Land, LSE, ~440p) resolves to a *live* NYSE ticker near $1.50, which would have priced the holding
wrongly instead of not at all. Two signals cover the first case, one covers the second:
- `PriceFetchJob` warns on a **first-ever** fetch that comes back empty (`PriceFetchSupport.warnNoPriceData`,
  the one place that wording lives) — restricted to first-ever because a quiet fetch is routine for
  an established ticker. The message branches: an unmapped symbol gets the copy-paste line to add,
  a mapped one is told to correct its existing entry. The intraday job deliberately stays silent
  (it runs every minute; the same warning would be log spam).
- On a first-ever fetch that *does* return data through the identity fallback,
  `PriceFetchSupport.noteIdentityTicker` logs at INFO what was assumed. That is the only signal that
  catches the BLND shape, and it can't be a warning — every US name is legitimately unmapped.
- Both sync views surface `SyncResult.unpricedSymbols` (`SyncPortfolioService.unpricedSymbols`,
  shared static): held symbols with **no intraday quote and no daily close ever stored**, rendered
  as their own panel naming the ticker actually tried. A missing intraday quote alone stays in the
  existing "Missing intraday prices" panel — that's an ordinary stale feed. Bonds are excluded (the
  gilt scraper, not Yahoo, prices them).

**A rejected ticker is recorded, not only logged** (`UnresolvedSymbols`, KV key
`unresolved_tickers`). The three signals above all end in the log, which no screen reads — so a
symbol Yahoo 404s looked exactly like one still backfilling, indefinitely. `EQQU` (Invesco EQQQ's
USD line, LSE) sat as `(fetching…)` on the watchlist and drew "a price fetch is queued now — reload
in a few seconds" from the limit probe on every reload, for as long as it stayed unmapped. Three
states, not two: **priced / still fetching / asked and refused.**
- `PriceFetchJob.fetchSingle` writes a verdict when the response is empty *and* nothing was ever
  stored for the ticker, and clears it the moment a fetch returns bars. That is the only writer;
  `deleteTicker` clears through `PriceFetchJob.forgetUnresolved` because the verdict names a ticker
  the symbol no longer resolves to. The bulk `run()` loop does not record — it works in ticker
  space and never sees the internal symbol a report has to name.
- The verdict carries **Yahoo's own answer to "then what is it?"**: `YahooPriceFetcher.search`
  (`/v1/finance/search`, no crumb, no auth) resolves `EQQU` → `EQQU.L` even though the chart
  endpoint 404s on it. Self-regulating over maintained — a suffix table would only ever be wrong,
  and the currency-based `exchangeSuffix` guess in the mappings UI cannot run here (no price means
  no currency). One extra request, on a failure only.
- Readers are pure KV lookups, which is what lets `OpenOrdersService.probe` consult it on every
  keystroke. `probe` returns the verdict *instead of* re-queueing a warm — repeating "reload in a
  few seconds" is what made a mistyped symbol indistinguishable from a slow one. `WatchlistService`
  puts it on `Row.missingReason`, and the UI renders amber `(no Yahoo data)` with the diagnosis in
  the title rather than grey `(fetching…)`. A null reader (the 7-arg test constructor) degrades to
  the old undiagnosed behaviour — a missing diagnosis must never blank a working row.
- The message names where to fix it. There is **no standalone mappings tab**: the editor is the
  "Add a mapping" row that opens the "All instrument mappings" list at the foot of Open orders,
  plus the inline unpriced-symbol editors on the two sync tabs. `UnresolvedSymbols.FIX_HINT` is the
  one place that wording lives. Until that add row existed the hint was **false** — it named a
  list that carried Remove buttons and no editor, and the symbols this class is written for
  (watchlist names, not holdings) reached none of the reactive editors either. Naming the wrong
  place is worse than naming none; see [instrument-mappings.md](instrument-mappings.md).

**Two mapping layers, both now user-editable at runtime** — a new instrument is only fully wired
when both exist. PHP (Primary Health Properties, Aug 2026) is the worked example of what each one
costs when missed:
1. **Description keyword → internal symbol.** Committed baselines are
   `AJBellCashStatementParser.SYMBOL_RULES` (cash-ledger description) and
   `AJBellOrdersParser.NAME_TO_SYMBOL` (order-export investment name); both now consult the
   `instrument_aliases` table **first**. Unmapped, the cash parser falls back to the cleaned
   investment name, so the ledger stored `PRIMARY HEALTH PROPERTIES PLC` while the holdings file
   called the same position `PHP`: the ledger view lost the position and reconciliation reported
   the whole £10k as a diff. The order parser passes the description through as the symbol, so the
   order lists but resolves to no ticker (Current / vs limit / σ→lim / P(touch) all blank). Both
   still warn at parse time.
2. **Internal symbol → Yahoo ticker.** `yahoo-tickers.properties` is the committed baseline;
   `ticker_mappings` rows **override** it (see below).

The alias pool is deliberately **one table, not one per broker** — AJ Bell's orders export and its
cash statement describe the same instrument in near-identical words, so a single keyword
("xtrackers msci world health") fixes the Symbol column *and* keeps the ledger row from stranding
when the trade settles.

Matching is a `contains` test, longest keyword first (a specific phrase must beat a shorter one
that also matches) — but **both sides go through `Instruments.matchKey` first**, and that is
load-bearing rather than incidental. It lowercases and collapses every run of non-alphanumerics to
a single space, and it is used in all four places an alias is touched: `suggestKeyword` builds the
panel's suggestion with it, `InstrumentMappingRepository` stores keywords in it (and normalises on
read too, so rows written before this still match), and both AJ Bell parsers reduce the description
to it before the test. They disagreed once, and the failure mode is the bad one: the panel suggests
a phrase with the punctuation stripped, so saving "Legal & General Group PLC" stored
`legal general group plc` while the parsers tested it against the raw `legal & general group plc`
— which does not contain it. The save wrote both rows, reported success, and the order stayed
unpriced; every name carrying an `&`, a `-` or a `.` was un-mappable from the dashboard in a way
that looked like it had worked (2026-08-24). The cash statement's committed `SYMBOL_RULES` stay on
the **raw uppercased** name — those are regexes that deliberately read punctuation
(`B\.?\s*A\.?\s*E\.?\s*SYSTEMS`).

### Derived dividend yields

`YahooPriceFetcher.fetchSeries` returns a `DailySeries(bars, dividends)` — the dividend events were
**always parsed** (`applyAdjustments` needs them to re-derive `adj_close`) and then thrown away.
Surfacing them costs **zero extra HTTP** and is the only free source of a yield for an instrument
whose `summaryDetail` publishes none. Same bargain `GiltPriceFetcher.Snapshot` strikes carrying a
SEDOL map beside its bars; `fetch(...)` still returns bars alone, so no existing caller changed.

**Units cancel, which is the whole reason this is safe.** Amounts are per share on the *same
split-adjusted basis as the `close` column*, so a yield taken against a stored close is a ratio of
two figures from one response — no FX, no split unwinding, and crucially no `GBp`-pence shift. That
is precisely the trap that makes `dividendRate ÷ price` 100× too small for a London listing.

**Two tables, and the second is the load-bearing one.** `dividend_history` holds the payments;
`dividend_scan` records that a symbol's **full** window has been read. Without that bookkeeping an
absent symbol is ambiguous in exactly the way that matters — an accumulating ETF that genuinely
distributes nothing is indistinguishable from a name nobody has fetched, and booking either as zero
understates portfolio income with nothing on screen to say why. Same three-state discipline as
`earnings_calendar_fetch` and the sector block's `AWAITING_DATA` / `NO_SECTOR` split. **Only a
full-window fetch may mark a scan**: `runFullRebuild`, `fetchSingle(symbol, years)` and the
first-ever backfill do; the nightly incremental run covers the gap since the last stored bar and sees
no dividends on almost every night, so a marker from there would libel every payer as an accumulator.
It still *upserts* what it finds, so a newly-declared dividend lands the same night.

**The estimator is a trailing-twelve-month sum, widened only when the window clipped** —
`domain/DividendYield`, framework-free and unit-tested. Measured against all 41 held names that
publish a `dividendYield`, a TTM sum reproduces Yahoo's own figure to a **mean absolute error of
0.06pp** (worst 0.51pp) — that field *is* a trailing yield. Its one failure mode is clipping: when a
payment slips, the window holds three quarterly payments instead of four and reads ~25% light. HICL
scored 4.48% against a real 5.96% for exactly that reason.

**The fix is not to infer a payment frequency and annualise the latest amount**, and that was
measured rather than assumed. Inferring frequency from the median gap between ex-dates misreads an
unequal interim/final payer as quarterly, and ×4 then reports **LGEN at 15.01% against a real
7.60%** — a doubling, in the flattering direction; mean error degrades 0.06pp → 0.34pp, worst case
→ 7.41pp. Instead the window widens to hold **`k` payments, where `k` is the larger of this year's
and last year's payment count** — the name's own cadence read off its own record, nothing guessed.
When payments are regular the counts agree and this *is* the TTM sum. Across the same 41 names it is
**identical to TTM on every one**, and HICL is the only holding in the portfolio where it moves at
all. Strictly dominant, so it ships. `Result.windowWidened` surfaces the clip rather than hiding it.

**Absent is never zero.** `DividendYield.derive` returns null when there is no price, and
`DividendYieldService.yieldsFor` omits any symbol with no scan marker, so a caller can always tell
"pays nothing" from "we don't know". A payer that stopped more than two years ago yields zero — a
figure off an older payment would describe an instrument that no longer distributes. Bonds are
skipped: a gilt's running yield comes from the coupon in its own id, which is exact.

**It self-heals, so no manual step is needed.** `StyleBreakdownService` hands its unscanned held
symbols to a second `FundamentalsWarmer` instance (`dividendWarmer` bean, label + cooldown reused,
distinct fetcher) which calls `PriceFetchJob.fetchSingle` on a background daemon thread. **The render
never waits** — the yield appears on the next load, the same bargain the sector block and the
open-orders quote warmer strike. Loading Allocation → Breakdown once backfilled 54 symbols / 2,552
payments unattended. `POST /rebuild-prices` does the same in one shot.

