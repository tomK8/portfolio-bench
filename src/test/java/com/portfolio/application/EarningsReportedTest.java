package com.portfolio.application;

import com.portfolio.domain.model.EarningsEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rules behind "just reported": how a beat is measured, when a past date is finished with, and
 * what a settled re-fetch is allowed to overwrite. All three are pure functions, so they pin without
 * a database, a clock or a feed.
 */
class EarningsReportedTest {

    private static EarningsEvent scheduled(String symbol, String session, Double forecast) {
        return new EarningsEvent(LocalDate.parse("2026-10-27"), symbol, symbol + " Inc", session,
                1_000_000_000L, forecast, 9, 1.10, null);
    }

    private static EarningsEvent settledRow(String symbol, Double forecast, Double actual) {
        // As the feed really sends it once the date has passed: no session, no last-year figure.
        return new EarningsEvent(LocalDate.parse("2026-10-27"), symbol, symbol + " Inc", "TBD",
                1_000_000_000L, forecast, 9, null, actual);
    }

    // ---- the surprise ---------------------------------------------------------

    @Test
    void surpriseIsTheMoveAgainstConsensus() {
        // MSFT 4.89 against 4.71, GOOG 2.12 against 2.94 — the feed's own figures for these two
        // agree with the arithmetic, which is what makes the two that don't worth distrusting.
        assertEquals(3.82, EarningsCalendarService.surprisePct(4.89, 4.71), 0.005);
        assertEquals(-27.89, EarningsCalendarService.surprisePct(2.12, 2.94), 0.005);
    }

    @Test
    void aLossBiggerThanExpectedIsAMissNotABeat() {
        // The reason the divisor is the magnitude of the consensus and not its signed value. BA was
        // expected to lose 0.21 and lost 0.34: worse, so negative. Dividing by −0.21 would report
        // +61.9% and put a red quarter in the beat column.
        Double ba = EarningsCalendarService.surprisePct(-0.34, -0.21);
        assertTrue(ba < 0, "losing more than expected is a miss, got " + ba);
        assertEquals(-61.90, ba, 0.005);
        assertEquals(-408.33, EarningsCalendarService.surprisePct(-0.61, -0.12), 0.005);
    }

    @Test
    void aSmallerLossThanExpectedIsABeat() {
        assertEquals(8.0, EarningsCalendarService.surprisePct(-0.23, -0.25), 0.005);
    }

    @Test
    void meetingConsensusExactlyIsZeroAndNotAbsent() {
        // ECL reported 2.18 against a 2.18 consensus and the feed published the surprise as "N/A".
        // A measured zero is not a missing figure — the inverse of "absent is never zero" — and
        // this is one of 31 such rows in the sample that prompted computing it here instead.
        Double ecl = EarningsCalendarService.surprisePct(2.18, 2.18);
        assertNotNull(ecl, "met consensus exactly — that is a zero, not an unknown");
        assertEquals(0.0, ecl, 1e-9);
    }

    @Test
    void noConsensusMeansNoSurprise() {
        // A figure no analyst covered (WSO.B) has nothing to be measured against, and a consensus
        // of exactly zero has no percentage over it even though the difference is real.
        assertNull(EarningsCalendarService.surprisePct(3.77, null));
        assertNull(EarningsCalendarService.surprisePct(null, 4.71));
        assertNull(EarningsCalendarService.surprisePct(1.00, 0.0));
    }

    // ---- when a past date is finished with ------------------------------------

    private static final LocalDate DATE = LocalDate.parse("2026-10-27");
    private static final ZoneId NY = ZoneId.of("America/New_York");

    private static Instant atNewYork(String localDateTime) {
        return LocalDate.parse(localDateTime.substring(0, 10))
                .atTime(Integer.parseInt(localDateTime.substring(11, 13)),
                        Integer.parseInt(localDateTime.substring(14, 16)))
                .atZone(NY).toInstant();
    }

    @Test
    void aDateFetchedBeforeItHappenedIsNotFinishedWith() {
        // The bug this whole change exists to fix. The roster was cached the morning of the 27th,
        // in the scheduled shape, and the old rule ("any cached past date is fresh forever") then
        // served that copy for good — so the figures could never arrive for exactly the dates whose
        // reports had happened.
        List<EarningsEvent> cached = List.of(scheduled("MSFT", "AMC", 4.71));
        assertFalse(EarningsCalendarService.settled(cached, DATE, atNewYork("2026-10-27 09:30")),
                "fetched while the date was still in progress — ask again once it has passed");
    }

    @Test
    void aDateWhoseRowsCarryActualsIsFinishedWith() {
        List<EarningsEvent> cached = List.of(settledRow("MSFT", 4.71, 4.89));
        assertTrue(EarningsCalendarService.settled(cached, DATE, atNewYork("2026-10-28 07:00")));
    }

    @Test
    void anEmptyPastDateIsFinishedWith() {
        // A weekend or a holiday publishes no roster, now and forever. Without this a past weekend
        // in the back window would be re-fetched on every single load.
        assertTrue(EarningsCalendarService.settled(List.of(), DATE, atNewYork("2026-10-27 09:30")));
    }

    @Test
    void aDateAlreadyAskedAfterItFlippedIsNotAskedAgain() {
        // The clause that bounds the retry. If the feed has flipped the roster — no session, no
        // last-year figure — and still publishes no actual, that is its answer; without this the
        // date would be re-fetched on every page load forever, which is the one failure a
        // content-based freshness rule can have.
        List<EarningsEvent> cached = List.of(settledRow("MSFT", 4.71, null));
        assertTrue(EarningsCalendarService.settled(cached, DATE, atNewYork("2026-10-28 07:00")),
                "asked after the flip; its answer stands");
    }

