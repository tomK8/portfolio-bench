package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.domain.swing.AnnualBreakdown;
import com.portfolio.domain.swing.EarningsLookup;
import com.portfolio.domain.swing.EventFilter;
import com.portfolio.domain.swing.FlushEvents;
import com.portfolio.domain.swing.FlushTrigger;
import com.portfolio.domain.swing.Flushes;
import com.portfolio.domain.swing.ForwardReturns;
import com.portfolio.domain.swing.MaSplit;
import com.portfolio.domain.swing.Placebo;
import com.portfolio.domain.swing.RealizedVol;
import com.portfolio.domain.swing.ReturnCorrelation;
import com.portfolio.domain.swing.SwingConfig;
import com.portfolio.domain.swing.SwingSeries;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates the swing-strategy validation report: resolves the configurable symbol universe and
 * cluster, loads daily bars from {@code price_history}, builds one {@link SwingSeries} per symbol and
 * runs the pure engines over both §9 regimes (full history + trailing 6 months) for each symbol and
 * for the pooled cluster. Each regime runs the whole §5/§6/§A/§B/§E machinery for two trigger families
 * (absolute + σ-scaled, §C), each in "all" and "ex-earnings" variants (§D), plus §7 correlation and a
 * §D coverage summary.
 *
 * <p>The universe + cluster persist in {@link KeyValueStore} ({@link #SWING_SYMBOLS_KEY} /
 * {@link #SWING_CLUSTER_KEY}), seeded from the spec defaults on first use. Because
 * {@code PriceFetchSupport.tickersToFetch} unions {@link #SWING_SYMBOLS_KEY}, the nightly 30-year
 * price job automatically keeps the swing universe backfilled; {@link #ensureBackfilled} covers the
 * first-run gap on demand and also warms the §D earnings cache.
 *
 * <p>No FX is involved — all statistics are unit-less return ratios off each symbol's own
 * {@code close}, so a {@code .L} pence listing needs no conversion.
 */
public class SwingAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(SwingAnalysisService.class);

    public static final String SWING_SYMBOLS_KEY = "swing_symbols";
    public static final String SWING_CLUSTER_KEY = "swing_cluster";

    /** Extra history loaded before the analysis window so the trailing σ has context at its start. */
    private static final int LOOKBACK_BUFFER_DAYS = 150;

    private final PriceHistoryRepository priceRepo;
    private final YahooTickerMap tickerMap;
    private final KeyValueStore kv;
    private final PriceFetchJob priceFetchJob;
    private final EarningsService earningsService;

    public SwingAnalysisService(PriceHistoryRepository priceRepo, YahooTickerMap tickerMap,
                                KeyValueStore kv, PriceFetchJob priceFetchJob,
                                EarningsService earningsService) {
        this.priceRepo = priceRepo;
        this.tickerMap = tickerMap;
        this.kv = kv;
        this.priceFetchJob = priceFetchJob;
        this.earningsService = earningsService;
    }

    // ---- configuration ------------------------------------------------------

    /** Persisted (or seeded-default) symbol universe. */
    public List<String> symbols() {
        return persistedOrSeed(SWING_SYMBOLS_KEY, SwingConfig.DEFAULT_SYMBOLS);
    }

    /** Persisted (or seeded-default) cluster subset. */
    public List<String> cluster() {
        return persistedOrSeed(SWING_CLUSTER_KEY, SwingConfig.DEFAULT_CLUSTER);
    }

    /** Config seeded from the persisted universe/cluster; the web layer overrides any other knob. */
    public SwingConfig.Builder baseConfigBuilder() {
        return SwingConfig.builder().symbols(symbols()).cluster(cluster());
    }

    public void setSymbols(List<String> syms) {
        kv.putStringSet(SWING_SYMBOLS_KEY, normalise(syms));
    }

    public void setCluster(List<String> clus) {
        kv.putStringSet(SWING_CLUSTER_KEY, normalise(clus));
    }

    private List<String> persistedOrSeed(String key, List<String> defaults) {
        Set<String> stored = kv.getStringSet(key);
        if (stored.isEmpty()) {
            kv.putStringSet(key, defaults);              // seed once so the fetch universe picks it up
            return new ArrayList<>(defaults);
        }
        return new ArrayList<>(stored);
    }

    private static List<String> normalise(List<String> in) {
        Set<String> out = new LinkedHashSet<>();
        if (in != null) {
            for (String s : in) {
                if (s == null) continue;
                String t = s.trim().toUpperCase();
                if (!t.isEmpty()) out.add(t);
            }
        }
        return new ArrayList<>(out);
    }

    // ---- report -------------------------------------------------------------

    public SwingReport report(SwingConfig cfg) {
        LocalDate today = LocalDate.now();
        LocalDate loadFrom = today.minusYears(cfg.years()).minusDays(LOOKBACK_BUFFER_DAYS);
        LocalDate fullCutoff = today.minusYears(cfg.years());
        LocalDate sixMonthCutoff = today.minusMonths(6);

        // §D: warm the earnings cache once (best-effort) before building per-symbol lookups.
        if (earningsService != null) {
            try {
                earningsService.ensureEarnings(cfg.symbols());
            } catch (RuntimeException e) {
                log.warn("Earnings cache warm failed", e);
            }
        }

        List<SwingReport.SymbolReport> perSymbol = new ArrayList<>();
        List<SwingSeries> clusterSeries = new ArrayList<>();
        Set<String> clusterSet = new LinkedHashSet<>(cfg.cluster());
        Map<String, EarningsLookup> earnings = new HashMap<>();

        for (String symbol : cfg.symbols()) {
            var series = loadSeries(symbol, loadFrom, today, cfg);
            if (series == null) {
                perSymbol.add(SwingReport.SymbolReport.missing(symbol, cfg.years()));
                continue;
            }
            earnings.put(symbol, earningsService == null
                    ? EarningsLookup.UNVERIFIED : earningsService.lookupFor(series, cfg));
            List<SwingSeries> single = List.of(series);
            double yearsAvailable = ChronoUnit.DAYS.between(series.firstDate(), series.lastDate()) / 365.25;
            boolean shortHistory = series.firstDate().isAfter(fullCutoff);   // data starts after the full-history cutoff
            perSymbol.add(new SwingReport.SymbolReport(
                    symbol, false, series.size(),
                    series.firstDate().toString(), series.lastDate().toString(),
                    yearsAvailable, shortHistory, cfg.years(),
                    RealizedVol.compute(series, cfg.volWindows()),
                    regime("full", single, fullCutoff, cfg, earnings),
                    regime("6m", single, sixMonthCutoff, cfg, earnings)));
            if (clusterSet.contains(symbol)) clusterSeries.add(series);
        }

        SwingReport.SymbolReport pooled = new SwingReport.SymbolReport(
                "CLUSTER", clusterSeries.isEmpty(), clusterSeries.size(), null, null,
                0, false, cfg.years(),
                pooledVol(perSymbol, clusterSet, cfg),
                regime("full", clusterSeries, fullCutoff, cfg, earnings),
                regime("6m", clusterSeries, sixMonthCutoff, cfg, earnings));

        ReturnCorrelation.Result correlation = ReturnCorrelation.compute(clusterSeries, cfg);

        return new SwingReport(cfg, perSymbol, pooled, correlation, earningsHygiene(cfg));
    }

    private SwingReport.Regime regime(String label, List<SwingSeries> series,
                                      LocalDate cutoff, SwingConfig cfg, Map<String, EarningsLookup> earnings) {
        List<SwingReport.FlushFamily> families = new ArrayList<>();
        families.add(family("Absolute", "abs", series, cutoff, cfg, earnings,
                Flushes.absoluteFlushTriggers(cfg), Flushes.absoluteBarrierTrigger(cfg)));
        families.add(family("σ-scaled", "sigma", series, cutoff, cfg, earnings,
                Flushes.sigmaFlushTriggers(cfg), Flushes.sigmaBarrierTrigger(cfg)));
        int overlap = Flushes.overlapCount(series, cutoff,
                Flushes.absoluteBarrierTrigger(cfg), Flushes.sigmaBarrierTrigger(cfg));
        return new SwingReport.Regime(label, cutoff == null ? null : cutoff.toString(),
                ForwardReturns.compute(series, cutoff, cfg), families, overlap);
    }

    private SwingReport.FlushFamily family(String label, String kind, List<SwingSeries> series,
                                           LocalDate cutoff, SwingConfig cfg, Map<String, EarningsLookup> earnings,
                                           List<FlushTrigger> flushTriggers, FlushTrigger barrierTrigger) {
        EventFilter exclude = earningsExclusion(series, earnings);
        boolean verified = allVerified(series, earnings);

        List<SwingReport.EarningsVariant> variants = new ArrayList<>();
        variants.add(new SwingReport.EarningsVariant("all",
                Flushes.profiles(series, cutoff, cfg, flushTriggers, null),
                barrierComparisons(series, cutoff, cfg, barrierTrigger, null)));
        variants.add(new SwingReport.EarningsVariant("ex-earnings",
                Flushes.profiles(series, cutoff, cfg, flushTriggers, exclude),
                barrierComparisons(series, cutoff, cfg, barrierTrigger, exclude)));

        return new SwingReport.FlushFamily(label, kind, verified, variants,
                FlushEvents.log(series, cutoff, cfg, barrierTrigger, earnings),
                AnnualBreakdown.annual(series, cutoff, cfg, barrierTrigger),
                MaSplit.split(series, cutoff, cfg, barrierTrigger));
    }

    private List<SwingReport.BarrierComparison> barrierComparisons(List<SwingSeries> series, LocalDate cutoff,
                                                                   SwingConfig cfg, FlushTrigger trig, EventFilter exclude) {
        List<SwingReport.BarrierComparison> out = new ArrayList<>();
        for (Flushes.BarrierResult flush : Flushes.barriers(series, cutoff, cfg, trig, exclude)) {
            int ts = flush.timeStop();
            Flushes.BarrierResult placebo = Placebo.allDays(series, cutoff, cfg, ts, exclude);
            Double actualTp = flush.closed() == 0 ? null : flush.pctTp();
            Placebo.Bootstrap bootstrap =
                    Placebo.bootstrap(series, cutoff, cfg, ts, flush.events(), actualTp, flush.meanReturn(), exclude);
            out.add(new SwingReport.BarrierComparison(ts, flush, placebo, bootstrap));
        }
        return out;
    }

    /** Per-{@code (symbol,date)} filter that removes bars within ±window of an earnings date (§D). */
    private EventFilter earningsExclusion(List<SwingSeries> series, Map<String, EarningsLookup> earnings) {
        Map<String, Set<LocalDate>> bySymbol = new HashMap<>();
        for (SwingSeries s : series) {
            EarningsLookup el = earnings.getOrDefault(s.symbol(), EarningsLookup.UNVERIFIED);
            if (!el.verified()) continue;
            Set<LocalDate> dates = new HashSet<>();
            for (int i = 0; i < s.size(); i++) if (el.at(i).withinWindow()) dates.add(s.dateAt(i));
            if (!dates.isEmpty()) bySymbol.put(s.symbol(), dates);
        }
        if (bySymbol.isEmpty()) return (sym, d) -> false;
        return (sym, d) -> {
            Set<LocalDate> set = bySymbol.get(sym);
            return set != null && set.contains(d);
        };
    }

    /** True only when every series in the list has a verified earnings source (pooled = all members). */
    private boolean allVerified(List<SwingSeries> series, Map<String, EarningsLookup> earnings) {
        if (series.isEmpty()) return false;
        for (SwingSeries s : series) {
            if (!earnings.getOrDefault(s.symbol(), EarningsLookup.UNVERIFIED).verified()) return false;
        }
        return true;
    }

    private SwingReport.EarningsHygiene earningsHygiene(SwingConfig cfg) {
        String source = earningsService == null ? "none" : earningsService.sourceName();
        String note = "Earnings dates are proxied by SEC 10-Q/10-K filing dates (within a few days of the "
                + "actual release), US filers only. Symbols with no SEC source (e.g. ASML, SEMI.L) are "
                + "flagged unverified and never treated as clean.";
        List<SwingReport.EarningsHygiene.Coverage> cov = new ArrayList<>();
        for (String symbol : cfg.symbols()) {
            boolean verified = earningsService != null && earningsService.isVerifiable(symbol);
            List<LocalDate> dates = verified ? earningsService.datesFor(symbol) : List.of();
            String from = dates.isEmpty() ? null : dates.get(0).toString();
            String to = dates.isEmpty() ? null : dates.get(dates.size() - 1).toString();
            cov.add(new SwingReport.EarningsHygiene.Coverage(symbol, verified, dates.size(), from, to));
        }
        return new SwingReport.EarningsHygiene(source, note, cov);
    }

    /** Pooled §1: cross-sectional mean of the cluster members' per-window realized vol. */
    private List<RealizedVol.VolStat> pooledVol(List<SwingReport.SymbolReport> perSymbol,
                                                Set<String> clusterSet, SwingConfig cfg) {
        List<RealizedVol.VolStat> out = new ArrayList<>();
        for (int wi = 0; wi < cfg.volWindows().length; wi++) {
            int window = cfg.volWindows()[wi];
            double sumDaily = 0, sumAnnual = 0;
            int members = 0;
            for (SwingReport.SymbolReport r : perSymbol) {
                if (r.missingData() || !clusterSet.contains(r.symbol())) continue;
                if (wi >= r.realizedVol().size()) continue;
                RealizedVol.VolStat v = r.realizedVol().get(wi);
                if (v.annualSigma() == null || v.dailySigma() == null) continue;
                sumDaily += v.dailySigma();
                sumAnnual += v.annualSigma();
                members++;
            }
            if (members == 0) {
                out.add(new RealizedVol.VolStat(window, null, null, 0, true));
            } else {
                out.add(new RealizedVol.VolStat(window, sumDaily / members, sumAnnual / members,
                        members, members < 2));
            }
        }
        return out;
    }

    private SwingSeries loadSeries(String symbol, LocalDate from, LocalDate to, SwingConfig cfg) {
        String ticker = Instruments.isBond(symbol) ? symbol : tickerMap.tickerFor(symbol);
        List<PriceBar> bars = priceRepo.getPriceHistory(ticker, from, to);
        return SwingSeries.of(symbol, bars, cfg.sigmaWindow(), cfg.maWindow());
    }

    // ---- backfill -----------------------------------------------------------

    /**
     * Fetches daily history for any universe/cluster symbol with no rows in {@code price_history}
     * yet (freshly added, or a default the nightly job hasn't reached) and force-warms the §D
     * earnings cache (refreshes the CIK registry and retries even previously-attempted names).
     * Returns the symbols whose prices were fetched. Intended to run off the request thread.
     */
    public List<String> ensureBackfilled(SwingConfig cfg) {
        Set<String> universe = new LinkedHashSet<>(cfg.symbols());
        universe.addAll(cfg.cluster());
        List<String> fetched = new ArrayList<>();
        for (String symbol : universe) {
            if (Instruments.isBond(symbol)) continue;
            String ticker = tickerMap.tickerFor(symbol);
            if (priceRepo.getEarliestPriceDate(ticker) != null) continue;   // already have history
            int rows = priceFetchJob.fetchSingle(symbol);
            log.info("Swing backfill: {} → {} rows", symbol, rows);
            if (rows > 0) fetched.add(symbol);
        }
        if (earningsService != null) {
            try {
                earningsService.ensureEarnings(universe, /*force*/ true);
            } catch (RuntimeException e) {
                log.warn("Swing earnings backfill failed", e);
            }
        }
        return fetched;
    }
}
