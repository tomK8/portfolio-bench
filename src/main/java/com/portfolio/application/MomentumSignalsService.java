package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.domain.momentum.CrashDiagnostics;
import com.portfolio.domain.momentum.Momentum;
import com.portfolio.domain.momentum.MomentumConfig;
import com.portfolio.domain.momentum.MomentumSeries;
import com.portfolio.domain.momentum.MomentumStats;
import com.portfolio.domain.momentum.Ranker;
import com.portfolio.domain.momentum.RegimeSplit;
import com.portfolio.domain.momentum.RotationBacktester;
import com.portfolio.domain.momentum.RotationBootstrap;
import com.portfolio.domain.momentum.SignalRankers;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The five-variant signal-speed / overlay comparison on the asset-class universe (the Momentum
 * signals tab). Same machinery as {@link MomentumAnalysisService} — one {@link MomentumSeries},
 * one common analysis start, one fit/holdout boundary, everything through the single
 * {@link RotationBacktester} path — but five signals run side by side at each N and the verdict
 * is decided by a pre-registered adoption rule, not by inspection.
 *
 * <p><b>What is shared and what differs.</b> Eligibility is identical across variants (full
 * {@code lookbackSessions} history — see {@link SignalRankers}); the random-selection bootstrap is
 * drawn <em>once per N</em> and every variant is ranked inside the same distribution (the control
 * is selection-agnostic: ranking orders the eligible set, it never changes membership). V4/V5's
 * overlays are not applied inside bootstrap draws — the bootstrap prices selection skill, and one
 * shared yardstick keeps the five variants comparable.
 *
 * <p><b>Contamination.</b> The variants were designed after inspecting the 2025–26 holdout of a
 * prior run, so the holdout is partially spent. The post-hoc check recomputes each challenger's
 * holdout comparison excluding calendar-2026 months (a contiguous tail slice, so the same
 * accounting path serves it); a challenger that only clears the holdout leg with 2026 included is
 * flagged POST-HOC in its verdict line, whatever the adoption rule says.
 */
public class MomentumSignalsService {

    private static final Logger log = LoggerFactory.getLogger(MomentumSignalsService.class);

    /** Fetch set (universe + benchmarks + regime) the nightly price job unions in. */
    public static final String SIGNALS_SYMBOLS_KEY = "momentum_signals_symbols";

    /** The validated asset-class run's universe, pinned. REIT (ALPS Active REIT ETF) lists 2021. */
    public static final List<String> SIGNALS_UNIVERSE = List.of(
            "XLB", "XLC", "XLE", "XLF", "XLI", "XLK", "XLP", "XLRE", "XLU", "XLV", "XLY",
            "EFA", "EEM", "TLT", "IEF", "GLD", "REIT");

    /** V5's trailing stop: sell at the close that sits ≥ this far below the peak since entry. */
    public static final double TRAILING_STOP = 0.15;
    /** V2's scoring window (sessions); eligibility still requires the full 12-1 history. */
    public static final int V2_LOOKBACK = 126;
    /** V3's short/medium component windows; the long component is the config's lookback. */
    private static final int[] V3_SHORT_MEDIUM = {63, 126};
    /** A stop counts as a whipsaw when the name re-enters within this many rebalances. */
    private static final int WHIPSAW_REBALANCES = 2;
    /** First contaminated date: the variants were designed after seeing 2026's months. */
    private static final LocalDate CONTAMINATED_FROM = LocalDate.of(2026, 1, 1);
    /** Sharpe-difference tolerance used by the "within noise" / "Sharpe-neutral" predictions. */
    private static final double SHARPE_NOISE = 0.10;

    static final String CONTEXT_HEADER =
            "The incumbent 12-1 signal validated at the 100th bootstrap percentile on this "
            + "universe but held GLD through a roughly 20 percent drawdown from its February 2026 "
            + "peak. This run tests whether faster or overlaid signals improve risk-adjusted "
            + "results across the full history, under a pre-registered adoption rule. It exists "
            + "to answer that question in general, not to make the 2026 gold episode look better.";

    static final List<String> PRE_REGISTRATION = List.of(
            "Adoption rule, fixed before running: a challenger replaces the incumbent only if it "
            + "beats 12-1 on BOTH Sharpe AND Calmar, net of its own costs, in BOTH the fit window "
            + "AND the holdout, at the same N. Otherwise the incumbent stands. Episode behaviour "
            + "(including gold 2026) is reported for understanding but has zero weight in the "
            + "adoption verdict.",
            "Contamination disclosure: these variants were designed after inspecting the 2025-26 "
            + "holdout months in a prior run. The holdout is therefore partially spent. Any "
            + "challenger whose advantage is concentrated in the 2026 months must be treated as "
            + "post-hoc and flagged as such in the verdict line, whatever the adoption rule says.",
            "Predicted outcomes, recorded before running so calibration can be checked afterwards: "
            + "V2 lands within noise of V1. V3 edges V1 on risk-adjusted metrics. V4 roughly "
            + "matches V1 here, with slightly better MaxDD via 2008 and 2022. V5 cuts MaxDD a "
            + "little and CAGR a little, roughly Sharpe-neutral, with the highest turnover. No "
            + "variant turns 2026 YTD from mid-single-digit positive into anything dramatic.");

