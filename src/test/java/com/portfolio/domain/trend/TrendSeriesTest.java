package com.portfolio.domain.trend;

import com.portfolio.domain.model.PriceBar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TrendSeriesTest {

    /** Weekday-only bars starting at {@code start}; adjClose = close × adjRatio, open = close × openRatio. */
    static List<PriceBar> bars(String symbol, LocalDate start, double adjRatio, double openRatio,
                               double... closes) {
        List<PriceBar> out = new ArrayList<>();
        LocalDate d = start;
        for (double c : closes) {
            while (d.getDayOfWeek().getValue() > 5) d = d.plusDays(1);
            Double open = openRatio > 0 ? c * openRatio : null;
            out.add(new PriceBar(symbol, d, open, null, null, c, c * adjRatio, 1.0, null, "USD"));
            d = d.plusDays(1);
        }
        return out;
    }

    private static TrendConfig config() {
        return TrendConfig.builder().maLengths(new int[]{3}).monthlyMaLengths(new int[]{2}).build();
    }

    @Test
    void spliceScalesExtensionToMatchPrimaryAtBothCloseAndAdjClose() {
        // Primary starts 2026-01-12 at close 100 / adjClose 90. Extension index runs through that
        // date at level 400 → factors: close 100/400, adj 90/400 (index adjClose == close).
        List<PriceBar> primary = bars("QQQ", LocalDate.parse("2026-01-12"), 0.9, 1.0, 100, 102, 104, 106);
        List<PriceBar> ext = bars("^NDX", LocalDate.parse("2026-01-05"), 1.0, 1.0, 380, 390, 395, 398, 399, 400);

        TrendSeries s = TrendSeries.of(config(), primary, ext, null);

        assertNotNull(s);
        assertTrue(s.spliced());
        assertEquals(5, s.spliceIndex, "5 extension bars strictly before the primary start");
        assertEquals(LocalDate.parse("2026-01-12"), s.spliceDate());
        // The extension bar dated on the primary's first day anchors the scale but is not included.
        assertEquals(100.0, s.close[s.spliceIndex], 1e-9);
        assertEquals(380 * 100.0 / 400, s.close[0], 1e-9);
        assertEquals(90.0, s.adjClose[s.spliceIndex], 1e-9);
        assertEquals(380 * 90.0 / 400, s.adjClose[0], 1e-9);
        // Continuity: no jump bigger than the underlying day moves at the splice boundary.
        assertEquals(399 * 100.0 / 400, s.close[s.spliceIndex - 1], 1e-9);
    }

    @Test
    void noExtensionMeansNoSplice() {
        TrendSeries s = TrendSeries.of(config(),
                bars("QQQ", LocalDate.parse("2026-01-05"), 1.0, 1.0, 100, 101, 102), null, null);
        assertNotNull(s);
        assertFalse(s.spliced());
        assertEquals(0, s.spliceIndex);
    }

    @Test
    void adjOpenIsTotalReturnConsistentAndFallsBackToAdjClose() {
        // openRatio 0.99 → adjOpen = open × adjClose/close = 0.99 × adjClose.
        TrendSeries withOpen = TrendSeries.of(config(),
                bars("QQQ", LocalDate.parse("2026-01-05"), 0.8, 0.99, 100, 110), null, null);
        assertEquals(0.99 * 80.0, withOpen.adjOpen[0], 1e-9);

        TrendSeries noOpen = TrendSeries.of(config(),
                bars("QQQ", LocalDate.parse("2026-01-05"), 0.8, -1, 100, 110), null, null);
        assertEquals(noOpen.adjClose[0], noOpen.adjOpen[0], 1e-9, "missing open falls back to adjClose");
    }

    @Test
    void cashDailyUsesPriorRateLookAheadFreeWithFlatFallback() {
        List<PriceBar> primary = bars("QQQ", LocalDate.parse("2026-01-05"), 1.0, 1.0, 100, 101, 102, 103);
        // Cash series starts on the second primary day with a 5.04% annual rate.
        List<PriceBar> cash = bars("^IRX", LocalDate.parse("2026-01-06"), 1.0, 1.0, 5.04, 5.04, 5.04);

        TrendConfig cfg = TrendConfig.builder().maLengths(new int[]{3})
                .monthlyMaLengths(new int[]{2}).flatCashRatePct(2.52).build();
        TrendSeries s = TrendSeries.of(cfg, primary, null, cash);

        assertEquals(0.0, s.cashDaily[0], 1e-15, "nothing accrues into the first bar");
        // Bar 1 (2026-01-06): no cash close strictly before → flat 2.52%/252 = 0.01% per day.
        assertEquals(2.52 / 100 / 252, s.cashDaily[1], 1e-15);
        assertEquals(LocalDate.parse("2026-01-06"), s.cashFallbackUntil);
        // Bar 2: the 01-06 rate is now known.
        assertEquals(5.04 / 100 / 252, s.cashDaily[2], 1e-15);
        assertEquals(LocalDate.parse("2026-01-06"), s.cashFrom);
    }

    @Test
    void smaAndMonthEndsAreComputedOnTheSignalSource() {
        // 2026-01: weekdays 26/27/28/29/30 then 2026-02-02 → index 4 is January's month end;
        // the partial final month is never a month end.
        List<PriceBar> primary = bars("QQQ", LocalDate.parse("2026-01-26"), 0.5, 1.0,
                100, 101, 102, 103, 104, 105);
        TrendSeries s = TrendSeries.of(config(), primary, null, null);

        double[] sma3 = s.sma(3);
        assertTrue(Double.isNaN(sma3[1]));
        assertEquals((100 + 101 + 102) / 3.0, sma3[2], 1e-9, "signal defaults to close, not adjClose");
        assertArrayEquals(new int[]{4}, s.monthEndIdx);

        TrendConfig adjCfg = TrendConfig.builder().maLengths(new int[]{3})
                .monthlyMaLengths(new int[]{2}).signalSource("adjClose").build();
        TrendSeries sAdj = TrendSeries.of(adjCfg, primary, null, null);
        assertEquals(0.5 * (100 + 101 + 102) / 3.0, sAdj.sma(3)[2], 1e-9);
    }

    @Test
    void tooFewPrimaryBarsReturnsNull() {
        assertNull(TrendSeries.of(config(), List.of(), null, null));
        assertNull(TrendSeries.of(config(),
                bars("QQQ", LocalDate.parse("2026-01-05"), 1.0, 1.0, 100), null, null));
    }
}
