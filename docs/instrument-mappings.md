# Instrument mappings


`InstrumentMappingService` (+ `InstrumentMappingRepository`, `MappingsController`) — mappings are
added where the gap shows up, not by editing a source file and redeploying. Two SQLite tables:
`ticker_mappings` (symbol → Yahoo ticker, an *overlay* on the properties file — a row here wins, so
a wrong committed mapping is correctable without a release) and `instrument_aliases` (keyword →
symbol, above). `YahooTickerMap` takes a `Supplier<Map<String,String>>` for the overlay (so the
adapter needs no persistence dependency), caches it, and `reload()`s after every mutation — the
singleton's ~30 consumers pick it up with no restart. The no-arg constructor (properties only) is
kept because ~50 tests use it.

Three things happen together on save, and all three are load-bearing:
1. **Check before saving.** `YahooPriceFetcher.probe(ticker)` — one chart call returning
   found/reachable plus the listing's **name, currency, exchange and last close**. Those context
   fields are the point: a mapping that resolves to *nothing* is easy to spot later (the position
   never prices), but one that resolves to the **wrong live listing** is not — `BLND` probes as
   "Blend Labs, Inc. · USD · NYSE · last 1.52", which is the trap made visible at entry time.
   `request()` keeps the body on a non-200 so Yahoo's 404 ("No data found, symbol may be delisted")
   is distinguishable from an unreachable Yahoo; only the second offers "Save anyway".
2. **Take effect immediately** — `reload()` plus a background `PriceFetchJob.fetchSingle` on a
   single-thread daemon executor, so the price columns fill in rather than waiting for the nightly
   job (XWHS.L backfilled 3,662 bars in ~3s).
3. **Repair what the gap already broke** — `CashTransactionRepository.renameSymbol` re-keys ledger
   rows stranded under the long description, replacing the manual
   `UPDATE cash_transactions SET symbol=…` this file used to prescribe. In-place is the only option:
   the AJBell dedup key is `(transaction_date, type, symbol, amount_gbp)`, so leaving the old rows
   and re-importing inserts a *second* set under the new symbol and double-counts the position. The
   re-key is deliberately **narrow** — only stored symbols containing a space (a stranded
   description; a ticker never has one) and not `Instruments.isBond`. Without that guard the keyword
   "bp" would re-key the genuine `BP.` position, and a rename is not undoable from the dashboard.
   The repaired-row count is reported in the response, never silent.

**UI**: `fragments/mappings.html` holds two shared fragments — `unpriced(...)` (ticker-only editor,
used by both sync tabs' unpriced-symbols panels) and `list` (the collapsible "All instrument
mappings" list at the foot of Open orders: an **add row**, then your rows with Remove buttons, then
the committed baseline read-only).