    static final List<String> PLAIN_ENGLISH = List.of(
            "V1 is the current rule: rank the asset-class ETFs by their return over the last 12 "
            + "months excluding the most recent month, and hold the top N equal-weighted.",
            "V2 is the same rule with a 6-month window — a faster clock, nothing else changes.",
            "V3 blends the 3-, 6- and 12-month rankings into one composite score.",
            "V4 keeps V1's ranking but refuses to buy a name whose own 12-month return did not "
            + "beat cash; that slot waits in cash until the next month-end.",
            "V5 keeps V1's ranking but sells any holding that closes 15% below its highest close "
            + "since purchase, parking that slot in cash until the next month-end.",
            "A challenger replaces V1 only if it beats it on both Sharpe and Calmar, in both the "
            + "fit window and the holdout, at the same N — a rule fixed before the run.",
            "These variants were designed after seeing how 2025-26 played out, so the holdout is "
            + "partially spent; a challenger whose edge lives in the 2026 months is flagged "
            + "post-hoc.",
            "The episode panel shows what each variant held and returned through five famous "
            + "drawdowns, including gold's 2026 slide.",
            "It is shown for understanding only and carries zero weight in the verdict, because "
            + "adopting rules to fix one remembered episode is exactly how overfitting happens.",
            "The five predictions were recorded before running; their pass/fail marks measure how "
            + "well we anticipate this system, not whether the run \"worked\".");

    private record Episode(String label, LocalDate from, LocalDate to) {
    }

    private static final List<Episode> EPISODES = List.of(
            new Episode("2008-09 → 2008-11 · GFC autumn",
                    LocalDate.of(2008, 9, 1), LocalDate.of(2008, 11, 30)),
            new Episode("2011-09 → 2011-10 · euro-crisis correction",
                    LocalDate.of(2011, 9, 1), LocalDate.of(2011, 10, 31)),
            new Episode("2020-02 → 2020-04 · COVID crash",
                    LocalDate.of(2020, 2, 1), LocalDate.of(2020, 4, 30)),
            new Episode("2022-01 → 2022-10 · 2022 bear",
                    LocalDate.of(2022, 1, 1), LocalDate.of(2022, 10, 31)),
            new Episode("2026-02 → 2026-06 · gold drawdown (contaminated window)",
                    LocalDate.of(2026, 2, 1), LocalDate.of(2026, 6, 30)));

    private static final LocalDate EPOCH = LocalDate.of(1970, 1, 1);

    private final PriceHistoryRepository priceRepo;
    private final YahooTickerMap tickerMap;
    private final KeyValueStore kv;
    private final PriceFetchJob priceFetchJob;

    public MomentumSignalsService(PriceHistoryRepository priceRepo, YahooTickerMap tickerMap,
                                  KeyValueStore kv, PriceFetchJob priceFetchJob) {
        this.priceRepo = priceRepo;
        this.tickerMap = tickerMap;
        this.kv = kv;
        this.priceFetchJob = priceFetchJob;
        if (kv.getStringSet(SIGNALS_SYMBOLS_KEY).isEmpty()) {
            syncFetchUniverse(baseConfigBuilder().build());
        }
    }

    /** Pinned universe; every other knob falls back to the incumbent defaults. */
    public MomentumConfig.Builder baseConfigBuilder() {
        return MomentumConfig.builder().universe(SIGNALS_UNIVERSE);
    }

    // ---- the five variants ----------------------------------------------------

    /** Everything one variant needs to run: its ranker, its selector shape, its stop overlay. */
    private record Sig(String key, String label, String description,
                       Ranker ranker, boolean gated, Double stop) {
    }

    private static List<Sig> signals(MomentumConfig cfg) {
        Ranker incumbent = Ranker.incumbent(cfg);
        int[] v3Lookbacks = {V3_SHORT_MEDIUM[0], V3_SHORT_MEDIUM[1], cfg.lookbackSessions()};
        return List.of(
                new Sig("V1", "V1 · 12-1 (incumbent)",
                        "12-1 total return, skip the most recent month — the control; reproduces "
                        + "the incumbent Momentum-tab code path exactly.",
                        incumbent, false, null),
                new Sig("V2", "V2 · 6-1",
                        "6-1 total return — the only change is the shorter scoring window; "
                        + "eligibility still requires the full 12-1 history.",
                        SignalRankers.lookbackSkip(V2_LOOKBACK, cfg.skipSessions(),
                                cfg.lookbackSessions()), false, null),
                new Sig("V3", "V3 · z-blend 3/6/12",
                        "Equal-weight average of the cross-sectional z-scores of the 3-1, 6-1 and "
                        + "12-1 returns at each rebalance.",
                        SignalRankers.compositeZ(v3Lookbacks, cfg.skipSessions()), false, null),
                new Sig("V4", "V4 · 12-1 + rf gate",
                        "V1's ranking, but a top-N name is only bought if its own 12-1 return "
                        + "beats the rf accrual over the same window; a failing slot idles in cash.",
                        incumbent, true, null),
                new Sig("V5", "V5 · 12-1 + 15% stop",
                        "V1's ranking plus a per-holding trailing stop: sell at the close that "
                        + "sits 15% below the highest close since entry; slot in cash until the "
                        + "next rebalance, free re-entry at any later one.",
                        incumbent, false, TRAILING_STOP));
    }

