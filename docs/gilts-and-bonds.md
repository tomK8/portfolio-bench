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

