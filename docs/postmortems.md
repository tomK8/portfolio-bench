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