    /** V4's hurdle: what cash would have accrued over the momentum window (compounded daily rf). */
    static double gateHurdle(MomentumConfig cfg) {
        return Math.pow(1 + cfg.rfDaily(), cfg.lookbackSessions() - cfg.skipSessions()) - 1;
    }

    // ---- report ----------------------------------------------------------------

    public MomentumSignalsReport report(MomentumConfig cfg, Integer episodeNParam) {
        LocalDate today = LocalDate.now();
        List<String> warnings = new ArrayList<>();

        LinkedHashMap<String, List<PriceBar>> universeBars = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String sym : cfg.universe()) {
            List<PriceBar> bars = load(sym, today);
            universeBars.put(sym, bars);
            if (bars.isEmpty()) missing.add(sym);
        }
        LinkedHashMap<String, List<PriceBar>> benchBars = new LinkedHashMap<>();
        Set<String> benchNames = new LinkedHashSet<>(cfg.benchmarkSymbols());
        benchNames.add(cfg.regimeSymbol());
        for (String sym : benchNames) benchBars.put(sym, load(sym, today));

        MomentumSeries s = MomentumSeries.of(universeBars, benchBars, cfg.maxFfillSessions());
        if (s == null) {
            warnings.add("No price history for any universe symbol — run Backfill first.");
            return empty(cfg, missing, warnings);
        }

        int startIdx = RotationBacktester.analysisStart(s, cfg);
        if (startIdx < 0 || startIdx >= s.size() - 1) {
            warnings.add("Not enough history: no month-end yet has "
                    + cfg.minEligibleFactor() + "×N eligible names. Run Backfill.");
            return empty(cfg, missing, warnings);
        }

        int lastIdx = s.size() - 1;
        LocalDate cutoff = today.minusMonths(cfg.holdoutMonths());
        int fitEndIdx = lastIdx;
        for (int i = lastIdx; i >= startIdx; i--) {
            if (!s.dateAt(i).isAfter(cutoff)) {
                fitEndIdx = i;
                break;
            }
        }
        boolean holdout = cfg.holdoutMonths() > 0 && fitEndIdx < lastIdx
                && s.dateAt(fitEndIdx).isBefore(cutoff.plusMonths(1));
        if (!holdout) fitEndIdx = lastIdx;
        int fitToK = fitEndIdx - startIdx;
        int lastK = lastIdx - startIdx;

        // Last bar strictly before the contaminated 2026 months, for the post-hoc check.
        int ex2026EndIdx = -1;
        for (int i = lastIdx; i >= startIdx; i--) {
            if (s.dateAt(i).isBefore(CONTAMINATED_FROM)) {
                ex2026EndIdx = i;
                break;
            }
        }

        RotationBacktester.Result baseRes = RotationBacktester.run(
                s, cfg, RotationBacktester.allEligible(), null, startIdx, lastIdx);
        Map<String, Double> baseFitMonths = MomentumStats.monthlyReturns(s, baseRes, 0, fitToK);
        Map<String, Double> baseHoldoutMonths = holdout
                ? MomentumStats.monthlyReturns(s, baseRes, fitToK + 1, lastK) : Map.of();
        Map<String, String> regimeStates = RegimeSplit.statesByHeldMonth(s, cfg);
        Map<String, Double> indexFitMonths = MomentumAnalysisService.indexMonths(
                s, cfg, startIdx, 0, fitToK);

        List<Sig> sigs = signals(cfg);
        double hurdle = gateHurdle(cfg);

        // Run everything first (per N, per variant), then verdict against V1 within each N.
        List<MomentumSignalsReport.NGroup> groups = new ArrayList<>();
        Map<Integer, Map<String, VariantRun>> runsByN = new LinkedHashMap<>();
        for (int n : cfg.topNs()) {
            RotationBootstrap.Samples samples = RotationBootstrap.samples(s, cfg, n, startIdx, fitEndIdx);
            Map<String, VariantRun> runs = new LinkedHashMap<>();
            for (Sig sig : sigs) {
                runs.put(sig.key(), runVariant(s, cfg, sig, n, hurdle, startIdx, fitEndIdx,
                        fitToK, lastK, ex2026EndIdx, holdout, baseFitMonths, baseHoldoutMonths));
            }
            runsByN.put(n, runs);
            VariantRun v1 = runs.get("V1");
            List<MomentumSignalsReport.VariantRow> rows = new ArrayList<>();
            for (Sig sig : sigs) {
                VariantRun run = runs.get(sig.key());
                Verdict verdict = "V1".equals(sig.key()) ? null : verdict(run, v1, holdout);
                rows.add(buildRow(s, cfg, sig, n, run, verdict, samples, regimeStates,
                        baseFitMonths, indexFitMonths, fitEndIdx, holdout));
            }
            groups.add(new MomentumSignalsReport.NGroup(n, rows));
        }

