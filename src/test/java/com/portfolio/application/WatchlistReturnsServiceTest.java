package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.application.WatchlistService.ReturnsGrid;
import com.portfolio.application.WatchlistService.SymbolReturns;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WatchlistReturnsServiceTest {

    @TempDir
    Path dbDir;

    // Use dates within the 35-day lookback window; anchor to today so they're always recent.
    private static final LocalDate D1 = LocalDate.now().minusDays(10);
    private static final LocalDate D2 = LocalDate.now().minusDays(5);
    private static final LocalDate D3 = LocalDate.now().minusDays(2);

    private WatchlistService service() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        return new WatchlistService(
                new WatchlistRepository(cf, kv),
                new PriceHistoryRepository(cf),
                new IntradayPriceRepository(cf),
                new FundamentalsRepository(cf),
                new CashTransactionRepository(cf, kv),
                () -> Map.of(),
                new YahooTickerMap());
    }

    private static PriceBar bar(String sym, LocalDate date, double close) {
        return new PriceBar(sym, date, null, null, null, close, close, 1.0, null, "GBP");
    }

    @Test
    void emptyWatchlistYieldsEmptyGrid() {
        ReturnsGrid g = service().returns();
        assertTrue(g.dates().isEmpty());
        assertTrue(g.rows().isEmpty());
    }

    @Test
    void singleSymbolTwoClosesProduceOneReturn() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var wlRepo = new WatchlistRepository(cf, kv);
        wlRepo.add("AAPL", BigDecimal.ZERO, BigDecimal.ZERO);

        var priceRepo = new PriceHistoryRepository(cf);
        // D1 close=100, D2 close=110 → return = +10%
        priceRepo.savePriceBars(List.of(bar("AAPL", D1, 100.0), bar("AAPL", D2, 110.0)));

        var svc = new WatchlistService(wlRepo, priceRepo,
                new IntradayPriceRepository(cf), new FundamentalsRepository(cf),
                new CashTransactionRepository(cf, kv), () -> Map.of(), new YahooTickerMap());
        ReturnsGrid g = svc.returns();

        assertEquals(1, g.dates().size());
        assertEquals(D2.toString(), g.dates().get(0));
        assertEquals(1, g.rows().size());
        BigDecimal r = g.rows().get(0).returns().get(0);
        assertNotNull(r);
        // (110-100)/100 = 0.10000
        assertEquals(0, new BigDecimal("0.10000").compareTo(r));
    }

    @Test
    void negativeReturnComputedCorrectly() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var wlRepo = new WatchlistRepository(cf, kv);
        wlRepo.add("AAPL", BigDecimal.ZERO, BigDecimal.ZERO);

        var priceRepo = new PriceHistoryRepository(cf);
        // 200 → 180 = −10%
        priceRepo.savePriceBars(List.of(bar("AAPL", D1, 200.0), bar("AAPL", D2, 180.0)));

        var svc = new WatchlistService(wlRepo, priceRepo,
                new IntradayPriceRepository(cf), new FundamentalsRepository(cf),
                new CashTransactionRepository(cf, kv), () -> Map.of(), new YahooTickerMap());

        BigDecimal r = svc.returns().rows().get(0).returns().get(0);
        assertNotNull(r);
        assertTrue(r.compareTo(BigDecimal.ZERO) < 0);
        assertEquals(0, new BigDecimal("-0.10000").compareTo(r));
    }

    @Test
    void multipleReturnsAreSortedOldestFirst() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var wlRepo = new WatchlistRepository(cf, kv);
        wlRepo.add("AAPL", BigDecimal.ZERO, BigDecimal.ZERO);

        var priceRepo = new PriceHistoryRepository(cf);
        // D1=100, D2=110 (+10%), D3=99 (−10%)
        priceRepo.savePriceBars(List.of(
                bar("AAPL", D1, 100.0), bar("AAPL", D2, 110.0), bar("AAPL", D3, 99.0)));

        var svc = new WatchlistService(wlRepo, priceRepo,
                new IntradayPriceRepository(cf), new FundamentalsRepository(cf),
                new CashTransactionRepository(cf, kv), () -> Map.of(), new YahooTickerMap());
        ReturnsGrid g = svc.returns();

        assertEquals(2, g.dates().size());
        assertEquals(D2.toString(), g.dates().get(0)); // oldest first
        assertEquals(D3.toString(), g.dates().get(1));

        List<BigDecimal> returns = g.rows().get(0).returns();
        assertTrue(returns.get(0).compareTo(BigDecimal.ZERO) > 0, "D2 return positive");
        assertTrue(returns.get(1).compareTo(BigDecimal.ZERO) < 0, "D3 return negative");
    }

    @Test
    void missingDayForOneSymbolIsNull() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var wlRepo = new WatchlistRepository(cf, kv);
        wlRepo.add("AAPL", BigDecimal.ZERO, BigDecimal.ZERO);
        wlRepo.add("MSFT", BigDecimal.ZERO, BigDecimal.ZERO);

        var priceRepo = new PriceHistoryRepository(cf);
        // AAPL has D1+D2+D3; MSFT only has D1+D3 (no D2 close — e.g. different exchange holiday)
        priceRepo.savePriceBars(List.of(
                bar("AAPL", D1, 100.0), bar("AAPL", D2, 105.0), bar("AAPL", D3, 110.0),
                bar("MSFT", D1, 200.0),                          bar("MSFT", D3, 210.0)));

        var svc = new WatchlistService(wlRepo, priceRepo,
                new IntradayPriceRepository(cf), new FundamentalsRepository(cf),
                new CashTransactionRepository(cf, kv), () -> Map.of(), new YahooTickerMap());
        ReturnsGrid g = svc.returns();

        // D2 and D3 should both be in the shared date set
        assertTrue(g.dates().contains(D2.toString()));
        assertTrue(g.dates().contains(D3.toString()));

        // MSFT has no D2 close so the D2 return slot should be null
        SymbolReturns msft = g.rows().stream()
                .filter(r -> r.symbol().equals("MSFT")).findFirst().orElseThrow();
        int d2idx = g.dates().indexOf(D2.toString());
        assertNull(msft.returns().get(d2idx));

        // But MSFT D3 return (210−200)/200 is non-null (prior bar D1 is the base)
        int d3idx = g.dates().indexOf(D3.toString());
        assertNotNull(msft.returns().get(d3idx));
    }

    @Test
    void symbolWithNoPriceHistoryReturnsNullVector() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var wlRepo = new WatchlistRepository(cf, kv);
        wlRepo.add("AAPL", BigDecimal.ZERO, BigDecimal.ZERO);
        wlRepo.add("GHOST", BigDecimal.ZERO, BigDecimal.ZERO);

        var priceRepo = new PriceHistoryRepository(cf);
        priceRepo.savePriceBars(List.of(bar("AAPL", D1, 100.0), bar("AAPL", D2, 110.0)));

        var svc = new WatchlistService(wlRepo, priceRepo,
                new IntradayPriceRepository(cf), new FundamentalsRepository(cf),
                new CashTransactionRepository(cf, kv), () -> Map.of(), new YahooTickerMap());
        ReturnsGrid g = svc.returns();

        SymbolReturns ghost = g.rows().stream()
                .filter(r -> r.symbol().equals("GHOST")).findFirst().orElseThrow();
        // GHOST has no closes → all return slots are null
        assertTrue(ghost.returns().stream().allMatch(r -> r == null));
    }

    @Test
    void displayOrderOfSymbolsPreserved() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var wlRepo = new WatchlistRepository(cf, kv);
        // Add in a specific order
        wlRepo.add("MSFT", BigDecimal.ZERO, BigDecimal.ZERO);
        wlRepo.add("AAPL", BigDecimal.ZERO, BigDecimal.ZERO);
        wlRepo.add("NVDA", BigDecimal.ZERO, BigDecimal.ZERO);

        var priceRepo = new PriceHistoryRepository(cf);
        for (String sym : List.of("MSFT", "AAPL", "NVDA")) {
            priceRepo.savePriceBars(List.of(bar(sym, D1, 100.0), bar(sym, D2, 102.0)));
        }

        var svc = new WatchlistService(wlRepo, priceRepo,
                new IntradayPriceRepository(cf), new FundamentalsRepository(cf),
                new CashTransactionRepository(cf, kv), () -> Map.of(), new YahooTickerMap());
        ReturnsGrid g = svc.returns();

        List<String> symbols = g.rows().stream().map(SymbolReturns::symbol).toList();
        assertEquals(List.of("MSFT", "AAPL", "NVDA"), symbols);
    }
}
