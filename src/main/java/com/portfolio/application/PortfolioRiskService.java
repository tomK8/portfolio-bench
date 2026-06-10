package com.portfolio.application;

import com.portfolio.application.PortfolioReturnService.DrawdownPoint;
import com.portfolio.application.PortfolioReturnService.ReturnPoint;
import com.portfolio.application.PortfolioReturnService.ReturnTimeline;
import com.portfolio.persistence.KeyValueStore;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Risk metrics derived from the daily TWR series in {@link PortfolioReturnService}.
 *
 * <p>Volatility = annualised sample stdev of weekday daily returns × √252. Weekends are
 * forward-filled to the prior trading day's price in the underlying value series, so weekend
 * "returns" are exactly zero and would silently compress vol toward zero. They're filtered
 * out here.
 *
 * <p>Sharpe = ({@code annualisedReturn} − {@code rf}) / {@code annualisedVol}, with
 * {@code annualisedReturn} = (1 + meanDailyReturn)^252 − 1 (compound, matches the way the
 * Returns tab annualises trailing windows). Sortino swaps the denominator for downside
 * deviation (rms of negative excess returns, annualised the same way). Calmar =
 * {@code annualisedReturn} / |maxDrawdown|.
 *
 * <p>{@code rf} is a single annual fraction stored in {@link KeyValueStore} under the
 * {@link #RISK_FREE_RATE_KEY} key, default {@link #DEFAULT_RISK_FREE_RATE}. The UI accepts a
 * percentage and the controller divides by 100 before writing.
 */
public class PortfolioRiskService {

    public static final String RISK_FREE_RATE_KEY = "risk_free_rate";
    public static final BigDecimal DEFAULT_RISK_FREE_RATE = new BigDecimal("0.04");

    private static final int TRADING_DAYS = 252;
    private static final int ROLLING_WINDOW = 63;
    private static final int ROLLING_STEP = 5;
    private static final int OUTPUT_SCALE = 6;

    private final PortfolioReturnService returnService;
    private final KeyValueStore settings;

    public PortfolioRiskService(PortfolioReturnService returnService, KeyValueStore settings) {
        this.returnService = returnService;
        this.settings = settings;
    }

    public RiskTimeline timeline() {
        return compute(returnService.timeline(), riskFreeRate());
    }

    public BigDecimal riskFreeRate() {
        return settings.getBigDecimal(RISK_FREE_RATE_KEY, DEFAULT_RISK_FREE_RATE);
    }

    public void setRiskFreeRate(BigDecimal rate) {
        settings.putBigDecimal(RISK_FREE_RATE_KEY, rate);
    }

    /** Package-private for unit tests — feeds a synthetic ReturnTimeline through the math. */
    static RiskTimeline compute(ReturnTimeline rt, BigDecimal rfFraction) {
        double rf = rfFraction.doubleValue();
        List<ReturnPoint> g = rt.growthPoints();
        if (g.size() < 2) {
            return new RiskTimeline(RiskSummary.empty(rfFraction), List.of(),
                    rt.drawdownPoints(), List.of());
        }

        List<DailyReturn> daily = dailyReturns(g);
        if (daily.size() < 2) {
            return new RiskTimeline(RiskSummary.empty(rfFraction), List.of(),
                    rt.drawdownPoints(), List.of());
        }
        double[] all = toArray(daily);

        double meanAll = mean(all);
        double stdAll = stdev(all, meanAll);
        double annVol = stdAll * Math.sqrt(TRADING_DAYS);
        double annRet = Math.pow(1 + meanAll, TRADING_DAYS) - 1;
        double downsideDev = downsideDeviation(all, rf / TRADING_DAYS) * Math.sqrt(TRADING_DAYS);

        Double vol1y = trailingVol(daily, 1);
        Double sharpe = annVol > 0 ? (annRet - rf) / annVol : null;
        Double sharpe1y = trailingSharpe(daily, 1, rf);
        Double sortino = downsideDev > 0 ? (annRet - rf) / downsideDev : null;

        BigDecimal maxDdBd = rt.summary().maxDrawdown();
        String maxDdDate = rt.summary().maxDrawdownDate();
        double maxDdAbs = maxDdBd == null ? 0.0 : Math.abs(maxDdBd.doubleValue());

        Integer recoveryDays = recoveryDays(rt.drawdownPoints(), maxDdDate);
        Double calmar = (maxDdAbs > 0 && annRet > 0) ? annRet / maxDdAbs : null;

        RiskSummary summary = new RiskSummary(
                bd(annVol), bd(vol1y),
                bd(sharpe), bd(sharpe1y),
                bd(sortino),
                maxDdBd, maxDdDate, recoveryDays,
                bd(calmar),
                rfFraction);
        return new RiskTimeline(summary, rollingVol(daily), rt.drawdownPoints(), histogram(all));
    }

    private static List<DailyReturn> dailyReturns(List<ReturnPoint> g) {
        List<DailyReturn> out = new ArrayList<>();
        for (int i = 1; i < g.size(); i++) {
            LocalDate d = LocalDate.parse(g.get(i).date());
            if (d.getDayOfWeek().getValue() > 5) continue;
            BigDecimal gPrev = g.get(i - 1).growth();
            BigDecimal gCur = g.get(i).growth();
            if (gPrev.signum() <= 0) continue;
            double r = gCur.doubleValue() / gPrev.doubleValue() - 1;
            out.add(new DailyReturn(d, r));
        }
        return out;
    }

    private static double[] toArray(List<DailyReturn> daily) {
        double[] out = new double[daily.size()];
        for (int i = 0; i < daily.size(); i++) out[i] = daily.get(i).r();
        return out;
    }

    private static double mean(double[] a) {
        if (a.length == 0) return 0;
        double s = 0;
        for (double v : a) s += v;
        return s / a.length;
    }

    /** Sample stdev (N-1 denominator). Zero when fewer than 2 observations. */
    private static double stdev(double[] a, double mean) {
        if (a.length < 2) return 0;
        double s = 0;
        for (double v : a) {
            double d = v - mean;
            s += d * d;
        }
        return Math.sqrt(s / (a.length - 1));
    }

    /**
     * RMS of {@code (r − rfPerDay)} restricted to days where the excess return is negative,
     * divided by the count of those days (Sortino convention, not full-sample N-1). Caller
     * multiplies by √252 to annualise.
     */
    private static double downsideDeviation(double[] a, double rfPerDay) {
        double s = 0;
        int n = 0;
        for (double v : a) {
            double d = v - rfPerDay;
            if (d < 0) { s += d * d; n++; }
        }
        return n == 0 ? 0 : Math.sqrt(s / n);
    }

    private static Double trailingVol(List<DailyReturn> daily, int years) {
        double[] a = window(daily, years);
        if (a.length < 2) return null;
        return stdev(a, mean(a)) * Math.sqrt(TRADING_DAYS);
    }

    private static Double trailingSharpe(List<DailyReturn> daily, int years, double rf) {
        double[] a = window(daily, years);
        if (a.length < 2) return null;
        double m = mean(a);
        double s = stdev(a, m);
        if (s == 0) return null;
        double annRet = Math.pow(1 + m, TRADING_DAYS) - 1;
        return (annRet - rf) / (s * Math.sqrt(TRADING_DAYS));
    }

    private static double[] window(List<DailyReturn> daily, int years) {
        LocalDate cutoff = daily.get(daily.size() - 1).d().minusYears(years);
        List<Double> w = new ArrayList<>();
        for (DailyReturn r : daily) if (r.d().isAfter(cutoff)) w.add(r.r());
        double[] a = new double[w.size()];
        for (int i = 0; i < w.size(); i++) a[i] = w.get(i);
        return a;
    }

    /**
     * Calendar days from the peak (last {@code drawdown == 0} on or before the trough) to the
     * recovery (next {@code drawdown == 0} after the trough). Returns {@code null} when the
     * series hasn't recovered yet — i.e. every point after the trough is still underwater.
     */
    private static Integer recoveryDays(List<DrawdownPoint> dd, String maxDdDate) {
        if (dd.isEmpty() || maxDdDate == null) return null;
        int troughIdx = -1;
        for (int i = 0; i < dd.size(); i++) {
            if (dd.get(i).date().equals(maxDdDate)) { troughIdx = i; break; }
        }
        if (troughIdx < 0) return null;
        int peakIdx = 0;
        for (int i = troughIdx; i >= 0; i--) {
            if (dd.get(i).drawdown().signum() == 0) { peakIdx = i; break; }
        }
        for (int i = troughIdx + 1; i < dd.size(); i++) {
            if (dd.get(i).drawdown().signum() == 0) {
                return (int) ChronoUnit.DAYS.between(
                        LocalDate.parse(dd.get(peakIdx).date()),
                        LocalDate.parse(dd.get(i).date()));
            }
        }
        return null;
    }

    private static List<RollingVolPoint> rollingVol(List<DailyReturn> daily) {
        List<RollingVolPoint> out = new ArrayList<>();
        if (daily.size() < ROLLING_WINDOW) return out;
        double sqrtTd = Math.sqrt(TRADING_DAYS);
        for (int i = ROLLING_WINDOW - 1; i < daily.size(); i += ROLLING_STEP) {
            double[] win = new double[ROLLING_WINDOW];
            for (int j = 0; j < ROLLING_WINDOW; j++) {
                win[j] = daily.get(i - ROLLING_WINDOW + 1 + j).r();
            }
            double m = mean(win);
            double s = stdev(win, m) * sqrtTd;
            out.add(new RollingVolPoint(daily.get(i).d().toString(),
                    BigDecimal.valueOf(s).setScale(OUTPUT_SCALE, RoundingMode.HALF_UP)));
        }
        return out;
    }

    /**
     * 22 fixed buckets: a "≤-5%" catchall, twenty 0.5%-wide bins from −5% to +5%, and a
     * "≥5%" catchall. Most days fall inside ±2%; the long tails are the story this chart
     * tells.
     */
    private static List<HistogramBucket> histogram(double[] returns) {
        int n = 22;
        double lo = -0.05;
        double hi = 0.05;
        double width = (hi - lo) / 20.0;
        int[] counts = new int[n];
        for (double r : returns) {
            if (r < lo) counts[0]++;
            else if (r >= hi) counts[n - 1]++;
            else {
                int idx = 1 + (int) Math.floor((r - lo) / width);
                if (idx >= n - 1) idx = n - 2;
                counts[idx]++;
            }
        }
        List<HistogramBucket> out = new ArrayList<>();
        out.add(new HistogramBucket("≤-5%", counts[0]));
        for (int i = 1; i < n - 1; i++) {
            double mid = lo + (i - 0.5) * width;
            out.add(new HistogramBucket(String.format("%+.1f%%", mid * 100), counts[i]));
        }
        out.add(new HistogramBucket("≥5%", counts[n - 1]));
        return out;
    }

    private static BigDecimal bd(Double d) {
        if (d == null || d.isNaN() || d.isInfinite()) return null;
        return BigDecimal.valueOf(d).setScale(OUTPUT_SCALE, RoundingMode.HALF_UP);
    }

    // ---- DTOs ----------------------------------------------------------------

    private record DailyReturn(LocalDate d, double r) {
    }

    public record RollingVolPoint(String date, BigDecimal annualizedVol) {
    }

    public record HistogramBucket(String label, int count) {
    }

    /**
     * Risk summary. Volatilities are annualised fractions (0.15 = 15%/yr). Sharpe / Sortino /
     * Calmar are pure ratios. {@code maxDrawdown} is the negative fraction from
     * {@link PortfolioReturnService.Summary}. {@code recoveryDays} is null when the portfolio
     * hasn't yet returned to its pre-trough peak. {@code riskFreeRate} echoes the rate used,
     * so the UI can show what the math was anchored to.
     */
    public record RiskSummary(
            BigDecimal volAnnualized, BigDecimal vol1y,
            BigDecimal sharpe, BigDecimal sharpe1y,
            BigDecimal sortino,
            BigDecimal maxDrawdown, String maxDrawdownDate, Integer recoveryDays,
            BigDecimal calmar,
            BigDecimal riskFreeRate) {

        static RiskSummary empty(BigDecimal rf) {
            return new RiskSummary(null, null, null, null, null, null, null, null, null, rf);
        }
    }

    public record RiskTimeline(RiskSummary summary,
                               List<RollingVolPoint> rollingVol,
                               List<DrawdownPoint> drawdownPoints,
                               List<HistogramBucket> dailyReturnHistogram) {
    }
}
