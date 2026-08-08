package com.portfolio.application;

import com.portfolio.domain.swing.Stats;
import com.portfolio.domain.trend.Episodes;
import com.portfolio.domain.trend.ExposureBootstrap;
import com.portfolio.domain.trend.LiveState;
import com.portfolio.domain.trend.TradeLog;
import com.portfolio.domain.trend.TrendAnnual;
import com.portfolio.domain.trend.TrendConfig;
import com.portfolio.domain.trend.TrendStats;
import com.portfolio.domain.trend.Whipsaw;

import java.util.List;

/**
 * The full trend-filter backtest report — the JSON payload behind the Trend filter tab. One
 * {@link VariantResult} per grid cell (signal variant × MA length, plus the monthly-close
 * cross-check cells), each carrying its headline stats <em>and</em> every sensitivity dimension,
 * so the UI presents the full sweep rather than a champion cell. Where variants disagree, the
 * disagreement is the finding.
 *
 * <p>Headline settings: full switch, next-open execution, {@code headlineCostBps} per side,
 * historical cash. Every other block varies exactly one dimension off that baseline.
 */
public record TrendReport(
        TrendConfig config,
        DataPanel data,
        TrendStats buyHold,
        List<VariantResult> variants,
        List<AnchorSweep> anchorSensitivity,
        LiveBlock liveState) {

    /**
     * Sample honesty in one place: the raw span, what part of it is a spliced price-only proxy,
     * where the cash series starts (and how far the flat-rate fallback reached), and the actual
     * analysis window every cell runs over (MA warm-up consumes the difference).
     * {@code dotComInSample} refers to the analysis window, not the raw span.
     */
    public record DataPanel(
            String primarySymbol, String from, String to, double years,
            boolean spliced, String extensionSymbol, String spliceDate,
            String proxiedFrom, String proxiedTo,
            boolean dotComInSample,
            String cashSymbol, String cashFrom, String cashFallbackUntil, double flatCashRatePct,
            String analysisStart, int analysisBars,
            List<String> warnings) {
    }

    /**
     * One grid cell. {@code costs} sweeps the per-side cost; {@code closeFill} re-runs at
     * signal-close fills (the idealised case); {@code halfSwitch} keeps half the position
     * invested when out; {@code cashZero} zeroes the cash leg — over 25 years that assumption
     * moves the answer, so it gets its own column. {@code bootstrap} is null when the variant
     * never exited (nothing to scatter). {@code trades} is the exportable log, headline settings.
     */
    public record VariantResult(
            String key, String family, String label, Integer maLength,
            TrendStats headline,
            List<CostRow> costs,
            TrendStats closeFill,
            TrendStats halfSwitch,
            TrendStats cashZero,
            Whipsaw.Result whipsaw,
            ExposureBootstrap.Result bootstrap,
            List<TrendAnnual.YearRow> annual,
            List<Episodes.Row> episodes,
            List<TradeLog.Row> trades) {
    }

    public record CostRow(double bps, TrendStats stats) {
    }

    /**
     * Timing-luck check for the month-anchored clock: the identical rule at every anchor offset.
     * A wide CAGR band means the headline cell is anchor-luck, not signal.
     */
    public record AnchorSweep(int maLength, Stats.Dist cagr, Stats.Dist maxDd, List<OffsetPoint> offsets) {
    }

    public record OffsetPoint(int offset, double cagr, double maxDd, int switches) {
    }

    /** Current close vs MA for the primary series and the actual holding, plus each variant's say. */
    public record LiveBlock(
            String asOf,
            String primarySymbol, List<LiveState.MaReadout> primary,
            String holdingSymbol, String holdingAsOf, List<LiveState.MaReadout> holding,
            List<LiveState.VariantState> variants) {
    }
}
