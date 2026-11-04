package com.portfolio.domain;

import com.portfolio.domain.PrintReaction.Baseline;
import com.portfolio.domain.PrintReaction.Move;
import com.portfolio.domain.PrintReaction.Session;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class PrintReactionTest {

    /** Mon 26 Oct – Fri 6 Nov 2026, weekends absent, as a real price series is. */
    private static NavigableMap<LocalDate, Double> series() {
        TreeMap<LocalDate, Double> m = new TreeMap<>();
        m.put(LocalDate.parse("2026-10-28"), 100.0);
        m.put(LocalDate.parse("2026-10-29"), 102.0);
        m.put(LocalDate.parse("2026-10-30"), 110.0);
        m.put(LocalDate.parse("2026-11-02"), 99.0);
        m.put(LocalDate.parse("2026-11-03"), 108.9);
        return m;
    }

    @Test
    void beforeTheOpenIsMeasuredOnThePrintDayItself() {
        Move m = PrintReaction.move(LocalDate.parse("2026-10-30"), Session.BMO, series());
        assertNotNull(m);
        assertEquals(LocalDate.parse("2026-10-29"), m.from());
        assertEquals(LocalDate.parse("2026-10-30"), m.to());
        assertEquals(0.0784, m.pct(), 1e-4);
    }

    @Test
    void afterTheCloseIsMeasuredOnTheNextSession() {
        Move m = PrintReaction.move(LocalDate.parse("2026-11-02"), Session.AMC, series());
        assertNotNull(m);
        assertEquals(LocalDate.parse("2026-11-02"), m.from());
        assertEquals(LocalDate.parse("2026-11-03"), m.to());
        assertEquals(0.10, m.pct(), 1e-9);
    }

    @Test
    void theWindowSkipsTheWeekendRatherThanCountingCalendarDays() {
        // Friday 30 Oct, after the close: the next session is Monday 2 Nov, not Saturday.
        Move m = PrintReaction.move(LocalDate.parse("2026-10-30"), Session.AMC, series());
        assertNotNull(m);
        assertEquals(LocalDate.parse("2026-11-02"), m.to());
    }

    @Test
    void anUnstatedSessionIsNotGuessedAtEitherWay() {
        assertNull(PrintReaction.window(LocalDate.parse("2026-10-30"), null, series()));
        assertNull(PrintReaction.move(LocalDate.parse("2026-10-30"), null, series()));
    }

    @Test
    void aReactionSessionThatHasNotClosedYetProducesNoMove() {
        // The last bar is the print day and the print was after the close: the session that will
        // carry the reaction has not happened. Measuring anything here would measure the day
        // before the news.
        TreeMap<LocalDate, Double> upToPrint = new TreeMap<>(series().headMap(
                LocalDate.parse("2026-11-03"), false));
        assertNull(PrintReaction.move(LocalDate.parse("2026-11-02"), Session.AMC, upToPrint));
    }

    @Test
    void excessIsTakenOverTheSameTwoDatesOrNotAtAll() {
        Move m = PrintReaction.move(LocalDate.parse("2026-11-02"), Session.AMC, series());
        TreeMap<LocalDate, Double> index = new TreeMap<>();
        index.put(LocalDate.parse("2026-11-02"), 200.0);
        index.put(LocalDate.parse("2026-11-03"), 204.0);
        assertEquals(0.08, PrintReaction.excess(m, index), 1e-9);

        // The index is missing the closing date — stretching to its nearest bar would make this a
        // difference of windows rather than of returns, so there is no figure.
        TreeMap<LocalDate, Double> gappy = new TreeMap<>();
        gappy.put(LocalDate.parse("2026-11-02"), 200.0);
        gappy.put(LocalDate.parse("2026-11-04"), 204.0);
        assertNull(PrintReaction.excess(m, gappy));
    }

    @Test
    void aBaselineUnderThreePrintsIsWithheldAndSaysHowManyItHad() {
        Move m = new Move(LocalDate.parse("2026-11-02"), 99.0, LocalDate.parse("2026-11-03"),
                108.9, 0.10);
        Baseline b = PrintReaction.baseline(m, List.of(move(0.02), move(-0.04)), 1);
        assertNull(b.multiple());
        assertNull(b.median());
        assertEquals(2, b.prints());
        assertEquals(1, b.dropped());
        assertFalse(b.usable());
    }

    @Test
    void theBaselineIsAMedianOfAbsoluteMovesSoOneBlowUpDoesNotSetTheScale() {
        Move m = new Move(LocalDate.parse("2026-11-02"), 99.0, LocalDate.parse("2026-11-03"),
                108.9, 0.10);
        Baseline b = PrintReaction.baseline(m, List.of(move(0.02), move(-0.04), move(0.40)), 0);
        assertEquals(0.04, b.median(), 1e-9);   // not the 0.1533 mean
        assertEquals(2.5, b.multiple(), 1e-9);
        assertEquals(3, b.prints());
    }

    @Test
    void aMedianOfZeroIsNoScaleRatherThanAnInfiniteMultiple() {
        Move m = new Move(LocalDate.parse("2026-11-02"), 99.0, LocalDate.parse("2026-11-03"),
                108.9, 0.10);
        Baseline b = PrintReaction.baseline(m, List.of(move(0.0), move(0.0), move(0.0)), 0);
        assertEquals(0.0, b.median(), 1e-9);
        assertNull(b.multiple());
    }

    @Test
    void sessionsAreReadOffTheMarketClockAndOnlyOutsideTradingHours() {
        // 16:30 and 02:00 New York on 2 November 2026 (EST, UTC−5).
        assertEquals(Session.AMC, PrintReaction.sessionAt(Instant.parse("2026-11-02T21:30:00Z")));
        assertEquals(Session.BMO, PrintReaction.sessionAt(Instant.parse("2026-11-02T07:00:00Z")));
        // 12:47 New York — inside the session, so it says nothing about which window applies.
        assertNull(PrintReaction.sessionAt(Instant.parse("2026-11-02T17:47:00Z")));
        assertNull(PrintReaction.sessionAt(null));
    }

    @Test
    void theTradingDayIsTheMarketsNotUtc() {
        // 20:30 New York on 2 November is already 3 November in UTC; the print belongs to the 2nd.
        assertEquals(LocalDate.parse("2026-11-02"),
                PrintReaction.usDayOf(Instant.parse("2026-11-03T01:30:00Z")));
    }

    @Test
    void aCompanyWhoseStampsClaimBothSessionsIsNotTrusted() {
        assertTrue(PrintReaction.sessionsConsistent(List.of(Session.AMC, Session.AMC)));
        assertFalse(PrintReaction.sessionsConsistent(List.of(Session.AMC, Session.BMO)));
        assertFalse(PrintReaction.sessionsConsistent(List.of()));
    }

    private static Move move(double pct) {
        return new Move(LocalDate.parse("2026-01-01"), 100.0, LocalDate.parse("2026-01-02"),
                100.0 * (1 + pct), pct);
    }
}
