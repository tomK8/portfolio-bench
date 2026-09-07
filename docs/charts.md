# Charts (Contributions, Value over time)


Two read-only JSON endpoints feed Chart.js panels:

- **`GET /contributions`** (`ContributionService`) — cumulative GBP per account plus Total.
  Source: `cash_transactions WHERE type = 'CONTRIBUTION'`. RothIRA has no contribution rows;
  the brought-forward seed (USD) is converted to GBP at the FX rate on Roth's earliest
  ledger date and emitted as a single step.
- **`GET /portfolio-value`** (`PortfolioValueService`) — month-end portfolio GBP value.
  Forward-replays the full ledger once, valuing surviving positions at the most recent
  `price_history` close ≤ each sample (with **ceil-fill to the earliest known close** as
  fallback for samples that predate all stored prices). Bonds use the per-£100 formula;
  `GBp` (pence) divides by 100. Roth USD seed folded in on its earliest ledger date.
  Cash FX'd via `HistoricalFxRateProvider` (Frankfurter).

`ValueTimeline.missingPrices` lists symbols ever held with zero rows in `price_history`,
including the date range they were held. The dashboard renders them as a yellow warning
panel above the chart; each one also logs WARN. Dropping any close for the symbol clears
the warning and fills in the dip.

**Yahoo testing:** JSON parsing is unit-tested against `yahoo-nvda-sample.json`; a live-API test
(`YahooPriceFetcherIntegrationTest`) is `@Tag("integration")` and excluded by the surefire
`excludedGroups` property. Run with `mvn test -Dgroups=integration -DexcludedGroups=`.


## Panel overviews

- **Contributions** — `GET /contributions` → JSON, rendered by Chart.js. Cumulative
  per-account + Total.
- **Value over time** — `GET /portfolio-value` → JSON, Chart.js. Monthly portfolio GBP
  value; yellow warning panel above the chart lists symbols that were ever held but
  have zero rows in `price_history`.
