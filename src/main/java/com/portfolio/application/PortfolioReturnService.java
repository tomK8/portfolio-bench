package com.portfolio.application;

import com.portfolio.application.PortfolioValueService.DailyValue;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.port.HistoricalFxRateProvider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Time-weighted return (TWR) of the portfolio.
 *
 * <p>For each calendar day the daily return is
 * <pre>r_t = (V_t − V_{t-1} − C_t) / V_{t-1}</pre>
 * where {@code V_t} is the GBP portfolio value from {@link PortfolioValueService#dailyValues()}
 * and {@code C_t} is the net external GBP contribution on day {@code t} (sum of
 * {@code CONTRIBUTION} ledger rows in {@code amountGbp}, plus the Roth USD seed converted to
 * GBP at the FX rate on its earliest ledger date — same convention as {@link ContributionService}).
 *
 * <p>Cumulative growth-of-£1 is {@code G_t = G_{t-1} × (1 + r_t)}, anchored at {@code 1.0} on
 * the first day the portfolio has a non-zero value. Trailing 1y/3y/5y returns read
 * {@code G_now / G_then − 1} for the appropriate {@code then}; windows longer than a year are
 * annualised as {@code (G_now / G_then)^(365.25/days) − 1}.
 *
 * <p>Known limitation: II's "Trf from AJ Bell" rows are recorded as contributions on the II
 * side without a matching outflow on the AJBell side (see {@link ContributionService}). That
 * inflates total contributions and depresses computed TWR on those days. The effect is
 * concentrated on transfer dates; trailing windows that don't cross such a date are unaffected.
 */
public class PortfolioReturnService {

    private static final int SCALE = 10;
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365.25");

    private final PortfolioValueService valueService;
    private final CashTransactionRepository cashRepo;
    private final KeyValueStore settings;
    private final HistoricalFxRateProvider fxProvider;

    public PortfolioReturnService(PortfolioValueService valueService,
                                  CashTransactionRepository cashRepo,
                                  KeyValueStore settings,
                                  HistoricalFxRateProvider fxProvider) {
        this.valueService = valueService;
        this.cashRepo = cashRepo;
        this.settings = settings;
        this.fxProvider = fxProvider;
    }

    public ReturnTimeline timeline() {
        List<DailyValue> values = valueService.dailyValues();
        if (values.isEmpty()) {
            return new ReturnTimeline(List.of(), List.of(), List.of(), List.of(), Summary.empty());
        }

        Map<LocalDate, BigDecimal> contribByDate = contributionsByDate();

        List<ReturnPoint> growthPoints = new ArrayList<>();
        List<ContribPoint> contribPoints = new ArrayList<>();
        BigDecimal growth = BigDecimal.ONE.setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal cumContrib = BigDecimal.ZERO;
        BigDecimal prevV = null;
        boolean chainStarted = false;

        for (DailyValue dv : values) {
            BigDecimal c = contribByDate.getOrDefault(dv.date(), BigDecimal.ZERO);
            cumContrib = cumContrib.add(c);

            if (!chainStarted) {
                // Wait for the portfolio to have value before starting the growth chain.
                if (dv.valueGbp().signum() > 0) {
                    chainStarted = true;
                    growthPoints.add(new ReturnPoint(dv.date().toString(), growth));
                    contribPoints.add(new ContribPoint(dv.date().toString(), cumContrib));
                    prevV = dv.valueGbp();
                }
                continue;
            }

            if (prevV.signum() > 0) {
                BigDecimal r = dv.valueGbp().subtract(prevV).subtract(c)
                        .divide(prevV, SCALE, RoundingMode.HALF_UP);
                growth = growth.multiply(BigDecimal.ONE.add(r))
                        .setScale(SCALE, RoundingMode.HALF_UP);
            }
            growthPoints.add(new ReturnPoint(dv.date().toString(), growth));
            contribPoints.add(new ContribPoint(dv.date().toString(), cumContrib));
            prevV = dv.valueGbp();
        }

        List<DrawdownPoint> drawdowns = drawdowns(growthPoints);
        List<AnnualReturn> annual = annualReturns(growthPoints);
        return new ReturnTimeline(growthPoints, contribPoints, drawdowns, annual,
                summarise(growthPoints, drawdowns));
    }

    /**
     * Calendar-year returns derived from the growth series. For each year between the first
     * and last growth dates:
     * <ul>
     *   <li>Anchor = growth on or before the prior Dec 31 (for the first year, the very first
     *       growth point — so its return is partial-period).</li>
     *   <li>Close = growth on or before Dec 31 of the year (for the current year, the latest
     *       growth point — also partial).</li>
     *   <li>{@code return = close / anchor − 1}.</li>
     * </ul>
     * {@code partial} flags the inception year and the in-progress current year so the UI can
     * show them differently from full calendar years.
     */
    private static List<AnnualReturn> annualReturns(List<ReturnPoint> growth) {
        if (growth.isEmpty()) return List.of();
        LocalDate firstDate = LocalDate.parse(growth.get(0).date());
        LocalDate lastDate = LocalDate.parse(growth.get(growth.size() - 1).date());
        List<AnnualReturn> out = new ArrayList<>();
        for (int year = firstDate.getYear(); year <= lastDate.getYear(); year++) {
            ReturnPoint anchor = (year == firstDate.getYear())
                    ? growth.get(0)
                    : findOnOrBefore(growth, LocalDate.of(year - 1, 12, 31));
            ReturnPoint close = (year == lastDate.getYear())
                    ? growth.get(growth.size() - 1)
                    : findOnOrBefore(growth, LocalDate.of(year, 12, 31));
            if (anchor == null || close == null
                    || anchor.growth().signum() <= 0
                    || anchor.date().equals(close.date())) continue;
            BigDecimal r = close.growth().divide(anchor.growth(), SCALE, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE);
            boolean partial = (year == firstDate.getYear() && firstDate.getDayOfYear() > 1)
                    || (year == lastDate.getYear() && lastDate.getDayOfYear() < 365);
            out.add(new AnnualReturn(year, r, partial,
                    anchor.date(), close.date()));
        }
        return out;
    }

    /**
     * Underwater curve: for each growth point, {@code (growth − running_peak) / running_peak}
     * — always in {@code [-1, 0]}, with 0 meaning "at all-time-high". Computed on the
     * growth-of-£1 series (TWR), so contributions don't masquerade as drawdown recoveries.
     */
    private static List<DrawdownPoint> drawdowns(List<ReturnPoint> growth) {
        List<DrawdownPoint> out = new ArrayList<>();
        BigDecimal peak = BigDecimal.ZERO;
        for (ReturnPoint p : growth) {
            if (p.growth().compareTo(peak) > 0) peak = p.growth();
            BigDecimal dd = peak.signum() > 0
                    ? p.growth().subtract(peak).divide(peak, SCALE, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            out.add(new DrawdownPoint(p.date(), dd));
        }
        return out;
    }

    /**
     * Sum every external GBP cash flow per date: ledger {@code CONTRIBUTION} rows in
     * {@code amountGbp} (FX baked in at import time), plus the Roth USD seed converted at
     * the FX rate on Roth's earliest ledger date.
     */
    private Map<LocalDate, BigDecimal> contributionsByDate() {
        Map<LocalDate, BigDecimal> out = new HashMap<>();
        for (CashTransaction t : cashRepo.loadContributions()) {
            out.merge(LocalDate.parse(t.transactionDate()),
                    BigDecimal.valueOf(t.amountGbp()), BigDecimal::add);
        }
        BigDecimal rothSeedUsd = settings.getBigDecimal(
                CashTransactionRepository.ROTH_BROUGHT_FORWARD_KEY, BigDecimal.ZERO);
        String rothStartStr = cashRepo.earliestTransactionDate(Account.ROTH_IRA);
        if (rothSeedUsd.signum() > 0 && rothStartStr != null) {
            LocalDate rothStart = LocalDate.parse(rothStartStr);
            BigDecimal seedGbp = rothSeedToGbp(rothSeedUsd, rothStart);
            if (seedGbp.signum() > 0) {
                out.merge(rothStart, seedGbp, BigDecimal::add);
            }
        }
        return out;
    }

    private BigDecimal rothSeedToGbp(BigDecimal seedUsd, LocalDate rothStart) {
        try {
            Map<LocalDate, BigDecimal> series = fxProvider.fetchRateSeries(
                    "USD", rothStart.minusDays(14), rothStart.plusDays(1));
            BigDecimal rate = HistoricalFxRateProvider.rateOnOrBefore(series, rothStart);
            if (rate == null || rate.signum() == 0) return BigDecimal.ZERO;
            return seedUsd.divide(rate, 2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private static Summary summarise(List<ReturnPoint> points, List<DrawdownPoint> drawdowns) {
        if (points.isEmpty()) return Summary.empty();
        ReturnPoint last = points.get(points.size() - 1);
        LocalDate now = LocalDate.parse(last.date());
        BigDecimal gNow = last.growth();
        BigDecimal maxDD = BigDecimal.ZERO;
        String maxDDDate = null;
        for (DrawdownPoint d : drawdowns) {
            if (d.drawdown().compareTo(maxDD) < 0) {
                maxDD = d.drawdown();
                maxDDDate = d.date();
            }
        }
        return new Summary(
                trailing(points, now.minusYears(1), gNow),
                trailing(points, now.minusYears(3), gNow),
                trailing(points, now.minusYears(5), gNow),
                trailing(points, LocalDate.parse(points.get(0).date()), gNow),
                maxDD, maxDDDate);
    }

    /**
     * Trailing return as a fractional GBP gain (0.07 = +7%). For windows longer than a year
     * the result is annualised; for ≤1y it's the simple total return over the window.
     * Returns {@code null} when the portfolio history doesn't cover the window — i.e. the
     * latest point on or before {@code windowStart} sits more than {@link #WINDOW_TOLERANCE_DAYS}
     * earlier than the window itself (so any return computed against it would silently
     * span a longer period than the user asked for).
     */
    private static BigDecimal trailing(List<ReturnPoint> points, LocalDate windowStart, BigDecimal gNow) {
        ReturnPoint anchor = findOnOrBefore(points, windowStart);
        if (anchor == null) return null;
        LocalDate anchorDate = LocalDate.parse(anchor.date());
        if (anchorDate.isBefore(windowStart.minusDays(WINDOW_TOLERANCE_DAYS))) return null;
        BigDecimal gThen = anchor.growth();
        if (gThen.signum() <= 0) return null;
        BigDecimal ratio = gNow.divide(gThen, SCALE, RoundingMode.HALF_UP);
        long days = ChronoUnit.DAYS.between(anchorDate,
                LocalDate.parse(points.get(points.size() - 1).date()));
        if (days <= 366) {
            return ratio.subtract(BigDecimal.ONE);
        }
        // Annualise: ratio^(365.25/days) − 1, via doubles (BigDecimal has no pow with fractional exponent).
        double annual = Math.pow(ratio.doubleValue(),
                DAYS_PER_YEAR.doubleValue() / days) - 1.0;
        return BigDecimal.valueOf(annual).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Maximum gap (days) between the window's start and the anchor data point. Dense daily data hits 0. */
    private static final int WINDOW_TOLERANCE_DAYS = 7;

    /** Latest point whose date is on or before {@code target}, or null if none. */
    private static ReturnPoint findOnOrBefore(List<ReturnPoint> points, LocalDate target) {
        ReturnPoint best = null;
        for (ReturnPoint p : points) {
            LocalDate d = LocalDate.parse(p.date());
            if (d.isAfter(target)) break;
            best = p;
        }
        return best;
    }

    // ---- DTOs ----------------------------------------------------------------

    public record ReturnPoint(String date, BigDecimal growth) {
    }

    public record ContribPoint(String date, BigDecimal cumulativeGbp) {
    }

    /**
     * Underwater point: {@code drawdown} is a non-positive fraction (e.g. −0.15 = 15% below
     * the running TWR peak); 0 means the portfolio is at all-time-high on that day.
     */
    public record DrawdownPoint(String date, BigDecimal drawdown) {
    }

    /**
     * Trailing returns as fractional GBP gains (0.07 = +7%). 1y is the raw period return;
     * 3y/5y/since are annualised. {@code maxDrawdown} is the deepest underwater fraction
     * across the entire history (negative, e.g. −0.25 = a 25% peak-to-trough drop), and
     * {@code maxDrawdownDate} is the day it bottomed. Any field is {@code null} when the
     * portfolio history is shorter than the corresponding window.
     */
    public record Summary(BigDecimal trailing1y, BigDecimal trailing3y,
                          BigDecimal trailing5y, BigDecimal sinceInception,
                          BigDecimal maxDrawdown, String maxDrawdownDate) {
        public static Summary empty() {
            return new Summary(null, null, null, null, null, null);
        }
    }

    /**
     * One calendar year's TWR. {@code partial} = true for the inception year (started mid-year)
     * and the current year (in progress). {@code fromDate}/{@code toDate} are the actual growth-
     * series anchor and close — useful in tooltips to show the underlying window.
     */
    public record AnnualReturn(int year, BigDecimal returnPct, boolean partial,
                               String fromDate, String toDate) {
    }

    public record ReturnTimeline(List<ReturnPoint> growthPoints,
                                 List<ContribPoint> contributionPoints,
                                 List<DrawdownPoint> drawdownPoints,
                                 List<AnnualReturn> annualReturns,
                                 Summary summary) {
    }
}
