# Gilts & bond identification


`Instruments.isBond(securityId)` checks for `%` or `GILT` prefix. Single source of truth used to
(1) sort bonds into their own section at the bottom of the Portfolio sheet, (2) skip Yahoo price
fetches (no coverage there), (3) switch the per-£100-nominal value formula (`qty × price / 100`)
in `PortfolioAggregator.realtime`, `SyncFromCashService.realtime` (cash-ledger view), and
`PortfolioValueService.positionGbp` (value chart) — keep these in sync if the bond price model
changes.

**The gilt id is built in exactly one place — `Instruments.giltId(coupon, year)`** — and it
canonicalises the coupon by trimming trailing zeros, so `4.5` / `4.50` / `4.500` all produce
`GILT 4.5% 2028`. That string is a **join key** across four writers that each read the coupon out
of different text, and the sources disagree about trailing zeros: HL pads (`"4.750"`), Tradeweb
pads (`"3.000"`), AJ Bell's *holdings* export doesn't (`"4.75%"`) — and AJ Bell's *cash statement*
writes `"GILT 4.50%"`. Each writer used to normalise for itself or not at all (two private copies
of the same trim, two parsers with none), so a coupon a broker happened to spell with a trailing
zero produced the position under one string and its prices under another. A £50,597 gilt bought
2026-08-21 landed in the ledger as `GILT 4.50% 2028` while HL had been pricing it all along as
`GILT 4.5% 2028`: the cash-ledger view joined neither the intraday quote nor the daily close, so
it reported the position as unpriced and **dropped it from the totals** (2026-08-24).
`GILT 1.00% 2024` and `GILT 5.00% 2025` carry the same defect and were only ever invisible because
both matured before anyone read a total that was missing them. Building the whole id there rather
than exposing a coupon-trimming helper is deliberate: a caller cannot then assemble the string
correctly and forget to canonicalise it. The six writers are `AJBellSippParser.extractBondId`
(holdings CSV), `AJBellCashStatementParser.tryExtractGiltId` (all three description shapes),
`IIInstruments.giltIdFromName` (both II parsers that see a name), `IICashStatementParser` (via
the SEDOL map — see [price-history.md](price-history.md)), `GiltPriceFetcher` (HL) and `TradewebGiltPriceParser` — **never concatenate
`"GILT " + coupon` anywhere else.**

**II names a gilt three ways and only one of them carries a coupon**, which is why its two paths
differ. The holdings export writes `UNITED KINGDOM 4.375 07/03/2030` — coupon as a bare decimal,
*no `%` sign at all* — while the order export and the website write `4 3/8% TREASURY GILT
07/03/2030`, the eighths a gilt is actually quoted in; `parser/IIInstruments.giltIdFromName`
reads both and is the only place either shape is decoded. The **cash statement carries neither**:
its description is `20000 HIS MAJE4LN STK30` and its symbol is II's own code, `T30`. Nothing in
that file identifies the instrument except the **SEDOL** (`BSQNRD0`), so that is what
`IICashStatementParser` joins on — see "Gilt SEDOLs" in [price-history.md](price-history.md) for where the map comes
from. A `giltIdFromName` match requires a gilt marker (`UNITED KINGDOM` / `TREASURY` / `GILT`)
**and** a maturity date **and** a plausible coupon: half a gilt id is worse than none, because
`Instruments.isBond` switches the whole position onto the per-£100-nominal value formula on the
strength of it. An unresolved gilt keeps the broker's code — a missing price, which the
unpriced-symbols panel surfaces, rather than a wrong one.

Unconverted, `T30` was the 2026-08-24 failure again in a new place: no feed knows the code, so
the cash-ledger view dropped a £19,902 position from the totals outright, and had it priced it
would have used the per-share formula against a per-£100 quote (2026-08-25). The two stored ledger
rows were re-keyed in place; a re-import of the same file now dedups against them, since the
canonical id is part of the II dedup key `(date, type, symbol, amount, currency)`.

