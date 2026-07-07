package com.portfolio.domain.trend;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ExposureBootstrapTest {

    private static TrendSeries series(double... closes) {
        return TrendSeries.of(
                TrendConfig.builder().maLengths(new int[]{2}).monthlyMaLengths(new int[]{2})
                        .flatCashRatePct(0).build(),
                TrendSeriesTest.bars("QQQ", LocalDate.parse("2026-01-05"), 1.0, 1.0, closes),
                null, null);
    }

    private static Backtester.Params params(TrendSeries s) {
        return new Backtester.Params(Backtester.Execution.CLOSE, 0, 0, s.cashDaily);
    }

    @Test
    void alwaysInvestedStrategyHasNothingToScatter() {
        TrendSeries s = series(100, 101, 102, 103);
        boolean[] target = Backtester.buyAndHold(s.size());
        Backtester.Result r = Backtester.run(s, target, 0, params(s));
        assertNull(ExposureBootstrap.run(s, target, 0, params(s), TrendStats.of(s, r), 50, 42));
    }

    @Test
    void sameSeedReproducesTheSameDistribution() {
        double[] closes = new double[60];
        for (int i = 0; i < 60; i++) closes[i] = 100 * Math.pow(1.01, i) * (i % 7 == 3 ? 0.97 : 1);
        TrendSeries s = series(closes);
        boolean[] target = Backtester.buyAndHold(s.size());
        Arrays.fill(target, 20, 30, false);
        TrendStats actual = TrendStats.of(s, Backtester.run(s, target, 0, params(s)));

        ExposureBootstrap.Result a = ExposureBootstrap.run(s, target, 0, params(s), actual, 100, 7);
        ExposureBootstrap.Result b = ExposureBootstrap.run(s, target, 0, params(s), actual, 100, 7);
        assertEquals(a, b);
        assertEquals(1, a.outSegments());
        assertEquals(1 - 10.0 / 60, a.timeInvested(), 1e-12, "time-invested fraction is matched");
    }

    @Test
    void dodgingTheOneCrashDayRanksNearTheTopOfTheDistribution() {
        // Steady +1% days with a single −50% crash into bar 30. The actual schedule is out for
        // exactly that day; a randomly placed 1-day out segment almost never covers it.
        double[] closes = new double[60];
        closes[0] = 100;
        for (int i = 1; i < 60; i++) closes[i] = closes[i - 1] * (i == 30 ? 0.5 : 1.01);
        TrendSeries s = series(closes);
        boolean[] target = Backtester.buyAndHold(s.size());
        target[29] = false;   // out during the return into bar 30
        TrendStats actual = TrendStats.of(s, Backtester.run(s, target, 0, params(s)));

        ExposureBootstrap.Result res = ExposureBootstrap.run(s, target, 0, params(s), actual, 400, 42);
        assertNotNull(res);
        assertTrue(res.cagrPercentile() > 90,
                "dodged the crash → beats nearly every random schedule, got " + res.cagrPercentile());
        assertTrue(res.maxDdPercentile() > 90,
                "shallowest drawdown of nearly all schedules, got " + res.maxDdPercentile());
        assertTrue(res.cagrDist().min() < actual.cagr(), "some iteration ate the crash");
    }
}
