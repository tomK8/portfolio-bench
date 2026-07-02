package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.domain.swing.Flushes;
import com.portfolio.domain.swing.ForwardReturns;
import com.portfolio.domain.swing.RealizedVol;
import com.portfolio.domain.swing.ReturnCorrelation;
import com.portfolio.domain.swing.SwingConfig;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Orchestrates the swing-strategy validation report: resolves the configurable symbol universe and
 * cluster, loads daily bars from {@code price_history}, builds one {@link com.portfolio.domain.swing.SwingSeries}
 * per symbol and runs the pure engines over both §9 regimes (full history + trailing 6 months) for
 * each symbol and for the pooled cluster, then adds §7 correlation.
 *
 * <p>The universe + cluster persist in {@link KeyValueStore} ({@link #SWING_SYMBOLS_KEY} /
 * {@link #SWING_CLUSTER_KEY}), seeded from the spec defaults on first use. Because
 * {@code PriceFetchSupport.tickersToFetch} unions {@link #SWING_SYMBOLS_KEY}, the nightly 30-year
 * price job automatically keeps the swing universe backfilled; {@link #ensureBackfilled} covers the
 * first-run gap on demand.
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

    public SwingAnalysisService(PriceHistoryRepository priceRepo, YahooTickerMap tickerMap,
                                KeyValueStore kv, PriceFetchJob priceFetchJob) {
        this.priceRepo = priceRepo;
        this.tickerMap = tickerMap;
        this.kv = kv;
        this.priceFetchJob = priceFetchJob;
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

        List<SwingReport.SymbolReport> perSymbol = new ArrayList<>();
        List<com.portfolio.domain.swing.SwingSeries> clusterSeries = new ArrayList<>();
        Set<String> clusterSet = new LinkedHashSet<>(cfg.cluster());

        for (String symbol : cfg.symbols()) {
            var series = loadSeries(symbol, loadFrom, today, cfg.sigmaWindow());
            if (series == null) {
                perSymbol.add(SwingReport.SymbolReport.missing(symbol));
                continue;
            }
            List<com.portfolio.domain.swing.SwingSeries> single = List.of(series);
            perSymbol.add(new SwingReport.SymbolReport(
                    symbol, false, series.size(),
                    series.firstDate().toString(), series.lastDate().toString(),
                    RealizedVol.compute(series, cfg.volWindows()),
                    regime("full", single, fullCutoff, cfg),
                    regime("6m", single, sixMonthCutoff, cfg)));
            if (clusterSet.contains(symbol)) clusterSeries.add(series);
        }

        SwingReport.SymbolReport pooled = new SwingReport.SymbolReport(
                "CLUSTER", clusterSeries.isEmpty(), clusterSeries.size(), null, null,
                pooledVol(perSymbol, clusterSet, cfg),
                regime("full", clusterSeries, fullCutoff, cfg),
                regime("6m", clusterSeries, sixMonthCutoff, cfg));

        ReturnCorrelation.Result correlation = ReturnCorrelation.compute(clusterSeries, cfg);

        return new SwingReport(cfg, perSymbol, pooled, correlation, null);
    }

    private SwingReport.Regime regime(String label, List<com.portfolio.domain.swing.SwingSeries> series,
                                      LocalDate cutoff, SwingConfig cfg) {
        return new SwingReport.Regime(label,
                ForwardReturns.compute(series, cutoff, cfg),
                Flushes.profiles(series, cutoff, cfg),
                Flushes.barriers(series, cutoff, cfg));
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

    private com.portfolio.domain.swing.SwingSeries loadSeries(String symbol, LocalDate from,
                                                              LocalDate to, int sigmaWindow) {
        String ticker = Instruments.isBond(symbol) ? symbol : tickerMap.tickerFor(symbol);
        List<PriceBar> bars = priceRepo.getPriceHistory(ticker, from, to);
        return com.portfolio.domain.swing.SwingSeries.of(symbol, bars, sigmaWindow);
    }

    // ---- backfill -----------------------------------------------------------

    /**
     * Fetches daily history for any universe/cluster symbol with no rows in {@code price_history}
     * yet (freshly added, or a default the nightly job hasn't reached). Returns the symbols that
     * were fetched. Intended to run off the request thread.
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
        return fetched;
    }
}