**A gilt's SEDOL is read out of its ISIN**, by `Instruments.sedolFromIsin` and nowhere else: a GB
ISIN *is* its SEDOL (`GB00` + the seven characters + a check digit). HL prints the old numeric
SEDOLs a character short — `240419` where the SEDOL is `0240419` — so reading the printed tail
captured nothing at all for the 6% 2028 and the 4.25% 2032, and padding the short form back out
would have been a guess. The ISIN in the same cell carries the full form for every row on the
page. See "Gilt SEDOLs" in [price-history.md](price-history.md), which is also where the rest of
that cell goes: the ISIN and the maturity date are what the Watchlist's gilt picker is searched
by, because the id built above is the one name for a gilt that no broker, exchange or factsheet
uses.

**II's price column is per £1 nominal, AJ Bell's is per £100.** II exports the 4.375% 2030 at
`£0.9951` against a `20,000` quantity, AJ Bell exports its gilts at `76.76` against `200,000` —
both `Market Value` figures are right, and `IISippParser` uses the exported value rather than
`qty × price`, so nothing has to reconcile the two conventions. Only the *feed* price matters for
the RT column, and that is HL's, always per £100.

`persistence/GiltSymbolMigration` re-keys rows written before that, run from the
`CashTransactionRepository` and `PriceHistoryRepository` constructors (the ledger has no re-import
path that rewrites a symbol in place, so nothing else can reach those rows). `UPDATE OR REPLACE`,
not a plain `UPDATE`: a bond may already hold rows under both spellings — the live database had
`GILT 1.00% 2024` beside `GILT 1% 2024` — and a primary-key collision would otherwise abort the
repository's whole constructor. Idempotent: once every stored symbol is canonical it is one
`SELECT DISTINCT` and no writes. `price_intraday` is deliberately not migrated (15-day retention,
self-heals).

The resulting string is the symbol under which `GiltPriceFetchJob` writes to `price_intraday`, so
the aggregator joins holding → RT price without any ticker translation.

## Benchmark yields on the Reference page

`GET /reference/gilt-yields` → `GiltYieldService.current()` → `CnbcGiltYieldFetcher`: the 2-, 5-,
10- and 30-year yields, loaded into `/reference` by htmx and re-polled every 60s while the tab is
visible. Nothing is stored and no job runs: the page asks, one keyless GET answers all four tenors,
and a 30s TTL stops a second tab or a reload sending its own.

**A tenor is a benchmark gilt, not a point on a fitted curve.** CNBC's `GB10Y-GB` is the yield of
whichever gilt it currently designates the 10-year, and the quote names that gilt by coupon and
maturity. Both are shown beside the yield, the gilt under its id from `Instruments.giltId` like
every other gilt name here, because on 2026-11-25 the "2-year" matured in May 2029. The Bank of
England's fitted curves are the constant-maturity alternative. They are linked, not fetched: the
IADB publishes only 5/10/20-year par yields, a business day late, so it cannot fill the 2-year or
the 30-year at all.

**The day's move is recomputed, never read.** It is `last − previous_day_closing` in basis points.
The feed's `change_pct` is the benchmark bond's *price* change and carries the opposite sign
(−0.0346% on a day the 2-year yield rose 1.5bp). No previous close means no change cell, never
"0.0 bp".

**A missing tenor is named, and a failed refresh keeps the last good figures.** A tenor the feed
does not answer (an unknown symbol comes back as `code: 1` with no fields, or `last` is blank) is
listed under the table, never shown as a blank or zero row. An answer with no tenors in it, or
with no `FormattedQuote` envelope at all, is a failure rather than a cached empty table. A failed
refresh keeps the previous rows and says when they were fetched. With nothing fetched yet, the
strip gives the reason and points at the links. The endpoint is unofficial, so a change of shape
has to read as a failure, never as "no gilts".

**Times are London time.** One request can return quotes stamped in different zones (the 2-year
in `-0500` and the rest in `+0000` on 2026-11-25), so each `last_time` is parsed with its own
offset and shown in Europe/London.
