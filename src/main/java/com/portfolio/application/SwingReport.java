package com.portfolio.application;

import com.portfolio.domain.swing.Flushes;
import com.portfolio.domain.swing.ForwardReturns;
import com.portfolio.domain.swing.Placebo;
import com.portfolio.domain.swing.RealizedVol;
import com.portfolio.domain.swing.ReturnCorrelation;
import com.portfolio.domain.swing.SwingConfig;

import java.util.List;

/**
 * The full swing-strategy validation report — the JSON payload behind the Swing stats tab.
 * Assembles the pure-domain engine outputs into a per-symbol + pooled-cluster structure, each side
 * split into the two §9 regimes (full history vs trailing 6 months), plus the cross-cluster
 * correlation block.
 *
 * <p>{@code earningsHygiene} is a reserved slot for §8 (earnings-window flagging), deferred until a
 * reliable multi-year earnings-date source exists — it ships {@code null} so the shape is stable
 * when that lands.
 *
 * @param config     the effective configuration used (echoed so the UI knows what it's looking at)
 * @param perSymbol  one report per watchlist symbol
 * @param pooled     the cluster pooled into a single report (symbol {@code "CLUSTER"})
 * @param correlation §7 correlation regime across the cluster
 * @param earningsHygiene reserved for §8; always {@code null} for now
 */
public record SwingReport(
        SwingConfig config,
        List<SymbolReport> perSymbol,
        SymbolReport pooled,
        ReturnCorrelation.Result correlation,
        Object earningsHygiene) {

    /**
     * One symbol's (or the pooled cluster's) statistics. §1 realized vol sits here (not regime-
     * split, per spec); §2–§6 live inside each {@link Regime}. For the pooled row, {@code barCount}
     * is the number of contributing cluster members and {@code from}/{@code to} are {@code null};
     * its {@code realizedVol} is the cross-sectional mean of members' per-window σ.
     *
     * <p>{@code from}/{@code to} are the actual first/last available data dates; {@code yearsAvailable}
     * is that span in years and {@code shortHistory} is {@code true} when the first bar is more recent
     * than the requested full-history cutoff — i.e. "full history" is really less than {@code
     * requestedYears} for this name (a recent listing/spin-off), which the UI surfaces rather than
     * silently truncating.
     */
    public record SymbolReport(
            String symbol,
            boolean missingData,
            int barCount,
            String from,
            String to,
            double yearsAvailable,
            boolean shortHistory,
            int requestedYears,
            List<RealizedVol.VolStat> realizedVol,
            Regime full,
            Regime trailing6m) {

        static SymbolReport missing(String symbol, int requestedYears) {
            return new SymbolReport(symbol, true, 0, null, null, 0, false, requestedYears,
                    List.of(), null, null);
        }
    }

    /**
     * §2–§6 over one time regime. {@code cutoff} is the window start date (the first eligible entry
     * date; {@code null} for an unbounded window). {@code forward} carries the §2 baseline, §3
     * conditionals and §4 continuation; {@code flushes} is §5 (one per flush definition);
     * {@code barriers} is §6, each entry pairing the flush-triggered simulation with its all-days
     * placebo and matched-random bootstrap controls.
     */
    public record Regime(
            String label,
            String cutoff,
            ForwardReturns.Result forward,
            List<Flushes.FlushProfile> flushes,
            List<BarrierComparison> barriers) {
    }

    /**
     * §6 for one time stop, with its controls side by side: {@code flush} is the flush-triggered
     * simulation, {@code placebo} the all-days control (entry at every fully-evaluable close), and
     * {@code bootstrap} the matched-random distribution (same entry count, random dates). Reading
     * {@code flush} against {@code placebo}/{@code bootstrap} answers "does the flush trigger add
     * anything beyond the regime drift?".
     */
    public record BarrierComparison(
            int timeStop,
            Flushes.BarrierResult flush,
            Flushes.BarrierResult placebo,
            Placebo.Bootstrap bootstrap) {
    }
}
