package com.portfolio.application;

import com.portfolio.domain.swing.AnnualBreakdown;
import com.portfolio.domain.swing.FlushEvents;
import com.portfolio.domain.swing.Flushes;
import com.portfolio.domain.swing.ForwardReturns;
import com.portfolio.domain.swing.MaSplit;
import com.portfolio.domain.swing.Placebo;
import com.portfolio.domain.swing.RealizedVol;
import com.portfolio.domain.swing.ReturnCorrelation;
import com.portfolio.domain.swing.SwingConfig;

import java.util.List;

/**
 * The full swing-strategy validation report — the JSON payload behind the Swing stats tab.
 * Assembles the pure-domain engine outputs into a per-symbol + pooled-cluster structure, each side
 * split into the two §9 regimes (full history vs trailing 6 months), plus the cross-cluster
 * correlation block and the §D earnings-hygiene coverage summary.
 *
 * @param config     the effective configuration used (echoed so the UI knows what it's looking at)
 * @param perSymbol  one report per watchlist symbol
 * @param pooled     the cluster pooled into a single report (symbol {@code "CLUSTER"})
 * @param correlation §7 correlation regime across the cluster
 * @param earningsHygiene §D coverage summary (which symbols could be verified, and the proxy caveat)
 */
public record SwingReport(
        SwingConfig config,
        List<SymbolReport> perSymbol,
        SymbolReport pooled,
        ReturnCorrelation.Result correlation,
        EarningsHygiene earningsHygiene) {

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
     * conditionals and §4 continuation. {@code families} runs the whole §5/§6/§A/§B/§E machinery for
     * two trigger families in parallel (absolute + σ-scaled, §C); {@code familyOverlapCount} is the
     * number of dates on which both families' §6-seed triggers fire (no combined table double-pools
     * those events).
     */
    public record Regime(
            String label,
            String cutoff,
            ForwardReturns.Result forward,
            List<FlushFamily> families,
            Integer familyOverlapCount) {
    }

    /**
     * One flush trigger family (§C) — either absolute (fixed −%) or σ-scaled (−k×σ×√N). Carries the
     * §5/§6 tables in two earnings variants ({@code variants}: "all" and "ex-earnings", §D), the §A
     * event log, the §B per-year breakdown, and the §E 200d-MA split. {@code earningsVerified} is
     * {@code false} when any pooled member lacks an earnings source — the ex-earnings variant is then
     * unreliable and the UI marks it.
     */
    public record FlushFamily(
            String label,
            String kind,
            boolean earningsVerified,
            List<EarningsVariant> variants,
            List<FlushEvents.FlushEventRow> eventLog,
            List<AnnualBreakdown.AnnualRow> annual,
            MaSplit.Result maSplit) {
    }

    /**
     * §5/§6 tables for one earnings variant. {@code name} is {@code "all"} (every event) or
     * {@code "ex-earnings"} (events within the ±window of an earnings date removed, and those dates
     * also removed from the placebo/bootstrap pool so the comparison stays like-for-like).
     */
    public record EarningsVariant(
            String name,
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

    /**
     * §D coverage summary. {@code source} names the proxy; {@code proxyNote} states its caveats
     * (filing-date proxy, US-filers-only). {@code coverage} lists each symbol's verifiability and how
     * many earnings dates were sourced — so the UI can mark unverified names rather than implying
     * their ex-earnings variants are clean.
     */
    public record EarningsHygiene(
            String source,
            String proxyNote,
            List<Coverage> coverage) {

        public record Coverage(String symbol, boolean verified, int dateCount, String from, String to) {
        }
    }
}
