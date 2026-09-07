# Attribution

- **Attribution** — `GET /attribution?from=YYYY-MM-DD&to=YYYY-MM-DD` → JSON, Chart.js
  horizontal bar + sortable table. Per-symbol GBP P&L over a window via
  `AttributionService` (formula: `end_value − start_value + window_cash_flows`). Each
  row also reports a period return (P&L ÷ peak GBP capital deployed in the window) and
  the same return annualised to 365.25 days — **blank under `MIN_ANNUALIZE_DAYS` (30)**,
  because compounding a 5-day move 73× yields five-figure percentages that swamp the column
  (a real +6.7% over 5 days read as 11,299%). End-value uses live intraday + live FX
  when `to == today`, daily close + historical FX otherwise — so totals reconcile with
  the cash-ledger view. A symbol whose boundary can't be valued (no `price_history`, or no FX
  rate for its currency) is **excluded from the rows and from every total** and reported in
  `AttributionResult.excluded`, which the UI renders as a yellow strip. Valuing it at zero
  instead was silently catastrophic: an FX timeout put every USD position at £0 on the *start*
  boundary while the end boundary still priced off the cached live rates, manufacturing a
  ~£299k phantom gain that was indistinguishable from real data (2026-08-09). Boundary prices
  come from two bulk `PriceHistoryRepository.loadPricesAsOf` queries, **not** whole series —
  the old `loadPriceSeries` pulled ~245,000 rows per request to read 94 of them, which made a
  5d window cost the same as `All` (~0.65s → ~0.10s). `% of total` is each row's share of the
  *net* P&L, so it sums to 100% but inflates past 100% when winners and losers cancel — kept
  deliberately (the blow-up says the net is small), flagged in the header tooltip. The panel's
  preset buttons guard against out-of-order responses with an `attrSeq` latch in
  `runAttribution` — several requests are in flight at once and a slow early one landing last
  would otherwise repaint the tiles for a window the buttons no longer show.

