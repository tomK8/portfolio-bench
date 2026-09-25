package com.portfolio.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two dueness rules, which are the whole contract of the reminders feature: one kind repeats
 * until a person stops it, the other stops by itself.
 *
 * <p>What these pin is the asymmetry. A hand-entered reminder has no upper bound — that is the
 * feature, since only the user knows when the thing is dealt with — while an earnings reminder is a
 * window with both ends read off a date that moves. Getting either bound wrong fails silently in the
 * expensive direction: too tight and the reminder you relied on never arrives, too loose and it
 * mails you daily about a print that already happened.
 */
class RemindersTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 25);

    // ---- hand-entered ------------------------------------------------------

    @Test
    void aManualReminderFiresOnItsDateAndEveryDayAfter() {
        assertTrue(Reminders.manualDue(TODAY, true, TODAY), "the day itself");
        assertTrue(Reminders.manualDue(TODAY.minusDays(1), true, TODAY), "yesterday");
        // No upper bound at all: an overdue reminder is one still doing its job, and an end-date
        // the app chose would be the app deciding the matter was closed.
        assertTrue(Reminders.manualDue(TODAY.minusYears(2), true, TODAY), "two years overdue");
    }

    @Test
    void aManualReminderIsSilentBeforeItsDate() {
        assertFalse(Reminders.manualDue(TODAY.plusDays(1), true, TODAY));
    }

    @Test
    void switchingOneOffSilencesItWithoutForgettingIt() {
        // The date is still passed; only the switch changes. That is what makes "off" different
        // from "deleted" — the row survives and can be switched back on.
        assertFalse(Reminders.manualDue(TODAY.minusDays(5), false, TODAY));
        assertTrue(Reminders.manualDue(TODAY.minusDays(5), true, TODAY));
    }

    // ---- earnings ----------------------------------------------------------

    @Test
    void anEarningsReminderOpensAWeekBeforeThePrint() {
        assertTrue(Reminders.earningsDue(TODAY.plusDays(7), true, TODAY), "exactly the lead");
        assertTrue(Reminders.earningsDue(TODAY.plusDays(1), true, TODAY));
        assertTrue(Reminders.earningsDue(TODAY, true, TODAY), "the day itself still counts");
    }

    @Test
    void itIsSilentBeyondTheLead() {
        assertFalse(Reminders.earningsDue(TODAY.plusDays(8), true, TODAY));
        assertFalse(Reminders.earningsDue(TODAY.plusMonths(2), true, TODAY));
    }

    /**
     * The bound that matters most. The fundamentals snapshot leaves the last date it knows in place
     * until Yahoo learns the next one, so a passed date is where the reporting rhythm was, not a
     * forward date. Without this test a stale snapshot would mail a warning about an event that has
     * already happened, every single day, until the feed caught up.
     */
    @Test
    void aPrintAlreadyBehindUsNeverFires() {
        assertFalse(Reminders.earningsDue(TODAY.minusDays(1), true, TODAY));
        assertFalse(Reminders.earningsDue(TODAY.minusMonths(3), true, TODAY));
    }

    @Test
    void anUnknownDateIsNotADueDate() {
        // Absent is never zero: no date means no reminder, not a reminder for today.
        assertFalse(Reminders.earningsDue(null, true, TODAY));
        assertFalse(Reminders.manualDue(null, true, TODAY));
    }

    @Test
    void mutingASymbolSilencesItInsideTheWindow() {
        assertFalse(Reminders.earningsDue(TODAY.plusDays(2), false, TODAY));
    }

    // ---- days until --------------------------------------------------------

    @Test
    void daysUntilGoesNegativeOnceTheDateHasPassed() {
        assertEquals(3L, Reminders.daysUntil(TODAY.plusDays(3), TODAY));
        assertEquals(0L, Reminders.daysUntil(TODAY, TODAY));
        assertEquals(-4L, Reminders.daysUntil(TODAY.minusDays(4), TODAY));
    }

    @Test
    void noDateMeansNoCount() {
        // Rather than 0, which would read as "due today" on the screen.
        assertEquals(null, Reminders.daysUntil(null, TODAY));
    }

    // ---- dedup keys --------------------------------------------------------

    @Test
    void aKeyIsPerItemPerDay() {
        assertEquals("R|7|2026-09-25", Reminders.manualKey(7, TODAY));
        assertEquals("E|BARC|2026-09-25", Reminders.earningsKey("barc", TODAY));
        // Tomorrow's key differs, which is exactly what makes the reminder repeat daily.
        assertFalse(Reminders.manualKey(7, TODAY).equals(Reminders.manualKey(7, TODAY.plusDays(1))));
    }

    @Test
    void theEarningsKeyIgnoresTheEarningsDateSoAShiftIsNotNews() {
        // Keyed by symbol and today only. A print that slips from Thursday to Friday while the
        // window is open is the same warning about the same print, and a key carrying the date
        // would mail the move as though it were a new event.
        assertEquals(Reminders.earningsKey("AAPL", TODAY), Reminders.earningsKey("AAPL", TODAY));
    }

    @Test
    void pruningKeepsOnlyTodaysKeys() {
        assertTrue(Reminders.isForDay(Reminders.manualKey(1, TODAY), TODAY));
        assertFalse(Reminders.isForDay(Reminders.manualKey(1, TODAY.minusDays(1)), TODAY));
    }
}