        int episodeN = episodeN(cfg, episodeNParam);
        List<MomentumSignalsReport.EpisodeRow> episodes =
                episodes(s, sigs, runsByN.get(episodeN), hurdle, episodeN);

        List<MomentumSignalsReport.Prediction> predictions = predictions(cfg, groups);

        List<MomentumReport.BenchmarkRow> benchmarks = new ArrayList<>();
        for (String sym : cfg.benchmarkSymbols()) {
            int bi = s.indexOf(sym);
            if (bi < 0) continue;
            RotationBacktester.Result bh = RotationBacktester.buyAndHold(s, bi, startIdx, lastIdx);
            MomentumStats fit = MomentumStats.of(s, bh, 0, fitToK, cfg.rfDaily(), baseFitMonths);
            MomentumStats hold = holdout
                    ? MomentumStats.of(s, bh, fitToK + 1, lastK, cfg.rfDaily(), baseHoldoutMonths) : null;
            benchmarks.add(new MomentumReport.BenchmarkRow(sym, fit, hold));
        }

        String startReason = "First completed month-end with ≥ " + cfg.minEligibleFactor()
                + "×" + maxN(cfg) + " = " + (cfg.minEligibleFactor() * maxN(cfg))
                + " rank-eligible names (expanding universe — names enter as history accrues).";

