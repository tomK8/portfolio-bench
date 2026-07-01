package com.portfolio.domain;

import com.portfolio.domain.model.IntradayBar;
import com.portfolio.domain.model.PriceBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PriceStatsTest {

    private static PriceBar bar(String date, double close) {
        return new PriceBar("X", LocalDate.parse(date), null, null, null, close, close, 1.0, null, "USD");
    }

    private static List<PriceBar> series(double... closes) {
        List<PriceBar> out = new ArrayList<>();
        LocalDate d = LocalDate.parse("2026-01-01");
        for (double c : closes) {
            out.add(bar(d.toString(), c));
            d = d.plusDays(1);
        }
        return out;
    }

    @Test
    void annualizedVol_null_whenTooFewPoints() {
        assertNull(PriceStats.annualizedVol(series(100.0), 30));
        assertNull(PriceStats.annualizedVol(List.of(), 30));
    }

    @Test
    void annualizedVol_constantSeries_isZero() {
        BigDecimal vol = PriceStats.annualizedVol(series(100, 100, 100, 100, 100), 30);
        assertNotNull(vol);
        assertEquals(0.0, vol.doubleValue(), 1e-9);
    }

    @Test
    void annualizedVol_matchesHandComputedStdev() {
        // Alternating +10% / -10%(ish) returns: log returns are +ln(1.1), then price back etc.
        // Use a simple series with a known daily log-return stdev.
        // returns: ln(110/100), ln(100/110), ln(110/100), ln(100/110)
        BigDecimal vol = PriceStats.annualizedVol(series(100, 110, 100, 110, 100), 30);
        double r = Math.log(1.1);                       // magnitude of each log return
        // returns are +r, -r, +r, -r  → mean 0, sample var = sum(r^2)/(n-1) = 4r^2/3
        double expectedDaily = Math.sqrt(4 * r * r / 3.0);
        double expected = expectedDaily * Math.sqrt(252.0);
        assertEquals(expected, vol.doubleValue(), 1e-6);
    }

    @Test
    void annualizedVol_honoursWindow() {
        // 20 flat points then a couple of jumps; a 2-return window sees only the recent moves.
        List<PriceBar> bars = series(100, 100, 100, 100, 100, 100, 100, 110, 99);
        BigDecimal windowed = PriceStats.annualizedVol(bars, 2);
        assertNotNull(windowed);
        assertTrue(windowed.doubleValue() > 0, "recent window includes the jumps");
    }

    @Test
    void closeOnOrBefore_walksBackToLastTradingDay() {
        List<PriceBar> bars = series(10, 11, 12, 13, 14);   // 2026-01-01 .. 2026-01-05
        assertEquals(13.0, PriceStats.closeOnOrBefore(bars, LocalDate.parse("2026-01-04")));
        // A weekend/gap date resolves to the prior available close.
        assertEquals(14.0, PriceStats.closeOnOrBefore(bars, LocalDate.parse("2026-01-09")));
        // Before the series starts → null.
        assertNull(PriceStats.closeOnOrBefore(bars, LocalDate.parse("2025-12-31")));
    }

    private static IntradayBar ib(String iso, double close) {
        return new IntradayBar("X", Instant.parse(iso), close, null, "USD");
    }

    @Test
    void annualizedVolIntraday_positiveForMovingSeries_skipsOvernight() {
        // Two sessions, moves within each; the overnight jump (last of day 1 → first of day 2)
        // must be skipped, so a big overnight gap doesn't inflate the estimate.
        List<IntradayBar> bars = List.of(
                ib("2026-01-05T14:30:00Z", 100), ib("2026-01-05T14:31:00Z", 101), ib("2026-01-05T14:32:00Z", 100),
                ib("2026-01-06T14:30:00Z", 130), ib("2026-01-06T14:31:00Z", 131), ib("2026-01-06T14:32:00Z", 130));
        BigDecimal vol = PriceStats.annualizedVolIntraday(bars);
        assertNotNull(vol);
        assertTrue(vol.doubleValue() > 0);
    }

    @Test
    void annualizedVolIntraday_constant_isZero_andNullWhenSparse() {
        List<IntradayBar> flat = List.of(
                ib("2026-01-05T14:30:00Z", 50), ib("2026-01-05T14:31:00Z", 50), ib("2026-01-05T14:32:00Z", 50));
        assertEquals(0.0, PriceStats.annualizedVolIntraday(flat).doubleValue(), 1e-12);
        assertNull(PriceStats.annualizedVolIntraday(List.of(ib("2026-01-05T14:30:00Z", 50))));
    }

    @Test
    void pctChange_basics() {
        assertEquals(0.10, PriceStats.pctChange(110.0, 100.0).doubleValue(), 1e-9);
        assertEquals(-0.20, PriceStats.pctChange(80.0, 100.0).doubleValue(), 1e-9);
        assertNull(PriceStats.pctChange(null, 100.0));
        assertNull(PriceStats.pctChange(100.0, 0.0));
    }
}