**The add row is the only editor not attached to a gap the app noticed**, and that is the point of
it. Every other one is reactive — `unpriced(...)` needs a *held* symbol that has never priced,
the Open orders row needs an order description that resolves to no symbol. A symbol in neither
state had no reachable editor anywhere in the app: a watchlist name Yahoo rejects is the ordinary
case, and `2330` (TSMC's Taipei listing, which the user neither holds nor has an order on) is the
one that found it. Worse, `UnresolvedSymbols.FIX_HINT` — the message that very symbol
produces — named this list as the place to fix it, and the list was its own inventory and
nothing else. The hint was pointing at a read-only table.

It is rendered by `mapRenderList`, not by the fragment, because the body is fetched rather than
server-rendered; it reuses the same `.map-row` markup and the same two endpoints as the reactive
editors, so there is still exactly one save path. `data-reload="list"` is what tells the shared
click handler to repaint the list it sits in, after the same 3.5s the orders panel waits — the
save's own confirmation is passed through `mapLoadList(body, notice)` so it survives the repaint
rather than being wiped by it. Open orders renders its own richer row (match text + symbol + ticker) from
`OrdersReport.unmapped`, computed by `OpenOrdersService.unmappedInstruments` after enrichment:
`needsAlias` is exactly `symbol.equals(name)` (the AJ Bell parser's fall-through contract — the
other two brokers export a real ticker alongside a descriptive name, which never collide), else the
symbol resolved but `getLatestPriceDate` is null, the same "never priced" test
`SyncPortfolioService.unpricedSymbols` applies. `SyncResult.unpricedSymbols` is
`List<UnpricedSymbol>` (symbol + ticker, `display()` for the old string) so the editor can be
pre-filled rather than only naming the problem.

All of it is driven by **one delegated listener on `document.body`** in `dashboard.js` (`mapVal` /
`mapStatus` / `mapProbeText` / `mapPost` / `mapLoadList`) rather than per-fragment binding — these
fragments are htmx-swapped, so anything bound inside them would need re-attaching after every swap.
Typing a symbol completes the ticker box (`XWHS` → `XWHS.L`, suffix from the order's currency via
`exchangeSuffix`; EUR gets nothing, it spans three exchanges) and stands down the moment the ticker
is hand-edited. A successful save on the orders panel repaints it after 3.5s — the backfill is on a
background thread, and repainting immediately would show the row mapped but still priceless.

**`close` vs `adj_close`:** use `close` for "market value of the position on date X", `adj_close`
for total-return calculations. **`YahooPriceFetcher` re-derives `adj_close` from the
`events.dividends` and `events.splits` stream on every fetch** — Yahoo's bundled `adjclose`
field is only split-adjusted (not dividend-adjusted) for many UK listings, so trusting it
silently understates total return. The backward-walk handles both dividends and splits and
yields a true total-return adj_close. The daily incremental run only refreshes the latest
day, so a new dividend on a held name leaves historical `adj_close` stale — trigger
**Rebuild adj_close** on the dashboard (or `POST /rebuild-prices`) to re-fetch the full
window per ticker and refresh every row via `upsertPriceBars`. The gilt paths (intraday
rollup, Tradeweb batch import) also use `upsertPriceBars`, so latest write wins for
`(symbol, date)` — clean price feeds both `close` and `adj_close` (no total-return math
for bonds).

**Currency is stored verbatim** from Yahoo. `.L` (London) tickers report `"GBp"` (pence);
`PortfolioAggregator.realtime` divides by 100 before producing the RT market value.

**II SIPP cash field (holdings view + Excel export only):** II's holdings CSV export omits the
cash balance, so the dashboard form supplies it as **one input per currency the account holds** —
`iiSippCash` (GBP), `iiSippCashUsd` (USD), `iiSippCashEur` (EUR). `IiCash.totalGbp` converts each
non-sterling box at the live FX rate the run already fetched and sums into the single GBP figure
that flows into `PortfolioMetrics` and the Excel sheet; `IiCash.gbp` does one currency at a time
for the reconciliation panel, so an II euro balance is compared against the ledger's **euro**
balance instead of disappearing into the sterling row. Each input persists in `KeyValueStore`
under its own key (`ii_sipp_cash_last`, `ii_sipp_cash_last_usd`, `ii_sipp_cash_last_eur`).

**The set of boxes is declared once, in `IiCashField`** (param name + KV key, in form order).
Adding a currency is that constant plus a label in `dashboard.html` — nothing between the
controller and the Excel writer names a currency, because the figures travel together as one
`IiCash` rather than as a parameter per currency. They used to be two `BigDecimal` parameters
threaded through `POST /sync`, `POST /export`, the sync and the reconciliation, which is four
signatures per new currency. `WebEndpointsTest.theFormCarriesEveryIiCashBox` asserts the form
really carries a box for every constant: a box the template misspells posts nothing, and nothing
means *keep the stored balance*, so the figure typed into it would never arrive and never complain.

The cash-ledger view, contributions chart, and value-over-time chart don't use these inputs —
they derive II cash from the latest stored `cash_balance_gbp` per `(account='II', currency)`
directly.

