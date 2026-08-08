package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.domain.swing.ForwardReturns;
import com.portfolio.domain.swing.SwingConfig;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SwingAnalysisServiceTest {

    @TempDir
    Path dbDir;

    private final YahooTickerMap tickers = new YahooTickerMap();

    private SwingAnalysisService service(PriceHistoryRepository priceRepo) {
        return new SwingAnalysisService(priceRepo, tickers, new KeyValueStore(dbDir), null);
    }

    // Deterministic 60-bar path ending near today; includes down days that trip the −5% trigger.
    private List<PriceBar> path(String symbol, double base) {
        double[] rets = {0.01, -0.02, 0.03, -0.06, 0.02, 0.01, -0.03, 0.04, -0.05, 0.02};
        List<PriceBar> bars = new ArrayList<>();
        LocalDate d = LocalDate.now().minusDays(60);
        double close = base;
        String ticker = tickers.tickerFor(symbol);
        for (int i = 0; i < 60; i++) {
            if (i > 0) close *= (1 + rets[i % rets.length]);
            bars.add(new PriceBar(ticker, d, null, null, null, close, close, 1.0, null, "USD"));
            d = d.plusDays(1);
        }
        return bars;
    }

    private SwingConfig cfg() {
        return SwingConfig.builder()
                .symbols(List.of("AAA", "BBB", "CCC"))
                .cluster(List.of("AAA", "BBB"))
                .build();
    }

    private SwingReport.SymbolReport find(SwingReport r, String sym) {
        return r.perSymbol().stream().filter(s -> s.symbol().equals(sym)).findFirst().orElseThrow();
    }

    @Test
    void reportCoversEverySymbolAndFlagsMissing() {
        var priceRepo = new PriceHistoryRepository(new JdbcConnectionFactory(dbDir));
        priceRepo.savePriceBars(path("AAA", 100));
        priceRepo.savePriceBars(path("BBB", 200));
        // CCC intentionally has no bars.

        SwingReport r = service(priceRepo).report(cfg());

        assertEquals(3, r.perSymbol().size());
        assertFalse(find(r, "AAA").missingData());
        assertFalse(find(r, "BBB").missingData());
        assertTrue(find(r, "CCC").missingData());
        assertNotNull(find(r, "AAA").full());
        assertFalse(find(r, "AAA").realizedVol().isEmpty());
    }

    @Test
    void pooledClusterConcatenatesObservations() {
        var priceRepo = new PriceHistoryRepository(new JdbcConnectionFactory(dbDir));
        priceRepo.savePriceBars(path("AAA", 100));
        priceRepo.savePriceBars(path("BBB", 200));

        SwingReport r = service(priceRepo).report(cfg());

        assertEquals("CLUSTER", r.pooled().symbol());
        assertFalse(r.pooled().missingData());
        assertEquals(2, r.pooled().barCount());          // two contributing members

        int aBase = find(r, "AAA").full().forward().baseline().get(0).n();
        int bBase = find(r, "BBB").full().forward().baseline().get(0).n();
        assertEquals(aBase + bBase, r.pooled().full().forward().baseline().get(0).n());

        ForwardReturns.ConditionalBlock aAbs = firstAbs(find(r, "AAA").full().forward());
        ForwardReturns.ConditionalBlock bAbs = firstAbs(find(r, "BBB").full().forward());
        ForwardReturns.ConditionalBlock pAbs = firstAbs(r.pooled().full().forward());
        assertEquals(aAbs.triggerDays() + bAbs.triggerDays(), pAbs.triggerDays());
        assertTrue(pAbs.triggerDays() > 0, "the −6%/−5% days should trip the −5% trigger");
    }

    @Test
    void correlationCoversTheCluster() {
        var priceRepo = new PriceHistoryRepository(new JdbcConnectionFactory(dbDir));
        priceRepo.savePriceBars(path("AAA", 100));
        priceRepo.savePriceBars(path("BBB", 200));

        SwingReport r = service(priceRepo).report(cfg());
        assertEquals(2, r.correlation().symbols().size());
        assertTrue(r.correlation().symbols().contains("AAA"));
    }

    @Test
    void regimeCarriesPlaceboAndBootstrapControls() {
        var priceRepo = new PriceHistoryRepository(new JdbcConnectionFactory(dbDir));
        priceRepo.savePriceBars(path("AAA", 100));

        SwingReport r = service(priceRepo).report(cfg());
        SwingReport.SymbolReport aaa = find(r, "AAA");

        // Every §6 time stop pairs the flush sim with its all-days placebo + bootstrap control.
        assertFalse(aaa.full().barriers().isEmpty());
        SwingReport.BarrierComparison cmp = aaa.full().barriers().get(0);
        assertNotNull(cmp.flush());
        assertNotNull(cmp.placebo());
        assertNotNull(cmp.bootstrap());
        assertEquals(0, cmp.placebo().open(), "all-days placebo never censors");
        // Reconciliation identity holds on the flush sim.
        var f = cmp.flush();
        assertEquals(f.events(), f.tpFirst() + f.slFirst() + f.timeCount() + f.ambiguous() + f.open());
    }

    @Test
    void shortHistoryFlaggedForRecentListing() {
        var priceRepo = new PriceHistoryRepository(new JdbcConnectionFactory(dbDir));
        priceRepo.savePriceBars(path("AAA", 100));   // only ~60 days of data, far short of 3y

        SwingReport.SymbolReport aaa = find(service(priceRepo).report(cfg()), "AAA");
        assertTrue(aaa.shortHistory(), "60 days of data is less than the requested 3-year window");
        assertEquals(3, aaa.requestedYears());
        assertTrue(aaa.yearsAvailable() < 1.0);
        assertNotNull(aaa.from());
    }

    @Test
    void pooledEpisodesNeverExceedEventCount() {
        var priceRepo = new PriceHistoryRepository(new JdbcConnectionFactory(dbDir));
        priceRepo.savePriceBars(path("AAA", 100));
        priceRepo.savePriceBars(path("BBB", 200));   // same calendar dates as AAA

        SwingReport r = service(priceRepo).report(cfg());
        ForwardReturns.ConditionalBlock pAbs = firstAbs(r.pooled().full().forward());
        assertTrue(pAbs.episodes() <= pAbs.triggerDays());
        // AAA and BBB share dates, so pooled episodes should be strictly below the summed count.
        assertTrue(pAbs.episodes() < pAbs.triggerDays() || pAbs.triggerDays() == 0);
    }

    @Test
    void symbolsAndClusterPersist() {
        var priceRepo = new PriceHistoryRepository(new JdbcConnectionFactory(dbDir));
        var svc = service(priceRepo);
        // First read seeds the spec defaults.
        assertEquals(SwingConfig.DEFAULT_SYMBOLS, svc.symbols());
        svc.setSymbols(List.of("nvda", "mu"));
        assertEquals(List.of("NVDA", "MU"), svc.symbols());   // normalised, uppercased
    }

    private static ForwardReturns.ConditionalBlock firstAbs(ForwardReturns.Result r) {
        return r.conditional().stream().filter(b -> b.kind().equals("abs")).findFirst().orElseThrow();
    }
}
