package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.domain.swing.Stats;
import com.portfolio.domain.trend.Backtester;
import com.portfolio.domain.trend.Episodes;
import com.portfolio.domain.trend.ExposureBootstrap;
import com.portfolio.domain.trend.LiveState;
import com.portfolio.domain.trend.Signals;
import com.portfolio.domain.trend.TradeLog;
import com.portfolio.domain.trend.TrendAnnual;
import com.portfolio.domain.trend.TrendConfig;
import com.portfolio.domain.trend.TrendSeries;
import com.portfolio.domain.trend.TrendSignal;
import com.portfolio.domain.trend.TrendStats;
import com.portfolio.domain.trend.Whipsaw;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Orchestrates the trend-filter backtest report: loads the primary / extension / cash daily bars
 * from {@code price_history}, builds the spliced {@link TrendSeries}, and runs every signal
 * variant × MA length over one <em>common</em> analysis window (the latest first-valid index of
 * any variant, so a 150-day cell never gets a head start on a 250-day one and buy-and-hold covers
 * the identical span). Each cell carries the full sensitivity block — costs, close-fill
 * execution, half-switch, 0% cash — plus whipsaw accounting, the matched random-exposure
 * bootstrap, calendar years, named episodes and the exportable trade log. No champion cell is
 * chosen anywhere.
 *
 * <p>The effective symbols are mirrored to {@link KeyValueStore} under {@link #TREND_SYMBOLS_KEY}
 * so {@code PriceFetchSupport.tickersToFetch} keeps them maintained nightly; {@link
 * #ensureBackfilled} covers the first-run gap with a {@value #DEEP_LOOKBACK_YEARS}-year fetch
 * (^NDX exists from 1985, ^IRX from the 1960s — the standard 30-year window would clip them).
 */
public class TrendAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(TrendAnalysisService.class);

    public static final String TREND_SYMBOLS_KEY = "trend_symbols";

    /** The GBP-quoted holding the live readout mirrors (its own series, no splice/cash). */
    static final String HOLDING_SYMBOL = "EQQQ";

    static final int DEEP_LOOKBACK_YEARS = 45;

    private static final LocalDate EPOCH = LocalDate.of(1970, 1, 1);
    private static final LocalDate DOT_COM_PEAK = LocalDate.of(2000, 3, 10);

    private final PriceHistoryRepository priceRepo;
    private final YahooTickerMap tickerMap;
    private final KeyValueStore kv;
    private final PriceFetchJob priceFetchJob;

    public TrendAnalysisService(PriceHistoryRepository priceRepo, YahooTickerMap tickerMap,
                                KeyValueStore kv, PriceFetchJob priceFetchJob) {
        this.priceRepo = priceRepo;
        this.tickerMap = tickerMap;
        this.kv = kv;
        this.priceFetchJob = priceFetchJob;
        // Seed the fetch-universe mirror on startup so the nightly job maintains the default
        // symbols even before the tab is first opened.
        if (kv.getStringSet(TREND_SYMBOLS_KEY).isEmpty()) {
            syncFetchUniverse(TrendConfig.builder().build());
        }
    }

    public TrendConfig.Builder baseConfigBuilder() {
        return TrendConfig.builder();
    }

    // ---- report -------------------------------------------------------------

    public TrendReport report(TrendConfig cfg) {
        syncFetchUniverse(cfg);
        LocalDate today = LocalDate.now();
        List<String> warnings = new ArrayList<>();

        List<PriceBar> primary = load(cfg.primarySymbol(), today);
        List<PriceBar> extension = cfg.spliceEnabled() ? load(cfg.extensionSymbol(), today) : List.of();
        List<PriceBar> cash = cfg.cashSeriesEnabled() ? load(cfg.cashSymbol(), today) : List.of();

        if (cfg.spliceEnabled() && extension.isEmpty()) {
            warnings.add("Extension series " + cfg.extensionSymbol()
                    + " has no local history — run Backfill (analysis proceeds unspliced).");
        }
        if (cfg.cashSeriesEnabled() && cash.isEmpty()) {
            warnings.add("Cash-rate series " + cfg.cashSymbol() + " has no local history — the flat "
                    + cfg.flatCashRatePct() + "% rate applies throughout. Run Backfill.");
        }

        TrendSeries s = TrendSeries.of(cfg, primary, extension, cash);
        if (s == null) {
            warnings.add("Primary series " + cfg.primarySymbol()
                    + " has no local daily history — run Backfill first.");
            return new TrendReport(cfg, emptyData(cfg, warnings), null, List.of(), List.of(), null);
        }

        List<TrendSignal> signals = new ArrayList<>(Signals.gridVariants(cfg));
        signals.addAll(Signals.monthlyVariants(cfg));
        int start = 0;
        for (TrendSignal sig : signals) start = Math.max(start, sig.firstValid(s));
        if (start >= s.size() - 1) {
            warnings.add("Not enough history for the longest MA window ("
                    + s.size() + " bars available).");
            return new TrendReport(cfg, dataPanel(cfg, s, -1, warnings), null, List.of(), List.of(),
                    liveBlock(cfg, s, today));
        }

        Backtester.Params headline = params(Backtester.Execution.NEXT_OPEN, 0,
                cfg.headlineCostBps(), s.cashDailyHistorical());
        Backtester.Result bh = Backtester.run(s, Backtester.buyAndHold(s.size()), start, headline);
        TrendStats bhStats = TrendStats.of(s, bh);

        List<TrendReport.VariantResult> variants = new ArrayList<>();
        for (TrendSignal sig : signals) {
            variants.add(variant(cfg, s, sig, start, headline, bh));
        }

        return new TrendReport(cfg, dataPanel(cfg, s, start, warnings), bhStats, variants,
                anchorSweeps(cfg, s, start), liveBlock(cfg, s, today));
    }

    private TrendReport.VariantResult variant(TrendConfig cfg, TrendSeries s, TrendSignal sig,
                                              int start, Backtester.Params headline, Backtester.Result bh) {
        boolean[] targets = sig.targets(s);
        Backtester.Result r = Backtester.run(s, targets, start, headline);
        TrendStats stats = TrendStats.of(s, r);

        List<TrendReport.CostRow> costs = new ArrayList<>();
        for (double bps : cfg.costsBps()) {
            TrendStats st = bps == cfg.headlineCostBps() ? stats
                    : TrendStats.of(s, Backtester.run(s, targets, start,
                            params(Backtester.Execution.NEXT_OPEN, 0, bps, s.cashDailyHistorical())));
            costs.add(new TrendReport.CostRow(bps, st));
        }

        TrendStats closeFill = TrendStats.of(s, Backtester.run(s, targets, start,
                params(Backtester.Execution.CLOSE, 0, cfg.headlineCostBps(), s.cashDailyHistorical())));
        TrendStats halfSwitch = TrendStats.of(s, Backtester.run(s, targets, start,
                params(Backtester.Execution.NEXT_OPEN, cfg.halfSwitchFraction(),
                        cfg.headlineCostBps(), s.cashDailyHistorical())));
        TrendStats cashZero = TrendStats.of(s, Backtester.run(s, targets, start,
                params(Backtester.Execution.NEXT_OPEN, 0, cfg.headlineCostBps(), s.cashDailyZero())));

        double costFraction = cfg.headlineCostBps() / 10_000.0;
        return new TrendReport.VariantResult(
                sig.key(), sig.family(), sig.label(), sig.maLength(),
                stats, costs, closeFill, halfSwitch, cashZero,
                Whipsaw.compute(s, r, costFraction),
                ExposureBootstrap.run(s, targets, start, headline, stats,
                        cfg.bootstrapIterations(), cfg.randomSeed()),
                TrendAnnual.rows(s, r, bh),
                Episodes.rows(s, r, bh),
                TradeLog.rows(s, r, sig, costFraction));
    }

    /** Timing-luck check: the identical month-anchored rule at every possible anchor offset. */
    private List<TrendReport.AnchorSweep> anchorSweeps(TrendConfig cfg, TrendSeries s, int start) {
        List<TrendReport.AnchorSweep> out = new ArrayList<>();
        Backtester.Params headline = params(Backtester.Execution.NEXT_OPEN, 0,
                cfg.headlineCostBps(), s.cashDailyHistorical());
        for (int len : cfg.maLengths()) {
            double[] cagrs = new double[cfg.anchorEvery()];
            double[] maxDds = new double[cfg.anchorEvery()];
            List<TrendReport.OffsetPoint> points = new ArrayList<>();
            for (int off = 0; off < cfg.anchorEvery(); off++) {
                boolean[] t = Signals.monthAnchored(len, cfg.anchorEvery(), off).targets(s);
                TrendStats st = TrendStats.of(s, Backtester.run(s, t, start, headline));
                cagrs[off] = st.cagr();
                maxDds[off] = st.maxDrawdown();
                points.add(new TrendReport.OffsetPoint(off, st.cagr(), st.maxDrawdown(), st.switches()));
            }
            out.add(new TrendReport.AnchorSweep(len, Stats.dist(cagrs), Stats.dist(maxDds), points));
        }
        return out;
    }

    private TrendReport.LiveBlock liveBlock(TrendConfig cfg, TrendSeries s, LocalDate today) {
        List<PriceBar> holdingBars = load(HOLDING_SYMBOL, today);
        TrendSeries holding = TrendSeries.of(
                TrendConfig.builder().primarySymbol(HOLDING_SYMBOL).extensionSymbol("").cashSymbol("")
                        .maLengths(cfg.maLengths()).monthlyMaLengths(cfg.monthlyMaLengths())
                        .signalSource(cfg.signalSource()).build(),
                holdingBars, null, null);
        return new TrendReport.LiveBlock(
                s.lastDate().toString(),
                cfg.primarySymbol(), LiveState.maReadouts(s, cfg.maLengths()),
                HOLDING_SYMBOL, holding == null ? null : holding.lastDate().toString(),
                holding == null ? List.of() : LiveState.maReadouts(holding, cfg.maLengths()),
                LiveState.variantStates(s, cfg));
    }

    private TrendReport.DataPanel dataPanel(TrendConfig cfg, TrendSeries s, int start, List<String> warnings) {
        double years = ChronoUnit.DAYS.between(s.firstDate(), s.lastDate()) / 365.25;
        boolean flatOnly = s.cashFrom() == null;
        LocalDate analysisStart = start >= 0 ? s.dateAt(start) : null;
        return new TrendReport.DataPanel(
                cfg.primarySymbol(), s.firstDate().toString(), s.lastDate().toString(), years,
                s.spliced(), cfg.spliceEnabled() ? cfg.extensionSymbol() : null,
                s.spliced() ? s.spliceDate().toString() : null,
                s.spliced() ? s.firstDate().toString() : null,
                s.spliced() ? s.dateAt(s.spliceIndex() - 1).toString() : null,
                analysisStart != null && !analysisStart.isAfter(DOT_COM_PEAK),
                cfg.cashSeriesEnabled() && !flatOnly ? cfg.cashSymbol() : null,
                s.cashFrom() == null ? null : s.cashFrom().toString(),
                s.cashFallbackUntil() == null ? null : s.cashFallbackUntil().toString(),
                cfg.flatCashRatePct(),
                analysisStart == null ? null : analysisStart.toString(),
                start >= 0 ? s.size() - start : 0,
                warnings);
    }

    private TrendReport.DataPanel emptyData(TrendConfig cfg, List<String> warnings) {
        return new TrendReport.DataPanel(cfg.primarySymbol(), null, null, 0,
                false, null, null, null, null, false,
                null, null, null, cfg.flatCashRatePct(), null, 0, warnings);
    }

    private static Backtester.Params params(Backtester.Execution exec, double outWeight,
                                            double bps, double[] cashDaily) {
        return new Backtester.Params(exec, outWeight, bps / 10_000.0, cashDaily);
    }

    private List<PriceBar> load(String symbol, LocalDate today) {
        return priceRepo.getPriceHistory(tickerMap.tickerFor(symbol), EPOCH, today);
    }

    // ---- fetch universe + backfill -------------------------------------------

    private Set<String> configuredSymbols(TrendConfig cfg) {
        Set<String> set = new LinkedHashSet<>();
        set.add(cfg.primarySymbol());
        if (cfg.spliceEnabled()) set.add(cfg.extensionSymbol());
        if (cfg.cashSeriesEnabled()) set.add(cfg.cashSymbol());
        return set;
    }

    /** Mirror the effective symbols to the KV set the nightly price job unions in. */
    private void syncFetchUniverse(TrendConfig cfg) {
        Set<String> desired = configuredSymbols(cfg);
        if (!desired.equals(kv.getStringSet(TREND_SYMBOLS_KEY))) {
            kv.putStringSet(TREND_SYMBOLS_KEY, desired);
        }
    }

    /**
     * Fetches deep daily history for any configured symbol with no rows yet. Intended to run off
     * the request thread. Returns the symbols actually fetched.
     */
    public List<String> ensureBackfilled(TrendConfig cfg) {
        syncFetchUniverse(cfg);
        List<String> fetched = new ArrayList<>();
        if (priceFetchJob == null) return fetched;
        for (String symbol : configuredSymbols(cfg)) {
            if (priceRepo.getEarliestPriceDate(tickerMap.tickerFor(symbol)) != null) continue;
            int rows = priceFetchJob.fetchSingle(symbol, DEEP_LOOKBACK_YEARS);
            log.info("Trend backfill: {} → {} rows", symbol, rows);
            if (rows > 0) fetched.add(symbol);
        }
        return fetched;
    }
}
