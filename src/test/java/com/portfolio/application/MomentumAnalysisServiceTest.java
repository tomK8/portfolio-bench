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

class MomentumAnalysisServiceTest {

    @TempDir
    Path dbDir;

    private final YahooTickerMap tickers = new YahooTickerMap();

    private MomentumAnalysisService service(PriceHistoryRepository priceRepo) {
        return new MomentumAnalysisService(priceRepo, tickers, new KeyValueStore(dbDir), null);
    }

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

    private MomentumConfig cfg() {
        return MomentumConfig.builder()
                .universe(List.of("WIN", "MID", "FLAT", "LOSE", "GHOST"))
                .benchmarkSymbols(List.of("QQQ"))
                .regimeSymbol("QQQ")
                .topNs(new int[]{1, 2})
                .minEligibleFactor(1)
                .bootstrapIterations(20)
                .build();
    }

    private void seedPrices(PriceHistoryRepository repo) {
        save(repo, "WIN", i -> 100 * Math.pow(1.0009, i));
        save(repo, "MID", i -> 100 * Math.pow(1.0004, i));
        save(repo, "FLAT", i -> 100.0);
        save(repo, "LOSE", i -> 100 * Math.pow(0.9996, i));
        save(repo, "QQQ", i -> 100 * Math.pow(1.0003, i));
        // GHOST intentionally has no bars.
    }

    @Test
    void reportAssemblesVariantsControlsAndHoldout() {
        var repo = new PriceHistoryRepository(new JdbcConnectionFactory(dbDir));
        seedPrices(repo);
        var svc = service(repo);

        MomentumReport r = svc.report(cfg());

        assertTrue(r.warnings().isEmpty(), () -> "unexpected warnings: " + r.warnings());
        assertEquals(2, r.variants().size());
        assertTrue(r.holdoutAvailable());
        assertNotNull(r.analysisStart());
        assertNotNull(r.fitFrom());
        assertNotNull(r.holdoutFrom());

        // GHOST (no data) flagged missing, never silently dropped.
        assertTrue(r.missingSymbols().contains("GHOST"));

        // Survivorship caveat present in the header.
        assertTrue(r.survivorshipCaveat().toLowerCase().contains("survivorship"));

        // Baseline and both benchmarks assembled.
        assertNotNull(r.baselineFit());
        assertEquals(1, r.benchmarks().size());
        assertEquals("QQQ", r.benchmarks().get(0).symbol());

        // Bootstrap ran on the fit window with the reported seed.
        var top1 = r.variants().get(0);
        assertEquals(1, top1.n());
        assertNotNull(top1.fit().bootstrap());
        assertEquals(cfg().randomSeed() + 1, top1.fit().bootstrap().seed());
        assertEquals(cfg().randomSeed(), r.bootstrapSeed());
    }

    @Test
    void reversedSignalUnderperformsTopSignal() {
        var repo = new PriceHistoryRepository(new JdbcConnectionFactory(dbDir));
        seedPrices(repo);
        var svc = service(repo);

        MomentumReport r = svc.report(cfg());

        // Sign-convention sanity: worst-momentum (LOSE) must not beat best-momentum (WIN).
        var top1 = r.variants().get(0);
        assertNotNull(top1.bottomNFitCagr());
        assertTrue(top1.fit().stats().cagr() > top1.bottomNFitCagr(),
                "top-N CAGR (" + top1.fit().stats().cagr() + ") should beat bottom-N ("
                        + top1.bottomNFitCagr() + ")");

        // The top single name should be the strongest trender.
        var lastRebalance = top1.log().get(top1.log().size() - 1);
        assertTrue(lastRebalance.heldRanks().get(0).startsWith("WIN"));
    }

    @Test
    void savingUniverseSeparatesRankedNamesFromFetchSet() {
        var repo = new PriceHistoryRepository(new JdbcConnectionFactory(dbDir));
        var svc = service(repo);

        svc.saveUniverse(List.of("aapl", "msft", "aapl"));   // lower-case + dup
        assertEquals(List.of("AAPL", "MSFT"), svc.universe());   // benchmarks not mixed in
    }
}
