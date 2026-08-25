package com.portfolio.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A trailing dividend yield derived from a name's own payment history — the fallback for an
 * instrument whose {@code summaryDetail} carries no {@code dividendYield} and no
 * {@code dividendRate}.
 *
 * <p><b>Why this exists.</b> Yahoo returns a near-empty summary for some listings: HICL.L, a
 * London infrastructure trust paying ~6%, has no sector, no dividend yield and no dividend rate,
 * so the income overlay read it as a non-payer and filed £10,270 under Value with no warning. The
 * chart endpoint the price job <em>already calls</em> ships the full ex-date/amount stream for the
 * same instrument, so the yield is derivable at zero extra HTTP.
 *
 * <p><b>Units cancel, which is the point.</b> Amounts are per share on the same split-adjusted
 * basis as the {@code close} column, so dividing by a stored close yields a pure ratio — no FX, no
 * {@code GBp}-pence shift, no split unwinding. That is precisely the trap that made
 * {@code dividendRate ÷ price} 100× too small for every London listing.
 *
 * <h2>The estimator, and why it is not the obvious one</h2>
 * The headline is a <b>trailing-twelve-month sum</b>, which is what Yahoo's own
 * {@code dividendYield} turns out to be: measured against all 41 held names that publish one, a
 * TTM sum reproduces it to a mean absolute error of 0.06pp (worst 0.51pp).
 *
 * <p>A TTM window has one failure mode: it clips. When a payment slips a few weeks, the window can
 * hold three quarterly payments instead of four and the yield reads 25% light — HICL scored 4.48%
 * against a real 5.96% for exactly that reason, its July 2025 payment having fallen out before the
 * 2026 replacement went ex.
 *
 * <p>The fix is <b>not</b> to infer a payment frequency and annualise the latest amount. That was
 * measured too, and it is dangerous: inferring frequency from the median gap between ex-dates
 * misreads an unequal interim/final payer as quarterly, and multiplying by four then reports
 * <b>LGEN at 15.01% against a real 7.60%</b> — a doubling, in the direction that flatters. Mean
 * error across the book degrades from 0.06pp to 0.34pp and the worst case to 7.41pp.
 *
 * <p>Instead the window is widened to hold <b>{@code k} payments, where {@code k} is the larger of
 * this year's and last year's payment count</b> — the name's own cadence, read off its own record,
 * with nothing guessed. When payments are regular the two counts agree and this <em>is</em> the TTM
 * sum; it differs only when the window clipped. Measured across the same 41 names it is
 * <b>identical to TTM on every one</b> (same 0.06pp mean, same 0.51pp worst), and HICL is the only
 * holding in the portfolio where it moves at all — to 5.96%. Strictly dominant, so it is what ships.
 */
public final class DividendYield {

    private static final MathContext MC = new MathContext(12);
    /** One year, in days. Leap-year drift is far below the spacing between payments. */
    private static final int YEAR_DAYS = 365;

    private DividendYield() {
    }

    /** One cash payment: when it went ex, and how much per share. */
    public record Payment(LocalDate exDate, BigDecimal amount) {
    }

    /**
     * The derived figure and the provenance a reader needs to argue with it.
     *
     * @param yield        annual dividend ÷ price, as a fraction; never null
     * @param annualAmount the summed cash per share behind it, in the price's own units
     * @param payments     how many payments were summed
     * @param lastExDate   the most recent ex-date summed, or null when nothing was
     * @param windowWidened true when the trailing year had clipped a payment and the window was
     *                      widened to last year's count — the HICL case, worth surfacing
     */
    public record Result(BigDecimal yield, BigDecimal annualAmount, int payments,
                         LocalDate lastExDate, boolean windowWidened) {

        /** A scanned name that has never paid: a real zero, not a gap. */
        static Result none() {
            return new Result(BigDecimal.ZERO, BigDecimal.ZERO, 0, null, false);
        }

        /** One line of provenance for the tooltip / modal, so a placement can be checked. */
        public String explain() {
            if (payments == 0) return "No dividends on record";
            return payments + (payments == 1 ? " payment" : " payments")
                    + " to " + lastExDate
                    + (windowWidened ? " (trailing year clipped one — widened to last year's count)" : "");
        }
    }

    /**
     * Derives the yield, or null when it cannot be stated.
     *
     * <p>Null means "we cannot say", never "zero": a caller has to tell an accumulating fund that
     * genuinely distributes nothing from a name whose history was never scanned, and a silent zero
     * would understate the portfolio's income with nothing on screen to explain it. An empty
     * payment list from a <em>scanned</em> symbol is the genuine zero, and the caller signals that
     * by calling this at all — see {@code DividendHistoryRepository.hasScanned}.
     *
     * @param payments every known payment for the symbol, any order; may be empty
     * @param price    the latest close, in the same units as the amounts; null or ≤ 0 yields null
     * @param asOf     the date the trailing windows are measured back from
     */
    public static Result derive(List<Payment> payments, BigDecimal price, LocalDate asOf) {
        if (price == null || price.signum() <= 0 || asOf == null) return null;
        if (payments == null || payments.isEmpty()) return Result.none();

        List<Payment> sorted = new ArrayList<>(payments);
        sorted.removeIf(p -> p == null || p.exDate() == null || p.amount() == null
                || p.amount().signum() <= 0);
        if (sorted.isEmpty()) return Result.none();
        sorted.sort(Comparator.comparing(Payment::exDate));

        LocalDate oneYearAgo = asOf.minusDays(YEAR_DAYS);
        LocalDate twoYearsAgo = asOf.minusDays(2L * YEAR_DAYS);

        int thisYear = 0, lastYear = 0;
        for (Payment p : sorted) {
            LocalDate d = p.exDate();
            if (d.isAfter(asOf)) continue;                       // declared but not yet ex
            if (d.isAfter(oneYearAgo)) thisYear++;
            else if (d.isAfter(twoYearsAgo)) lastYear++;
        }
        // Nothing in two years: on the record, it has stopped paying. A yield off a payment older
        // than that would describe an instrument that no longer exists.
        if (thisYear == 0 && lastYear == 0) return Result.none();

        int k = Math.max(thisYear, lastYear);
        List<Payment> eligible = new ArrayList<>();
        for (Payment p : sorted) {
            if (!p.exDate().isAfter(asOf)) eligible.add(p);
        }
        List<Payment> summed = eligible.subList(Math.max(0, eligible.size() - k), eligible.size());

        BigDecimal annual = BigDecimal.ZERO;
        for (Payment p : summed) annual = annual.add(p.amount());

        return new Result(annual.divide(price, MC), annual, summed.size(),
                summed.get(summed.size() - 1).exDate(), thisYear < lastYear);
    }
}
