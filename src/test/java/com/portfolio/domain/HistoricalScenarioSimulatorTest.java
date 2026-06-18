package com.portfolio.domain;

import com.portfolio.domain.HistoricalScenarioSimulator.CashBucket;
import com.portfolio.domain.HistoricalScenarioSimulator.CashResult;
import com.portfolio.domain.HistoricalScenarioSimulator.Position;
import com.portfolio.domain.HistoricalScenarioSimulator.Result;
import com.portfolio.domain.HistoricalScenarioSimulator.SymbolResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoricalScenarioSimulatorTest {

    private final HistoricalScenarioSimulator sim = new HistoricalScenarioSimulator();

    private static NavigableMap<LocalDate, BigDecimal> series(Object... datePriceAlternating) {
        TreeMap<LocalDate, BigDecimal> out = new TreeMap<>();
        for (int i = 0; i < datePriceAlternating.length; i += 2) {
            out.put(LocalDate.parse((String) datePriceAlternating[i]),
                    new BigDecimal(datePriceAlternating[i + 1].toString()));
        }
        return out;
    }

    private static SymbolResult find(Result r, String sym) {
        return r.perSymbol().stream().filter(x -> x.symbol().equals(sym)).findFirst().orElse(null);
    }

    private static CashResult findCash(Result r, String ccy) {
        return r.perCash().stream().filter(x -> x.currency().equals(ccy)).findFirst().orElse(null);
    }

    @Test
    void singleEquityHeldThroughout_returnMatchesPriceRatio() {
        // Today: £1000 of MSFT. Scenario: price goes 100 → 130 → period return +30%.
        Position p = new Position("MSFT", new BigDecimal("1000"), false, null);
        Map<String, NavigableMap<LocalDate, BigDecimal>> prices = Map.of("MSFT",
                series("2022-01-01", "100", "2022-12-31", "130"));

        Result r = sim.simulate(List.of(p), List.of(),
                LocalDate.parse("2022-01-01"), LocalDate.parse("2022-12-31"),
                Map.of(), prices);

        SymbolResult msft = find(r, "MSFT");
        assertEquals(0, new BigDecimal("1000.00").compareTo(msft.startValueGbp()));
        assertEquals(0, new BigDecimal("1300.00").compareTo(msft.endValueGbp()));
        assertEquals(0, new BigDecimal("300.00").compareTo(msft.pnlGbp()));
        assertEquals(0.30, msft.periodReturn().doubleValue(), 1e-6);
        assertFalse(msft.substituted());
        assertFalse(msft.missing());
    }

    @Test
    void substituteAppliesReplacementsReturnCurve() {
        // £1000 of NVDA, substituted with EQQQ. NVDA's own price would be 100 → 500 (+400%);
        // EQQQ's is 50 → 60 (+20%). With substitute, result should be +20%.
        Position p = new Position("NVDA", new BigDecimal("1000"), false, null);
        Map<String, NavigableMap<LocalDate, BigDecimal>> prices = Map.of(
                "NVDA", series("2022-01-01", "100", "2022-12-31", "500"),
                "EQQQ", series("2022-01-01", "50", "2022-12-31", "60"));

        Result r = sim.simulate(List.of(p), List.of(),
                LocalDate.parse("2022-01-01"), LocalDate.parse("2022-12-31"),
                Map.of("NVDA", "EQQQ"), prices);

        SymbolResult nv = find(r, "NVDA");
        assertEquals("EQQQ", nv.effectiveSymbol());
        assertTrue(nv.substituted());
        assertEquals(0, new BigDecimal("1200.00").compareTo(nv.endValueGbp()));
        assertEquals(0.20, nv.periodReturn().doubleValue(), 1e-6);
    }

    @Test
    void missingSymbolWithoutSubstitute_heldFlat() {
        Position p = new Position("OBSCURE", new BigDecimal("500"), false, null);

        Result r = sim.simulate(List.of(p), List.of(),
                LocalDate.parse("2022-01-01"), LocalDate.parse("2022-12-31"),
                Map.of(), Map.of());

        SymbolResult o = find(r, "OBSCURE");
        assertTrue(o.missing());
        assertEquals(0, new BigDecimal("500.00").compareTo(o.endValueGbp()));
        assertEquals(0, BigDecimal.ZERO.compareTo(o.pnlGbp()));
    }

    @Test
    void substituteWithoutData_fallsBackToOriginalIfAvailable() {
        // User asked for EQQQ substitute but EQQQ wasn't loaded; original NVDA series exists.
        // Better to project the original than to give up — substituted flips back to false.
        Position p = new Position("NVDA", new BigDecimal("1000"), false, null);
        Map<String, NavigableMap<LocalDate, BigDecimal>> prices = Map.of(
                "NVDA", series("2022-01-01", "100", "2022-12-31", "150"));

        Result r = sim.simulate(List.of(p), List.of(),
                LocalDate.parse("2022-01-01"), LocalDate.parse("2022-12-31"),
                Map.of("NVDA", "EQQQ"), prices);

        SymbolResult nv = find(r, "NVDA");
        assertFalse(nv.substituted());
        assertFalse(nv.missing());
        assertEquals("NVDA", nv.effectiveSymbol());
        assertEquals(0, new BigDecimal("1500.00").compareTo(nv.endValueGbp()));
    }

    @Test
    void cashCompoundsAtFlatRate() {
        // £1000 cash at 5% for 1 year → £1050.
        CashBucket cb = new CashBucket("GBP", new BigDecimal("1000"), new BigDecimal("0.05"));

        Result r = sim.simulate(List.of(), List.of(cb),
                LocalDate.parse("2022-01-01"), LocalDate.parse("2023-01-01"),
                Map.of(), Map.of());

        CashResult c = findCash(r, "GBP");
        assertEquals(1050.0, c.endValueGbp().doubleValue(), 0.5);
        assertEquals(0.05, c.periodReturn().doubleValue(), 0.001);
    }

    @Test
    void cashAtZeroRateStaysFlat() {
        CashBucket cb = new CashBucket("GBP", new BigDecimal("2000"), BigDecimal.ZERO);

        Result r = sim.simulate(List.of(), List.of(cb),
                LocalDate.parse("2022-01-01"), LocalDate.parse("2022-12-31"),
                Map.of(), Map.of());

        CashResult c = findCash(r, "GBP");
        assertEquals(0, new BigDecimal("2000.00").compareTo(c.endValueGbp()));
    }

    @Test
    void bondAccruesCouponOnTopOfPriceReturn() {
        // £1000 gilt at 4% coupon. Clean price 100 → 105 over 1 year → price return +5%.
        // Coupon accrual = 1000 × 0.04 × (365/365.25) ≈ £39.97.
        // Total end ≈ £1050 + £39.97 ≈ £1089.97.
        Position p = new Position("GILT 4% 2030", new BigDecimal("1000"), true, new BigDecimal("0.04"));
        Map<String, NavigableMap<LocalDate, BigDecimal>> prices = Map.of(
                "GILT 4% 2030", series("2022-01-01", "100", "2023-01-01", "105"));

        Result r = sim.simulate(List.of(p), List.of(),
                LocalDate.parse("2022-01-01"), LocalDate.parse("2023-01-01"),
                Map.of(), prices);

        SymbolResult b = find(r, "GILT 4% 2030");
        assertEquals(1089.97, b.endValueGbp().doubleValue(), 0.5);
    }

    @Test
    void substitutedBondDoesNotAccrueCoupon() {
        // Gilt substituted with EQQQ → projection inherits EQQQ's nature (equity), no coupon.
        // EQQQ flat over the window → end ≈ start with no coupon top-up.
        Position p = new Position("GILT 4% 2030", new BigDecimal("1000"), true, new BigDecimal("0.04"));
        Map<String, NavigableMap<LocalDate, BigDecimal>> prices = Map.of(
                "EQQQ", series("2022-01-01", "50", "2023-01-01", "50"));

        Result r = sim.simulate(List.of(p), List.of(),
                LocalDate.parse("2022-01-01"), LocalDate.parse("2023-01-01"),
                Map.of("GILT 4% 2030", "EQQQ"), prices);

        SymbolResult b = find(r, "GILT 4% 2030");
        assertEquals(0, new BigDecimal("1000.00").compareTo(b.endValueGbp()));
        assertTrue(b.substituted());
    }

    @Test
    void timelineGoesDayByDayAndStartsAtTotalStart() {
        Position p = new Position("MSFT", new BigDecimal("1000"), false, null);
        Map<String, NavigableMap<LocalDate, BigDecimal>> prices = Map.of("MSFT",
                series("2022-01-01", "100", "2022-12-31", "120"));

        Result r = sim.simulate(List.of(p), List.of(),
                LocalDate.parse("2022-01-01"), LocalDate.parse("2022-01-05"),
                Map.of(), prices);

        // 5 days inclusive.
        assertEquals(5, r.timeline().size());
        assertEquals(LocalDate.parse("2022-01-01"), r.timeline().get(0).date());
        assertEquals(LocalDate.parse("2022-01-05"), r.timeline().get(4).date());
        // Day 0 should equal the starting GBP value (price ratio = 1).
        assertEquals(0, new BigDecimal("1000.00").compareTo(r.timeline().get(0).totalValueGbp()));
    }

    @Test
    void partialCoverageUsesFloorFillThenCeilFillFallback() {
        // Sparse series: prices only on 2022-03-01 and 2022-10-01.
        // - Sample 2022-01-01 (before any data) → ceil-fill to 2022-03-01's £100.
        // - Sample 2022-05-01 → floor-fills to 2022-03-01's £100.
        // - Sample 2022-12-01 → floor-fills to 2022-10-01's £120.
        // Period start = ceil-filled 100, period end = 120 → +20%.
        Position p = new Position("THINLY", new BigDecimal("1000"), false, null);
        Map<String, NavigableMap<LocalDate, BigDecimal>> prices = Map.of("THINLY",
                series("2022-03-01", "100", "2022-10-01", "120"));

        Result r = sim.simulate(List.of(p), List.of(),
                LocalDate.parse("2022-01-01"), LocalDate.parse("2022-12-31"),
                Map.of(), prices);

        SymbolResult s = find(r, "THINLY");
        assertEquals(0, new BigDecimal("1200.00").compareTo(s.endValueGbp()));
        assertEquals(0.20, s.periodReturn().doubleValue(), 1e-6);
        assertFalse(s.missing());
    }

    @Test
    void totalsAggregatePositionsAndCash() {
        // £1000 MSFT (+10%) + £500 cash @ 0% over 1 year → start £1500, end £1600, +6.67%.
        Position p = new Position("MSFT", new BigDecimal("1000"), false, null);
        CashBucket cb = new CashBucket("GBP", new BigDecimal("500"), BigDecimal.ZERO);
        Map<String, NavigableMap<LocalDate, BigDecimal>> prices = Map.of("MSFT",
                series("2022-01-01", "100", "2023-01-01", "110"));

        Result r = sim.simulate(List.of(p), List.of(cb),
                LocalDate.parse("2022-01-01"), LocalDate.parse("2023-01-01"),
                Map.of(), prices);

        assertEquals(0, new BigDecimal("1500.00").compareTo(r.startTotalGbp()));
        assertEquals(0, new BigDecimal("1600.00").compareTo(r.endTotalGbp()));
        assertEquals(0, new BigDecimal("100.00").compareTo(r.pnlGbp()));
        assertEquals(100.0 / 1500.0, r.periodReturn().doubleValue(), 1e-4);
    }

    @Test
    void invertedWindowRejected() {
        assertThrows(IllegalArgumentException.class, () -> sim.simulate(
                List.of(), List.of(),
                LocalDate.parse("2022-12-31"), LocalDate.parse("2022-01-01"),
                Map.of(), Map.of()));
    }

    @Test
    void duplicatePositionRejected() {
        Position p1 = new Position("MSFT", new BigDecimal("1000"), false, null);
        Position p2 = new Position("msft", new BigDecimal("500"), false, null);  // same after upper()
        assertThrows(IllegalArgumentException.class, () -> sim.simulate(
                List.of(p1, p2), List.of(),
                LocalDate.parse("2022-01-01"), LocalDate.parse("2022-12-31"),
                Map.of(), Map.of()));
    }

    @Test
    void emptyPortfolioYieldsTimelineOfZeros() {
        Result r = sim.simulate(List.of(), List.of(),
                LocalDate.parse("2022-01-01"), LocalDate.parse("2022-01-03"),
                Map.of(), Map.of());

        assertEquals(3, r.timeline().size());
        assertEquals(0, BigDecimal.ZERO.compareTo(r.timeline().get(0).totalValueGbp()));
        assertEquals(0, BigDecimal.ZERO.compareTo(r.startTotalGbp()));
        assertNull(r.periodReturn());
    }

    @Test
    void zeroValuePositionReturnsNullPercentNotDivideByZero() {
        // Zero-value position shouldn't blow up — return is undefined, render as null.
        Position p = new Position("DUST", BigDecimal.ZERO, false, null);
        Map<String, NavigableMap<LocalDate, BigDecimal>> prices = Map.of("DUST",
                series("2022-01-01", "10", "2022-12-31", "20"));

        Result r = sim.simulate(List.of(p), List.of(),
                LocalDate.parse("2022-01-01"), LocalDate.parse("2022-12-31"),
                Map.of(), prices);

        SymbolResult d = find(r, "DUST");
        assertNotNull(d);
        assertNull(d.periodReturn());
        assertEquals(0, BigDecimal.ZERO.compareTo(d.endValueGbp()));
    }
}
