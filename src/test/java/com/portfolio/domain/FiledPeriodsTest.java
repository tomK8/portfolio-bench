package com.portfolio.domain;

import com.portfolio.domain.FiledPeriods.Fact;
import com.portfolio.domain.FiledPeriods.Ttm;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FiledPeriodsTest {

    private static Fact f(String start, String end, String value) {
        return new Fact(LocalDate.parse(start), LocalDate.parse(end), new BigDecimal(value));
    }

    @Test
    void sumsFourFiledQuarters() {
        Optional<Ttm> ttm = FiledPeriods.trailingFourQuarters(List.of(
                f("2025-07-01", "2025-09-30", "10"),
                f("2025-10-01", "2025-12-31", "20"),
                f("2026-01-01", "2026-03-31", "30"),
                f("2026-04-01", "2026-06-30", "40")));

        assertTrue(ttm.isPresent());
        assertEquals(0, ttm.get().total().compareTo(new BigDecimal("100")));
        assertEquals(LocalDate.parse("2026-06-30"), ttm.get().periodEnd());
        assertEquals(4, ttm.get().quarters().size());
        assertNull(ttm.get().derivedQuarterEnd(), "nothing was derived — all four were filed");
        assertEquals(LocalDate.parse("2025-07-01"), ttm.get().quarters().get(0).start(),
                "oldest first");
    }

    /**
     * The real NVDA shape: a 10-K presents FY2026 (130.387bn) and the nine months to October
     * (86.088bn) but never the Nov-Jan quarter, so it has to be recovered by subtraction.
     * 130.387 − 86.088 = 44.299, which lands between the 36.010 before it and the 53.536 after.
     */
    @Test
    void recoversTheQuarterA10KLeavesImplicit() {
        Optional<Ttm> ttm = FiledPeriods.trailingFourQuarters(List.of(
                f("2025-01-27", "2026-01-25", "130387"),      // FY2026
                f("2025-01-27", "2025-10-26", "86088"),       // 9M FY2026
                f("2025-07-28", "2025-10-26", "36010"),       // Q3 FY2026
                f("2026-01-26", "2026-04-26", "53536"),       // Q1 FY2027
                f("2026-04-27", "2026-07-26", "63734")));     // Q2 FY2027

        assertTrue(ttm.isPresent());
        Ttm t = ttm.get();
        assertEquals(LocalDate.parse("2026-01-25"), t.derivedQuarterEnd());
        assertEquals(0, t.total().compareTo(new BigDecimal("197579")));
        assertEquals(LocalDate.parse("2026-07-26"), t.periodEnd());
        assertEquals(0, t.quarters().get(1).value().compareTo(new BigDecimal("44299")),
                "the derived quarter sits in sequence, not at the end");
    }

    /** No nine-month window on file: three contiguous quarters plus the year do the same job. */
    @Test
    void recoversTheFinalQuarterFromThreeQuartersAndTheYear() {
        Optional<Ttm> ttm = FiledPeriods.trailingFourQuarters(List.of(
                f("2025-01-01", "2025-12-31", "100"),
                f("2025-01-01", "2025-03-31", "20"),
                f("2025-04-01", "2025-06-30", "25"),
                f("2025-07-01", "2025-09-30", "30")));

        assertTrue(ttm.isPresent());
        assertEquals(0, ttm.get().total().compareTo(new BigDecimal("100")));
        assertEquals(LocalDate.parse("2025-12-31"), ttm.get().derivedQuarterEnd());
        assertEquals(0, ttm.get().quarters().get(3).value().compareTo(new BigDecimal("25")),
                "100 − (20 + 25 + 30)");
    }

    /**
     * A 10-Q tags its own quarter <em>and</em> the year to date. Adding everything on file would
     * count the same months two and three times — the TTM must come to the four quarters alone.
     */
    @Test
    void ignoresTheYearToDateWindowsAQuarterlyFilingCarries() {
        Optional<Ttm> ttm = FiledPeriods.trailingFourQuarters(List.of(
                f("2025-07-01", "2025-09-30", "10"),
                f("2025-10-01", "2025-12-31", "20"),
                f("2026-01-01", "2026-03-31", "30"),
                f("2026-04-01", "2026-06-30", "40"),
                f("2026-01-01", "2026-06-30", "70"),          // H1 year-to-date
                f("2025-07-01", "2026-03-31", "60"),          // 9M year-to-date
                f("2025-07-01", "2026-06-30", "100")));       // the year itself

        assertTrue(ttm.isPresent());
        assertEquals(0, ttm.get().total().compareTo(new BigDecimal("100")),
                "the four quarters, not the quarters plus the year-to-dates");
        assertNull(ttm.get().derivedQuarterEnd(), "the year's final quarter was already filed");
    }

    @Test
    void refusesWhenAQuarterIsMissingAndCannotBeRecovered() {
        // Q2 of the window is absent and there is no year to subtract it out of.
        assertTrue(FiledPeriods.trailingFourQuarters(List.of(
                f("2025-07-01", "2025-09-30", "10"),
                f("2026-01-01", "2026-03-31", "30"),
                f("2026-04-01", "2026-06-30", "40"))).isEmpty());
    }

    @Test
    void refusesFewerThanFourQuarters() {
        assertTrue(FiledPeriods.trailingFourQuarters(List.of(
                f("2026-01-01", "2026-03-31", "30"),
                f("2026-04-01", "2026-06-30", "40"))).isEmpty());
    }

    /**
     * Fiscal quarters end on a weekday, so a boundary can leave a day or two unaccounted for.
     * The chain tolerates that; a genuine gap of a month is still a gap.
     */
    @Test
    void toleratesAWeekendAtAQuarterBoundaryButNotAGap() {
        assertTrue(FiledPeriods.trailingFourQuarters(List.of(
                f("2025-06-29", "2025-09-27", "10"),
                f("2025-09-29", "2025-12-27", "20"),          // two days after the previous end
                f("2025-12-29", "2026-03-28", "30"),
                f("2026-03-30", "2026-06-27", "40"))).isPresent());

        assertTrue(FiledPeriods.trailingFourQuarters(List.of(
                f("2025-06-29", "2025-09-27", "10"),
                f("2025-10-27", "2026-01-25", "20"),          // a month later — not contiguous
                f("2026-01-26", "2026-04-26", "30"),
                f("2026-04-27", "2026-07-26", "40"))).isEmpty());
    }

    @Test
    void handlesNoFactsAtAll() {
        assertTrue(FiledPeriods.trailingFourQuarters(List.of()).isEmpty());
        assertTrue(FiledPeriods.trailingFourQuarters(null).isEmpty());
    }

    /**
     * MSFT's FY2016, verbatim from EDGAR. The filer restated the year on adopting ASC 606 — the
     * annual went 2.10 → 2.56 in the FY2018 10-K — but never re-tagged its four quarters, which
     * still sum to 2.09. Four quarters that tile a year are still the right TTM; the disagreement
     * is a filed fact about the window and has to be reportable.
     */
    @Test
    void namesTheFilersOwnAnnualWhereItContradictsTheQuartersSummed() {
        List<Fact> quarters = List.of(
                f("2015-07-01", "2015-09-30", "0.61"),
                f("2015-10-01", "2015-12-31", "0.62"),
                f("2016-01-01", "2016-03-31", "0.47"),
                f("2016-04-01", "2016-06-30", "0.39"));
        Fact restatedYear = f("2015-07-01", "2016-06-30", "2.56");

        Optional<Ttm> ttm = FiledPeriods.trailingFourQuarters(quarters);
        assertTrue(ttm.isPresent());
        assertEquals(0, ttm.get().total().compareTo(new BigDecimal("2.09")),
                "the quarters are still what a trailing window is built from");

        Fact window = new Fact(LocalDate.parse("2015-07-01"), LocalDate.parse("2016-06-30"),
                ttm.get().total());
        Optional<Fact> annual = FiledPeriods.annualContradicting(window,
                concat(quarters, restatedYear));
        assertTrue(annual.isPresent(), "the filer's own year disagrees by 22%");
        assertEquals(0, annual.get().value().compareTo(new BigDecimal("2.56")));
    }

    /**
     * MSFT's FY2017 beside it: here the quarters <em>were</em> re-tagged, so they add to the
     * restated annual and there is nothing to report. The same filer, one year apart — which is
     * why this cannot be a rule about companies.
     */
    @Test
    void saysNothingWhereTheQuartersWereRestatedToo() {
        List<Fact> quarters = List.of(
                f("2016-07-01", "2016-09-30", "0.72"),
                f("2016-10-01", "2016-12-31", "0.80"),
                f("2017-01-01", "2017-03-31", "0.70"),
                f("2017-04-01", "2017-06-30", "1.03"));
        Fact window = new Fact(LocalDate.parse("2016-07-01"), LocalDate.parse("2017-06-30"),
                new BigDecimal("3.25"));

        assertTrue(FiledPeriods.annualContradicting(window,
                concat(quarters, f("2016-07-01", "2017-06-30", "3.25"))).isEmpty());
    }

    /** Five figures each rounded to a cent can disagree by $0.025. That is not a restatement. */
    @Test
    void treatsACentOfRoundingAsAgreement() {
        Fact window = new Fact(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"),
                new BigDecimal("4.00"));
        assertTrue(FiledPeriods.annualContradicting(window,
                List.of(f("2025-01-01", "2025-12-31", "4.02"))).isEmpty());
        assertTrue(FiledPeriods.annualContradicting(window,
                List.of(f("2025-01-01", "2025-12-31", "4.10"))).isPresent());
        // A filer whose per-share figures are large enough that a cent is not the granularity.
        Fact big = new Fact(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"),
                new BigDecimal("1000"));
        assertTrue(FiledPeriods.annualContradicting(big,
                List.of(f("2025-01-01", "2025-12-31", "1005"))).isEmpty());
    }

    @Test
    void reportsNothingWhenNoAnnualCoversTheWindow() {
        Fact window = new Fact(LocalDate.parse("2025-04-01"), LocalDate.parse("2026-03-31"),
                new BigDecimal("4.00"));
        // A trailing window that is not a fiscal year has no annual to disagree with it.
        assertTrue(FiledPeriods.annualContradicting(window,
                List.of(f("2025-01-01", "2025-12-31", "9.99"))).isEmpty());
        assertTrue(FiledPeriods.annualContradicting(null, List.of()).isEmpty());
        assertTrue(FiledPeriods.annualContradicting(window, null).isEmpty());
    }

    private static List<Fact> concat(List<Fact> facts, Fact extra) {
        List<Fact> out = new java.util.ArrayList<>(facts);
        out.add(extra);
        return out;
    }

    /** A loss is a figure like any other — the derivation must not guard against a negative. */
    @Test
    void carriesALossThroughTheDerivation() {
        Optional<Ttm> ttm = FiledPeriods.trailingFourQuarters(List.of(
                f("2025-01-01", "2025-12-31", "10"),
                f("2025-01-01", "2025-09-30", "30"),
                f("2025-01-01", "2025-03-31", "10"),
                f("2025-04-01", "2025-06-30", "10"),
                f("2025-07-01", "2025-09-30", "10")));

        assertTrue(ttm.isPresent());
        assertEquals(0, ttm.get().quarters().get(3).value().compareTo(new BigDecimal("-20")),
                "10 − 30 = a loss in the final quarter");
    }
}
