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
