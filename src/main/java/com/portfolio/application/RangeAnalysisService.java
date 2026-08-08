package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.domain.range.RangeArms;
import com.portfolio.domain.range.RangeBacktester;
import com.portfolio.domain.range.RangeBacktester.Trade;
import com.portfolio.domain.range.RangeConfig;
import com.portfolio.domain.range.RangeEpisodes;
import com.portfolio.domain.range.RangeSeries;
import com.portfolio.domain.range.RangeTradeStats;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import com.portfolio.port.HistoricalFxRateProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Orchestrates the percentile-floor range-strategy backtest (the "Range floor" tab under Tools).
 *
 * <p>Loads maximum available daily history per cohort name, builds look-ahead-free trailing
 * percentile bands, runs the {@link RangeBacktester} state machine per name for the
 * pre-registered cell (accumulation + trading modes, the trailing-75th-percentile exit variant,
 * and the Arm-D reversed signal), assembles the capital arms per name (local currency) and per
 * family / pooled (GBP via daily historical FX), the matched random-accumulation bootstrap, the
 * sensitivity grid (report, never select), the exportable event log and the featured-name chart.
 *
 * <p><b>FX basis</b> (documented in the report): per-name results are local-currency, unit-less
 * return ratios — the pence/EUR/USD unit cancels. Pooled and family capital arms convert the
 * fixed GBP notional at daily ECB (Frankfurter) rates on entry/exit/end dates, so FX P&amp;L is
 * genuinely part of pooled results. Dates before the FX source's first rate ceil-fill to the
 * earliest known rate. If the FX fetch fails, pooled arms are omitted with a warning — per-name
 * results degrade, never lie.
 *
 * <p>The symbol universe mirrors to {@link KeyValueStore} under {@link #RANGE_SYMBOLS_KEY} from
 * the constructor seed and {@link #ensureBackfilled} <em>only — never from {@code report()}</em>,
 * so an exploratory {@code ?familyA=} sweep can't drop cohort names from nightly maintenance.
 */
public class RangeAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(RangeAnalysisService.class);

    public static final String RANGE_SYMBOLS_KEY = "range_symbols";

    private static final LocalDate EPOCH = LocalDate.of(1970, 1, 1);
    private static final double SHORT_HISTORY_YEARS = 10.0;
    /** Featured chart: keep every bar in the last N sessions, every 5th before. */
    private static final int FEATURED_DENSE_SESSIONS = 756;

    private final PriceHistoryRepository priceRepo;
    private final YahooTickerMap tickerMap;
    private final HistoricalFxRateProvider fxProvider;
    private final KeyValueStore kv;
    private final PriceFetchJob priceFetchJob;

    public RangeAnalysisService(PriceHistoryRepository priceRepo, YahooTickerMap tickerMap,
                                HistoricalFxRateProvider fxProvider, KeyValueStore kv,
                                PriceFetchJob priceFetchJob) {
        this.priceRepo = priceRepo;
        this.tickerMap = tickerMap;
        this.fxProvider = fxProvider;
        this.kv = kv;
        this.priceFetchJob = priceFetchJob;
        if (kv.getStringSet(RANGE_SYMBOLS_KEY).isEmpty()) {
            syncFetchUniverse(RangeConfig.builder().build());
        }
    }

    public RangeConfig.Builder baseConfigBuilder() {
        return RangeConfig.builder();
    }

    // ---- report --------------------------------------------------------------

    public RangeReport report(RangeConfig cfg) {
        LocalDate today = LocalDate.now();
        List<String> warnings = new ArrayList<>();

        // 1. Series per name, max available history.
        Map<String, RangeSeries> seriesBySymbol = new LinkedHashMap<>();
        List<RangeReport.SymbolData> data = new ArrayList<>();
        for (String sym : cfg.symbols()) {
            String family = cfg.familyA().contains(sym) ? "A" : "B";
            List<PriceBar> bars = priceRepo.getPriceHistory(tickerMap.tickerFor(sym), EPOCH, today);
            RangeSeries s = RangeSeries.of(sym, bars, cfg.maxDailyMove());
            if (s == null) {
                warnings.add(sym + " has no local daily history — run Backfill.");
                data.add(new RangeReport.SymbolData(sym, family, null, null, null, null, 0,
                        false, true, List.of()));
                continue;
            }
            seriesBySymbol.put(sym, s);
            double years = ChronoUnit.DAYS.between(s.firstDate(), s.lastDate()) / 365.25;
            List<RangeReport.DataErrorRow> errs = new ArrayList<>();
            for (RangeSeries.DataError e : s.dataErrors()) {
                errs.add(new RangeReport.DataErrorRow(e.date().toString(), e.movePct()));
            }
            if (!errs.isEmpty()) {
                warnings.add(sym + ": " + errs.size() + " daily move(s) over "
                        + Math.round(cfg.maxDailyMove() * 100) + "% — check units/splits.");
            }
            data.add(new RangeReport.SymbolData(sym, family, s.currency(),
                    s.firstDate().toString(), s.lastDate().toString(), years, s.size(),
                    years < SHORT_HISTORY_YEARS, false, errs));
        }
        if (seriesBySymbol.isEmpty()) {
            return emptyReport(cfg, warnings, data);
        }

        // 2. FX lookups (GBP capital arms). Degrade to per-name-only on failure.
        Map<String, RangeArms.FxLookup> fxBySymbol = new LinkedHashMap<>();
        boolean fxAvailable = buildFxLookups(seriesBySymbol, today, fxBySymbol, warnings);

        // 3. Percentile bands — one sorted-window pass per name for every needed percentile.
        double[] pcts = neededPercentiles(cfg);
        Map<String, double[][]> levelsBySymbol = new LinkedHashMap<>();
        for (var e : seriesBySymbol.entrySet()) {
            levelsBySymbol.put(e.getKey(), e.getValue().trailingLevels(pcts, cfg.percentileWindow()));
        }

        // 4. Pre-registered-cell runs per name.
        Map<String, Runs> runsBySymbol = new LinkedHashMap<>();
        for (var e : seriesBySymbol.entrySet()) {
            runsBySymbol.put(e.getKey(),
                    preRegRuns(e.getValue(), levelsBySymbol.get(e.getKey()), pcts, cfg));
        }

        // 5. Scopes: pooled, families, then per name.
        LocalDate trailingCutoff = today.minusMonths(cfg.trailingMonths());
        List<RangeReport.ScopeResult> scopes = new ArrayList<>();
        scopes.add(scope("Pooled", present(cfg.symbols(), seriesBySymbol), true,
                seriesBySymbol, runsBySymbol, fxBySymbol, fxAvailable, trailingCutoff, cfg));
        scopes.add(scope("Family A (exchanges)", present(cfg.familyA(), seriesBySymbol), true,
                seriesBySymbol, runsBySymbol, fxBySymbol, fxAvailable, trailingCutoff, cfg));
        scopes.add(scope("Family B (data/publishing)", present(cfg.familyB(), seriesBySymbol), true,
                seriesBySymbol, runsBySymbol, fxBySymbol, fxAvailable, trailingCutoff, cfg));
        for (String sym : seriesBySymbol.keySet()) {
            scopes.add(scope(sym, List.of(sym), false,
                    seriesBySymbol, runsBySymbol, fxBySymbol, fxAvailable, trailingCutoff, cfg));
        }

        // 6. Verdicts from the pooled scope.
        List<String> verdicts = verdicts(scopes.get(0), fxAvailable);

        // 7. Sensitivity grid (full history; report, never select).
        List<RangeReport.GridCell> grid = fxAvailable
                ? sensitivityGrid(cfg, seriesBySymbol, levelsBySymbol, fxBySymbol)
                : List.of();
        if (!fxAvailable) warnings.add("Sensitivity grid skipped: FX unavailable for GBP arms.");

        // 8. Event log (pre-registered cell, full history, both modes).
        List<RangeReport.EventRow> events = eventLog(cfg, runsBySymbol);

        // 9. Featured chart.
        RangeReport.Featured featured = featured(cfg, seriesBySymbol, runsBySymbol);

        return new RangeReport(preRegistration(), exploratory(cfg) != null,
                exploratory(cfg) == null ? List.of() : exploratory(cfg),
                cfg, warnings, verdicts, data, fxNote(), fxAvailable,
                scopes, grid, events, featured);
    }

    // ---- per-name runs --------------------------------------------------------

    /** The four pre-registered-cell trade lists for one name. */
    private record Runs(List<Trade> accumulation, List<Trade> pctlExit,
                        List<Trade> trading, List<Trade> armD) {
    }

    private Runs preRegRuns(RangeSeries s, double[][] levels, double[] pcts, RangeConfig cfg) {
        double[] entry = levels[idx(pcts, cfg.entryPercentile())];
        double[] exit = levels[idx(pcts, cfg.exitPercentile())];
        double[] mirror = levels[idx(pcts, cfg.mirrorPercentile())];
        int rankW = cfg.percentileWindow();
        List<Trade> acc = RangeBacktester.run(s, new RangeBacktester.Params(
                entry, RangeBacktester.Direction.FLOOR, cfg.aboveRunSessions(),
                cfg.takeProfit(), null, Double.NaN, rankW));
        List<Trade> pctl = RangeBacktester.run(s, new RangeBacktester.Params(
                entry, RangeBacktester.Direction.FLOOR, cfg.aboveRunSessions(),
                Double.NaN, exit, Double.NaN, rankW));
        List<Trade> trading = RangeBacktester.run(s, new RangeBacktester.Params(
                entry, RangeBacktester.Direction.FLOOR, cfg.aboveRunSessions(),
                cfg.takeProfit(), null, cfg.stopLoss(), rankW));
        List<Trade> armD = RangeBacktester.run(s, new RangeBacktester.Params(
                mirror, RangeBacktester.Direction.CEILING, cfg.aboveRunSessions(),
                cfg.takeProfit(), null, Double.NaN, rankW));
        return new Runs(acc, pctl, trading, armD);
    }

    // ---- scopes ----------------------------------------------------------------

    private RangeReport.ScopeResult scope(String name, List<String> members, boolean gbpBasis,
                                          Map<String, RangeSeries> series, Map<String, Runs> runs,
                                          Map<String, RangeArms.FxLookup> fx, boolean fxAvailable,
                                          LocalDate trailingCutoff, RangeConfig cfg) {
        return new RangeReport.ScopeResult(name, members, gbpBasis ? "GBP" : "local",
                window("full", members, null, gbpBasis, series, runs, fx, fxAvailable, cfg),
                window("trailing " + cfg.trailingMonths() + "m", members, trailingCutoff,
                        gbpBasis, series, runs, fx, fxAvailable, cfg));
    }

    private RangeReport.WindowResult window(String label, List<String> members, LocalDate cutoff,
                                            boolean gbpBasis, Map<String, RangeSeries> series,
                                            Map<String, Runs> runs,
                                            Map<String, RangeArms.FxLookup> fx, boolean fxAvailable,
                                            RangeConfig cfg) {
        List<Trade> acc = new ArrayList<>();
        List<Trade> pctl = new ArrayList<>();
        List<Trade> trading = new ArrayList<>();
        List<RangeArms.NameInput> accInputs = new ArrayList<>();
        List<RangeArms.NameInput> armDInputs = new ArrayList<>();
        List<LocalDate> entryDates = new ArrayList<>();
        LocalDate from = null, to = null;
        for (String sym : members) {
            RangeSeries s = series.get(sym);
            Runs r = runs.get(sym);
            List<Trade> a = filter(r.accumulation(), cutoff);
            acc.addAll(a);
            pctl.addAll(filter(r.pctlExit(), cutoff));
            trading.addAll(filter(r.trading(), cutoff));
            for (Trade t : a) entryDates.add(t.entryDate());
            RangeArms.FxLookup lookup = gbpBasis ? fx.get(sym) : RangeArms.FxLookup.IDENTITY;
            if (!a.isEmpty()) {
                accInputs.add(new RangeArms.NameInput(s, a, cfg.stampFor(sym), lookup));
                LocalDate first = a.get(0).entryDate();
                if (from == null || first.isBefore(from)) from = first;
                if (to == null || s.lastDate().isAfter(to)) to = s.lastDate();
            }
            List<Trade> d = filter(r.armD(), cutoff);
            if (!d.isEmpty()) {
                armDInputs.add(new RangeArms.NameInput(s, d, cfg.stampFor(sym), lookup));
            }
        }

        int episodes = RangeEpisodes.count(entryDates, cfg.dedupCalendarDays());
        boolean thin = episodes < cfg.episodeFloor();
        RangeTradeStats accStats = RangeTradeStats.of(acc, cfg.stopLoss());
        RangeTradeStats pctlStats = RangeTradeStats.of(pctl, cfg.stopLoss());
        RangeTradeStats tradingStats = RangeTradeStats.of(trading, cfg.stopLoss());

        RangeArms.ArmOutcome armA = null, armB = null, armD = null;
        RangeArms.Bootstrap armC = null;
        String armsNote = null;
        if (accInputs.isEmpty()) {
            armsNote = "No entries in this window.";
        } else if (gbpBasis && !fxAvailable) {
            armsNote = "FX unavailable — GBP capital arms omitted (per-name local results unaffected).";
        } else {
            double n = cfg.notionalGbp();
            double comm = cfg.commissionFraction();
            double rf = cfg.rfAnnual();
            armA = combineStrategy(accInputs, n, comm, rf, Double.NaN);
            armB = combineBuyHold(accInputs, n, comm, rf);
            armC = RangeArms.randomArm(accInputs, n, comm, rf,
                    cfg.bootstrapIterations(), cfg.randomSeed(), armA);
            armD = combineStrategy(armDInputs, n, comm, rf, Double.NaN);
        }
        return new RangeReport.WindowResult(label,
                from == null ? null : from.toString(), to == null ? null : to.toString(),
                episodes, thin, accStats, pctlStats, tradingStats,
                armA, armB, armC, armD, armsNote);
    }

    private static RangeArms.ArmOutcome combineStrategy(List<RangeArms.NameInput> inputs,
                                                        double n, double comm, double rf,
                                                        double ambiguousRet) {
        List<RangeArms.ArmOutcome> parts = new ArrayList<>();
        for (RangeArms.NameInput in : inputs) {
            parts.add(RangeArms.strategyArm(in, n, comm, rf, ambiguousRet));
        }
        return RangeArms.combine(parts);
    }

    private static RangeArms.ArmOutcome combineBuyHold(List<RangeArms.NameInput> inputs,
                                                       double n, double comm, double rf) {
        List<RangeArms.ArmOutcome> parts = new ArrayList<>();
        for (RangeArms.NameInput in : inputs) {
            parts.add(RangeArms.buyHoldArm(in, n, comm, rf));
        }
        return RangeArms.combine(parts);
    }

    private static List<Trade> filter(List<Trade> trades, LocalDate cutoff) {
        if (cutoff == null) return trades;
        List<Trade> out = new ArrayList<>();
        for (Trade t : trades) {
            if (!t.entryDate().isBefore(cutoff)) out.add(t);
        }
        return out;
    }

    // ---- verdicts ---------------------------------------------------------------

    private List<String> verdicts(RangeReport.ScopeResult pooled, boolean fxAvailable) {
        List<String> out = new ArrayList<>();
        if (!fxAvailable) {
            out.add("Verdict unavailable: FX fetch failed, so the pooled GBP capital arms could "
                    + "not be computed this run.");
            return out;
        }
        out.add(verdictLine(pooled.full()));
        out.add(verdictLine(pooled.trailing()));
        return out;
    }

    private String verdictLine(RangeReport.WindowResult w) {
        String prefix = "[" + w.label() + "] ";
        if (w.armA() == null || w.armB() == null || w.armC() == null
                || w.armC().roAllocatedAnn() == null) {
            return prefix + "No entries — nothing to compare.";
        }
        double aAnn = w.armA().returnOnAllocatedAnn();
        double bAnn = w.armB().returnOnAllocatedAnn();
        double cMedAnn = w.armC().roAllocatedAnn().median();
        double aRoD = w.armA().returnOnDeployed();
        double bRoD = w.armB().returnOnDeployed();
        double cMedRoD = w.armC().roDeployed() == null ? Double.NaN : w.armC().roDeployed().median();
        boolean winsAllocated = aAnn > bAnn && aAnn > cMedAnn;
        boolean winsDeployed = aRoD > bRoD && aRoD > cMedRoD;
        String detail = String.format(
                "A %.2f%%/yr vs B %.2f%%/yr vs C-median %.2f%%/yr on allocated capital; "
                        + "A %.2f vs B %.2f vs C-median %.2f per pound-year deployed.",
                aAnn * 100, bAnn * 100, cMedAnn * 100, aRoD * 100, bRoD * 100, cMedRoD * 100);
        if (winsAllocated && winsDeployed) {
            return prefix + "The band strategy beat buy-and-hold and the random-accumulation "
                    + "median on both capital bases. " + detail;
        }
        if (!winsAllocated && !winsDeployed) {
            return prefix + "Floor timing added nothing over owning the name. " + detail;
        }
        return prefix + "Mixed: the two capital bases disagree — "
                + (winsDeployed ? "per pound-year deployed the strategy wins, but on standing "
                + "allocated capital it does not (idle cash drag)."
                : "on allocated capital the strategy wins, but per pound-year deployed it does not.")
                + " " + detail;
    }

    // ---- sensitivity grid --------------------------------------------------------

    private List<RangeReport.GridCell> sensitivityGrid(RangeConfig cfg,
                                                       Map<String, RangeSeries> series,
                                                       Map<String, double[][]> levels,
                                                       Map<String, RangeArms.FxLookup> fx) {
        double[] pcts = neededPercentiles(cfg);
        List<RangeReport.GridCell> out = new ArrayList<>();
        List<List<String>> scopeMembers = List.of(
                present(cfg.symbols(), series), present(cfg.familyA(), series),
                present(cfg.familyB(), series));
        List<String> scopeNames = List.of("Pooled", "Family A", "Family B");
        int cellSeq = 0;
        for (int sc = 0; sc < scopeMembers.size(); sc++) {
            for (String mode : List.of("accumulation", "trading")) {
                boolean tradingMode = mode.equals("trading");
                for (double p : cfg.sweepPercentiles()) {
                    for (double tp : cfg.sweepTakeProfits()) {
                        cellSeq++;
                        out.add(gridCell(cfg, series, levels, fx, scopeNames.get(sc),
                                scopeMembers.get(sc), mode, tradingMode, p, tp,
                                pcts, cfg.randomSeed() + cellSeq));
                    }
                }
            }
        }
        return out;
    }

    private RangeReport.GridCell gridCell(RangeConfig cfg, Map<String, RangeSeries> series,
                                          Map<String, double[][]> levels,
                                          Map<String, RangeArms.FxLookup> fx,
                                          String scopeName, List<String> members,
                                          String mode, boolean tradingMode,
                                          double p, double tp, double[] pcts, long seed) {
        double sl = tradingMode ? cfg.stopLoss() : Double.NaN;
        double ambiguousRet = tradingMode ? -cfg.stopLoss() : Double.NaN;
        List<RangeArms.NameInput> inputs = new ArrayList<>();
        List<LocalDate> entryDates = new ArrayList<>();
        int events = 0;
        for (String sym : members) {
            RangeSeries s = series.get(sym);
            double[] entry = levels.get(sym)[idx(pcts, p)];
            List<Trade> trades = RangeBacktester.run(s, new RangeBacktester.Params(
                    entry, RangeBacktester.Direction.FLOOR, cfg.aboveRunSessions(),
                    tp, null, sl, cfg.percentileWindow()));
            events += trades.size();
            for (Trade t : trades) entryDates.add(t.entryDate());
            if (!trades.isEmpty()) {
                inputs.add(new RangeArms.NameInput(s, trades, cfg.stampFor(sym), fx.get(sym)));
            }
        }
        int episodes = RangeEpisodes.count(entryDates, cfg.dedupCalendarDays());
        boolean thin = episodes < cfg.episodeFloor();
        boolean preReg = close(p, cfg.entryPercentile()) && close(tp, cfg.takeProfit());
        if (inputs.isEmpty()) {
            return new RangeReport.GridCell(scopeName, mode, p, tp, 0, 0,
                    null, null, null, false, true, preReg);
        }
        double n = cfg.notionalGbp();
        RangeArms.ArmOutcome armA = combineStrategy(inputs, n, cfg.commissionFraction(),
                cfg.rfAnnual(), ambiguousRet);
        RangeArms.Bootstrap boot = RangeArms.randomArm(inputs, n, cfg.commissionFraction(),
                cfg.rfAnnual(), cfg.bootstrapIterations(), seed, armA);
        Double pctl = boot.actualPctlDeployed();
        boolean insideIqr = pctl != null && pctl >= 25.0 && pctl <= 75.0;
        return new RangeReport.GridCell(scopeName, mode, p, tp, events, episodes,
                nanToNull(armA.returnOnDeployed()),
                boot.roDeployed() == null ? null : nanToNull(boot.roDeployed().median()),
                pctl, insideIqr, thin, preReg);
    }

    // ---- event log -----------------------------------------------------------------

    private List<RangeReport.EventRow> eventLog(RangeConfig cfg, Map<String, Runs> runs) {
        List<RangeReport.EventRow> out = new ArrayList<>();
        for (var e : runs.entrySet()) {
            double stamp = cfg.stampFor(e.getKey());
            for (Trade t : e.getValue().accumulation()) {
                out.add(eventRow(e.getKey(), "accumulation", t, stamp, cfg));
            }
            for (Trade t : e.getValue().trading()) {
                out.add(eventRow(e.getKey(), "trading", t, stamp, cfg));
            }
        }
        out.sort((a, b) -> a.entryDate().compareTo(b.entryDate()));
        return out;
    }

    private RangeReport.EventRow eventRow(String symbol, String mode, Trade t, double stamp,
                                          RangeConfig cfg) {
        double n = cfg.notionalGbp();
        double costs = n * (stamp + cfg.commissionFraction());
        if (!t.open() && t.exitType() != RangeBacktester.ExitType.AMBIGUOUS) {
            costs += n * (1 + t.trRet()) * cfg.commissionFraction();
        }
        return new RangeReport.EventRow(symbol, mode, t.entryDate().toString(),
                t.level(), nanToNull(t.pctlRank()), t.fill(),
                t.exitType().name(), t.exitDate().toString(), nanToNull(t.exitFill()),
                nanToNull(t.priceRet()), nanToNull(t.trRet()), t.mae(),
                t.underwaterSessions(), t.daysHeld(), costs);
    }

    // ---- featured chart --------------------------------------------------------------

    private RangeReport.Featured featured(RangeConfig cfg, Map<String, RangeSeries> series,
                                          Map<String, Runs> runs) {
        RangeSeries s = series.get(cfg.featuredSymbol());
        if (s == null) return null;
        Runs r = runs.get(cfg.featuredSymbol());
        List<Trade> trades = r.accumulation();
        int start = trades.isEmpty() ? Math.max(0, s.size() - FEATURED_DENSE_SESSIONS)
                : Math.max(0, trades.get(0).entryIdx() - cfg.percentileWindow());
        int denseFrom = Math.max(start, s.size() - FEATURED_DENSE_SESSIONS);
        List<String> dates = new ArrayList<>();
        List<Double> closes = new ArrayList<>();
        for (int i = start; i < s.size(); i++) {
            if (i < denseFrom && (i - start) % 5 != 0) continue;
            dates.add(s.dateAt(i).toString());
            closes.add(s.closeAt(i));
        }
        List<RangeReport.Marker> markers = new ArrayList<>();
        int seq = 0;
        for (Trade t : trades) {
            seq++;
            markers.add(new RangeReport.Marker(t.entryDate().toString(), t.fill(), "entry", seq));
            if (t.open()) {
                markers.add(new RangeReport.Marker(t.exitDate().toString(),
                        s.closeAt(s.size() - 1), "open", seq));
            } else {
                markers.add(new RangeReport.Marker(t.exitDate().toString(), t.exitFill(),
                        t.exitType().name().toLowerCase(), seq));
            }
        }
        return new RangeReport.Featured(cfg.featuredSymbol(), s.currency(), dates, closes, markers);
    }

    // ---- FX ---------------------------------------------------------------------------

    /**
     * One rate series per non-GBP listing currency, wrapped as {@link RangeArms.FxLookup}s
     * (floor lookup; ceil-fill to the earliest rate for pre-source dates). GBP/GBp names use
     * IDENTITY — the pence unit cancels in return ratios.
     */
    private boolean buildFxLookups(Map<String, RangeSeries> series, LocalDate today,
                                   Map<String, RangeArms.FxLookup> out, List<String> warnings) {
        Set<String> currencies = new LinkedHashSet<>();
        LocalDate earliest = today;
        for (RangeSeries s : series.values()) {
            String ccy = normaliseCcy(s.currency());
            if (!"GBP".equals(ccy)) currencies.add(ccy);
            if (s.firstDate().isBefore(earliest)) earliest = s.firstDate();
        }
        Map<String, TreeMap<LocalDate, Double>> byCcy = new LinkedHashMap<>();
        for (String ccy : currencies) {
            try {
                Map<LocalDate, BigDecimal> raw = fxProvider.fetchRateSeries(ccy, earliest, today);
                TreeMap<LocalDate, Double> m = new TreeMap<>();
                for (var e : raw.entrySet()) m.put(e.getKey(), e.getValue().doubleValue());
                if (m.isEmpty()) throw new IllegalStateException("empty FX series for " + ccy);
                byCcy.put(ccy, m);
            } catch (Exception e) {
                log.warn("Range: FX series fetch failed for {}", ccy, e);
                warnings.add("FX series unavailable for " + ccy
                        + " — pooled GBP capital arms omitted this run.");
                return false;
            }
        }
        for (var e : series.entrySet()) {
            String ccy = normaliseCcy(e.getValue().currency());
            if ("GBP".equals(ccy)) {
                out.put(e.getKey(), RangeArms.FxLookup.IDENTITY);
            } else {
                TreeMap<LocalDate, Double> m = byCcy.get(ccy);
                out.put(e.getKey(), d -> {
                    var f = m.floorEntry(d);
                    return f != null ? f.getValue() : m.firstEntry().getValue();
                });
            }
        }
        return true;
    }

    private static String normaliseCcy(String ccy) {
        if (ccy == null) return "USD";           // US listings sometimes omit it; USD is the default
        return "GBp".equals(ccy) ? "GBP" : ccy;
    }

    // ---- pre-registration / config echo --------------------------------------------------

    private static List<String> preRegistration() {
        return List.of(
                "Parameters below were fixed before running. The sensitivity grid is reported "
                        + "for robustness only. No cell is selected as \"the strategy\" because it "
                        + "scored best. Any parameter changed after seeing results makes the rerun "
                        + "exploratory and must be labelled as such in the research log.",
                "Cohort membership was fixed before running and no name may be dropped after "
                        + "results are seen. Family A (exchanges / market infrastructure): LSEG.L, "
                        + "DB1.DE, ICE, CME, NDAQ. Family B (data / analytics / professional "
                        + "publishing): MSCI, SPGI, MCO, FDS, REL.L, WKL.AS, TRI. "
                        + "Featured name: LSEG.L.");
    }

    /** Names of parameters differing from the pre-registered defaults; null when none. */
    private static List<String> exploratory(RangeConfig cfg) {
        RangeConfig def = RangeConfig.builder().build();
        List<String> diffs = new ArrayList<>();
        if (!cfg.familyA().equals(def.familyA())) diffs.add("familyA");
        if (!cfg.familyB().equals(def.familyB())) diffs.add("familyB");
        if (cfg.entryPercentile() != def.entryPercentile()) diffs.add("entryPercentile");
        if (cfg.exitPercentile() != def.exitPercentile()) diffs.add("exitPercentile");
        if (cfg.percentileWindow() != def.percentileWindow()) diffs.add("percentileWindow");
        if (cfg.aboveRunSessions() != def.aboveRunSessions()) diffs.add("aboveRunSessions");
        if (cfg.takeProfit() != def.takeProfit()) diffs.add("takeProfit");
        if (cfg.stopLoss() != def.stopLoss()) diffs.add("stopLoss");
        if (cfg.rfAnnualPct() != def.rfAnnualPct()) diffs.add("rf");
        if (cfg.notionalGbp() != def.notionalGbp()) diffs.add("notional");
        if (cfg.commissionBps() != def.commissionBps()) diffs.add("commission");
        if (cfg.trailingMonths() != def.trailingMonths()) diffs.add("trailingMonths");
        return diffs.isEmpty() ? null : diffs;
    }

    private static String fxNote() {
        return "Per-name tables are local-currency, unit-less return ratios (pence/EUR/USD "
                + "cancels). Pooled and family capital arms convert the fixed GBP notional at "
                + "daily ECB (Frankfurter) rates on entry, exit and end dates — FX P&L is part "
                + "of pooled results by design. Dates before the FX source's first rate (1999) "
                + "ceil-fill to the earliest known rate.";
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** entry ∪ sweep ∪ exit ∪ mirror percentiles, deduplicated, order-stable. */
    private static double[] neededPercentiles(RangeConfig cfg) {
        List<Double> ps = new ArrayList<>();
        addUnique(ps, cfg.entryPercentile());
        for (double p : cfg.sweepPercentiles()) addUnique(ps, p);
        addUnique(ps, cfg.exitPercentile());
        addUnique(ps, cfg.mirrorPercentile());
        double[] out = new double[ps.size()];
        for (int i = 0; i < out.length; i++) out[i] = ps.get(i);
        return out;
    }

    private static void addUnique(List<Double> ps, double p) {
        for (double x : ps) if (close(x, p)) return;
        ps.add(p);
    }

    private static int idx(double[] pcts, double p) {
        for (int i = 0; i < pcts.length; i++) if (close(pcts[i], p)) return i;
        throw new IllegalStateException("percentile " + p + " not precomputed");
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    private static List<String> present(List<String> symbols, Map<String, RangeSeries> series) {
        List<String> out = new ArrayList<>();
        for (String s : symbols) if (series.containsKey(s)) out.add(s);
        return out;
    }

    private static Double nanToNull(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
    }

    private RangeReport emptyReport(RangeConfig cfg, List<String> warnings,
                                    List<RangeReport.SymbolData> data) {
        warnings.add("No cohort name has local daily history yet — run Backfill first.");
        return new RangeReport(preRegistration(), exploratory(cfg) != null,
                exploratory(cfg) == null ? List.of() : exploratory(cfg),
                cfg, warnings, List.of(), data, fxNote(), false,
                List.of(), List.of(), List.of(), null);
    }

    // ---- fetch universe + backfill ------------------------------------------------------------

    private void syncFetchUniverse(RangeConfig cfg) {
        Set<String> desired = new LinkedHashSet<>(cfg.symbols());
        if (!desired.equals(kv.getStringSet(RANGE_SYMBOLS_KEY))) {
            kv.putStringSet(RANGE_SYMBOLS_KEY, desired);
        }
    }

    /**
     * Fetch daily history for any configured symbol with no local coverage. The cohort names all
     * list within the standard fetch window, so no deep-lookback special case is needed (unlike
     * Trend's 45-year indexes). Intended to run off the request thread.
     */
    public List<String> ensureBackfilled(RangeConfig cfg) {
        syncFetchUniverse(cfg);
        List<String> fetched = new ArrayList<>();
        if (priceFetchJob == null) return fetched;
        for (String symbol : cfg.symbols()) {
            LocalDate earliest = priceRepo.getEarliestPriceDate(tickerMap.tickerFor(symbol));
            if (earliest != null) continue;
            int rows = priceFetchJob.fetchSingle(symbol);
            log.info("Range backfill: {} → {} rows", symbol, rows);
            if (rows > 0) fetched.add(symbol);
        }
        return fetched;
    }
}
