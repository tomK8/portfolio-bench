# Annualised return (cash-ledger IRR)


The **Annualised return** column on Holdings → From cash ledger, and the four columns it becomes
in that tab's Excel export. `SyncFromCashService` computes it per row; the maths is
`domain/PositionIrr` (flows, episodes, holding period) over `domain/Irr` (the solver). No new
queries — it rides on `loadDividendTransactions()`, which the view already loads.

**IRR, not CAGR, and that is the whole reason the column exists.** Positions here are accumulated
in chunks: GOOG has 20 flows since its current episode opened, EQQQ 32. A CAGR needs one start
date and a position built from nine purchases across three years has nine, so the existing Gain %
divides today's value by a cost basis assembled at nine different times and calls the result a
return. IRR asks instead what constant annual rate reconciles every pound with the date it went
in. Flows: buys negative (the ledger amount, so dealing fees and FX charges are already inside
it), sells positive and net, dividends positive on their pay date, and current market value as a
positive terminal flow dated today — a hypothetical liquidation. `CHARGE` rows never appear
because `loadDividendTransactions` doesn't load them; per-trade fees are already inside the
transaction amounts, which is what the column wants.

**It never divides by cost basis, which is the second reason.** Brokers net realised gains through
the surviving shares' average cost, leaving a nonsensical or negative figure (MRVL and AMAT both
do this) that makes every gain-over-cost ratio garbage while leaving the IRR untouched. Where the
two disagree on such a name, this column is the one to believe. The worked example on the live
data is `GILT 4.75% 2038`: **−5.39% Gain % against +1.21% IRR**, because the clean price fell
while three £2,375 coupons landed. Gain % is price-only by construction; the IRR sees the coupons
on their dates.

**Episodes — the reason a re-entered position doesn't report nonsense.** A flow series that
changes sign more than once can admit several IRRs, and a position sold to zero and later
re-bought does exactly that. `PositionIrr.findEpisodes` walks the quantity timeline (summed
across accounts — one row on screen is one episode here) using `CashLedgerReconstructor`'s sign
model, splits at each return to zero, and reports the **current** episode only, flagged `↻` on
the row. AAPL is the live case: 20 shares bought 2018, all 20 sold 2020-02-25, re-entered
2025-10-03 — averaging the closed 2018–20 holding into today's rate would describe a position
that stopped existing six years ago. A **partial** sale is not an exit and does not reset;
splits (zero-amount `TRANSACTION` rows) carry no flow and cannot re-date an episode.

**Under `MIN_ANNUALIZE_DAYS` (90) nothing is annualised** — the raw period return is shown with
its span beside it (`-4.00% · 2d`), and the rate is not computed at all rather than computed and
hidden. Same convention and same reasoning as Attribution and Period diff. The marker is styled
muted *inside* a green/red cell precisely so the caveat can't read as part of the figure.

**The solver — `domain/Irr`.** Newton-Raphson from a naive seed, bisection fallback on
`[-0.99, +10.0]`, and the fallback is load-bearing rather than decorative: the NPV curve is nearly
flat when one flow dwarfs the others (a £50 dividend against a £20,000 purchase), so a derivative
step can throw the iterate past −1 where the discount factor is undefined. Newton returns `null`
the moment it leaves the bracket, flattens or goes non-finite, so the caller falls through rather
than printing a number nobody solved for; `Solution.method()` records which one answered.
A root outside the bracket is reported as `null`, **not clamped to the edge** — a total loss must
not print as "−99%". No sign change means no root, so all-outflow or all-inflow series return
`null` rather than a bracket midpoint dressed as an answer.
**The day-count basis is a parameter and the two callers legitimately differ**:
`DAYS_PER_YEAR_XIRR` (365) is the spreadsheet convention, so this column reconciles with Excel
cell-for-cell (`IrrTest` pins Microsoft's own published XIRR example to 1e-8);
`DAYS_PER_YEAR_CALENDAR` (365.25) is the house convention Attribution and Period diff use, and
`PortfolioReturnService.moneyWeightedSummary` stays on it so its tiles remain comparable with the
ones beside them. That method **used to carry its own private bisection-only `xirr`**; it now
delegates here, so there is one implementation of the maths and not two.

**Currency: GBP is the headline, native is an overlay.** The rate is solved on `amountGbp`, so FX
is inside it. A second solve on the rows' native `amount` gives the currency contribution
(GBP rate − native rate, of the order of a few %/yr on the USD book) — but **only when every flow
in the episode settled in the listing currency**, checked per row rather than assumed per broker.
AJ Bell settles USD-listed trades in GBP, so its rows carry no native amount and a "native" IRR
built from them would be the GBP one wearing a dollar sign; AAPL and GOOG are both mixed and
correctly get no native leg. A **GBP** listing gets none either (`REPORTING_CURRENCY`): there is
no contribution to isolate, and a "Currency effect" column of +0.00% on every UK holding reads as
a finding rather than as the definition it is.

**Cost-weighted holding period** (`Σ(cost×days) ÷ Σcost`, in years) rides along as context — it is
what stops a small early purchase dating the whole position, and it is the number that explains
why a 323-day episode can show 0.41 yr of deployed capital.

**Presentation.** The screen keeps one column; everything else is in the cell's `title`, composed
by `PositionIrr.Result.explain()` in Java rather than as a four-clause Thymeleaf expression.
`data-sort-value` carries the raw rate so `parseVal` sorts on the number and not on the marker
text beside it. Excel gets four columns instead, because a sheet has nowhere to put a tooltip:
`Annualised return` stays a real number, `Return basis` carries the caveat beside it
("Annualised (current episode)" / "Period return, 41d" / "No rate" — same convention as the
orders sheet's `Quote`), plus `Holding period (yr)`, `Native return` and `Currency effect`.
An unsolvable rate writes **no cell**, never a zero: an empty cell reads as "not computed", a
0.00% reads as "broke even". The IRR columns get no total row — rates over different flow series
do not sum, and the portfolio-level money-weighted return is a different calculation over
different flows (Performance → Returns).

