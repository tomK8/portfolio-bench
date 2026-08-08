package com.portfolio.domain;

import com.portfolio.domain.WatchlistAlerts.Flags;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class WatchlistAlertsTest {

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    @Test
    void nearHigh_firesWithinBand_notOutside() {
        // High 100, band 3%. Price 98 → 2% below → fires. Price 96 → 4% below → no.
        assertTrue(WatchlistAlerts.evaluate(bd("98"), bd("100"), null, bd("3"), null).nearHigh());
        assertFalse(WatchlistAlerts.evaluate(bd("96"), bd("100"), null, bd("3"), null).nearHigh());
    }

    @Test
    void nearHigh_firesOnFreshHighAboveStored() {
        // Price pushed past the stored 52-week high — distance is negative, still within band.
        assertTrue(WatchlistAlerts.evaluate(bd("105"), bd("100"), null, bd("3"), null).nearHigh());
    }

    @Test
    void nearHigh_disabledWhenThresholdMissingOrZero() {
        assertFalse(WatchlistAlerts.evaluate(bd("99"), bd("100"), null, null, null).nearHigh());
        assertFalse(WatchlistAlerts.evaluate(bd("99"), bd("100"), null, bd("0"), null).nearHigh());
    }

    @Test
    void bigMove_firesEitherDirection_atOrAboveThreshold() {
        Flags up = WatchlistAlerts.evaluate(bd("100"), null, bd("0.10"), null, bd("10"));
        assertTrue(up.bigMove());
        assertEquals(1, up.moveDirection());

        Flags down = WatchlistAlerts.evaluate(bd("100"), null, bd("-0.12"), null, bd("10"));
        assertTrue(down.bigMove());
        assertEquals(-1, down.moveDirection());

        Flags small = WatchlistAlerts.evaluate(bd("100"), null, bd("0.05"), null, bd("10"));
        assertFalse(small.bigMove());
    }

    @Test
    void bothFire_reportedTogether() {
        Flags f = WatchlistAlerts.evaluate(bd("99"), bd("100"), bd("-0.15"), bd("3"), bd("10"));
        assertTrue(f.nearHigh());
        assertTrue(f.bigMove());
        assertTrue(f.both());
        assertTrue(f.any());
        assertEquals(-1, f.moveDirection());
    }

    @Test
    void nothingFires_isQuiet() {
        Flags f = WatchlistAlerts.evaluate(bd("90"), bd("100"), bd("0.02"), bd("3"), bd("10"));
        assertFalse(f.any());
        assertFalse(f.both());
    }
}
