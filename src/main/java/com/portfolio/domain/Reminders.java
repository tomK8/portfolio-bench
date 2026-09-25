package com.portfolio.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * When a reminder is due, and the key that stops it being said twice in one day.
 *
 * <p>Two kinds, deliberately asymmetric because they answer to different owners:
 *
 * <ul>
 *   <li><b>A hand-entered reminder is open-ended.</b> It starts on its date and repeats every day
 *       after it, for as long as it takes, because only you know when it is dealt with — an
 *       end-date the app chose would be the app deciding that for you. Switching it off or deleting
 *       it is the only thing that stops it, which is exactly what was asked for.
 *   <li><b>An earnings reminder is a window that closes by itself.</b> It opens
 *       {@link #EARNINGS_LEAD_DAYS} days before the print and shuts the day after it, because the
 *       thing it is warning about has then happened. It has no end-date of its own for the same
 *       reason it has no start-date of its own: both are read off the earnings date, which moves
 *       when the company moves it.
 * </ul>
 *
 * <p><b>A date already behind us never fires an earnings reminder.</b> The fundamentals snapshot
 * leaves the last date it knows in place until it learns the next one (see {@code docs/earnings.md}),
 * so a passed date says where the reporting rhythm was, not when the next print is. Treating it as
 * due would mail a warning about an event that already happened, every day, until the feed caught
 * up — the same conflation the four earnings states exist to prevent.
 *
 * <p>Pure and framework-free: every decision here is a function of the dates and the on/off switch,
 * so the rules can be pinned in tests without a database, a clock or a mail server.
 */
public final class Reminders {

    /**
     * How much notice an earnings reminder gives. A week: long enough to act before the print —
     * close a position, roll an option, decide to sit through it — and short enough that the daily
     * repeat stays a prompt rather than noise.
     */
    public static final int EARNINGS_LEAD_DAYS = 7;

    private Reminders() {
    }

    /**
     * Whether a hand-entered reminder should be said today: switched on, and its date has arrived or
     * passed. Being overdue is not a reason to stop — it is the reason it keeps repeating.
     */
    public static boolean manualDue(LocalDate dueDate, boolean enabled, LocalDate today) {
        return enabled && dueDate != null && today != null && !dueDate.isAfter(today);
    }

    /**
     * Whether an earnings reminder should be said today: switched on, and the print falls inside
     * {@code [today, today + EARNINGS_LEAD_DAYS]}. The lower bound is what keeps a stale snapshot
     * date silent; the upper bound is the week of notice.
     */
    public static boolean earningsDue(LocalDate earningsDate, boolean enabled, LocalDate today) {
        if (!enabled || earningsDate == null || today == null) return false;
        if (earningsDate.isBefore(today)) return false;
        return !earningsDate.isAfter(today.plusDays(EARNINGS_LEAD_DAYS));
    }

    /**
     * Whole days from {@code today} to {@code date} — negative once the date is behind us, which is
     * how an overdue hand-entered reminder reports how long it has been waiting. Null in, null out:
     * "we have no date" is not "due in 0 days".
     */
    public static Long daysUntil(LocalDate date, LocalDate today) {
        if (date == null || today == null) return null;
        return ChronoUnit.DAYS.between(today, date);
    }

    /**
     * The once-per-day key for a hand-entered reminder. Keyed by id, so editing the text or the date
     * of one already said today does not re-say it; the next day's key is new either way.
     */
    public static String manualKey(long id, LocalDate today) {
        return "R|" + id + "|" + today;
    }

    /**
     * The once-per-day key for an earnings reminder. Keyed by symbol and the day, deliberately
     * <b>not</b> by the earnings date: a date that shifts while the window is open is the same
     * warning about the same print, and keying on it would mail the move as if it were news.
     */
    public static String earningsKey(String symbol, LocalDate today) {
        return "E|" + (symbol == null ? "" : symbol.trim().toUpperCase()) + "|" + today;
    }

    /** Whether {@code key} belongs to {@code day} — the test that prunes yesterday's keys. */
    public static boolean isForDay(String key, LocalDate day) {
        return key != null && day != null && key.endsWith("|" + day);
    }
}
