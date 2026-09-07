# Period diff

- **Period diff** — `GET /period-diff?from=&to=` → JSON, rendered as stat tiles + a
  From/To/Delta table under the Performance group. Compares the portfolio on **any two dates**,
  valuing both boundaries from the cash ledger via `PortfolioReturnService.periodDiff` — it does
  **not** read `portfolio_snapshots` any more. (It used to diff two saved broker-written snapshot
  rows, which limited the picker to days a `/sync` happened to run and put the two ends on two
  different brokers' conventions.) Three figures, deliberately all shown because they only agree
  when no money moved: **Change** (raw end − start), **Gain** (change − external flows inside the
  window = what the market did), and **Total return** — the chain-linked daily TWR
  `Π(1 + (V_t − V_{t−1} − C_t)/V_{t−1}) − 1`, the same definition as the Returns tab, so a
  full-history window reproduces its since-inception number. Annualised is blank under
  `MIN_ANNUALIZE_DAYS` (30), same convention as Attribution. Flows are counted over
  **`(from, to]`** — a contribution landing on the start date is part of the opening balance, not
  of the window. **External flows are reported as two figures, not one**:
  `contributionsGbp` (ledger `CONTRIBUTION` rows — internal AJ Bell → II SIPP transfers net to
  zero across them, the outbound leg being recorded as a negative "Pension payment requested" /
  "Transfer Out" on the AJ Bell side) and `broughtForwardGbp` (the Roth USD seed as a single GBP
  step on Roth's earliest ledger date, surfaced in its own tile + a warning). Both come off the
  gain and both feed the TWR's `C` term — the value genuinely steps for a non-market reason — but
  only the first is money anyone paid in. Folding them together labelled a £138,857 account
  opening as £138,857 "contributed" over a 1y window (2026-08-09). `contributionsByDate()` still
  returns the combined map for `timeline()` / `moneyWeightedSummary()`, composed from
  `ledgerContributionsByDate()` + `broughtForwardByDate()`. Boundary valuation comes from `PortfolioValueService.dailyValuesIn(from, to)`,
  the windowed form of `dailyValues()`: the ledger still replays from its first row (quantities
  and cash must be caught up) but only in-window days are valued, so a 1y window prices ~365 days
  instead of ~9,500. It also returns `unpricedSymbols` — anything held in the window with no
  `price_history` rows values at zero on **both** ends, so it's surfaced as a warning rather than
  quietly shrinking the portfolio (same lesson as Attribution's `excluded`). Reversed dates, a
  window that predates the ledger, or a zero opening value degrade to null figures + a warning;
  nothing throws. Per-symbol P&L over the same window stays on the Attribution tab — not
  duplicated here.

