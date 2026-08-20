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
    void dailyVol_isAnnualizedVolWithoutRootT() {
        List<PriceBar> bars = series(100, 110, 100, 110, 100);
        BigDecimal daily = PriceStats.dailyVol(bars, 30);
        BigDecimal annual = PriceStats.annualizedVol(bars, 30);
        assertNotNull(daily);
        // annualizedVol == dailyVol × √252 (the only difference between the two; each rounded to 8dp)
        assertEquals(annual.doubleValue(), daily.doubleValue() * Math.sqrt(252.0), 1e-6);

        double r = Math.log(1.1);
        double expectedDaily = Math.sqrt(4 * r * r / 3.0);   // same hand-computed stdev, no √252
        assertEquals(expectedDaily, daily.doubleValue(), 1e-8);
    }

    @Test
    void dailyVol_null_whenTooFewReturns() {
        assertNull(PriceStats.dailyVol(series(100.0), 10));
        assertNull(PriceStats.dailyVol(List.of(), 10));
    }

    @Test
    void sigmaDistance_signAndScale() {
        // dailyVol 2%/day, horizon 5 → σ_5 = 0.02·√5 ≈ 0.0447214.
        BigDecimal vol = new BigDecimal("0.02");
        // limit 5% above current: ln(105/100)=0.0487902 → /0.0447214 ≈ 1.0910.
        BigDecimal up = PriceStats.sigmaDistance(new BigDecimal("100"), new BigDecimal("105"), vol, 5);
        assertNotNull(up);
        assertEquals(0.0487902 / (0.02 * Math.sqrt(5)), up.doubleValue(), 1e-3);
        assertTrue(up.signum() > 0, "limit above the market → positive");
        // limit below current → negative, symmetric magnitude for the reciprocal ratio.
        BigDecimal down = PriceStats.sigmaDistance(new BigDecimal("105"), new BigDecimal("100"), vol, 5);
        assertTrue(down.signum() < 0);
        // null guards
        assertNull(PriceStats.sigmaDistance(null, new BigDecimal("100"), vol, 5));
        assertNull(PriceStats.sigmaDistance(new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ZERO, 5));
    }

    @Test
    void touchProbability_reflectionPrinciple() {
        // At the barrier (d=0) → certain touch.
        assertEquals(1.0, PriceStats.touchProbability(BigDecimal.ZERO).doubleValue(), 1e-9);
        // 1σ away → 2·Φ(−1) ≈ 0.31731; sign-independent.
        assertEquals(0.3173, PriceStats.touchProbability(new BigDecimal("1")).doubleValue(), 1e-3);
        assertEquals(0.3173, PriceStats.touchProbability(new BigDecimal("-1")).doubleValue(), 1e-3);
        // 3σ away → 2·Φ(−3) ≈ 0.0027, well under 1.
        assertEquals(0.0027, PriceStats.touchProbability(new BigDecimal("3")).doubleValue(), 1e-3);
        assertNull(PriceStats.touchProbability(null));
    }

    @Test
    void normalCdf_knownValues() {
        assertEquals(0.5, PriceStats.normalCdf(0.0), 1e-6);
        assertEquals(0.8413447, PriceStats.normalCdf(1.0), 1e-5);
        assertEquals(0.1586553, PriceStats.normalCdf(-1.0), 1e-5);
        assertEquals(0.9772499, PriceStats.normalCdf(2.0), 1e-5);
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
    void closeNSessionsBefore_countsSessionsNotCalendarDays() {
        // Wed/Thu/Fri closes; the following Monday is "today" (mid-session, no bar yet). A calendar
        // anchor would map today−1/−2/−3 all onto Friday; session counting must stay one trading
        // day apart.
        List<PriceBar> bars = List.of(
                bar("2026-07-01", 100),    // Wed
                bar("2026-07-02", 110),    // Thu
                bar("2026-07-03", 120));   // Fri
        LocalDate monday = LocalDate.parse("2026-07-06");
        assertEquals(120.0, PriceStats.closeNSessionsBefore(bars, monday, 1));   // previous session = Fri
        assertEquals(110.0, PriceStats.closeNSessionsBefore(bars, monday, 2));   // 2 sessions ago = Thu
        assertEquals(100.0, PriceStats.closeNSessionsBefore(bars, monday, 3));   // 3 sessions ago = Wed
        assertNull(PriceStats.closeNSessionsBefore(bars, monday, 4));            // not enough sessions
    }

    @Test
    void closeNSessionsBefore_skipsTodaysOwnBar() {
        // Once today's daily bar is stored, n=1 must still be the *previous* session — the live
        // current price supersedes today's close, so today's own bar is not a valid base.
        List<PriceBar> bars = List.of(
                bar("2026-07-02", 110),    // Thu
                bar("2026-07-03", 120),    // Fri
                bar("2026-07-06", 130));   // Mon (today)
        LocalDate monday = LocalDate.parse("2026-07-06");
        assertEquals(120.0, PriceStats.closeNSessionsBefore(bars, monday, 1));   // Fri, not Mon's 130
        assertEquals(110.0, PriceStats.closeNSessionsBefore(bars, monday, 2));   // Thu
    }

    @Test
    void closeNSessionsBefore_skipsPhantomWeekendBars() {
        // GiltPriceFetchJob rolls the last price forward into price_history on Sat/Sun; those bars
        // are not sessions and must not be counted (mirrors the 39 gilts with 07-04/07-05 rows).
        List<PriceBar> bars = List.of(
                bar("2026-07-01", 100),    // Wed
                bar("2026-07-02", 110),    // Thu
                bar("2026-07-03", 120),    // Fri
                bar("2026-07-04", 120),    // Sat  (phantom roll-forward)
                bar("2026-07-05", 120));   // Sun  (phantom roll-forward)
        LocalDate monday = LocalDate.parse("2026-07-06");
        assertEquals(120.0, PriceStats.closeNSessionsBefore(bars, monday, 1));   // Fri, not the Sat/Sun rows
        assertEquals(110.0, PriceStats.closeNSessionsBefore(bars, monday, 2));   // Thu
        assertEquals(100.0, PriceStats.closeNSessionsBefore(bars, monday, 3));   // Wed
    }

    @Test
    void closeNSessionsBefore_guards() {
        List<PriceBar> bars = List.of(bar("2026-07-03", 120));
        assertNull(PriceStats.closeNSessionsBefore(bars, LocalDate.parse("2026-07-06"), 0));
        assertNull(PriceStats.closeNSessionsBefore(null, LocalDate.parse("2026-07-06"), 1));
        assertNull(PriceStats.closeNSessionsBefore(List.of(), LocalDate.parse("2026-07-06"), 1));
    }

    @Test
    void tradingSessionsBetween_countsWeekdaysExclusiveOfStart() {
        // Mon 2026-07-06 → Fri 2026-07-10: Tue/Wed/Thu/Fri = 4 sessions ahead, not 4 calendar days
        // by coincidence — the start day is excluded because a horizon starts from today's close.
        assertEquals(4, PriceStats.tradingSessionsBetween(
                LocalDate.parse("2026-07-06"), LocalDate.parse("2026-07-10")));
        // Fri → the following Mon is one session, though it is three calendar days.
        assertEquals(1, PriceStats.tradingSessionsBetween(
                LocalDate.parse("2026-07-10"), LocalDate.parse("2026-07-13")));
        // The conversion that matters for an order's expiry: 30 calendar days ≈ 21 sessions, so
        // feeding the calendar count into the σ√horizon scaling would overstate the diffusion.
        assertEquals(22, PriceStats.tradingSessionsBetween(
                LocalDate.parse("2026-07-06"), LocalDate.parse("2026-08-05")));
    }

    @Test
    void tradingSessionsBetween_guards() {
        LocalDate d = LocalDate.parse("2026-07-06");
        assertEquals(0, PriceStats.tradingSessionsBetween(d, d));               // same day
        assertEquals(0, PriceStats.tradingSessionsBetween(d, d.minusDays(5)));  // backwards
        assertEquals(0, PriceStats.tradingSessionsBetween(null, d));
        assertEquals(0, PriceStats.tradingSessionsBetween(d, null));
        // A weekend-only span has no sessions at all — Sat → Sun.
        assertEquals(0, PriceStats.tradingSessionsBetween(
                LocalDate.parse("2026-07-11"), LocalDate.parse("2026-07-12")));
    }

    @Test
    void touchProbability_growsWithTheHorizon() {
        // Same barrier, more time: 20 sessions must never price below 5. The two share one daily
        // vol and differ only by the √horizon scaling, which is what the orders panel relies on.
        BigDecimal cur = new BigDecimal("100"), lim = new BigDecimal("95"), vol = new BigDecimal("0.02");
        double p5 = PriceStats.touchProbability(PriceStats.sigmaDistance(cur, lim, vol, 5)).doubleValue();
        double p20 = PriceStats.touchProbability(PriceStats.sigmaDistance(cur, lim, vol, 20)).doubleValue();
        double p60 = PriceStats.touchProbability(PriceStats.sigmaDistance(cur, lim, vol, 60)).doubleValue();
        assertTrue(p5 < p20 && p20 < p60, "touch probability must be monotonic in the horizon");
        assertTrue(p60 <= 1.0);
    }

    @Test
    void pctChange_basics() {
        assertEquals(0.10, PriceStats.pctChange(110.0, 100.0).doubleValue(), 1e-9);
        assertEquals(-0.20, PriceStats.pctChange(80.0, 100.0).doubleValue(), 1e-9);
        assertNull(PriceStats.pctChange(null, 100.0));
        assertNull(PriceStats.pctChange(100.0, 0.0));
    }
}
