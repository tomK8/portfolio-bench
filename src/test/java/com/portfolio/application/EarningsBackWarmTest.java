package com.portfolio.application;

import com.portfolio.domain.model.EarningsEvent;
import com.portfolio.persistence.EarningsCalendarRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.port.EarningsCalendarFeed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The journey the reported figures actually travel: a date cached while it was still ahead, asked
 * once more after it has passed, and the two answers merged.
 *
 * <p>This is the integration counterpart to {@code EarningsReportedTest}'s pure rules. What it
 * guards is the ordering problem behind the whole feature — the cache is normally filled
 * <em>before</em> a date happens, which is exactly when the feed has no figures to give.
 */
class EarningsBackWarmTest {

    @TempDir
    Path dbDir;

    /**
     * The most recent weekday strictly before today — the date {@code warmBack} would really be
     * asking about. Not {@code now().minusDays(1)}: on a Monday that is a Sunday, which warmBack
     * skips because no roster is ever published for one, and the test would be asserting about a
     * date the code is right to ignore.
     */
    private static final LocalDate TARGET = mostRecentWeekdayBefore(LocalDate.now());

    /** How far back {@link #TARGET} is, so a pass can be scoped to just reach it. */
    private static final int SPAN = (int) java.time.temporal.ChronoUnit.DAYS
            .between(TARGET, LocalDate.now());

