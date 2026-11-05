# Postmortems

Resolved incidents kept for the invariants they justify.

## Three reconciliation bugs the transfers exposed (2026-08-27)

Moving euro-listed names into II, and buying a gilt there, put three latent defects on screen at
once: Files RT read £1,311,403 against a ledger/Live £1,328,010, BNP and RHM were 16.6% too big on
the Files tab, and II reported £20,631.74 of cash against a real £718.38. All three are fixed; the
three views now reconcile to the penny.

**1. `IISippParser` had never seen a euro.** `parseCurrency` knew `$` → USD and defaulted
*everything else* to GBP, so BNP arrived as 620 shares of a GBP listing and the RT column
multiplied the live €100.66 by the quantity without converting — £62,024 instead of £53,181, and
the same 16.6% on RHM. `parseCurrencyAmount` also stripped only `[£$,]`, so the native
`€62,409.20` failed to parse and the position carried a null market value as well. Nothing had
exposed it because **II held only GBP and USD listings until the euro names transferred in**. Both
now handle `€`, and the parser reads II's own **`Market Value £`** column into
`currentMarketValueGbp` — the export carries the broker's conversion beside the native figure, so
the position is valued at II's rate instead of being re-crossed here.

**2. II back-dates rows, and the importer skipped them without refreshing balances.** On
2026-08-25 a £19,913.36 gilt purchase was imported while that day's £26.45 interest credit had not
yet posted, so the purchase stored a closing balance of £691.93. The interest arrived in a later
export **earlier in the day's order**, which shifted the purchase to £718.38 — but the II dedup key
matched, the row was skipped, and its balance stayed stale. AJ Bell's importer has always
UPDATEd `cash_balance_gbp` on matched rows for exactly this reason ("AJ Bell can retroactively
insert a dividend mid-file, which shifts every later row's running balance"); `saveII` now does the
same.

**That fixes the stored chain but not the reported balance**, and the second half is the
interesting one. `latestCashBalances()` takes the last row per `(account, currency)` ordered by
`(transaction_date, rowid)` — and **rowid records when a row was imported, not where it sits in the
day**. The interest credit has the higher rowid and the earlier position, so no ordering over those
two columns can tell them apart. The file already knows: it is newest-first, so its first data row
*is* the account's balance. `saveII` now records that per `(account, currency)` into the
`KeyValueStore` (`cash_balance_<account>_<ccy>_{gbp,native}`) and `latestCashBalances()` prefers it
over the row scan, falling back when no importer has asserted one. The recorded figures came out
£718.38 and $74,739.15 — exactly the two numbers typed into the II SIPP cash form, which is the
check that the source is right. Left unfixed the ledger and Live overstated the whole portfolio by
the £19,913.36 the gilt had been bought with, while the Files tab (which takes II cash from the
form) did not — which is why only two of the three views agreed.

**3. The asset reconciliation could not see a position only one side had.** `reconcileAssets`
unions both symbol sets, but priced the ledger side from `latestBySymbol`, which is keyed off the
*parsed holdings* — so a symbol the ledger holds and no broker export names had no quote, was
dropped before the comparison, and left an unexplained gap between the two totals. That is exactly
the in-flight case the panel most needs to report: GLE, mid-transfer, had left AJ Bell's export and
not reached II's, and sat in the ledger at £6,034.47 with the panel silent. It now fetches quotes
for ledger-only symbols too and reports them against zero.

**The arithmetic afterwards**, which is what "reconciled" should mean:
`Files RT 1,302,186.67 + GLE 6,034.47 − Roth rounding 0.52 = 1,308,220.62 = the cash-ledger total`.

**A standing gotcha this turned up, now fixed.** `POST /sync` and `POST /export` used to persist
the II SIPP cash inputs with `defaultValue = "0"`, and `WebSupport.parseCash` mapped blank and
unreadable input to `BigDecimal.ZERO` — so **any request that omitted those params silently
overwrote the stored balances with zero**. They live in one `ii_sipp_cash_last*.txt` per currency
(`{,_usd,_eur}`, declared in `IiCashField`) and are the
holdings view's *only* source of II cash (II's holdings export omits the balance), so a stray
parameterless POST — a form that forgets a field, a hand-run `curl`, a script hitting `/export` —
cost a real figure with nothing on screen to say so; the only symptom was a total quietly short by
that amount.

`parseCash` now returns **null** for absent, blank *and* unreadable input, and `persistCash` reads
those three the same way: **leave the stored balance alone and return it**. All three are
distinguished from an explicit `0`, which is a real balance a user can state and still works.
Clearing the box therefore no longer zeroes anything — **type `0` to mean zero** — and the label
says so on hover. The asymmetry is deliberate: stating zero is one keystroke, while silently losing
a four-figure balance is invisible until the totals stop reconciling.

---

## AMZN showed a loss on a position that was up — one view's rule, ten views' bug (2026-10-29)

**Symptom.** On Holdings → From cash ledger, AMZN read **−£505.06 (−2.04%)** next to an
**Annualised return of +4.32%**. Within the current episode AMZN had four buys, no sales and no
dividends, and with only buys plus a terminal market value a negative gain cannot produce a
positive IRR — so the two columns could not both be right.

**Cause.** The Roth IRA books a mispriced fill as a cancellation plus a re-entry, and the
cancellation arrives as an ordinary opposite-signed row:

