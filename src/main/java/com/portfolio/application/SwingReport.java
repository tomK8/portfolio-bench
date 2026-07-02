package com.portfolio.application;

import com.portfolio.domain.swing.Flushes;
import com.portfolio.domain.swing.ForwardReturns;
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
     */
    public record SymbolReport(
            String symbol,
            boolean missingData,
            int barCount,
            String from,
            String to,
            List<RealizedVol.VolStat> realizedVol,
            Regime full,
            Regime trailing6m) {

        static SymbolReport missing(String symbol) {
            return new SymbolReport(symbol, true, 0, null, null, List.of(), null, null);
        }
    }

    /**
     * §2–§6 over one time regime. {@code forward} carries the §2 baseline, §3 conditionals and §4
     * continuation; {@code flushes} is §5 (one per flush definition); {@code barriers} is §6 (one
     * per time stop).
     */
    public record Regime(
            String label,
            ForwardReturns.Result forward,
            List<Flushes.FlushProfile> flushes,
            List<Flushes.BarrierResult> barriers) {
    }
}
