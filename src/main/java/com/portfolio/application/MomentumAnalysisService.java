package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.domain.momentum.CrashDiagnostics;
import com.portfolio.domain.momentum.MomentumConfig;
import com.portfolio.domain.momentum.MomentumSeries;
import com.portfolio.domain.momentum.MomentumStats;
import com.portfolio.domain.momentum.Momentum;
import com.portfolio.domain.momentum.RegimeSplit;
import com.portfolio.domain.momentum.RotationBacktester;
import com.portfolio.domain.momentum.RotationBootstrap;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates the cross-sectional momentum backtest report (Momentum tab). Loads the universe +
 * benchmark daily bars from {@code price_history}, builds the aligned {@link MomentumSeries}, and
 * runs — over one common expanding-window start and a fit/holdout split — the top-N variants, the
 * bottom-N reversal check, the equal-weight baseline, the QQQ/SPY benchmarks, the matched random
 * bootstrap, and the regime/crash diagnostics. Everything routes through the single
 * {@link RotationBacktester} accounting path.
 *
 * <p>The universe is mirrored to {@link KeyValueStore} under {@link #MOMENTUM_SYMBOLS_KEY} so the
 * nightly price job maintains it; {@link #ensureBackfilled} covers the first-run gap with a deep
 * fetch. Both are written from the constructor seed and {@code ensureBackfilled} only — never from
 * {@code report()} — so an exploratory {@code ?universe=} sweep can't drop names from nightly
 * maintenance (same discipline as {@code trend_symbols}).
 */
public class MomentumAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(MomentumAnalysisService.class);

    /** Fetch set (universe + benchmarks + regime) the nightly price job unions in. */
    public static final String MOMENTUM_SYMBOLS_KEY = "momentum_symbols";
    /** The ranked universe only (user-editable) — distinct from the fetch set, which adds benchmarks. */
    public static final String MOMENTUM_UNIVERSE_KEY = "momentum_universe";
    /**
     * Every universe ever saved (one comma-joined list per line, newest last, capped) — feeds the
     * Universe input's hint datalist so previously saved lists are recallable. Browser form
     * history proved unreliable for this (it only remembers values from submitted forms).
     */
    public static final String UNIVERSE_HISTORY_KEY = "momentum_universe_history";
    private static final int UNIVERSE_HISTORY_MAX = 20;

    static final int DEEP_LOOKBACK_YEARS = 45;
    private static final LocalDate EPOCH = LocalDate.of(1970, 1, 1);

    static final String SURVIVORSHIP_CAVEAT =
            "This universe is hand-picked from today's known survivors and winners — it is "
            + "survivorship-biased, which inflates the absolute momentum CAGR. The meaningful "
            + "output is the RELATIVE comparison against the controls below (equal-weight baseline, "
            + "matched random-selection bootstrap, reversed-signal check), not the raw return.";

    private final PriceHistoryRepository priceRepo;
    private final YahooTickerMap tickerMap;
    private final KeyValueStore kv;
    private final PriceFetchJob priceFetchJob;

    public MomentumAnalysisService(PriceHistoryRepository priceRepo, YahooTickerMap tickerMap,
                                   KeyValueStore kv, PriceFetchJob priceFetchJob) {
        this.priceRepo = priceRepo;
        this.tickerMap = tickerMap;
        this.kv = kv;
        this.priceFetchJob = priceFetchJob;
        if (kv.getStringSet(MOMENTUM_SYMBOLS_KEY).isEmpty()) {
            syncFetchUniverse(MomentumConfig.builder().build());   // seeds both keys from defaults
        }
    }

    public MomentumConfig.Builder baseConfigBuilder() {
        return MomentumConfig.builder().universe(universe());
    }

    /** Persisted (or seeded-default) ranked universe — benchmarks are not part of it. */
    public List<String> universe() {
        Set<String> saved = kv.getStringSet(MOMENTUM_UNIVERSE_KEY);
        return saved.isEmpty() ? MomentumConfig.DEFAULT_UNIVERSE : new ArrayList<>(saved);
    }

    // ---- report -------------------------------------------------------------

    public MomentumReport report(MomentumConfig cfg) {
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

        // Baseline first: every variant's hit rate and regime split compare against it.
        RotationBacktester.Result baseRes = RotationBacktester.run(
                s, cfg, RotationBacktester.allEligible(), null, startIdx, lastIdx);
        Map<String, Double> baseFitMonths = MomentumStats.monthlyReturns(s, baseRes, 0, fitToK);
        Map<String, Double> baseHoldoutMonths = holdout
                ? MomentumStats.monthlyReturns(s, baseRes, fitToK + 1, lastK) : Map.of();

        Map<String, String> regimeStates = RegimeSplit.statesByHeldMonth(s, cfg);
        Map<String, Double> indexFitMonths = indexMonths(s, cfg, startIdx, 0, fitToK);

        // Variants.
        List<MomentumReport.Variant> variants = new ArrayList<>();
        for (int n : cfg.topNs()) {
            variants.add(buildVariant(s, cfg, n, startIdx, fitEndIdx, fitToK, lastK, holdout,
                    baseFitMonths, baseHoldoutMonths, regimeStates, indexFitMonths));
        }

        // Equal-weight baseline as its own section (bootstrap not meaningful for the whole-universe
        // control, so null; regime/crash still informative).
        MomentumStats baseFitStats = MomentumStats.of(s, baseRes, 0, fitToK, cfg.rfDaily(), baseFitMonths);
        MomentumReport.Section baselineFit = new MomentumReport.Section(
                baseFitStats, meanTurnover(baseRes, s.dateAt(fitEndIdx)), 0.0,
                RegimeSplit.compute(baseFitMonths, regimeStates, baseFitMonths, cfg.rfAnnualPct()),
                CrashDiagnostics.compute(s, cfg, baseFitMonths, indexFitMonths), null);
        MomentumStats baselineHoldout = holdout
                ? MomentumStats.of(s, baseRes, fitToK + 1, lastK, cfg.rfDaily(), baseHoldoutMonths) : null;

        // Benchmarks (buy-and-hold).
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

        List<MomentumReport.SymbolSpan> spans = spans(s, cfg);
        List<MomentumReport.GapRow> gaps = s.gaps().stream()
                .map(g -> new MomentumReport.GapRow(g.symbol(), g.from().toString(), g.to().toString(), g.sessions()))
                .toList();

        String startReason = "First completed month-end with ≥ " + cfg.minEligibleFactor()
                + "×" + maxN(cfg) + " = " + (cfg.minEligibleFactor() * maxN(cfg))
                + " rank-eligible names (expanding universe — names enter as history accrues).";

        return new MomentumReport(cfg, SURVIVORSHIP_CAVEAT, cfg.randomSeed(),
                s.dateAt(startIdx).toString(), startReason,
                s.dateAt(startIdx).toString(), s.dateAt(fitEndIdx).toString(),
                holdout ? s.dateAt(fitEndIdx + 1).toString() : null,
                holdout ? s.dateAt(lastIdx).toString() : null, holdout,
                spans, missing, gaps, variants, baselineFit, baselineHoldout, benchmarks, warnings);
    }

    private MomentumReport.Variant buildVariant(
            MomentumSeries s, MomentumConfig cfg, int n, int startIdx, int fitEndIdx,
            int fitToK, int lastK, boolean holdout,
            Map<String, Double> baseFitMonths, Map<String, Double> baseHoldoutMonths,
            Map<String, String> regimeStates, Map<String, Double> indexFitMonths) {

        RotationBacktester.Result res = RotationBacktester.run(
                s, cfg, RotationBacktester.topN(n), n, startIdx, startIdx + lastK);
        MomentumStats fitStats = MomentumStats.of(s, res, 0, fitToK, cfg.rfDaily(), baseFitMonths);

        // Cost drag: identical rule at zero cost, fit-window CAGR delta (percentage points).
        RotationBacktester.Result free = RotationBacktester.run(
                s, cfg.withCostPerSideBps(0), RotationBacktester.topN(n), n, startIdx, startIdx + lastK);
        MomentumStats freeFit = MomentumStats.of(s, free, 0, fitToK, cfg.rfDaily(), null);
        double costDrag = (freeFit.cagr() - fitStats.cagr()) * 100;

        RegimeSplit.Result regime = RegimeSplit.compute(
                fitStats.months(), regimeStates, baseFitMonths, cfg.rfAnnualPct());
        CrashDiagnostics.Result crash = CrashDiagnostics.compute(s, cfg, fitStats.months(), indexFitMonths);
        RotationBootstrap bootstrap = RotationBootstrap.run(s, cfg, n, startIdx, fitEndIdx, fitStats);

        MomentumReport.Section fit = new MomentumReport.Section(
                fitStats, meanTurnover(res, s.dateAt(fitEndIdx)), costDrag, regime, crash, bootstrap);

        MomentumStats holdoutStats = null;
        double holdoutTurnover = 0;
        if (holdout) {
            holdoutStats = MomentumStats.of(s, res, fitToK + 1, lastK, cfg.rfDaily(), baseHoldoutMonths);
            holdoutTurnover = meanTurnoverAfter(res, s.dateAt(fitEndIdx));
        }

        // Reversed-signal sanity: matched worst-N over the same windows.
        RotationBacktester.Result rev = RotationBacktester.run(
                s, cfg, RotationBacktester.bottomN(n), n, startIdx, startIdx + lastK);
        Double revFitCagr = MomentumStats.of(s, rev, 0, fitToK, cfg.rfDaily(), null).cagr();
        Double revHoldoutCagr = holdout
                ? MomentumStats.of(s, rev, fitToK + 1, lastK, cfg.rfDaily(), null).cagr() : null;

        String verdict = verdict(n, fitStats, baseFitMonths, bootstrap, revFitCagr);
        List<MomentumReport.RebalanceRow> logRows = logRows(res, s.dateAt(fitEndIdx), holdout);

        return new MomentumReport.Variant("Top " + n, n, fit, holdoutStats, holdoutTurnover,
                revFitCagr, revHoldoutCagr, verdict, logRows);
    }

    private String verdict(int n, MomentumStats fit, Map<String, Double> baseMonths,
                           RotationBootstrap boot, Double revCagr) {
        double baseCagr = compoundToCagr(baseMonths);
        String pct = boot == null ? "n/a"
                : Math.round(boot.cagrPercentile() * 100) + "th pct"
                  + (boot.cagrInsideIqr() ? " (≈random)" : " (signal)");
        String rev = revCagr == null ? "" : String.format(" · bottom-%d %.1f%%", n, revCagr * 100);
        return String.format("Top %d: CAGR %.1f%% vs EW baseline %.1f%% · bootstrap %s%s",
                n, fit.cagr() * 100, baseCagr * 100, pct, rev);
    }

    // ---- helpers ------------------------------------------------------------

    private Map<String, Double> indexMonths(MomentumSeries s, MomentumConfig cfg,
                                            int startIdx, int fromK, int toK) {
        int ri = s.indexOf(cfg.regimeSymbol());
        if (ri < 0) return Map.of();
        RotationBacktester.Result bh = RotationBacktester.buyAndHold(s, ri, startIdx, startIdx + toK);
        return MomentumStats.monthlyReturns(s, bh, fromK, toK);
    }

    private static double compoundToCagr(Map<String, Double> months) {
        if (months.isEmpty()) return Double.NaN;
        double f = 1;
        for (double v : months.values()) f *= 1 + v;
        return Math.pow(f, 12.0 / months.size()) - 1;
    }

    private double meanTurnover(RotationBacktester.Result r, LocalDate fitEnd) {
        double sum = 0;
        int n = 0;
        for (RotationBacktester.RebalanceEvent e : r.events()) {
            if (e.date().isAfter(fitEnd)) continue;
            sum += e.turnover();
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    private double meanTurnoverAfter(RotationBacktester.Result r, LocalDate fitEnd) {
        double sum = 0;
        int n = 0;
        for (RotationBacktester.RebalanceEvent e : r.events()) {
            if (!e.date().isAfter(fitEnd)) continue;
            sum += e.turnover();
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    private List<MomentumReport.RebalanceRow> logRows(RotationBacktester.Result r,
                                                      LocalDate fitEnd, boolean holdout) {
        List<MomentumReport.RebalanceRow> rows = new ArrayList<>();
        for (RotationBacktester.RebalanceEvent e : r.events()) {
            String section = e.date().isAfter(fitEnd) ? "holdout" : "fit";
            List<String> ranks = e.held().stream()
                    .map(x -> String.format("%s:%d:%.3f", x.symbol(), x.rank(), x.score()))
                    .toList();
            rows.add(new MomentumReport.RebalanceRow(e.date().toString(), section, e.eligible(),
                    e.in(), e.out(), ranks, e.turnover(), e.cost(), e.cashWeight()));
        }
        return rows;
    }

    private List<MomentumReport.SymbolSpan> spans(MomentumSeries s, MomentumConfig cfg) {
        Set<String> ranked = new LinkedHashSet<>(cfg.universe());
        List<MomentumReport.SymbolSpan> out = new ArrayList<>();
        Set<String> all = new LinkedHashSet<>(cfg.universe());
        all.addAll(cfg.benchmarkSymbols());
        all.add(cfg.regimeSymbol());
        for (String sym : all) {
            int idx = s.indexOf(sym);
            if (idx < 0) continue;
            int first = -1, last = -1, bars = 0;
            for (int i = 0; i < s.size(); i++) {
                if (!Double.isNaN(s.adj(idx, i))) {
                    if (first < 0) first = i;
                    last = i;
                    bars++;
                }
            }
            if (first < 0) continue;
            out.add(new MomentumReport.SymbolSpan(sym, s.dateAt(first).toString(),
                    s.dateAt(last).toString(), bars, ranked.contains(sym)));
        }
        return out;
    }

    private static int maxN(MomentumConfig cfg) {
        int m = 0;
        for (int n : cfg.topNs()) m = Math.max(m, n);
        return m;
    }

    private MomentumReport empty(MomentumConfig cfg, List<String> missing, List<String> warnings) {
        return new MomentumReport(cfg, SURVIVORSHIP_CAVEAT, cfg.randomSeed(), null, null,
                null, null, null, null, false, List.of(), missing, List.of(),
                List.of(), null, null, List.of(), warnings);
    }

    private List<PriceBar> load(String symbol, LocalDate today) {
        return priceRepo.getPriceHistory(tickerMap.tickerFor(symbol), EPOCH, today);
    }

    // ---- fetch universe + backfill ------------------------------------------

    private Set<String> configuredSymbols(MomentumConfig cfg) {
        Set<String> set = new LinkedHashSet<>(cfg.universe());
        set.addAll(cfg.benchmarkSymbols());
        set.add(cfg.regimeSymbol());
        return set;
    }

    private void syncFetchUniverse(MomentumConfig cfg) {
        Set<String> universe = new LinkedHashSet<>(cfg.universe());
        if (!universe.equals(kv.getStringSet(MOMENTUM_UNIVERSE_KEY))) {
            kv.putStringSet(MOMENTUM_UNIVERSE_KEY, universe);
        }
        Set<String> fetchSet = configuredSymbols(cfg);   // universe + benchmarks + regime
        if (!fetchSet.equals(kv.getStringSet(MOMENTUM_SYMBOLS_KEY))) {
            kv.putStringSet(MOMENTUM_SYMBOLS_KEY, fetchSet);
        }
    }

    /**
     * Persist the universe and deep-fetch any configured symbol whose coverage is missing or
     * shallow (earliest bar inside the standard 30-year window — the nightly job may have created a
     * shallow row first). Intended to run off the request thread.
     */
    public List<String> ensureBackfilled(MomentumConfig cfg) {
        syncFetchUniverse(cfg);
        List<String> fetched = new ArrayList<>();
        if (priceFetchJob == null) return fetched;
        LocalDate standardWindow = LocalDate.now().minusYears(31);
        for (String symbol : configuredSymbols(cfg)) {
            LocalDate earliest = priceRepo.getEarliestPriceDate(tickerMap.tickerFor(symbol));
            if (earliest != null && !earliest.isAfter(standardWindow)) continue;
            int rows = priceFetchJob.fetchSingle(symbol, DEEP_LOOKBACK_YEARS);
            log.info("Momentum backfill: {} → {} rows", symbol, rows);
            if (rows > 0) fetched.add(symbol);
        }
        return fetched;
    }

    /** Persist a user-edited universe (upper-cased, de-duped, order preserved). */
    public List<String> saveUniverse(List<String> symbols) {
        Set<String> clean = new LinkedHashSet<>();
        for (String s : symbols) {
            if (s == null) continue;
            String t = s.trim().toUpperCase();
            if (!t.isEmpty()) clean.add(t);
        }
        if (clean.isEmpty()) return universe();
        MomentumConfig cfg = MomentumConfig.builder().universe(new ArrayList<>(clean)).build();
        syncFetchUniverse(cfg);
        rememberUniverse(String.join(",", clean));
        return new ArrayList<>(clean);
    }

    /**
     * Previously saved universes, one comma-joined list per entry, newest last. The built-in
     * default and the currently active universe are always present (oldest / newest position
     * respectively) even before anything was explicitly saved, so switching to a custom list
     * never loses the way back to the stock one.
     */
    public List<String> universeHistory() {
        List<String> out = new ArrayList<>(kv.getStringSet(UNIVERSE_HISTORY_KEY));
        String builtIn = String.join(",", MomentumConfig.builder().build().universe());
        if (!out.contains(builtIn)) out.add(0, builtIn);
        String current = String.join(",", universe());
        if (!out.contains(current)) out.add(current);
        return out;
    }

    /** Append to the saved-list history; re-saving an existing list bumps it to newest. */
    private void rememberUniverse(String joined) {
        Set<String> hist = new LinkedHashSet<>(kv.getStringSet(UNIVERSE_HISTORY_KEY));
        hist.remove(joined);
        hist.add(joined);
        while (hist.size() > UNIVERSE_HISTORY_MAX) {
            var it = hist.iterator();
            it.next();
            it.remove();
        }
        kv.putStringSet(UNIVERSE_HISTORY_KEY, hist);
    }
}
