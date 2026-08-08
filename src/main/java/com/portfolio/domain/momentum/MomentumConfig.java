package com.portfolio.domain.momentum;

import java.util.List;

/**
 * All knobs for the cross-sectional momentum rotation backtest, with the spec's defaults. Every
 * parameter the user might sweep — N, lookback, skip window, costs, rf, universe, holdout length —
 * lives here; the web layer overrides any subset via query params and the rest fall back to
 * {@link Builder} defaults (same convention as {@code TrendConfig}/{@code SwingConfig}).
 *
 * <p><b>Skip-month rationale:</b> the most recent {@code skipSessions} are excluded from the
 * momentum window because 1-month returns mean-revert. It is a parameter (so sensitivity can be
 * tested) but is never silently removed — the report always states the window actually used.
 *
 * @param universe            ranked symbol list (hand-picked with hindsight — survivorship-biased;
 *                            the report header must say so)
 * @param topNs               portfolio sizes run as separate variants (e.g. 3, 4, 5)
 * @param lookbackSessions    momentum window start, sessions before the rebalance (252 ≈ 12 months)
 * @param skipSessions        most-recent sessions excluded from the window (21 ≈ 1 month)
 * @param costPerSideBps      per-trade cost, basis points each way, charged on |Δweight|
 * @param rfAnnualPct         flat annual risk-free rate (percent) — Sharpe numerator and the
 *                            return credited to the cash slot when fewer than N names qualify
 * @param holdoutMonths       most recent months reserved as holdout; fit-section metrics and the
 *                            bootstrap never see them
 * @param bootstrapIterations matched random-selection bootstrap iterations per variant
 * @param randomSeed          bootstrap RNG seed, logged in the report so runs reproduce
 * @param maxFfillSessions    max sessions a price may be forward-filled; a longer gap makes the
 *                            symbol ineligible that month (logged, never silently filled)
 * @param minEligibleFactor   analysis starts at the first month-end with ≥ factor×N eligible names
 *                            (expanding universe — names enter as history accrues)
 * @param regimeSymbol        index for the §E-style regime split and crash-setup detection (QQQ)
 * @param regimeMaWindow      regime MA length in sessions (200d convention)
 * @param crashDrawdownPct    index decline from its running high (percent) that opens a
 *                            "crash setup" window
 * @param crashWindowMonths   months of strategy performance pooled after each crash-setup month
 * @param benchmarkSymbols    buy-and-hold benchmarks reported alongside (QQQ, SPY)
 */
public record MomentumConfig(
        List<String> universe,
        int[] topNs,
        int lookbackSessions,
        int skipSessions,
        double costPerSideBps,
        double rfAnnualPct,
        int holdoutMonths,
        int bootstrapIterations,
        long randomSeed,
        int maxFfillSessions,
        int minEligibleFactor,
        String regimeSymbol,
        int regimeMaWindow,
        double crashDrawdownPct,
        int crashWindowMonths,
        List<String> benchmarkSymbols) {

    /** Spec's seed universe: ~33 liquid US large caps across sectors, selected with hindsight. */
    public static final List<String> DEFAULT_UNIVERSE = List.of(
            "NVDA", "MSFT", "AAPL", "GOOGL", "AMZN", "META", "AVGO", "TSM", "MU", "LRCX",
            "KLAC", "AMAT", "LLY", "NVO", "JNJ", "UNH", "ISRG", "XOM", "CVX", "KO",
            "PG", "WMT", "COST", "JPM", "GS", "V", "MA", "CAT", "HON", "DIS",
            "NKE", "ORCL", "CRM");

    /** Per-side cost as a fraction (10 bps → 0.001). */
    public double costPerSide() {
        return costPerSideBps / 10_000.0;
    }

    /** Daily risk-free return (flat rate / 252). */
    public double rfDaily() {
        return rfAnnualPct / 100.0 / 252.0;
    }

    /** A copy with a different per-side cost — used for the zero-cost run behind the cost-drag figure. */
    public MomentumConfig withCostPerSideBps(double bps) {
        return new MomentumConfig(universe, topNs, lookbackSessions, skipSessions, bps, rfAnnualPct,
                holdoutMonths, bootstrapIterations, randomSeed, maxFfillSessions, minEligibleFactor,
                regimeSymbol, regimeMaWindow, crashDrawdownPct, crashWindowMonths, benchmarkSymbols);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder so the web layer can override an arbitrary subset and keep the rest default. */
    public static final class Builder {
        private List<String> universe = DEFAULT_UNIVERSE;
        private int[] topNs = {3, 4, 5};
        private int lookbackSessions = 252;
        private int skipSessions = 21;
        private double costPerSideBps = 10;
        private double rfAnnualPct = 4.0;
        private int holdoutMonths = 12;
        private int bootstrapIterations = 1000;
        private long randomSeed = 42L;
        private int maxFfillSessions = 5;
        private int minEligibleFactor = 2;
        private String regimeSymbol = "QQQ";
        private int regimeMaWindow = 200;
        private double crashDrawdownPct = 15;
        private int crashWindowMonths = 3;
        private List<String> benchmarkSymbols = List.of("QQQ", "SPY");

        public Builder universe(List<String> v) { if (v != null && !v.isEmpty()) universe = List.copyOf(v); return this; }
        public Builder topNs(int[] v) { if (v != null && v.length > 0) topNs = v; return this; }
        public Builder lookbackSessions(int v) { if (v > 0) lookbackSessions = v; return this; }
        public Builder skipSessions(int v) { if (v >= 0) skipSessions = v; return this; }
        public Builder costPerSideBps(double v) { if (v >= 0) costPerSideBps = v; return this; }
        public Builder rfAnnualPct(double v) { if (v >= 0) rfAnnualPct = v; return this; }
        public Builder holdoutMonths(int v) { if (v >= 0) holdoutMonths = v; return this; }
        public Builder bootstrapIterations(int v) { if (v > 0) bootstrapIterations = v; return this; }
        public Builder randomSeed(long v) { randomSeed = v; return this; }
        public Builder maxFfillSessions(int v) { if (v >= 0) maxFfillSessions = v; return this; }
        public Builder minEligibleFactor(int v) { if (v > 0) minEligibleFactor = v; return this; }
        public Builder regimeSymbol(String v) { if (v != null && !v.isBlank()) regimeSymbol = v.trim().toUpperCase(); return this; }
        public Builder regimeMaWindow(int v) { if (v > 0) regimeMaWindow = v; return this; }
        public Builder crashDrawdownPct(double v) { if (v > 0) crashDrawdownPct = v; return this; }
        public Builder crashWindowMonths(int v) { if (v > 0) crashWindowMonths = v; return this; }
        public Builder benchmarkSymbols(List<String> v) { if (v != null && !v.isEmpty()) benchmarkSymbols = List.copyOf(v); return this; }

        public MomentumConfig build() {
            if (skipSessions >= lookbackSessions) {
                throw new IllegalArgumentException("skipSessions must be < lookbackSessions");
            }
            return new MomentumConfig(universe, topNs, lookbackSessions, skipSessions,
                    costPerSideBps, rfAnnualPct, holdoutMonths, bootstrapIterations, randomSeed,
                    maxFfillSessions, minEligibleFactor, regimeSymbol, regimeMaWindow,
                    crashDrawdownPct, crashWindowMonths, benchmarkSymbols);
        }
    }
}
