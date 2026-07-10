package com.portfolio.application;

import com.portfolio.domain.range.RangeArms;
import com.portfolio.domain.range.RangeConfig;
import com.portfolio.domain.range.RangeTradeStats;

import java.util.List;

/**
 * JSON DTO for the percentile-floor range-strategy backtest ({@code GET /range}). Shape mirrors
 * {@code TrendReport}/{@code SwingReport}: the config is echoed verbatim (with the seed) so a
 * re-run is reproducible; NaNs serialize as JSON {@code null} via {@code JacksonConfig}.
 *
 * <p>{@code preRegistration} is printed verbatim at the top of the report. {@code exploratory}
 * flips when any parameter differs from the pre-registered defaults — that run must be labelled
 * exploratory, per the pre-registration block.
 */
public record RangeReport(
        List<String> preRegistration,
        boolean exploratory,
        List<String> exploratoryChanges,
        RangeConfig config,
        List<String> warnings,
        List<String> verdicts,
        List<SymbolData> data,
        String fxNote,
        boolean fxAvailable,
        List<ScopeResult> scopes,
        List<GridCell> sensitivity,
        List<EventRow> events,
        Featured featured) {

    /** One name's data-panel row: actual span, hygiene flags, unit errors. */
    public record SymbolData(String symbol, String family, String currency,
                             String from, String to, Double years, int bars,
                             boolean shortHistory, boolean missing,
                             List<DataErrorRow> dataErrors) {
    }

    public record DataErrorRow(String date, double movePct) {
    }

    /**
     * One scope (Pooled / Family A / Family B / a single name) over both regime windows.
     * {@code currencyBasis} is "GBP" for pooled/family capital arms, "local" per name.
     */
    public record ScopeResult(String scope, List<String> members, String currencyBasis,
                              WindowResult full, WindowResult trailing) {
    }

    /**
     * One regime window's results for a scope. {@code episodes} is the dedup-adjusted independent
     * event count ({@code thin} when below the floor — dim the cell). Arms are null when the
     * window has no trades (or FX is unavailable for a GBP-basis scope — see {@code armsNote}).
     */
    public record WindowResult(String label, String from, String to,
                               int episodes, boolean thin,
                               RangeTradeStats accumulation,
                               RangeTradeStats pctlExitVariant,
                               RangeTradeStats trading,
                               RangeArms.ArmOutcome armA,
                               RangeArms.ArmOutcome armB,
                               RangeArms.Bootstrap armC,
                               RangeArms.ArmOutcome armD,
                               String armsNote) {
    }

    /**
     * One sensitivity-grid cell: Arm A's bootstrap percentile (return-on-deployed basis) within
     * its matched Arm-C distribution. {@code insideIqr} ⇒ the UI dims the cell (indistinguishable
     * from random-date accumulation); {@code preRegistered} outlines the (10, 10) cell.
     */
    public record GridCell(String scope, String mode, double entryPct, double tp,
                           int events, int episodes,
                           Double armARoD, Double cMedianRoD, Double pctlDeployed,
                           boolean insideIqr, boolean thin, boolean preRegistered) {
    }

    /** One trade in the exportable event log (GBP costs at the standard notional). */
    public record EventRow(String symbol, String mode, String entryDate,
                           double level, Double pctlRank, double fill,
                           String exitType, String exitDate, Double exitFill,
                           Double priceRet, Double trRet, double mae,
                           int underwaterSessions, long daysHeld, double costsPaidGbp) {
    }

    /** The featured name's price series (thinned) + trade markers for the annotated chart. */
    public record Featured(String symbol, String currency,
                           List<String> dates, List<Double> closes,
                           List<Marker> markers) {
    }

    /** {@code kind}: entry | tp | pctl | sl | ambiguous | open. */
    public record Marker(String date, double price, String kind, int tradeSeq) {
    }
}