    @Test
    void askingBeforeTheFeedFlippedDoesNotCountAsAsking() {
        // What buried 2026-10-29. The date had passed, so the old third clause called it answered —
        // but the roster was still the scheduled shape, session and last-year figure intact, which
        // is the feed saying it has not got to this date yet rather than saying there is nothing.
        // (It was worse than a blank: that shape also carries an eps column holding last year's
        // figure, which the parser then read as the actual. SNDK's year-ago $0.90 was published
        // against a $46.19 consensus as a 98% miss on a quarter that beat at $48.92.)
        List<EarningsEvent> cached = List.of(scheduled("MSFT", "AMC", 4.71));
        assertFalse(EarningsCalendarService.settled(cached, DATE, atNewYork("2026-10-28 06:03")),
                "the date passed but the roster had not flipped — ask again");
    }

    @Test
    void aLastYearFigureAloneIsEnoughToReadAsScheduled() {
        // The flip drops both fields together, so either one surviving says the same thing. A row
        // whose session the feed never supplied is the case the session test alone would miss.
        EarningsEvent noSession = new EarningsEvent(DATE, "MSFT", "MSFT Inc", "TBD",
                1_000_000_000L, 4.71, 9, 1.10, null);
        assertFalse(EarningsCalendarService.settled(List.of(noSession), DATE,
                        atNewYork("2026-10-28 06:03")),
                "last year's figure is still there, so this is the scheduled shape");
    }

    @Test
    void lateNightInLondonIsStillTheSameDayAtTheFeed() {
        // 00:30 on the 28th in London is 20:30 on the 27th in New York, so the date has NOT
        // finished at the source. Judging this on local time would mark it permanently
        // asked-after-settlement and lose that day's figures for good.
        Instant londonSmallHours = LocalDate.parse("2026-10-28").atTime(0, 30)
                .atZone(ZoneId.of("Europe/London")).toInstant();
        List<EarningsEvent> cached = List.of(scheduled("MSFT", "AMC", 4.71));
        assertFalse(EarningsCalendarService.settled(cached, DATE, londonSmallHours),
                "still the 27th in New York — the roster cannot have settled yet");
    }

    // ---- merging a settled roster over a scheduled one ------------------------

    @Test
    void aSettledRefetchKeepsTheSessionItNoLongerCarries() {
        // The point of merging rather than replacing. The settled roster brings the actual and
        // drops the session; a straight save would erase AMC from every row of a date already
        // fetched — and "reported after last night's close" is the whole reason to look at it.
        List<EarningsEvent> cached = List.of(scheduled("MSFT", "AMC", 4.71));
        List<EarningsEvent> live = List.of(settledRow("MSFT", 4.71, 4.89));

        EarningsEvent merged = EarningsCalendarService.mergeSettled(live, cached).get(0);
        assertEquals("AMC", merged.time(), "the session survives the re-fetch");
        assertEquals(4.89, merged.actualEps(), 1e-9, "and the actual arrives");
        assertEquals(1.10, merged.lastYearEps(), 1e-9, "as does last year's figure");
    }

    @Test
    void theLiveRosterStaysAuthoritativeAboutMembershipAndFigures() {
        // Merging must not resurrect a company dropped from the roster, which is why the cache
        // replaces per date in the first place. Nor may a stale consensus outrank the final one.
        List<EarningsEvent> cached =
                List.of(scheduled("MSFT", "AMC", 4.60), scheduled("DROPPED", "BMO", 1.00));
        List<EarningsEvent> live = List.of(settledRow("MSFT", 4.71, 4.89));

        List<EarningsEvent> merged = EarningsCalendarService.mergeSettled(live, cached);
        assertEquals(List.of("MSFT"), merged.stream().map(EarningsEvent::symbol).toList());
        assertEquals(4.71, merged.get(0).epsForecast(), 1e-9,
                "the consensus on the settled row is the final one a beat is judged against");
    }

    @Test
    void aGenuinelyUnknownSessionIsNotDressedUpFromTheCache() {
        // TBD in, TBD out: the cached row never knew the session either, so there is nothing to
        // carry forward and nothing is invented.
        List<EarningsEvent> cached = List.of(scheduled("MSFT", "TBD", 4.71));
        List<EarningsEvent> live = List.of(settledRow("MSFT", 4.71, 4.89));
        assertEquals("TBD", EarningsCalendarService.mergeSettled(live, cached).get(0).time());
    }

    @Test
    void mergingAgainstNothingCachedIsTheLiveRoster() {
        List<EarningsEvent> live = List.of(settledRow("MSFT", 4.71, 4.89));
        assertSame(live, EarningsCalendarService.mergeSettled(live, List.of()));
        assertSame(live, EarningsCalendarService.mergeSettled(live, null));
    }

    @Test
    void mergeIsKeyedOnSymbolNotPosition() {
        // The two rosters are ordered by market cap and a settled one can reorder, so a positional
        // merge would attach one company's session to another's figures.
        List<EarningsEvent> cached =
                List.of(scheduled("AAA", "BMO", 1.0), scheduled("BBB", "AMC", 2.0));
        List<EarningsEvent> live =
                List.of(settledRow("BBB", 2.0, 2.5), settledRow("AAA", 1.0, 0.9));

        Map<String, String> sessions = EarningsCalendarService.mergeSettled(live, cached).stream()
                .collect(Collectors.toMap(EarningsEvent::symbol, EarningsEvent::time));
        assertEquals("BMO", sessions.get("AAA"));
        assertEquals("AMC", sessions.get("BBB"));
    }
}