| 2026-06-02 | BUY 40 @ 257.55 | −$10,302 |
| 2026-06-05 | "Cancel Buy" 40 | +$10,302 |
| 2026-06-05 | "Correct Buy" 40 @ 255.00 | −$10,200 |

`PositionDetailService` dropped both legs of such a pair before running FIFO. **It was the only
caller that did.** `CashLedgerReconstructor` — read by eleven services, both sync views among them
— replayed the cancellation as a genuine sale, and FIFO retired the **oldest** lot (45 shares at
£165.09, bought 2025-10-03) instead of the 2026-06-02 lot at £191.16 that was actually being
cancelled. The expensive lot stayed in the basis and overstated it by exactly £1,042.70, which was
enough to flip the sign of the position.

The IRR was right throughout, because it reads the ledger's own dated flows and the cancellation's
cash nets out there. `cash-ledger-irr.md` already said to believe that column where the two
disagree; this is why.

**Spread.** Twelve correction pairs across seven symbols, all Roth IRA. On the open rows:

| | Cost overstated | Was | Now |
|---|---|---|---|
| AMZN | £1,042.70 | −£505.06 | **+£537.64** |
| NVDA | £2,779.78 | −£3,543.32 | −£763.54 |
| AVGO | £402.18 | −£646.96 | −£244.77 |

£4,224.66 of phantom cost. TSM carried three pairs and lost nothing — its cancellations happened to
line up with the oldest surviving lot anyway, which is exactly why this stayed invisible.

**Fix.** The rule — until then a private `pruneCorrections` inside a single service — moved to
`domain/LedgerCorrections`, where every lot engine reaches it, and is
applied inside `CashLedgerReconstructor.reconstructByAccount` and `PositionIrr.compute` so all
eleven readers get one answer. A reversal that is invisible to one reader and a sale to the next is
not a presentation choice — the two readers disagree about what happened.

**The second bug, found while fixing the first.** Applying the rule everywhere made a **closed**
SPCX position appear as 50 live shares worth £5,402.87. SPCX was bought and sold the same day, and
only then was the buy cancelled and re-entered — so removing the pair left a 06-12 sale standing
before the 06-15 buy that replaced it. `Timeline.sell` drains its lots and stops, so the oversell
was **swallowed in silence** and the replacement buy opened a position on an empty timeline. The
broker's own export does not list SPCX, and the Position tab had already been showing those phantom
50 shares, alone, for the same reason.

So a pair is now taken **only when the ledger still makes sense without it**: tentatively remove it,
replay that `(account, symbol)` running quantity, and keep the removal only if the quantity never
goes negative. Where it would, the pair stays and the position reports what the broker's own
quantities sum to — zero for SPCX, correctly, and the Position tab's phantom went with it. The
alternative was re-dating the replacement onto the cancelled row's day, which needs both an
inference about which row replaces which and an invented order for two bookings on one date; the
guard needs neither and refuses the edit instead of guessing.

**The invariant.** A rule about what the ledger *means* belongs in `domain`, not in whichever view
first needed it. The giveaway was `OrderFillReconciler` and `WatchlistService` both reaching across
into `PositionDetailService` for a static — three callers for a rule that lived in none of their
layers, while the two engines that most needed it could not import it at all.

## Allocation stood £30k above the ledger — one panel on a once-a-day price (2026-11-05)

**Symptom.** Allocation → Breakdown totalled **£1,301,551.98** against a Cash-ledger and Live
**£1,271,010.13** on the same book at the same minute — £30,541.85 apart, every sector row
inflated with it.

**Cause.** Two valuation paths. `AllocationService` valued positions from `price_history`
(`floorEntry(today)`); `SyncFromCashService` and the Live tab read `price_intraday`, refreshed
every 60s. `price_history` is written by a **22:00 cron**, and the laptop had slept through it, so
the missed run fired late at **10:26** and stamped a partial "today" bar at the 10:26 price. By
16:07 the book was down 2.37% and the panel was still reading 10:26 — a +£29.4k overstatement.
Re-pricing the book at the 10:26 quotes reproduces the screen to **0.045%**, which is what made
the diagnosis certain rather than plausible.

Two things that looked like causes and were not. The **sector rows legitimately differ** between
the tabs — Allocation looks *through* funds into sectors while the ledger's Group-by puts exactly
one group per holding, so the multi-sector funds sit in their own £224,925 row there; only the
inflation was a bug. And **`fetched_at` is overwritten on upsert**, so filtering on it does *not*
reconstruct what the table held earlier — the first attempt at a reconstruction was wrong for that
reason, and the log is what settled it.

**Why nothing caught it.** `CatchUpService` gates on the intraday pass's own heartbeat, never on
the age of the prices — deliberate, because out of hours the prices are legitimately old while the
job ticks along fine ([live-tab.md](live-tab.md)). The intraday pass was the one job that *was*
healthy, so the gate stayed shut. This was never dormancy: it was a once-a-day job feeding a panel
read intraday.

**Fix.** The replay's today sample is valued on the cached quote, falling back to the daily close
— see "History is daily closes; today is the live quote" in
[allocation-breakdowns.md](allocation-breakdowns.md). Restarting the app appeared to fix it,
because `onStartup` re-ran the fetch; the gap reopened as soon as the market moved again, which is
the shape of every bug whose repair is a restart.
