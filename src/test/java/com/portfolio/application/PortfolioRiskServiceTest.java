package com.portfolio.application;

import com.portfolio.application.PortfolioReturnService.AnnualReturn;
import com.portfolio.application.PortfolioReturnService.ContribPoint;
import com.portfolio.application.PortfolioReturnService.DrawdownPoint;
import com.portfolio.application.PortfolioReturnService.ReturnPoint;
import com.portfolio.application.PortfolioReturnService.ReturnTimeline;
import com.portfolio.application.PortfolioReturnService.Summary;
import com.portfolio.application.PortfolioRiskService.RiskTimeline;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortfolioRiskServiceTest {

    private static final BigDecimal RF = new BigDecimal("0.04");

    @Test
    void emptyTimelineReturnsEmptySummaryWithRf() {
        ReturnTimeline rt = new ReturnTimeline(List.of(), List.of(), List.of(), List.of(),
                Summary.empty());
        RiskTimeline out = PortfolioRiskService.compute(rt, RF);
        assertNull(out.summary().volAnnualized());
        assertNull(out.summary().sharpe());
        assertEquals(RF, out.summary().riskFreeRate());
        assertTrue(out.rollingVol().isEmpty());
        assertTrue(out.dailyReturnHistogram().isEmpty());
    }

    @Test
    void flatGrowthGivesZeroVolAndNoSharpe() {
        // 300 weekdays of growth=1.0 → all daily returns 0 → vol=0 → Sharpe undefined.
        // Sortino IS defined: every day is below the rf threshold so the denominator is the
        // constant rf/√252, giving sortino = (0 − rf) / (rf/√252) = −√252.
        List<ReturnPoint> growth = flatGrowth(LocalDate.of(2024, 1, 1), 300, "1.000000");
        ReturnTimeline rt = new ReturnTimeline(growth, List.of(), List.of(), List.of(),
                Summary.empty());
        RiskTimeline out = PortfolioRiskService.compute(rt, RF);
        assertEquals(0.0, out.summary().volAnnualized().doubleValue(), 1e-9);
        assertNull(out.summary().sharpe());
        assertEquals(-Math.sqrt(252), out.summary().sortino().doubleValue(), 1e-3);
    }

    @Test
    void constantOnePercentDailyGrowthGivesHighSharpe() {
        // 252 weekdays of +1% daily returns → mean ≈ 0.01, stdev = 0 → Sharpe undefined; cover
        // the variance path instead by alternating +1% / -0.5% small returns.
        List<ReturnPoint> growth = alternatingGrowth(LocalDate.of(2024, 1, 1), 300, 0.01, -0.005);
        ReturnTimeline rt = new ReturnTimeline(growth, List.of(), List.of(), List.of(),
                Summary.empty());
        RiskTimeline out = PortfolioRiskService.compute(rt, RF);

        // Mean per day ≈ +0.25%, stdev ≈ 0.75%, ann vol ≈ 0.75% * sqrt(252) ≈ 11.9%.
        double vol = out.summary().volAnnualized().doubleValue();
        assertTrue(vol > 0.10 && vol < 0.13, "ann vol = " + vol);

        // Sharpe should be strongly positive — annualised return ~~ (1.0025)^252 - 1 ≈ 88%.
        assertNotNull(out.summary().sharpe());
        assertTrue(out.summary().sharpe().doubleValue() > 5.0, "sharpe = " + out.summary().sharpe());

        // Sortino's denominator is RMS of the down days only, smaller than vol → ratio higher.
        assertNotNull(out.summary().sortino());
        assertTrue(out.summary().sortino().doubleValue() > out.summary().sharpe().doubleValue(),
                "sortino should exceed sharpe");
    }

    @Test
    void weekendDaysAreFilteredFromVolatility() {
        // Calendar daily with non-zero returns ONLY on Mon-Fri. If weekends were not filtered,
        // their zero returns would more than halve the stdev.
        LocalDate start = LocalDate.of(2024, 1, 1); // Monday
        List<ReturnPoint> growth = new ArrayList<>();
        BigDecimal g = new BigDecimal("1.0000000000");
        growth.add(new ReturnPoint(start.toString(), g));
        for (int i = 1; i < 300; i++) {
            LocalDate d = start.plusDays(i);
            boolean weekend = d.getDayOfWeek().getValue() > 5;
            // ±0.5% on weekdays, 0 on weekends (which is what the underlying value timeline emits)
            BigDecimal r = weekend ? BigDecimal.ZERO
                    : (i % 2 == 0 ? new BigDecimal("0.005") : new BigDecimal("-0.005"));
            g = g.multiply(BigDecimal.ONE.add(r));
            growth.add(new ReturnPoint(d.toString(), g));
        }
        ReturnTimeline rt = new ReturnTimeline(growth, List.of(), List.of(), List.of(),
                Summary.empty());
        RiskTimeline out = PortfolioRiskService.compute(rt, RF);

        // Sample stdev of ±0.5% pure series ≈ 0.005, annualised ≈ 7.9%. If weekend zeros had
        // been included (≈ 5/7 weight on 0 returns), the stdev would shrink to ~0.0033 → ~5.2%.
        double vol = out.summary().volAnnualized().doubleValue();
        assertTrue(vol > 0.07 && vol < 0.09, "weekday-filtered vol expected ~7.9%, got " + vol);
    }

    @Test
    void rollingVolPointsCoverFullSeriesAfterWarmup() {
        List<ReturnPoint> growth = alternatingGrowth(LocalDate.of(2024, 1, 1), 200, 0.01, -0.005);
        ReturnTimeline rt = new ReturnTimeline(growth, List.of(), List.of(), List.of(),
                Summary.empty());
        RiskTimeline out = PortfolioRiskService.compute(rt, RF);

        // 200 calendar days ≈ 142 weekdays. With a 63-day warmup and step 5, we should see
        // ~16 points and they should all be positive.
        assertTrue(out.rollingVol().size() >= 10, "expected >=10 rolling points, got "
                + out.rollingVol().size());
        for (var p : out.rollingVol()) {
            assertTrue(p.annualizedVol().signum() > 0, "rolling vol should be positive: " + p);
        }
    }

    @Test
    void recoveryDaysComputedFromDrawdownSeries() {
        // peak on day 0, trough on day 10, recovery on day 20.
        List<DrawdownPoint> dd = new ArrayList<>();
        LocalDate base = LocalDate.of(2024, 1, 1);
        for (int i = 0; i <= 30; i++) {
            BigDecimal v;
            if (i == 0 || i >= 20) v = BigDecimal.ZERO;
            else if (i == 10) v = new BigDecimal("-0.30");
            else v = new BigDecimal("-0.10");
            dd.add(new DrawdownPoint(base.plusDays(i).toString(), v));
        }
        Summary summary = new Summary(null, null, null, null,
                new BigDecimal("-0.30"), base.plusDays(10).toString());
        List<ReturnPoint> growth = flatGrowth(base, 50, "1.0");
        ReturnTimeline rt = new ReturnTimeline(growth, List.of(), dd, List.of(), summary);

        RiskTimeline out = PortfolioRiskService.compute(rt, RF);
        assertEquals(20, out.summary().recoveryDays(), "peak day 0 → recovery day 20");
        assertEquals("-0.30", out.summary().maxDrawdown().toPlainString());
    }

    @Test
    void recoveryDaysNullWhenStillUnderwater() {
        List<DrawdownPoint> dd = new ArrayList<>();
        LocalDate base = LocalDate.of(2024, 1, 1);
        dd.add(new DrawdownPoint(base.toString(), BigDecimal.ZERO));
        for (int i = 1; i <= 20; i++) {
            dd.add(new DrawdownPoint(base.plusDays(i).toString(), new BigDecimal("-0.20")));
        }
        Summary summary = new Summary(null, null, null, null,
                new BigDecimal("-0.20"), base.plusDays(10).toString());
        List<ReturnPoint> growth = flatGrowth(base, 30, "1.0");
        ReturnTimeline rt = new ReturnTimeline(growth, List.of(), dd, List.of(), summary);

        RiskTimeline out = PortfolioRiskService.compute(rt, RF);
        assertNull(out.summary().recoveryDays());
    }

    @Test
    void histogramBucketsAllReturnsIncludingTails() {
        // Mix of in-band, low-tail, high-tail returns. Verify bucket counts add up and tail
        // catchalls are populated when returns escape ±5%.
        List<ReturnPoint> growth = new ArrayList<>();
        LocalDate base = LocalDate.of(2024, 1, 1);
        BigDecimal g = BigDecimal.ONE.setScale(10);
        growth.add(new ReturnPoint(base.toString(), g));
        double[] dailyR = {0.001, -0.001, 0.06, -0.07, 0.005, -0.004, 0.001, 0.002};
        for (int i = 1; i < 200; i++) {
            LocalDate d = base.plusDays(i);
            // Only push on weekdays so the count matches.
            if (d.getDayOfWeek().getValue() > 5) {
                growth.add(new ReturnPoint(d.toString(), g));
                continue;
            }
            double r = dailyR[i % dailyR.length];
            g = g.multiply(BigDecimal.valueOf(1 + r));
            growth.add(new ReturnPoint(d.toString(), g));
        }
        ReturnTimeline rt = new ReturnTimeline(growth, List.of(), List.of(), List.of(),
                Summary.empty());
        RiskTimeline out = PortfolioRiskService.compute(rt, RF);

        // 22 buckets total.
        assertEquals(22, out.dailyReturnHistogram().size());
        int total = out.dailyReturnHistogram().stream().mapToInt(b -> b.count()).sum();
        // Equal to weekday-filtered count.
        int weekdays = (int) growth.stream().skip(1)
                .filter(p -> LocalDate.parse(p.date()).getDayOfWeek().getValue() <= 5)
                .count();
        assertEquals(weekdays, total);

        // At least one observation in each tail catchall (we injected ±6-7% returns).
        assertTrue(out.dailyReturnHistogram().get(0).count() > 0, "≤-5% tail should be populated");
        assertTrue(out.dailyReturnHistogram().get(21).count() > 0, "≥5% tail should be populated");
    }

    // ---- Helpers ----------------------------------------------------------

    private static List<ReturnPoint> flatGrowth(LocalDate start, int days, String value) {
        List<ReturnPoint> out = new ArrayList<>();
        BigDecimal g = new BigDecimal(value);
        for (int i = 0; i < days; i++) {
            out.add(new ReturnPoint(start.plusDays(i).toString(), g));
        }
        return out;
    }

    /** Calendar-daily series with alternating up/down returns on each step (weekends included). */
    private static List<ReturnPoint> alternatingGrowth(LocalDate start, int days,
                                                       double upR, double downR) {
        List<ReturnPoint> out = new ArrayList<>();
        BigDecimal g = BigDecimal.ONE.setScale(10);
        out.add(new ReturnPoint(start.toString(), g));
        for (int i = 1; i < days; i++) {
            double r = (i % 2 == 0) ? upR : downR;
            g = g.multiply(BigDecimal.valueOf(1 + r));
            out.add(new ReturnPoint(start.plusDays(i).toString(), g));
        }
        return out;
    }
}
