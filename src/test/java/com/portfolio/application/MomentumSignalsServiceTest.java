package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.domain.momentum.MomentumConfig;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;

import static org.junit.jupiter.api.Assertions.*;

class MomentumSignalsServiceTest {

    @TempDir
    Path dbDir;

    private final YahooTickerMap tickers = new YahooTickerMap();

    /** ~3.6 years of daily bars ending today, price = fn(dayOrdinal). */
    private void save(PriceHistoryRepository repo, String symbol, IntToDoubleFunction fn) {
        List<PriceBar> bars = new ArrayList<>();
        LocalDate d = LocalDate.now().minusDays(1300);
        String ticker = tickers.tickerFor(symbol);
        for (int i = 0; i < 1300; i++) {
            double p = fn.applyAsDouble(i);
            bars.add(new PriceBar(ticker, d, null, null, null, p, p, 1.0, null, "USD"));
            d = d.plusDays(1);
        }
        repo.savePriceBars(bars);
    }

    private void seedPrices(PriceHistoryRepository repo) {
        // Wiggly enough that selections differ across signals and the V5 stop actually fires.
        save(repo, "WIN", i -> 100 * Math.pow(1.0009, i) * (1 + 0.2 * Math.sin(i / 23.0)));
        save(repo, "MID", i -> 100 * Math.pow(1.0004, i) * (1 + 0.2 * Math.sin(i / 31.0 + 1)));
        save(repo, "FLAT", i -> 100 + 5 * Math.cos(i / 17.0));
        save(repo, "LOSE", i -> 100 * Math.pow(0.9996, i) * (1 + 0.2 * Math.sin(i / 41.0 + 2)));
        save(repo, "QQQ", i -> 100 * Math.pow(1.0003, i));
    }

    private MomentumConfig cfg() {
        return MomentumConfig.builder()
                .universe(List.of("WIN", "MID", "FLAT", "LOSE"))
                .benchmarkSymbols(List.of("QQQ"))
                .regimeSymbol("QQQ")
                .topNs(new int[]{1, 2})
                .minEligibleFactor(1)
                .bootstrapIterations(20)
                .build();
    }

    @Test
    void reportAssemblesFiveVariantsWithVerdictsPredictionsAndEpisodes() {
        var repo = new PriceHistoryRepository(new JdbcConnectionFactory(dbDir));
        seedPrices(repo);
        var svc = new MomentumSignalsService(repo, tickers, new KeyValueStore(dbDir), null);

        MomentumSignalsReport r = svc.report(cfg(), null);

        assertTrue(r.warnings().isEmpty(), () -> "unexpected warnings: " + r.warnings());
        assertEquals(2, r.groups().size());
        for (MomentumSignalsReport.NGroup g : r.groups()) {
            assertEquals(List.of("V1", "V2", "V3", "V4", "V5"),
                    g.variants().stream().map(MomentumSignalsReport.VariantRow::key).toList());
            // V1 is the control: no verdict. Challengers all carry one.
            assertNull(g.variants().get(0).verdict());
            for (int i = 1; i < 5; i++) {
                String v = g.variants().get(i).verdict();
                assertNotNull(v);
                assertTrue(v.startsWith("ADOPT") || v.startsWith("REJECT"), v);
            }
            // Shared bootstrap: same distribution (same seed) under every variant at this N.
            long seed = g.variants().get(0).bootstrap().seed();
            for (var row : g.variants()) assertEquals(seed, row.bootstrap().seed());
            // V4 diagnostics present only on V4; V5 stop diagnostics only on V5.
            assertNotNull(g.variants().get(3).gateBindsTotal());
            assertNull(g.variants().get(0).gateBindsTotal());
            assertNotNull(g.variants().get(4).stopsByYear());
            assertNull(g.variants().get(1).stopsByYear());
        }

        // Pre-registration and context ship verbatim; §0 stays within its ten-sentence budget.
        assertEquals(3, r.preRegistration().size());
        assertTrue(r.contextHeader().contains("100th bootstrap percentile"));
        assertTrue(r.plainEnglish().size() <= 10);

        // Five predictions, each marked per N.
        assertEquals(5, r.predictions().size());
        for (var p : r.predictions()) assertEquals(2, p.marks().size());

        // Episode panel: 5 episodes × 5 variants at the middle N.
        assertEquals(2, r.episodeN());
        assertEquals(25, r.episodes().size());
    }

    /**
     * The V1 reproduction assertion at service level: the signals report's V1 numbers must equal
     * the incumbent Momentum tab's same-N variant exactly — same series, same windows, same
     * accounting path, bit-identical stats.
     */
    @Test
    void v1ReproducesIncumbentMomentumTabExactly() {
        var repo = new PriceHistoryRepository(new JdbcConnectionFactory(dbDir));
        seedPrices(repo);
        KeyValueStore kv = new KeyValueStore(dbDir);
        var signals = new MomentumSignalsService(repo, tickers, kv, null);
        var incumbent = new MomentumAnalysisService(repo, tickers, kv, null);

        MomentumConfig cfg = cfg();
        MomentumSignalsReport sr = signals.report(cfg, null);
        MomentumReport mr = incumbent.report(cfg);

        assertEquals(mr.fitFrom(), sr.fitFrom());
        assertEquals(mr.fitTo(), sr.fitTo());
        assertEquals(mr.holdoutFrom(), sr.holdoutFrom());

        for (int gi = 0; gi < sr.groups().size(); gi++) {
            MomentumSignalsReport.VariantRow v1 = sr.groups().get(gi).variants().get(0);
            MomentumReport.Variant tab = mr.variants().get(gi);
            assertEquals(tab.fit().stats(), v1.fit(), "fit stats must be bit-identical");
            assertEquals(tab.holdout(), v1.holdout(), "holdout stats must be bit-identical");
            assertEquals(tab.fit().bootstrap(), v1.bootstrap(), "same seed, same distribution");
            assertEquals(tab.log(), v1.log(), "same rebalances, same costs");
        }
    }

    /** Holdout separation: no month after the fit boundary may appear in any fit-section stats. */
    @Test
    void noPostCutoffDataReachesFitSections() {
        var repo = new PriceHistoryRepository(new JdbcConnectionFactory(dbDir));
        seedPrices(repo);
        var svc = new MomentumSignalsService(repo, tickers, new KeyValueStore(dbDir), null);

        MomentumSignalsReport r = svc.report(cfg(), null);
        assertTrue(r.holdoutAvailable());
        String fitTo = r.fitTo();
        String fitToMonth = fitTo.substring(0, 7);
        assertTrue(r.holdoutFrom().compareTo(fitTo) > 0);

        for (MomentumSignalsReport.NGroup g : r.groups()) {
            for (MomentumSignalsReport.VariantRow row : g.variants()) {
                for (String month : row.fit().months().keySet()) {
                    assertTrue(month.compareTo(fitToMonth) <= 0,
                            row.key() + " fit contains post-cutoff month " + month);
                }
                if (row.holdout() != null) {
                    for (String month : row.holdout().months().keySet()) {
                        assertTrue(month.compareTo(fitToMonth) >= 0,
                                row.key() + " holdout leaked pre-cutoff month " + month);
                    }
                }
                for (MomentumReport.RebalanceRow lr : row.log()) {
                    if ("fit".equals(lr.section())) {
                        assertTrue(lr.date().compareTo(fitTo) <= 0);
                    } else {
                        assertTrue(lr.date().compareTo(fitTo) > 0);
                    }
                }
            }
        }
    }
}
