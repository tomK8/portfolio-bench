package com.portfolio.domain;

import com.portfolio.domain.WatchlistAlerts.Flags;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class WatchlistAlertsTest {

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    /** Evaluate with no price-level triggers set (the common case for the percent-only tests). */
    private static Flags eval(BigDecimal price, BigDecimal high, BigDecimal moveFrac,
                              BigDecimal highPct, BigDecimal movePct) {
        return WatchlistAlerts.evaluate(price, high, moveFrac, highPct, movePct, null, null);
    }

    @Test
    void nearHigh_firesWithinBand_notOutside() {
        // High 100, band 3%. Price 98 → 2% below → fires. Price 96 → 4% below → no.
        assertTrue(eval(bd("98"), bd("100"), null, bd("3"), null).nearHigh());
        assertFalse(eval(bd("96"), bd("100"), null, bd("3"), null).nearHigh());
    }

    @Test
    void nearHigh_firesOnFreshHighAboveStored() {
        // Price pushed past the stored 52-week high — distance is negative, still within band.
        assertTrue(eval(bd("105"), bd("100"), null, bd("3"), null).nearHigh());
    }

    @Test
    void nearHigh_disabledWhenThresholdMissingOrZero() {
        assertFalse(eval(bd("99"), bd("100"), null, null, null).nearHigh());
        assertFalse(eval(bd("99"), bd("100"), null, bd("0"), null).nearHigh());
    }

    @Test
    void bigMove_firesEitherDirection_atOrAboveThreshold() {
        Flags up = eval(bd("100"), null, bd("0.10"), null, bd("10"));
        assertTrue(up.bigMove());
        assertEquals(1, up.moveDirection());

        Flags down = eval(bd("100"), null, bd("-0.12"), null, bd("10"));
        assertTrue(down.bigMove());
        assertEquals(-1, down.moveDirection());

        Flags small = eval(bd("100"), null, bd("0.05"), null, bd("10"));
        assertFalse(small.bigMove());
    }

    @Test
    void bothFire_reportedTogether() {
        Flags f = eval(bd("99"), bd("100"), bd("-0.15"), bd("3"), bd("10"));
        assertTrue(f.nearHigh());
        assertTrue(f.bigMove());
        assertTrue(f.both());
        assertTrue(f.any());
        assertEquals(-1, f.moveDirection());
    }

    @Test
    void nothingFires_isQuiet() {
        Flags f = eval(bd("90"), bd("100"), bd("0.02"), bd("3"), bd("10"));
        assertFalse(f.any());
        assertFalse(f.both());
    }

    // ---- Absolute price-level triggers --------------------------------------

    @Test
    void aboveLevel_firesAtOrAboveTarget() {
        // Target ≥ 120. Price 121 fires; exactly 120 fires (at-or-through); 119 does not.
        assertTrue(WatchlistAlerts.evaluate(bd("121"), null, null, null, null, bd("120"), null).aboveLevel());
        assertTrue(WatchlistAlerts.evaluate(bd("120"), null, null, null, null, bd("120"), null).aboveLevel());
        assertFalse(WatchlistAlerts.evaluate(bd("119"), null, null, null, null, bd("120"), null).aboveLevel());
    }

    @Test
    void belowLevel_firesAtOrBelowTarget() {
        // Target ≤ 80. Price 79 fires; exactly 80 fires; 81 does not.
        assertTrue(WatchlistAlerts.evaluate(bd("79"), null, null, null, null, null, bd("80")).belowLevel());
        assertTrue(WatchlistAlerts.evaluate(bd("80"), null, null, null, null, null, bd("80")).belowLevel());
        assertFalse(WatchlistAlerts.evaluate(bd("81"), null, null, null, null, null, bd("80")).belowLevel());
    }

    @Test
    void priceLevels_disabledWhenNullOrNonPositive() {
        Flags none = WatchlistAlerts.evaluate(bd("100"), null, null, null, null, null, null);
        assertFalse(none.aboveLevel());
        assertFalse(none.belowLevel());
        assertFalse(none.any());

        Flags zeros = WatchlistAlerts.evaluate(bd("100"), null, null, null, null, bd("0"), bd("0"));
        assertFalse(zeros.aboveLevel());
        assertFalse(zeros.belowLevel());
    }

    @Test
    void priceLevels_countTowardAny_butNotBoth() {
        Flags f = WatchlistAlerts.evaluate(bd("130"), null, null, null, null, bd("120"), null);
        assertTrue(f.aboveLevel());
        assertTrue(f.any());
        assertFalse(f.both());   // both() stays near-high + big-move only
    }

    @Test
    void priceLevels_bandCanFireBothEnds() {
        // A degenerate band where the price sits at/above the upper and at/below the lower target.
        Flags f = WatchlistAlerts.evaluate(bd("100"), null, null, null, null, bd("90"), bd("110"));
        assertTrue(f.aboveLevel());
        assertTrue(f.belowLevel());
    }
}
