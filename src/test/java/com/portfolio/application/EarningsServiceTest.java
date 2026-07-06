package com.portfolio.application;

import com.portfolio.domain.model.PriceBar;
import com.portfolio.domain.swing.EarningsLookup;
import com.portfolio.domain.swing.SwingSeries;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EarningsServiceTest {

    private static SwingSeries series(int n) {
        List<PriceBar> bars = new ArrayList<>();
        LocalDate d = LocalDate.parse("2026-01-01");
        for (int i = 0; i < n; i++) {
            bars.add(new PriceBar("X", d, null, 100.0, 100.0, 100.0, 100.0, 1.0, null, "USD"));
            d = d.plusDays(1);
        }
        return SwingSeries.of("X", bars, 5, 200);
    }

    @Test
    void lookupFlagsWindowAndTradingDaysToNearest() {
        SwingSeries s = series(40);
        EarningsLookup el = EarningsService.buildLookup(s, List.of(s.dateAt(20)), 3);

        assertTrue(el.verified());
        assertEquals(0, el.at(20).tradingDaysToNearest());
        assertTrue(el.at(20).withinWindow());
        assertEquals(3, el.at(23).tradingDaysToNearest());
        assertTrue(el.at(23).withinWindow());
        assertEquals(4, el.at(24).tradingDaysToNearest());
        assertFalse(el.at(24).withinWindow(), "4 sessions is outside a ±3 window");
    }

    @Test
    void unverifiedSentinelNeverFlags() {
        assertFalse(EarningsLookup.UNVERIFIED.verified());
        assertFalse(EarningsLookup.UNVERIFIED.at(5).withinWindow());
        assertNull(EarningsLookup.UNVERIFIED.at(5).tradingDaysToNearest());
    }
}
