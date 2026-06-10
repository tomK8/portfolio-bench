package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.application.BenchmarkReturnService.BenchmarkTimeline;
import com.portfolio.application.PortfolioReturnService.ReturnPoint;
import com.portfolio.application.PortfolioValueService.DailyValue;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.PriceHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkReturnServiceTest {

    @TempDir
    Path dbDir;

    private static PriceBar bar(String symbol, String date, double adjClose) {
        return new PriceBar(symbol, LocalDate.parse(date), adjClose, adjClose, adjClose,
                adjClose, adjClose, 1.0, 1L, "USD");
    }

    private static DailyValue dv(String date, String value) {
        BigDecimal v = new BigDecimal(value);
        return new DailyValue(LocalDate.parse(date), v, v);
    }

    private BenchmarkReturnService service(List<DailyValue> values, List<PriceBar> bars) {
        PriceHistoryRepository priceRepo = new PriceHistoryRepository(new JdbcConnectionFactory(dbDir));
        if (!bars.isEmpty()) priceRepo.savePriceBars(bars);
        PortfolioValueService stub = new PortfolioValueService(null, null, null, null, null) {
            @Override
            public List<DailyValue> dailyValues() { return values; }
        };
        return new BenchmarkReturnService(stub, priceRepo, new YahooTickerMap());
    }

    @Test
    void emptyPortfolioYieldsEmpty() {
        BenchmarkTimeline t = service(List.of(), List.of()).timeline("EQQQ.L");
        assertTrue(t.growthPoints().isEmpty());
        assertFalse(t.missing(), "no portfolio inception → not a missing-data condition");
    }

    @Test
    void blankSymbolYieldsEmpty() {
        BenchmarkTimeline t = service(List.of(dv("2024-01-01", "100")), List.of()).timeline("  ");
        assertTrue(t.growthPoints().isEmpty());
        assertFalse(t.missing());
    }

    @Test
    void missingSymbolFlagsMissingTrue() {
        BenchmarkTimeline t = service(
                List.of(dv("2024-01-01", "100")),
                List.of(bar("OTHER", "2024-01-01", 10.0))
        ).timeline("EQQQ.L");
        assertTrue(t.growthPoints().isEmpty());
        assertTrue(t.missing(), "no rows for EQQQ.L → UI should prompt for backfill");
    }

    @Test
    void growthAnchorsAtOneAndScalesProportionally() {
        // Anchor at 100, next bar 110 → ×1.10.
        BenchmarkTimeline t = service(
                List.of(dv("2024-01-01", "1000")),
                List.of(
                        bar("QQQ", "2024-01-01", 100.0),
                        bar("QQQ", "2024-01-02", 110.0))
        ).timeline("QQQ");

        assertEquals(2, t.growthPoints().size());
        assertEquals(0, new BigDecimal("1.0000000000").compareTo(t.growthPoints().get(0).growth()));
        assertEquals(0, new BigDecimal("1.1000000000").compareTo(t.growthPoints().get(1).growth()));
        assertFalse(t.missing());
    }

    @Test
    void usesAdjCloseRatioCurrencyAgnostic() {
        // GBp listing — currency is irrelevant for the ratio, no FX conversion is applied.
        PriceBar a = new PriceBar("EQQQ.L", LocalDate.parse("2024-01-01"),
                500.0, 500.0, 500.0, 500.0, 500.0, 1.0, 1L, "GBp");
        PriceBar b = new PriceBar("EQQQ.L", LocalDate.parse("2024-01-02"),
                625.0, 625.0, 625.0, 625.0, 625.0, 1.0, 1L, "GBp");
        BenchmarkTimeline t = service(
                List.of(dv("2024-01-01", "1000")),
                List.of(a, b)
        ).timeline("EQQQ.L");

        assertEquals(2, t.growthPoints().size());
        assertEquals(0, new BigDecimal("1.2500000000").compareTo(t.growthPoints().get(1).growth()));
    }

    @Test
    void inceptionIsFirstPositivePortfolioValueDate() {
        // Leading zero-V day is skipped; benchmark window starts 2024-01-02.
        // QQQ has a bar on 2024-01-01 that should NOT be the anchor (it's before inception).
        BenchmarkTimeline t = service(
                List.of(dv("2024-01-01", "0"), dv("2024-01-02", "100"), dv("2024-01-03", "120")),
                List.of(
                        bar("QQQ", "2024-01-01", 200.0),  // pre-inception, must be filtered out
                        bar("QQQ", "2024-01-02", 100.0),
                        bar("QQQ", "2024-01-03", 120.0))
        ).timeline("QQQ");

        assertEquals(2, t.growthPoints().size(), "only bars on/after inception are included");
        assertEquals("2024-01-02", t.growthPoints().get(0).date());
        assertEquals(0, new BigDecimal("1.2000000000").compareTo(t.growthPoints().get(1).growth()));
    }

    @Test
    void sinceInceptionAnnualisesOverMultiYearWindow() {
        // Two bars ~5y apart, doubled → (2.0)^(365.25/days) − 1.
        BenchmarkTimeline t = service(
                List.of(dv("2020-01-01", "100")),
                List.of(
                        bar("QQQ", "2020-01-01", 100.0),
                        bar("QQQ", "2025-01-01", 200.0))
        ).timeline("QQQ");

        BigDecimal since = t.summary().sinceInception();
        assertNotNull(since);
        long days = LocalDate.parse("2020-01-01").until(LocalDate.parse("2025-01-01"),
                java.time.temporal.ChronoUnit.DAYS);
        double expected = Math.pow(2.0, 365.25 / days) - 1.0;
        assertEquals(expected, since.doubleValue(), 1e-4);
        assertNull(t.summary().trailing1y(), "no point within 1y window → null");
    }

    @Test
    void internalSymbolReadsByMappedYahooTicker() {
        // EQQQ → EQQQ.L per yahoo-tickers.properties; price_history is keyed by the Yahoo
        // ticker, so picking EQQQ from the dropdown must read bars stored under EQQQ.L.
        BenchmarkTimeline t = service(
                List.of(dv("2024-01-01", "100")),
                List.of(
                        bar("EQQQ.L", "2024-01-01", 500.0),
                        bar("EQQQ.L", "2024-01-02", 525.0))
        ).timeline("EQQQ");

        assertFalse(t.missing(), "EQQQ → EQQQ.L mapping should find the bars");
        assertEquals(2, t.growthPoints().size());
        assertEquals(0, new BigDecimal("1.0500000000").compareTo(t.growthPoints().get(1).growth()));
    }

    @Test
    void symbolIsNormalisedToUpperCase() {
        BenchmarkTimeline t = service(
                List.of(dv("2024-01-01", "100")),
                List.of(bar("QQQ", "2024-01-01", 100.0))
        ).timeline("qqq");
        assertEquals("QQQ", t.symbol());
        assertEquals(1, t.growthPoints().size());
    }
}
