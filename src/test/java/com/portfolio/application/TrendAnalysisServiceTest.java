package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.domain.trend.TrendConfig;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TrendAnalysisServiceTest {

    @TempDir
    Path dbDir;

    private final YahooTickerMap tickers = new YahooTickerMap();

    private PriceHistoryRepository repo() {
        return new PriceHistoryRepository(new JdbcConnectionFactory(dbDir));
    }

    private TrendAnalysisService service(PriceHistoryRepository repo, KeyValueStore kv) {
        return new TrendAnalysisService(repo, tickers, kv, null);
    }

    /** Weekday bars ending near today: drift up, a −1.5%/day crash leg, then recovery. */
    private List<PriceBar> path(String symbol, int n, LocalDate start, double base) {
        List<PriceBar> bars = new ArrayList<>();
        LocalDate d = start;
        double close = base;
        for (int i = 0; i < n; i++) {
            while (d.getDayOfWeek().getValue() > 5) d = d.plusDays(1);
            double ret = i < n / 2 ? 0.004 : (i < n / 2 + 40 ? -0.015 : 0.006);
            if (i > 0) close *= 1 + ret;
            bars.add(new PriceBar(symbol, d, close * 0.999, null, null, close, close * 0.98, 1.0, null, "USD"));
            d = d.plusDays(1);
        }
        return bars;
    }

    private List<PriceBar> flatRate(String symbol, LocalDate start, int n) {
        List<PriceBar> bars = new ArrayList<>();
        LocalDate d = start;
        for (int i = 0; i < n; i++) {
            while (d.getDayOfWeek().getValue() > 5) d = d.plusDays(1);
            bars.add(new PriceBar(symbol, d, null, null, null, 5.04, 5.04, 1.0, null, "USD"));
            d = d.plusDays(1);
        }
        return bars;
    }

    /** Small windows so ~300 synthetic bars exercise every engine. */
    private TrendConfig.Builder smallCfg(TrendAnalysisService svc) {
        return svc.baseConfigBuilder()
                .maLengths(new int[]{20, 30})
                .monthlyMaLengths(new int[]{2})
                .anchorEvery(5)
                .bufferPcts(new double[]{0.02})
                .bufferNs(new int[]{2})
                .bootstrapIterations(25);
    }

    @Test
    void reportBuildsTheFullGridWithSplicedAndCashSeries() {
        PriceHistoryRepository repo = repo();
        LocalDate primaryStart = LocalDate.now().minusDays(320);
        repo.savePriceBars(path("QQQ", 220, primaryStart, 100));
        repo.savePriceBars(path("^NDX", 100, primaryStart.minusDays(150), 4000));
        repo.savePriceBars(flatRate("^IRX", primaryStart.minusDays(150), 300));

        TrendAnalysisService svc = service(repo, new KeyValueStore(dbDir));
        TrendReport r = svc.report(smallCfg(svc).build());

        assertTrue(r.data().warnings().isEmpty(), "warnings: " + r.data().warnings());
        assertTrue(r.data().spliced());
        assertNotNull(r.data().spliceDate());
        assertNotNull(r.data().proxiedFrom());
        assertEquals("^IRX", r.data().cashSymbol());
        assertNotNull(r.data().analysisStart());

        // 2 MA lengths × (anchored + touch + 1 buffer% + 1 bufferN) + 1 monthly cell.
        assertEquals(9, r.variants().size());
        assertEquals(0, r.buyHold().switches());
        assertEquals(1.0, r.buyHold().timeInvested(), 1e-9);

        for (TrendReport.VariantResult v : r.variants()) {
            assertEquals(3, v.costs().size());
            assertNotNull(v.closeFill());
            assertNotNull(v.halfSwitch());
            assertNotNull(v.cashZero());
            assertEquals(v.headline().roundTrips(), v.whipsaw().roundTrips(),
                    v.key() + ": whipsaw and stats must agree on round trips");
            if (v.headline().switches() > 0) {
                assertNotNull(v.bootstrap(), v.key() + " exited but has no bootstrap");
                assertTrue(v.bootstrap().cagrPercentile() >= 0 && v.bootstrap().cagrPercentile() <= 100);
                assertFalse(v.trades().isEmpty());
            }
            assertFalse(v.annual().isEmpty());
            assertEquals(com.portfolio.domain.trend.Episodes.NAMED.size(), v.episodes().size());
            // Higher cost can never help: CAGR must be non-increasing across the cost sweep.
            for (int i = 1; i < v.costs().size(); i++) {
                assertTrue(v.costs().get(i).stats().cagr() <= v.costs().get(i - 1).stats().cagr() + 1e-12,
                        v.key() + ": cost row " + i + " beats a cheaper one");
            }
        }

        // The crash leg forces every variant below its MA at some point → someone must trade.
        assertTrue(r.variants().stream().anyMatch(v -> v.headline().switches() > 0));

        assertEquals(2, r.anchorSensitivity().size());
        assertEquals(5, r.anchorSensitivity().get(0).offsets().size());

        assertNotNull(r.liveState());
        assertEquals(2, r.liveState().primary().size());
        assertFalse(r.liveState().variants().isEmpty());
        assertTrue(r.liveState().holding().isEmpty(), "no EQQQ bars stored → empty holding readout");
    }

    @Test
    void missingPrimaryYieldsWarningsNotACrash() {
        TrendAnalysisService svc = service(repo(), new KeyValueStore(dbDir));
        TrendReport r = svc.report(smallCfg(svc).build());

        assertTrue(r.variants().isEmpty());
        assertNull(r.buyHold());
        assertTrue(r.data().warnings().stream().anyMatch(w -> w.contains("Backfill")));
    }

    @Test
    void missingExtensionWarnsAndProceedsUnspliced() {
        PriceHistoryRepository repo = repo();
        repo.savePriceBars(path("QQQ", 220, LocalDate.now().minusDays(320), 100));

        TrendAnalysisService svc = service(repo, new KeyValueStore(dbDir));
        TrendReport r = svc.report(smallCfg(svc).build());

        assertFalse(r.data().spliced());
        assertFalse(r.variants().isEmpty(), "analysis proceeds without the splice");
        assertTrue(r.data().warnings().stream().anyMatch(w -> w.contains("^NDX")));
        assertTrue(r.data().warnings().stream().anyMatch(w -> w.contains("^IRX")));
    }

    @Test
    void fetchUniverseMirrorsTheEffectiveSymbols() {
        KeyValueStore kv = new KeyValueStore(dbDir);
        TrendAnalysisService svc = service(repo(), kv);

        assertEquals(Set.of("QQQ", "^NDX", "^IRX"), kv.getStringSet(TrendAnalysisService.TREND_SYMBOLS_KEY),
                "constructor seeds the defaults for the nightly job");

        svc.report(smallCfg(svc).extensionSymbol("").build());
        assertEquals(Set.of("QQQ", "^IRX"), kv.getStringSet(TrendAnalysisService.TREND_SYMBOLS_KEY),
                "disabling the splice drops the extension from the fetch set");
    }
}