        return new MomentumSignalsReport(cfg, CONTEXT_HEADER, PRE_REGISTRATION, PLAIN_ENGLISH,
                MomentumAnalysisService.SURVIVORSHIP_CAVEAT, cfg.randomSeed(),
                s.dateAt(startIdx).toString(), startReason,
                s.dateAt(startIdx).toString(), s.dateAt(fitEndIdx).toString(),
                holdout ? s.dateAt(fitEndIdx + 1).toString() : null,
                holdout ? s.dateAt(lastIdx).toString() : null, holdout,
                MomentumAnalysisService.spans(s, cfg), missing,
                s.gaps().stream().map(g -> new MomentumReport.GapRow(
                        g.symbol(), g.from().toString(), g.to().toString(), g.sessions())).toList(),
                groups, predictions, episodeN, episodes, benchmarks, warnings);
    }

    // ---- one variant at one N --------------------------------------------------

    /** A variant's raw run + every stats slice the verdict and the row need. */
    private record VariantRun(RotationBacktester.Result res, MomentumStats fit,
                              MomentumStats holdoutStats, MomentumStats holdoutEx2026,
                              double costDragCagrPct, Map<String, Double> fullMonths) {
    }

    private VariantRun runVariant(MomentumSeries s, MomentumConfig cfg, Sig sig, int n,
                                  double hurdle, int startIdx, int fitEndIdx, int fitToK,
                                  int lastK, int ex2026EndIdx, boolean holdout,
                                  Map<String, Double> baseFitMonths,
                                  Map<String, Double> baseHoldoutMonths) {
        RotationBacktester.Selector selector = sig.gated()
                ? RotationBacktester.gatedTopN(n, hurdle) : RotationBacktester.topN(n);

        RotationBacktester.Result res = RotationBacktester.run(
                s, cfg, sig.ranker(), selector, n, startIdx, startIdx + lastK, sig.stop());
        MomentumStats fit = MomentumStats.of(s, res, 0, fitToK, cfg.rfDaily(), baseFitMonths);

        RotationBacktester.Result free = RotationBacktester.run(
                s, cfg.withCostPerSideBps(0), sig.ranker(), selector, n,
                startIdx, startIdx + lastK, sig.stop());
        double costDrag = (MomentumStats.of(s, free, 0, fitToK, cfg.rfDaily(), null).cagr()
                - fit.cagr()) * 100;

        MomentumStats holdoutStats = holdout
                ? MomentumStats.of(s, res, fitToK + 1, lastK, cfg.rfDaily(), baseHoldoutMonths) : null;
        // The ex-2026 slice is the holdout's uncontaminated head: [fitEnd+1, last 2025 bar].
        MomentumStats ex2026 = null;
        if (holdout && ex2026EndIdx > fitEndIdx) {
            ex2026 = MomentumStats.of(s, res, fitToK + 1, ex2026EndIdx - startIdx, cfg.rfDaily(), null);
        }
        Map<String, Double> fullMonths = MomentumStats.monthlyReturns(s, res, 0, lastK);
        return new VariantRun(res, fit, holdoutStats, ex2026, costDrag, fullMonths);
    }

    // ---- the pre-registered verdict ---------------------------------------------

    private record Verdict(boolean adopt, boolean postHoc, String text) {
    }

    /**
     * The adoption rule, verbatim: beat V1 on BOTH Sharpe AND Calmar, in BOTH fit AND holdout, at
     * the same N. A NaN on either side fails that comparison (conservative: the incumbent
     * stands). Post-hoc flag: the challenger clears the holdout leg with the 2026 months included
     * but not without them.
     */
    private Verdict verdict(VariantRun c, VariantRun v1, boolean holdout) {
        boolean fitSharpe = beats(c.fit().sharpe(), v1.fit().sharpe());
        boolean fitCalmar = beats(c.fit().calmar(), v1.fit().calmar());
        boolean hoSharpe = holdout && c.holdoutStats() != null
                && beats(c.holdoutStats().sharpe(), v1.holdoutStats().sharpe());
        boolean hoCalmar = holdout && c.holdoutStats() != null
                && beats(c.holdoutStats().calmar(), v1.holdoutStats().calmar());
        boolean adopt = fitSharpe && fitCalmar && hoSharpe && hoCalmar;

        boolean beatsHoldoutFull = hoSharpe && hoCalmar;
        boolean beatsHoldoutEx2026 = c.holdoutEx2026() != null && v1.holdoutEx2026() != null
                && beats(c.holdoutEx2026().sharpe(), v1.holdoutEx2026().sharpe())
                && beats(c.holdoutEx2026().calmar(), v1.holdoutEx2026().calmar());
        boolean postHoc = beatsHoldoutFull && !beatsHoldoutEx2026;

        StringBuilder t = new StringBuilder(adopt ? "ADOPT" : "REJECT");
        t.append(" — fit: Sharpe ").append(cmp(c.fit().sharpe(), v1.fit().sharpe(), fitSharpe))
                .append(", Calmar ").append(cmp(c.fit().calmar(), v1.fit().calmar(), fitCalmar));
        if (holdout && c.holdoutStats() != null) {
            t.append(" · holdout: Sharpe ")
                    .append(cmp(c.holdoutStats().sharpe(), v1.holdoutStats().sharpe(), hoSharpe))
                    .append(", Calmar ")
                    .append(cmp(c.holdoutStats().calmar(), v1.holdoutStats().calmar(), hoCalmar));
        } else {
            t.append(" · holdout: unavailable ✗");
        }
        if (postHoc) {
            t.append(" · POST-HOC: holdout edge only exists with the contaminated 2026 months "
                    + "included");
        }
        return new Verdict(adopt, postHoc, t.toString());
    }

    private static boolean beats(double challenger, double incumbent) {
        return !Double.isNaN(challenger) && !Double.isNaN(incumbent) && challenger > incumbent;
    }

    private static String cmp(double c, double v1, boolean pass) {
        return String.format("%.2f vs %.2f %s", c, v1, pass ? "✓" : "✗");
    }

    // ---- row assembly ------------------------------------------------------------

    private MomentumSignalsReport.VariantRow buildRow(
            MomentumSeries s, MomentumConfig cfg, Sig sig, int n, VariantRun run, Verdict verdict,
            RotationBootstrap.Samples samples, Map<String, String> regimeStates,
            Map<String, Double> baseFitMonths, Map<String, Double> indexFitMonths,
            int fitEndIdx, boolean holdout) {

        LocalDate fitEnd = s.dateAt(fitEndIdx);
        RotationBacktester.Result res = run.res();

        double fitTurnover = turnoverWithStops(res, fitEnd, true);
        double holdoutTurnover = holdout ? turnoverWithStops(res, fitEnd, false) : 0;

        RegimeSplit.Result regime = RegimeSplit.compute(
                run.fit().months(), regimeStates, baseFitMonths, cfg.rfAnnualPct());
        CrashDiagnostics.Result crash = CrashDiagnostics.compute(s, cfg, run.fit().months(), indexFitMonths);
        RotationBootstrap bootstrap = RotationBootstrap.of(samples, run.fit());

        Double ytd2026 = ytd2026(run.fullMonths());

        Map<String, Integer> gateBinds = null;
        Integer gateBindsTotal = null;
        if (sig.gated()) {
            gateBinds = new LinkedHashMap<>();
            int total = 0;
            for (RotationBacktester.RebalanceEvent e : res.events()) {
                int binds = Math.min(n, e.eligible()) - e.held().size();
                if (binds <= 0) continue;
                gateBinds.merge(String.valueOf(e.date().getYear()), binds, Integer::sum);
                total += binds;
            }
            gateBindsTotal = total;
        }

        Map<String, Integer> stopsByYear = null;
        List<MomentumSignalsReport.StopRow> stopRows = List.of();
        List<MomentumSignalsReport.WhipsawRow> whipsaws = List.of();
        if (sig.stop() != null) {
            stopsByYear = new LinkedHashMap<>();
            List<MomentumSignalsReport.StopRow> sr = new ArrayList<>();
            for (RotationBacktester.StopEvent e : res.stops()) {
                stopsByYear.merge(String.valueOf(e.date().getYear()), 1, Integer::sum);
                sr.add(new MomentumSignalsReport.StopRow(e.date().toString(),
                        e.date().isAfter(fitEnd) ? "holdout" : "fit",
                        e.symbol(), e.weightSold(), e.cost(), e.drawdownPct()));
            }
            stopRows = sr;
            whipsaws = whipsaws(s, cfg, res, n);
        }

        return new MomentumSignalsReport.VariantRow(sig.key(), sig.label(), sig.description(),
                run.fit(), run.holdoutStats(), fitTurnover, holdoutTurnover, run.costDragCagrPct(),
                bootstrap, regime, crash, ytd2026,
                verdict != null && verdict.adopt(), verdict != null && verdict.postHoc(),
                verdict == null ? null : verdict.text(),
                gateBinds, gateBindsTotal, stopsByYear, stopRows, whipsaws,
                MomentumAnalysisService.logRows(res, fitEnd, holdout));
    }

    /**
     * Mean per-rebalance turnover including V5's intra-month stop sells (each contributes
     * {@code weightSold/2}, the same one-sided convention as rebalance turnover) — the extra
     * trading is exactly what the overlay is being charged for.
     */
    private static double turnoverWithStops(RotationBacktester.Result r, LocalDate fitEnd, boolean fit) {
        double sum = 0;
        int n = 0;
        for (RotationBacktester.RebalanceEvent e : r.events()) {
            if (e.date().isAfter(fitEnd) == fit) continue;
            sum += e.turnover();
            n++;
        }
        for (RotationBacktester.StopEvent e : r.stops()) {
            if (e.date().isAfter(fitEnd) == fit) continue;
            sum += e.weightSold() / 2;
        }
        return n == 0 ? 0 : sum / n;
    }

    /** Stops whose name re-entered within {@link #WHIPSAW_REBALANCES} of the next rebalances. */
    private static List<MomentumSignalsReport.WhipsawRow> whipsaws(
            MomentumSeries s, MomentumConfig cfg, RotationBacktester.Result res, int n) {
        Map<LocalDate, Integer> dateIdx = new HashMap<>();
        for (int i = 0; i < s.size(); i++) dateIdx.put(s.dateAt(i), i);
        List<MomentumSignalsReport.WhipsawRow> out = new ArrayList<>();
        List<RotationBacktester.RebalanceEvent> events = res.events();
        for (RotationBacktester.StopEvent stop : res.stops()) {
            int seen = 0;
            for (RotationBacktester.RebalanceEvent e : events) {
                if (!e.date().isAfter(stop.date())) continue;
                seen++;
                if (seen > WHIPSAW_REBALANCES) break;
                if (!e.in().contains(stop.symbol())) continue;
                int si = s.indexOf(stop.symbol());
                Integer ri = dateIdx.get(e.date());
                double gap = Double.NaN;
                if (si >= 0 && ri != null) {
                    double a0 = s.adj(si, stop.barIdx()), a1 = s.adj(si, ri);
                    if (!Double.isNaN(a0) && !Double.isNaN(a1) && a0 > 0) gap = a1 / a0 - 1;
                }
                double txnBps = (stop.cost() + cfg.costPerSide() / n) * 10_000;
                out.add(new MomentumSignalsReport.WhipsawRow(stop.date().toString(), stop.symbol(),
                        stop.drawdownPct(), e.date().toString(), txnBps, gap));
                break;
            }
        }
        return out;
    }

    private static Double ytd2026(Map<String, Double> months) {
        double f = 1;
        boolean any = false;
        for (Map.Entry<String, Double> e : months.entrySet()) {
            if (!e.getKey().startsWith("2026-")) continue;
            f *= 1 + e.getValue();
            any = true;
        }
        return any ? f - 1 : null;
    }

    // ---- predictions ---------------------------------------------------------------

    private List<MomentumSignalsReport.Prediction> predictions(
            MomentumConfig cfg, List<MomentumSignalsReport.NGroup> groups) {
        List<MomentumSignalsReport.Prediction> out = new ArrayList<>();

        out.add(prediction("V2 lands within noise of V1.", groups, (v, g) -> {
            double d = v.get("V2").fit().sharpe() - v.get("V1").fit().sharpe();
            boolean pass = Math.abs(d) <= SHARPE_NOISE;
            return mark(g, pass, String.format("fit ΔSharpe %+.2f (noise band ±%.2f)", d, SHARPE_NOISE));
        }));
        out.add(prediction("V3 edges V1 on risk-adjusted metrics.", groups, (v, g) -> {
            MomentumStats c = v.get("V3").fit(), i = v.get("V1").fit();
            boolean pass = beats(c.sharpe(), i.sharpe()) && beats(c.calmar(), i.calmar());
            return mark(g, pass, String.format("Sharpe %.2f vs %.2f · Calmar %.2f vs %.2f",
                    c.sharpe(), i.sharpe(), c.calmar(), i.calmar()));
        }));
        out.add(prediction("V4 roughly matches V1 here, with slightly better MaxDD via 2008 and 2022.",
                groups, (v, g) -> {
            MomentumStats c = v.get("V4").fit(), i = v.get("V1").fit();
            boolean pass = Math.abs(c.cagr() - i.cagr()) <= 0.01 && c.maxDrawdown() > i.maxDrawdown();
            return mark(g, pass, String.format("ΔCAGR %+.1fpp · MaxDD %.1f%% vs %.1f%%",
                    (c.cagr() - i.cagr()) * 100, c.maxDrawdown() * 100, i.maxDrawdown() * 100));
        }));
        out.add(prediction("V5 cuts MaxDD a little and CAGR a little, roughly Sharpe-neutral, "
                + "with the highest turnover.", groups, (v, g) -> {
            MomentumSignalsReport.VariantRow c = v.get("V5"), i = v.get("V1");
            boolean turnoverMax = true;
            for (MomentumSignalsReport.VariantRow o : v.values()) {
                if (o != c && o.fitTurnover() >= c.fitTurnover()) turnoverMax = false;
            }
            boolean pass = c.fit().maxDrawdown() > i.fit().maxDrawdown()
                    && c.fit().cagr() < i.fit().cagr()
                    && Math.abs(c.fit().sharpe() - i.fit().sharpe()) <= SHARPE_NOISE
                    && turnoverMax;
            return mark(g, pass, String.format(
                    "MaxDD %.1f%% vs %.1f%% · ΔCAGR %+.1fpp · ΔSharpe %+.2f · turnover %s",
                    c.fit().maxDrawdown() * 100, i.fit().maxDrawdown() * 100,
                    (c.fit().cagr() - i.fit().cagr()) * 100,
                    c.fit().sharpe() - i.fit().sharpe(),
                    turnoverMax ? "highest" : "not highest"));
        }));
        out.add(prediction("No variant turns 2026 YTD from mid-single-digit positive into "
                + "anything dramatic.", groups, (v, g) -> {
            StringBuilder d = new StringBuilder();
            boolean pass = true;
            for (MomentumSignalsReport.VariantRow row : v.values()) {
                Double y = row.ytd2026();
                if (y == null) {
                    pass = false;
                    d.append(row.key()).append(" n/a ");
                    continue;
                }
                if (y <= 0 || y >= 0.10) pass = false;
                d.append(String.format("%s %+.1f%% ", row.key(), y * 100));
            }
            return mark(g, pass, d.toString().trim() + " (pass = all in 0..10%)");
        }));
        return out;
    }

    private interface MarkFn {
        MomentumSignalsReport.Prediction.Mark apply(
                Map<String, MomentumSignalsReport.VariantRow> variants,
                MomentumSignalsReport.NGroup g);
    }

    private static MomentumSignalsReport.Prediction prediction(
            String text, List<MomentumSignalsReport.NGroup> groups, MarkFn fn) {
        List<MomentumSignalsReport.Prediction.Mark> marks = new ArrayList<>();
        for (MomentumSignalsReport.NGroup g : groups) {
            Map<String, MomentumSignalsReport.VariantRow> byKey = new LinkedHashMap<>();
            for (MomentumSignalsReport.VariantRow r : g.variants()) byKey.put(r.key(), r);
            marks.add(fn.apply(byKey, g));
        }
        return new MomentumSignalsReport.Prediction(text, marks);
    }

    private static MomentumSignalsReport.Prediction.Mark mark(
            MomentumSignalsReport.NGroup g, boolean pass, String detail) {
        return new MomentumSignalsReport.Prediction.Mark(g.n(), pass, detail);
    }

    // ---- episode panel ---------------------------------------------------------------

    private int episodeN(MomentumConfig cfg, Integer param) {
        int[] ns = cfg.topNs();
        if (param != null) {
            for (int n : ns) if (n == param) return param;
        }
        return ns[ns.length / 2];   // middle N (4 for {3,4,5})
    }

    private List<MomentumSignalsReport.EpisodeRow> episodes(
            MomentumSeries s, List<Sig> sigs, Map<String, VariantRun> runs,
            double hurdle, int n) {
        Map<LocalDate, Integer> dateIdx = new HashMap<>();
        for (int i = 0; i < s.size(); i++) dateIdx.put(s.dateAt(i), i);

        List<MomentumSignalsReport.EpisodeRow> out = new ArrayList<>();
        for (Episode ep : EPISODES) {
            for (Sig sig : sigs) {
                VariantRun run = runs.get(sig.key());
                out.add(episodeRow(s, sig, run, ep, dateIdx, hurdle, n));
            }
        }
        return out;
    }

    private MomentumSignalsReport.EpisodeRow episodeRow(
            MomentumSeries s, Sig sig, VariantRun run, Episode ep,
            Map<LocalDate, Integer> dateIdx, double hurdle, int n) {

        RotationBacktester.Result res = run.res();
        LocalDate analysisStart = s.dateAt(res.startIdx());
        if (!ep.to().isAfter(analysisStart)) {
            return new MomentumSignalsReport.EpisodeRow(ep.label(), ep.from().toString(),
                    ep.to().toString(), sig.key(), null, List.of(),
                    List.of("window predates analysis start (" + analysisStart + ")"));
        }

        // Window return: compound the daily slice whose dates fall inside the episode.
        double f = 1;
        boolean any = false;
        for (int k = 0; k < res.dailyRet().length; k++) {
            LocalDate d = s.dateAt(res.startIdx() + k);
            if (d.isBefore(ep.from()) || d.isAfter(ep.to())) continue;
            f *= 1 + res.dailyRet()[k];
            any = true;
        }
        Double windowReturn = any ? f - 1 : null;

        // Positioning event (last rebalance before the window) + every in-window rebalance.
        RotationBacktester.RebalanceEvent positioning = null;
        List<RotationBacktester.RebalanceEvent> inWindow = new ArrayList<>();
        for (RotationBacktester.RebalanceEvent e : res.events()) {
            if (e.date().isBefore(ep.from())) positioning = e;
            else if (!e.date().isAfter(ep.to())) inWindow.add(e);
        }

        List<String> holdings = new ArrayList<>();
        if (positioning != null) holdings.add(holdingsLine(positioning));
        for (RotationBacktester.RebalanceEvent e : inWindow) holdings.add(holdingsLine(e));

        List<String> gld = new ArrayList<>();
        boolean heldIn = positioning != null
                && positioning.held().stream().anyMatch(r -> "GLD".equals(r.symbol()));
        gld.add(heldIn ? "held into window" : "not held entering window");
        List<RotationBacktester.RebalanceEvent> scanned = new ArrayList<>();
        if (positioning != null) scanned.add(positioning);
        scanned.addAll(inWindow);
        for (RotationBacktester.RebalanceEvent e : scanned) {
            if (e.out().contains("GLD")) gld.add("derated " + e.date());
            if (e.in().contains("GLD")) gld.add("entered " + e.date());
            if (sig.gated()) {
                Integer bar = dateIdx.get(e.date());
                if (bar != null) {
                    List<Momentum.Ranked> ranked = sig.ranker().rank(s, bar);
                    boolean topN = ranked.stream().limit(n)
                            .anyMatch(r -> "GLD".equals(r.symbol()));
                    boolean held = e.held().stream().anyMatch(r -> "GLD".equals(r.symbol()));
                    if (topN && !held) gld.add("gated " + e.date() + " (12-1 below rf hurdle "
                            + String.format("%.1f%%", hurdle * 100) + ")");
                }
            }
        }
        for (RotationBacktester.StopEvent st : res.stops()) {
            if (!"GLD".equals(st.symbol())) continue;
            if (st.date().isBefore(ep.from()) || st.date().isAfter(ep.to())) continue;
            gld.add(String.format("stopped %s (%.1f%% below peak)", st.date(),
                    -st.drawdownPct() * 100));
        }

        return new MomentumSignalsReport.EpisodeRow(ep.label(), ep.from().toString(),
                ep.to().toString(), sig.key(), windowReturn, holdings, gld);
    }

    private static String holdingsLine(RotationBacktester.RebalanceEvent e) {
        StringBuilder b = new StringBuilder(e.date().toString()).append(": ");
        if (e.held().isEmpty()) {
            b.append("(all cash)");
        } else {
            for (int i = 0; i < e.held().size(); i++) {
                if (i > 0) b.append(" ");
                b.append(e.held().get(i).symbol());
            }
        }
        if (e.cashWeight() > 1e-9) {
            b.append(String.format(" · cash %.0f%%", e.cashWeight() * 100));
        }
        return b.toString();
    }

    // ---- helpers / plumbing ------------------------------------------------------------

    private static int maxN(MomentumConfig cfg) {
        int m = 0;
        for (int n : cfg.topNs()) m = Math.max(m, n);
        return m;
    }

    private MomentumSignalsReport empty(MomentumConfig cfg, List<String> missing, List<String> warnings) {
        return new MomentumSignalsReport(cfg, CONTEXT_HEADER, PRE_REGISTRATION, PLAIN_ENGLISH,
                MomentumAnalysisService.SURVIVORSHIP_CAVEAT, cfg.randomSeed(),
                null, null, null, null, null, null, false,
                List.of(), missing, List.of(), List.of(), List.of(), 0, List.of(), List.of(), warnings);
    }

    private List<PriceBar> load(String symbol, LocalDate today) {
        return priceRepo.getPriceHistory(tickerMap.tickerFor(symbol), EPOCH, today);
    }

    private Set<String> configuredSymbols(MomentumConfig cfg) {
        Set<String> set = new LinkedHashSet<>(cfg.universe());
        set.addAll(cfg.benchmarkSymbols());
        set.add(cfg.regimeSymbol());
        return set;
    }

    private void syncFetchUniverse(MomentumConfig cfg) {
        Set<String> fetchSet = configuredSymbols(cfg);
        if (!fetchSet.equals(kv.getStringSet(SIGNALS_SYMBOLS_KEY))) {
            kv.putStringSet(SIGNALS_SYMBOLS_KEY, fetchSet);
        }
    }

    /**
     * Sync the fetch set and deep-fetch any configured symbol with missing or shallow coverage.
     * Written from the constructor seed and here only — never from {@code report()} — same
     * discipline as the other tabs. Intended to run off the request thread.
     */
    public List<String> ensureBackfilled(MomentumConfig cfg) {
        syncFetchUniverse(cfg);
        List<String> fetched = new ArrayList<>();
        if (priceFetchJob == null) return fetched;
        for (String symbol : configuredSymbols(cfg)) {
            LocalDate earliest = priceRepo.getEarliestPriceDate(tickerMap.tickerFor(symbol));
            if (earliest != null) continue;   // the cohort all lists post-1993; 30y standard is enough
            int rows = priceFetchJob.fetchSingle(symbol);
            log.info("Momentum-signals backfill: {} → {} rows", symbol, rows);
            if (rows > 0) fetched.add(symbol);
        }
        return fetched;
    }
}
