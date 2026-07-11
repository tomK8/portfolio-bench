package com.portfolio.application;

import com.portfolio.domain.momentum.CrashDiagnostics;
import com.portfolio.domain.momentum.MomentumConfig;
import com.portfolio.domain.momentum.MomentumStats;
import com.portfolio.domain.momentum.RegimeSplit;
import com.portfolio.domain.momentum.RotationBootstrap;

import java.util.List;
import java.util.Map;

/**
 * JSON DTO for the five-variant signal-speed / overlay comparison (the Momentum signals tab).
 * Assembled by {@link MomentumSignalsService}. Reuses {@link MomentumReport}'s nested row records
 * where the shape is identical (spans, gaps, rebalance log, benchmarks) so the UI renderers can be
 * shared too.
 *
 * <p>The header carries the run's context and the pre-registration block <b>verbatim</b> — the
 * adoption rule, the contamination disclosure and the five predictions were fixed before the run,
 * and the report's job is to hold the verdicts to them. The episode panel is explicitly
 * non-decisional: it exists for understanding, and carries zero weight in ADOPT/REJECT.
 */
public record MomentumSignalsReport(
        MomentumConfig config,
        String contextHeader,
        List<String> preRegistration,
        List<String> plainEnglish,
        String survivorshipCaveat,
        long bootstrapSeed,
        String analysisStart,
        String analysisStartReason,
        String fitFrom,
        String fitTo,
        String holdoutFrom,
        String holdoutTo,
        boolean holdoutAvailable,
        List<MomentumReport.SymbolSpan> symbolSpans,
        List<String> missingSymbols,
        List<MomentumReport.GapRow> gaps,
        List<NGroup> groups,
        List<Prediction> predictions,
        int episodeN,
        List<EpisodeRow> episodes,
        List<MomentumReport.BenchmarkRow> benchmarks,
        List<String> warnings) {

    /** The five variants head-to-head at one portfolio size N. */
    public record NGroup(int n, List<VariantRow> variants) {
    }

    /**
     * One signal variant at one N. V1 (the incumbent control) has a null verdict; challengers get
     * the pre-registered ADOPT/REJECT plus the post-hoc flag where the holdout edge only exists
     * with the contaminated 2026 months included. {@code stops}/{@code whipsaws}/{@code
     * stopsByYear} are populated for V5 only, {@code gateBindsByYear}/{@code gateBindsTotal} for
     * V4 only.
     */
    public record VariantRow(
            String key,
            String label,
            String description,
            MomentumStats fit,
            MomentumStats holdout,
            double fitTurnover,
            double holdoutTurnover,
            double costDragCagrPct,
            RotationBootstrap bootstrap,
            RegimeSplit.Result regime,
            CrashDiagnostics.Result crash,
            Double ytd2026,
            boolean adopt,
            boolean postHoc,
            String verdict,
            Map<String, Integer> gateBindsByYear,
            Integer gateBindsTotal,
            Map<String, Integer> stopsByYear,
            List<StopRow> stops,
            List<WhipsawRow> whipsaws,
            List<MomentumReport.RebalanceRow> log) {
    }

    /** One trailing-stop exit (V5), for the stop log / CSV. Costs and weights are portfolio fractions. */
    public record StopRow(String date, String section, String symbol, double weightSold,
                          double cost, double drawdownPct) {
    }

    /**
     * A stop followed by re-entry within the next two monthly rebalances (V5's whipsaw).
     * {@code txnCostBps} = exit + re-entry cost in portfolio bps; {@code trGapPct} = the name's
     * total return from stop close to re-entry close (negative = re-entered cheaper, the stop
     * helped; positive = bought back higher, the whipsaw cost more than the commissions).
     */
    public record WhipsawRow(String stopDate, String symbol, double stopDrawdownPct,
                             String reentryDate, double txnCostBps, double trGapPct) {
    }

    /** One pre-recorded prediction with its per-N pass/fail marks, scored after the fact. */
    public record Prediction(String text, List<Mark> marks) {
        public record Mark(int n, boolean pass, String detail) {
        }
    }

    /**
     * One variant's behaviour through one named episode (at {@code episodeN}) — explicitly
     * non-decisional. {@code holdings} lists the positioning month-end and each in-window
     * rebalance as "date: SYM SYM …"; {@code gldNotes} pinpoints when the variant derated, gated
     * or stopped GLD inside the window.
     */
    public record EpisodeRow(String episode, String from, String to, String variantKey,
                             Double windowReturn, List<String> holdings, List<String> gldNotes) {
    }
}
