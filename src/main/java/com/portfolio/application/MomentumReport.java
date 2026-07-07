package com.portfolio.application;

import com.portfolio.domain.momentum.CrashDiagnostics;
import com.portfolio.domain.momentum.MomentumConfig;
import com.portfolio.domain.momentum.MomentumStats;
import com.portfolio.domain.momentum.RegimeSplit;
import com.portfolio.domain.momentum.RotationBootstrap;

import java.util.List;
import java.util.Map;

/**
 * JSON DTO for the cross-sectional momentum backtest (the Momentum tab). Assembled by
 * {@link MomentumAnalysisService}; every non-finite double serializes as {@code null} app-wide via
 * {@code config/JacksonConfig}, so empty distributions never corrupt the payload.
 *
 * <p>The header carries the survivorship caveat verbatim (the whole point of the exercise is the
 * comparison against controls, not raw CAGR), the reproducible bootstrap seed, and the fit/holdout
 * window boundaries — kept explicit so the split can't be fudged.
 */
public record MomentumReport(
        MomentumConfig config,
        String survivorshipCaveat,
        long bootstrapSeed,
        String analysisStart,
        String analysisStartReason,
        String fitFrom,
        String fitTo,
        String holdoutFrom,
        String holdoutTo,
        boolean holdoutAvailable,
        List<SymbolSpan> symbolSpans,
        List<String> missingSymbols,
        List<GapRow> gaps,
        List<Variant> variants,
        Section baselineFit,
        MomentumStats baselineHoldout,
        List<BenchmarkRow> benchmarks,
        List<String> warnings) {

    /** Data span for one universe/benchmark symbol, for the header table. */
    public record SymbolSpan(String symbol, String from, String to, int bars, boolean ranked) {
    }

    /** A forward-fill gap that made a symbol ineligible over a stretch — surfaced, never hidden. */
    public record GapRow(String symbol, String from, String to, int sessions) {
    }

    /**
     * The rich fit-window bundle for one variant or the baseline: performance, turnover, the
     * cost drag it paid, regime split, crash diagnostics and the matched random bootstrap.
     */
    public record Section(
            MomentumStats stats,
            double meanMonthlyTurnover,
            double costDragCagrPct,
            RegimeSplit.Result regime,
            CrashDiagnostics.Result crash,
            RotationBootstrap bootstrap) {
    }

    /**
     * One momentum variant (top-N). The fit section holds the controls; holdout is lean
     * performance only. {@code bottomNFitCagr} is the matched reversed-signal (worst-N) CAGR — if
     * it beats {@code fit.stats().cagr()}, the sign convention or data is wrong.
     */
    public record Variant(
            String label,
            int n,
            Section fit,
            MomentumStats holdout,
            double holdoutMeanTurnover,
            Double bottomNFitCagr,
            Double bottomNHoldoutCagr,
            String verdict,
            List<RebalanceRow> log) {
    }

    /** One rebalance for the event log / CSV export. {@code heldRanks} = "SYM:rank:score" list. */
    public record RebalanceRow(
            String date,
            String section,
            int eligible,
            List<String> in,
            List<String> out,
            List<String> heldRanks,
            double turnover,
            double cost,
            double cashWeight) {
    }

    /** Buy-and-hold benchmark (QQQ, SPY) over the same fit and holdout windows. */
    public record BenchmarkRow(String symbol, MomentumStats fit, MomentumStats holdout) {
    }

    /** Convenience for the UI: baseline fit monthly returns (regime/grid share the same keys). */
    public Map<String, Double> baselineMonths() {
        return baselineFit == null ? Map.of() : baselineFit.stats().months();
    }
}
