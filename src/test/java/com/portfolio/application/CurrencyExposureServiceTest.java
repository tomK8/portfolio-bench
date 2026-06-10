package com.portfolio.application;

import com.portfolio.application.AllocationService.AllocationPoint;
import com.portfolio.application.CurrencyExposureService.TrendPoint;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrencyExposureServiceTest {

    @Test
    void trendBucketsSymbolGbpByCurrencyMap() {
        // Two symbols: one USD (NVDA), one GBP (LGEN). NVDA worth £6000, LGEN £4000.
        // Expected: USD = 60%, GBP = 40%.
        Map<String, String> ccyBySymbol = Map.of("NVDA", "USD", "LGEN", "GBP");
        List<AllocationPoint> points = List.of(
                point("2024-01-01", "0", "10000", "10000",
                        sym("NVDA", "6000"), sym("LGEN", "4000")));
        TrendPoint tp = CurrencyExposureService.computeTrend(points, ccyBySymbol).get(0);
        Map<String, BigDecimal> w = tp.weightByCurrency();
        assertEquals(0.6, w.get("USD").doubleValue(), 1e-6);
        assertEquals(0.4, w.get("GBP").doubleValue(), 1e-6);
    }

    @Test
    void weightsSumToOneAcrossCurrencies() {
        Map<String, String> ccyBySymbol = Map.of(
                "A", "USD", "B", "EUR", "C", "USD", "D", "GBP");
        List<AllocationPoint> points = List.of(
                point("2024-01-01", "0", "1000", "1000",
                        sym("A", "300"), sym("B", "200"),
                        sym("C", "400"), sym("D", "100")));
        TrendPoint tp = CurrencyExposureService.computeTrend(points, ccyBySymbol).get(0);
        Map<String, BigDecimal> w = tp.weightByCurrency();
        double sum = w.values().stream().mapToDouble(BigDecimal::doubleValue).sum();
        assertEquals(1.0, sum, 1e-6);
        // USD = (300+400)/1000 = 0.7
        assertEquals(0.7, w.get("USD").doubleValue(), 1e-6);
        assertEquals(0.2, w.get("EUR").doubleValue(), 1e-6);
        assertEquals(0.1, w.get("GBP").doubleValue(), 1e-6);
    }

    @Test
    void unknownSymbolDefaultsToGbp() {
        // Symbol UNKNOWN has no entry in the currency map → bucket as GBP.
        Map<String, String> ccyBySymbol = Map.of("USDSTOCK", "USD");
        List<AllocationPoint> points = List.of(
                point("2024-01-01", "0", "1000", "1000",
                        sym("USDSTOCK", "400"), sym("UNKNOWN", "600")));
        TrendPoint tp = CurrencyExposureService.computeTrend(points, ccyBySymbol).get(0);
        Map<String, BigDecimal> w = tp.weightByCurrency();
        assertEquals(0.6, w.get("GBP").doubleValue(), 1e-6);
        assertEquals(0.4, w.get("USD").doubleValue(), 1e-6);
    }

    @Test
    void zeroValueSymbolsAreSkipped() {
        Map<String, String> ccyBySymbol = Map.of("A", "USD", "B", "GBP", "C", "EUR");
        List<AllocationPoint> points = List.of(
                point("2024-01-01", "0", "1000", "1000",
                        sym("A", "500"), sym("B", "500"), sym("C", "0")));
        TrendPoint tp = CurrencyExposureService.computeTrend(points, ccyBySymbol).get(0);
        // C should not appear since its weight is zero.
        assertTrue(tp.weightByCurrency().get("EUR") == null
                        || tp.weightByCurrency().get("EUR").signum() == 0,
                "Zero-valued EUR position should not appear in the weight map");
        assertEquals(0.5, tp.weightByCurrency().get("USD").doubleValue(), 1e-6);
        assertEquals(0.5, tp.weightByCurrency().get("GBP").doubleValue(), 1e-6);
    }

    @Test
    void emptyInvestedReturnsEmptyWeights() {
        Map<String, String> ccyBySymbol = Map.of();
        List<AllocationPoint> points = List.of(
                point("2024-01-01", "5000", "0", "5000"));
        TrendPoint tp = CurrencyExposureService.computeTrend(points, ccyBySymbol).get(0);
        assertTrue(tp.weightByCurrency().isEmpty(),
                "No positions → empty weight map (cash isn't in the invested trend)");
    }

    @Test
    void trendCoversAllSamples() {
        Map<String, String> ccyBySymbol = Map.of("X", "USD", "Y", "GBP");
        List<AllocationPoint> points = List.of(
                point("2024-01-01", "1000", "0", "1000"),
                point("2024-01-08", "500", "500", "1000",
                        sym("X", "500")),
                point("2024-01-15", "0", "1000", "1000",
                        sym("X", "300"), sym("Y", "700")));
        List<TrendPoint> trend = CurrencyExposureService.computeTrend(points, ccyBySymbol);
        assertEquals(3, trend.size());
        assertTrue(trend.get(0).weightByCurrency().isEmpty());
        assertEquals(1.0, trend.get(1).weightByCurrency().get("USD").doubleValue(), 1e-6);
        assertEquals(0.3, trend.get(2).weightByCurrency().get("USD").doubleValue(), 1e-6);
        assertEquals(0.7, trend.get(2).weightByCurrency().get("GBP").doubleValue(), 1e-6);
    }

    // ---- Helpers ----

    private static AllocationPoint point(String date, String cash, String invested,
                                         String total, Map.Entry<String, BigDecimal>... symbols) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : symbols) m.put(e.getKey(), e.getValue());
        return new AllocationPoint(date,
                new BigDecimal(cash), new BigDecimal(invested),
                new BigDecimal(total), m);
    }

    private static Map.Entry<String, BigDecimal> sym(String name, String gbp) {
        return Map.entry(name, new BigDecimal(gbp));
    }
}
