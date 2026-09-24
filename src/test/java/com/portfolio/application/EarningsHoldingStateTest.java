package com.portfolio.application;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Precedence between the Earnings tab's two date sources.
 *
 * <p>The holdings section answers "when does this position next report" out of a strong source
 * (the US market calendar: a session, a consensus estimate, authority over the window) and a weak
 * one (the per-symbol date cached on the fundamentals snapshot). What these pin is the order the
 * two are consulted in and — the part that actually bites — the three distinct silences the state
 * has to keep apart, because a row that conflated them is what made this section blank for 39 of
 * 42 positions before the fallback existed.
 */
class EarningsHoldingStateTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 24);

    @Test
    void theCalendarOutranksTheSnapshot() {
        // A calendar event is the better answer even when the snapshot also has a date: only the
        // calendar carries the session and the consensus estimate the REPORTING row prints.
        assertEquals("REPORTING", EarningsCalendarService.holdingState(
                true, LocalDate.of(2026, 10, 14), TODAY, false));
        assertEquals("REPORTING", EarningsCalendarService.holdingState(
                true, null, TODAY, true));
    }

    @Test
    void aForwardSnapshotDateFillsInWhatTheCalendarMissed() {
        // BARC: a London listing the US feed never carries, dated 22 Oct by the snapshot.
        assertEquals("EXPECTED", EarningsCalendarService.holdingState(
                false, LocalDate.of(2026, 10, 22), TODAY, true));
        // GOOG: a US name the feed covers but has nothing due for inside the look-ahead. The same
        // fallback applies — the cached date exists either way, and dating one while leaving the
        // other blank would draw a line the data does not.
        assertEquals("EXPECTED", EarningsCalendarService.holdingState(
                false, LocalDate.of(2026, 10, 28), TODAY, false));
    }

    @Test
    void todayCountsAsAhead() {
        // A company reporting this morning has not stopped being the next report.
        assertEquals("EXPECTED", EarningsCalendarService.holdingState(false, TODAY, TODAY, true));
    }

    @Test
    void aPassedSnapshotDateIsNotAForwardDate() {
        // LGEN reported on 5 Aug and Yahoo has not published the next one; it leaves the old date
        // in place. That is where the rhythm was, not when the company next reports, so the row
        // falls back to the silence its listing earns — the date still travels with it as context.
        assertEquals("NOT_COVERED", EarningsCalendarService.holdingState(
                false, LocalDate.of(2026, 8, 5), TODAY, true));
        assertEquals("NONE_SCHEDULED", EarningsCalendarService.holdingState(
                false, LocalDate.of(2026, 8, 5), TODAY, false));
    }

    @Test
    void theListingSeparatesTheTwoSilences() {
        // With no date from either source, the question is whether the feed could have seen one.
        // SGLN (a London ETC) gets "not covered": the feed's silence says nothing about it.
        assertEquals("NOT_COVERED", EarningsCalendarService.holdingState(false, null, TODAY, true));
        // A US name the feed does cover, with nothing due and nothing cached, really is quiet.
        assertEquals("NONE_SCHEDULED", EarningsCalendarService.holdingState(false, null, TODAY, false));
    }
}
