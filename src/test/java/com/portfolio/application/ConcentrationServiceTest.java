package com.portfolio.application;

import com.portfolio.application.AllocationService.AllocationPoint;
import com.portfolio.application.ConcentrationService.TrendPoint;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcentrationServiceTest {

    @Test
    void equalWeightFivePositionsGivesHhiOneFifth() {
        // Five equal positions of £100 each in a £500 invested book.
        // HHI = 5 × (0.2)² = 0.2. Effective N = 5.
        List<AllocationPoint> points = List.of(
                point("2024-01-01", "100", "500", "500",
                        symbol("A", "100"), symbol("B", "100"),
                        symbol("C", "100"), symbol("D", "100"),
                        symbol("E", "100")));
        List<TrendPoint> trend = ConcentrationService.computeTrend(points);
        TrendPoint tp = trend.get(0);
        assertEquals(0.2, tp.hhi().doubleValue(), 1e-6);
        assertEquals(5.0, tp.effectiveN().doubleValue(), 1e-3);
        assertEquals(5, tp.positionCount());
    }

    @Test
    void singlePositionGivesHhiOne() {
        List<AllocationPoint> points = List.of(
                point("2024-01-01", "0", "1000", "1000",
                        symbol("X", "1000")));
        TrendPoint tp = ConcentrationService.computeTrend(points).get(0);
        assertEquals(1.0, tp.hhi().doubleValue(), 1e-6);
        assertEquals(1.0, tp.effectiveN().doubleValue(), 1e-3);
        assertEquals(1, tp.positionCount());
    }

    @Test
    void unevenSplitMatchesFormula() {
        // 60/40 → HHI = 0.36 + 0.16 = 0.52. Effective N = 1/0.52 ≈ 1.923.
        List<AllocationPoint> points = List.of(
                point("2024-01-01", "0", "1000", "1000",
                        symbol("A", "600"), symbol("B", "400")));
        TrendPoint tp = ConcentrationService.computeTrend(points).get(0);
        assertEquals(0.52, tp.hhi().doubleValue(), 1e-6);
        assertEquals(1.0 / 0.52, tp.effectiveN().doubleValue(), 1e-3);
    }

    @Test
    void cashIsExcludedFromHhiBase() {
        // £400 cash + £600 invested split 50/50 → HHI uses invested base only.
        // Two positions of £300 each → weight 0.5, HHI = 0.5. NOT 0.18 (the wrong
        // computation that would include cash dragging weights toward 0).
        List<AllocationPoint> points = List.of(
                point("2024-01-01", "400", "600", "1000",
                        symbol("A", "300"), symbol("B", "300")));
        TrendPoint tp = ConcentrationService.computeTrend(points).get(0);
        assertEquals(0.5, tp.hhi().doubleValue(), 1e-6);
    }

    @Test
    void emptyInvestedReturnsZeroEffectiveNull() {
        // All-cash day, before first buy. HHI = 0, effective N = null (would be 1/0).
        List<AllocationPoint> points = List.of(
                point("2024-01-01", "5000", "0", "5000"));
        TrendPoint tp = ConcentrationService.computeTrend(points).get(0);
        assertEquals(0, tp.hhi().compareTo(BigDecimal.ZERO));
        assertNull(tp.effectiveN());
        assertEquals(0, tp.positionCount());
    }

    @Test
    void zeroValueSymbolsAreNotCountedAsPositions() {
        // A symbol that was held but currently sits at zero GBP shouldn't count
        // toward positionCount or contribute to HHI.
        List<AllocationPoint> points = List.of(
                point("2024-01-01", "0", "1000", "1000",
                        symbol("REAL", "1000"), symbol("ZERO", "0")));
        TrendPoint tp = ConcentrationService.computeTrend(points).get(0);
        assertEquals(1.0, tp.hhi().doubleValue(), 1e-6);
        assertEquals(1, tp.positionCount());
    }

    @Test
    void trendCoversAllPoints() {
        // 3 weekly samples → 3 trend points, even when invested is zero in the first.
        List<AllocationPoint> points = List.of(
                point("2024-01-01", "1000", "0", "1000"),
                point("2024-01-08", "500", "500", "1000",
                        symbol("X", "500")),
                point("2024-01-15", "0", "1000", "1000",
                        symbol("X", "600"), symbol("Y", "400")));
        List<TrendPoint> trend = ConcentrationService.computeTrend(points);
        assertEquals(3, trend.size());
        assertNull(trend.get(0).effectiveN());
        assertNotNull(trend.get(1).effectiveN());
        assertEquals(1.0, trend.get(1).hhi().doubleValue(), 1e-6); // single-name
        // 60/40 sample
        assertEquals(0.52, trend.get(2).hhi().doubleValue(), 1e-6);
    }

    @Test
    void trendPointPositionCountReflectsActiveSymbols() {
        List<AllocationPoint> points = List.of(
                point("2024-01-01", "0", "10000", "10000",
                        symbol("A", "5000"), symbol("B", "3000"),
                        symbol("C", "2000")));
        TrendPoint tp = ConcentrationService.computeTrend(points).get(0);
        assertEquals(3, tp.positionCount());
        // HHI = 0.5² + 0.3² + 0.2² = 0.25 + 0.09 + 0.04 = 0.38
        assertEquals(0.38, tp.hhi().doubleValue(), 1e-6);
        assertTrue(tp.effectiveN().doubleValue() > 2.5
                && tp.effectiveN().doubleValue() < 3.0,
                "effective N for 50/30/20 should sit between 2 and 3");
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

    private static Map.Entry<String, BigDecimal> symbol(String name, String gbp) {
        return Map.entry(name, new BigDecimal(gbp));
    }
}
