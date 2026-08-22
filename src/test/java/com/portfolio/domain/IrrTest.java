package com.portfolio.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class IrrTest {

    private static Irr.Flow f(String date, double amount) {
        return new Irr.Flow(LocalDate.parse(date), amount);
    }

    /** NPV at the solved rate, on the same basis the solver used — the definition of a root. */
    private static double npvAt(List<Irr.Flow> flows, double r, double basis) {
        LocalDate t0 = flows.stream().map(Irr.Flow::date).min(LocalDate::compareTo).orElseThrow();
        double s = 0;
        for (Irr.Flow fl : flows) {
            double dt = (fl.date().toEpochDay() - t0.toEpochDay()) / basis;
            s += fl.amount() * Math.pow(1 + r, -dt);
        }
        return s;
    }

    @Test
    void singleBuyAndTerminalValueEqualsCagrExactly() {
        // £1,000 in, £2,000 out five years later. With one purchase there IS a single holding
        // period, so IRR must collapse onto the CAGR — the check that the discounting is right.
        List<Irr.Flow> flows = List.of(f("2020-01-01", -1000), f("2025-01-01", 2000));
        double days = LocalDate.parse("2025-01-01").toEpochDay() - LocalDate.parse("2020-01-01").toEpochDay();
        double cagr = Math.pow(2000.0 / 1000.0, Irr.DAYS_PER_YEAR_XIRR / days) - 1;

        Double irr = Irr.solve(flows);
        assertNotNull(irr);
        assertEquals(cagr, irr, 1e-9);
        assertEquals(0.148524, irr, 1e-6);
    }

    @Test
    void twoEqualBuysOneYearApartWithFlatPricesIsZero() {
        // £1,000 in, another £1,000 in a year later, worth exactly £2,000 today: nothing was
        // earned, so the rate is zero however the flows are spaced.
        List<Irr.Flow> flows = List.of(
                f("2024-01-01", -1000),
                f("2025-01-01", -1000),
                f("2026-01-01", 2000));

        Double irr = Irr.solve(flows);
        assertNotNull(irr);
        assertEquals(0.0, irr, 1e-9);
    }

    @Test
    void reproducesSpreadsheetXirrOnTheDocumentedWorkedExample() {
        // Microsoft's own published XIRR example; Excel and Sheets both return 0.373362535.
        List<Irr.Flow> flows = List.of(
                f("2008-01-01", -10000),
                f("2008-03-01", 2750),
                f("2008-10-30", 4250),
                f("2009-02-15", 3250),
                f("2009-04-01", 2750));

        Double irr = Irr.solve(flows);
        assertNotNull(irr);
        assertEquals(0.373362535, irr, 1e-8);
    }

    @Test
    void solvesCleanlyWhenFlowMagnitudesDifferByOrdersOfMagnitude() {
        // A quarter-million-pound purchase against penny dividends: the NPV curve is flat
        // enough here to throw a Newton step past -1, which is what the bisection fallback
        // exists for. Whichever answers, the result must actually be a root.
        List<Irr.Flow> flows = List.of(
                f("2019-06-03", -250000.0),
                f("2020-06-03", 0.01),
                f("2021-06-03", 0.02),
                f("2022-06-03", 0.01),
                f("2026-08-22", 415000.0));

        Irr.Solution s = Irr.solveDetailed(flows, Irr.DAYS_PER_YEAR_XIRR);
        assertNotNull(s);
        assertTrue(Math.abs(npvAt(flows, s.rate(), Irr.DAYS_PER_YEAR_XIRR)) < 1e-4,
                "solved rate must zero the NPV, method=" + s.method());
        assertTrue(s.rate() > 0.06 && s.rate() < 0.08, "≈7%/yr, got " + s.rate());
    }

    @Test
    void everySolvedRateIsARootWhicheverSolverAnswered() {
        // The fallback's contract is that the *answer* is right, not that Newton produced it.
        // Sweep enough shapes to exercise both branches and assert the invariant on all of them.
        Random rnd = new Random(20260822L);
        int newton = 0, bisection = 0;
        for (int i = 0; i < 400; i++) {
            List<Irr.Flow> flows = new ArrayList<>();
            LocalDate d = LocalDate.parse("2015-01-05").plusDays(rnd.nextInt(400));
            double invested = 0;
            int buys = 1 + rnd.nextInt(6);
            for (int b = 0; b < buys; b++) {
                double amt = Math.pow(10, rnd.nextInt(6)) * (1 + rnd.nextDouble());
                flows.add(new Irr.Flow(d, -amt));
                invested += amt;
                d = d.plusDays(1 + rnd.nextInt(900));
            }
            flows.add(new Irr.Flow(d.plusDays(1 + rnd.nextInt(900)), invested * (0.05 + 4 * rnd.nextDouble())));

            Irr.Solution s = Irr.solveDetailed(flows, Irr.DAYS_PER_YEAR_XIRR);
            if (s == null) continue;   // root outside [-0.99, 10] — reported, not clamped
            if (s.method() == Irr.Method.NEWTON) newton++; else bisection++;
            double scale = flows.stream().mapToDouble(fl -> Math.abs(fl.amount())).sum();
            assertTrue(Math.abs(npvAt(flows, s.rate(), Irr.DAYS_PER_YEAR_XIRR)) < 1e-6 * scale,
                    "not a root: rate=" + s.rate() + " method=" + s.method() + " flows=" + flows);
            assertTrue(s.rate() > Irr.MIN_RATE && s.rate() < Irr.MAX_RATE);
        }
        assertTrue(newton > 0, "Newton should carry the ordinary cases");
        assertTrue(newton + bisection > 300, "most shapes should solve");
    }

    @Test
    void flowsThatNeverChangeSignHaveNoRate() {
        // You cannot earn a return on money you never put in. Bisection would otherwise
        // report its bracket midpoint as an answer.
        assertNull(Irr.solve(List.of(f("2024-01-01", -100), f("2025-01-01", -100))));
        assertNull(Irr.solve(List.of(f("2024-01-01", 100), f("2025-01-01", 100))));
    }

    @Test
    void degenerateInputYieldsNullNotAnAnswer() {
        assertNull(Irr.solve(List.of()));
        assertNull(Irr.solve(List.of(f("2024-01-01", -100))));
        assertNull(Irr.solve(List.of(f("2024-01-01", -100), f("2025-01-01", 200)), 0));
    }

    @Test
    void aTotalLossBeyondTheBracketIsReportedRatherThanClamped() {
        // Worth 0.001% of cost after a year: the root sits below -0.99 and must not be
        // silently pinned to the bracket edge and printed as "-99%".
        assertNull(Irr.solve(List.of(f("2024-01-01", -100000), f("2025-01-01", 0.001))));
    }

    @Test
    void sameDayFlowsDoNotDivideByZeroSpan() {
        // Everything on one date: no time passes, so no annual rate exists.
        assertNull(Irr.solve(List.of(f("2024-01-01", -100), f("2024-01-01", 120))));
    }

    @Test
    void theTwoDayCountBasesDifferOnlySlightlyAndBothSolve() {
        List<Irr.Flow> flows = List.of(f("2020-01-01", -1000), f("2025-01-01", 2000));
        Double xirr = Irr.solve(flows, Irr.DAYS_PER_YEAR_XIRR);
        Double house = Irr.solve(flows, Irr.DAYS_PER_YEAR_CALENDAR);
        assertNotNull(xirr);
        assertNotNull(house);
        assertTrue(house > xirr, "365.25 spreads the same growth over slightly more years");
        assertEquals(xirr, house, 2e-4);
    }
}