    private static LocalDate mostRecentWeekdayBefore(LocalDate day) {
        LocalDate d = day.minusDays(1);
        while (d.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                || d.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    /** Records what it was asked for, and answers with whichever roster the test set up. */
    private static final class FakeFeed implements EarningsCalendarFeed {
        private final Map<LocalDate, List<EarningsEvent>> rosters;
        final List<LocalDate> asked = new ArrayList<>();

        FakeFeed(Map<LocalDate, List<EarningsEvent>> rosters) {
            this.rosters = rosters;
        }

        @Override public List<EarningsEvent> forDate(LocalDate date) {
            asked.add(date);
            return rosters.get(date);
        }

        @Override public String sourceName() {
            return "fake";
        }
    }

    private static EarningsEvent scheduled(LocalDate d, String symbol) {
        return new EarningsEvent(d, symbol, symbol + " Inc", "AMC", 1_000_000_000L, 4.71, 9, 1.10, null);
    }

    private static EarningsEvent settled(LocalDate d, String symbol) {
        // The real settled shape: an actual, no session, no last-year figure.
        return new EarningsEvent(d, symbol, symbol + " Inc", "TBD", 1_000_000_000L, 4.71, 9, null, 4.89);
    }

    private static EarningsEvent blank(LocalDate d, String symbol) {
        // Flipped, but with nothing to show for it — the date whose figures the feed never
        // publishes. Settled in shape (no session, no last-year figure), empty in content.
        return new EarningsEvent(d, symbol, symbol + " Inc", "TBD", 1_000_000_000L, 4.71, 9, null, null);
    }

    private EarningsCalendarService service(FakeFeed feed) {
        // warmBack touches the feed and the calendar repo and nothing else, so the rest stays null
        // rather than being stood up to be ignored.
        return new EarningsCalendarService(feed, new EarningsCalendarRepository(
                new JdbcConnectionFactory(dbDir)), null, null, null, null);
    }

    /**
     * Backdate a cached date's fetch stamp, so the row looks like what it would really be: fetched
     * on the day it was still in progress. {@code save} stamps {@code now}, which in a test that
     * caches yesterday's roster would wrongly read as "asked after the date passed".
     */
    private void stampFetchedDuring(LocalDate date) throws Exception {
        Instant during = date.atTime(9, 30).atZone(ZoneId.of("America/New_York")).toInstant();
        try (Connection conn = new JdbcConnectionFactory(dbDir).open();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE earnings_calendar_fetch SET fetched_at = ? WHERE date = ?")) {
            ps.setString(1, during.toString());
            ps.setString(2, date.toString());
            assertEquals(1, ps.executeUpdate());
        }
    }

    @Test
    void aDateCachedBeforeItHappenedIsAskedAgainAndGainsItsFigures() throws Exception {
        // The bug, end to end. Under the old rule ("any cached past date is fresh forever") the
        // scheduled copy below was served for good and the actual could never arrive.
        var repo = new EarningsCalendarRepository(new JdbcConnectionFactory(dbDir));
        repo.save(TARGET, List.of(scheduled(TARGET, "MSFT")));
        stampFetchedDuring(TARGET);
        assertNull(repo.load(TARGET).get(0).actualEps(), "nothing reported yet, as cached");

        var feed = new FakeFeed(Map.of(TARGET, List.of(settled(TARGET, "MSFT"))));
        assertEquals(1, service(feed).warmBack(SPAN));

        EarningsEvent row = repo.load(TARGET).get(0);
        assertEquals(4.89, row.actualEps(), 1e-9, "the figures arrived");
        assertEquals("AMC", row.time(), "and the session the settled roster drops was kept");
        assertEquals(1.10, row.lastYearEps(), 1e-9);
    }

    @Test
    void aDateThatAlreadyHasItsFiguresIsNotAskedAgain() throws Exception {
        var repo = new EarningsCalendarRepository(new JdbcConnectionFactory(dbDir));
        repo.save(TARGET, List.of(settled(TARGET, "MSFT")));

        var feed = new FakeFeed(Map.of(TARGET, List.of(settled(TARGET, "MSFT"))));
        service(feed).warmBack(SPAN);
        assertFalse(feed.asked.contains(TARGET),
                "no request for a date with nothing left to learn");
    }

    @Test
    void aDateAlreadyAskedAfterItFlippedIsNotAskedForever() throws Exception {
        // The bound on the retry. The feed has flipped this date — no session, no last-year figure
        // — and still publishes no actual, which is its answer; without this clause every page load
        // would ask again, for good.
        var repo = new EarningsCalendarRepository(new JdbcConnectionFactory(dbDir));
        repo.save(TARGET, List.of(blank(TARGET, "MSFT")));       // stamped "now", i.e. today

        var feed = new FakeFeed(Map.of(TARGET, List.of(blank(TARGET, "MSFT"))));
        service(feed).warmBack(SPAN);
        assertFalse(feed.asked.contains(TARGET));
    }

    @Test
    void aDateAskedBeforeTheFeedFlippedIsAskedAgain() throws Exception {
        // 2026-10-29, end to end. The date had passed when we asked — at 06:03 New York the next
        // morning — so the old bound called it answered and closed it for good, while what came
        // back was still the scheduled shape: the feed had not got to the date yet. Asking a second
        // time is the only way the figures ever arrive.
        var repo = new EarningsCalendarRepository(new JdbcConnectionFactory(dbDir));
        repo.save(TARGET, List.of(scheduled(TARGET, "MSFT")));   // stamped "now" = after TARGET

        var feed = new FakeFeed(Map.of(TARGET, List.of(settled(TARGET, "MSFT"))));
        assertEquals(1, service(feed).warmBack(SPAN));
        assertEquals(4.89, repo.load(TARGET).get(0).actualEps(), 1e-9, "the figures arrived");
    }

    @Test
    void aFailedRefetchLeavesTheDateUnsettledSoItIsRetried() throws Exception {
        // A null is a fetch failure, not an empty roster. The cached scheduled copy must survive it
        // — degrading to last-known — and the date must stay askable rather than being written off.
        var repo = new EarningsCalendarRepository(new JdbcConnectionFactory(dbDir));
        repo.save(TARGET, List.of(scheduled(TARGET, "MSFT")));
        stampFetchedDuring(TARGET);

        var failing = new FakeFeed(Map.of());   // forDate returns null
        assertEquals(0, service(failing).warmBack(SPAN));
        assertTrue(failing.asked.contains(TARGET));
        assertEquals(1, repo.load(TARGET).size(), "the cached roster is not lost");

        var working = new FakeFeed(Map.of(TARGET, List.of(settled(TARGET, "MSFT"))));
        assertEquals(1, service(working).warmBack(SPAN), "still unsettled, so retried");
        assertEquals(4.89, repo.load(TARGET).get(0).actualEps(), 1e-9);
    }

    @Test
    void weekendsAreNeverAsked() {
        var feed = new FakeFeed(Map.of());
        service(feed).warmBack(14);
        for (LocalDate d : feed.asked) {
            assertNotEquals(java.time.DayOfWeek.SATURDAY, d.getDayOfWeek());
            assertNotEquals(java.time.DayOfWeek.SUNDAY, d.getDayOfWeek());
        }
    }

    @Test
    void todayIsNeverAsked() {
        // warmBack is the backward pass. Today's roster is on the forward path's three-hour TTL,
        // and re-fetching it here would double that traffic for a date with no figures anyway.
        var feed = new FakeFeed(Map.of());
        service(feed).warmBack(14);
        assertFalse(feed.asked.contains(LocalDate.now()));
    }
}
