package com.portfolio.application;

import com.portfolio.domain.model.GiltYield;
import com.portfolio.port.GiltYieldFeed;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GiltYieldServiceTest {

    /** A feed that answers from a script, counting the requests it was sent. */
    private static final class ScriptedFeed implements GiltYieldFeed {
        List<GiltYield> answer = new ArrayList<>();
        Exception failure;
        int calls;
        List<Integer> askedFor;

        @Override
        public List<GiltYield> benchmarkYields(List<Integer> tenorYears) throws Exception {
            calls++;
            askedFor = tenorYears;
            if (failure != null) throw failure;
            return answer;
        }

        @Override
        public String sourceName() {
            return "CNBC";
        }
    }

    /** A clock the test moves by hand. */
    private static final class ManualClock extends Clock {
        Instant now;

        ManualClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    // 18:33:20 UTC in November is 18:33:20 in London; July below is BST.
    private static final Instant NOV = Instant.parse("2026-11-25T18:33:20Z");

    private final ScriptedFeed feed = new ScriptedFeed();
    private final ManualClock clock = new ManualClock(NOV);
    private final GiltYieldService service = new GiltYieldService(feed, clock);

    private static GiltYield quote(int tenor, String last, String previous) {
        return new GiltYield(tenor, new BigDecimal(last), previous == null ? null : new BigDecimal(previous),
                Instant.parse("2026-11-25T18:30:39Z"), "GILT 4.125% 2031", LocalDate.of(2031, 3, 7),
                "https://www.cnbc.com/quotes/GB" + tenor + "Y-GB");
    }

    private static List<String> tenors(GiltYieldReport report) {
        return report.rows().stream().map(GiltYieldReport.Row::tenor).toList();
    }

    @Test
    void asksForTheFourTenorsTheCurveIsReadAt() {
        service.current();
        assertEquals(List.of(2, 5, 10, 30), feed.askedFor);
    }

    @Test
    void rowsComeInTenorOrderWhateverOrderTheFeedAnswersIn() {
        feed.answer = List.of(quote(30, "5.6509", "5.6289"), quote(2, "4.8037", "4.7889"),
                quote(10, "5.136", "5.1389"), quote(5, "4.8005", "4.7915"));
        GiltYieldReport report = service.current();

        assertEquals(List.of("2-year", "5-year", "10-year", "30-year"), tenors(report));
        assertEquals(List.of(), report.missing());
        assertNull(report.error());
        assertEquals("CNBC", report.source());
        assertEquals("18:33:20", report.fetchedAt());
    }

    @Test
    void everyCellArrivesFormatted() {
        feed.answer = List.of(quote(2, "4.8037", "4.7889"));
        GiltYieldReport.Row row = service.current().rows().get(0);

        assertEquals("4.804%", row.yield());
        assertEquals("+1.5 bp", row.change());
        assertEquals("4.789%", row.previousClose());
        assertEquals("GILT 4.125% 2031", row.benchmark());
        assertEquals("7 Mar 2031", row.matures());
        assertEquals("Wed 25 Nov 18:30", row.asOf());
        assertEquals("https://www.cnbc.com/quotes/GB2Y-GB", row.url());
    }

    @Test
    void timesAreLondonTimeAcrossTheClockChange() {
        GiltYield summer = new GiltYield(10, new BigDecimal("4.5"), null,
                Instant.parse("2026-07-01T16:30:00Z"), null, null, null);
        feed.answer = List.of(summer);
        GiltYieldReport.Row row = service.current().rows().get(0);

        assertEquals("Wed 1 Jul 17:30", row.asOf());
        assertNull(row.change());
        assertNull(row.previousClose());
        assertNull(row.benchmark());
        assertNull(row.matures());
    }

    @Test
    void movesAreSignedAndAMoveThatRoundsAwayIsNotNegativeZero() {
        assertEquals("-0.3 bp", GiltYieldService.basisPoints(new BigDecimal("-0.29")));
        assertEquals("+12.0 bp", GiltYieldService.basisPoints(new BigDecimal("11.96")));
        assertEquals("0.0 bp", GiltYieldService.basisPoints(new BigDecimal("-0.04")));
        assertEquals("0.0 bp", GiltYieldService.basisPoints(BigDecimal.ZERO));
        assertNull(GiltYieldService.basisPoints(null));
    }

    @Test
    void aTenorTheSourceDidNotAnswerIsNamedNotFilled() {
        feed.answer = List.of(quote(2, "4.8", "4.7"), quote(5, "4.8", "4.7"), quote(10, "5.1", "5.0"));
        GiltYieldReport report = service.current();

        assertEquals(List.of("2-year", "5-year", "10-year"), tenors(report));
        assertEquals(List.of("30-year"), report.missing());
        assertNull(report.error());
    }

    @Test
    void oneFetchAnswersEveryRequestWithinTheTtl() {
        feed.answer = List.of(quote(10, "5.1", "5.0"));
        GiltYieldReport first = service.current();
        clock.advance(GiltYieldService.TTL.minusSeconds(1));
        assertSame(first, service.current());
        assertEquals(1, feed.calls);

        clock.advance(Duration.ofSeconds(1));
        service.current();
        assertEquals(2, feed.calls);
    }

    @Test
    void aFailureWithNothingFetchedYetNamesEveryTenorAndTheReason() {
        feed.failure = new IOException("CNBC answered HTTP 503");
        GiltYieldReport report = service.current();

        assertTrue(report.rows().isEmpty());
        assertEquals(List.of("2-year", "5-year", "10-year", "30-year"), report.missing());
        assertEquals("CNBC answered HTTP 503", report.error());
        assertNull(report.fetchedAt());
    }

    @Test
    void aFailedRefreshKeepsTheLastGoodFiguresAndSaysSo() {
        feed.answer = List.of(quote(10, "5.1", "5.0"));
        GiltYieldReport good = service.current();

        clock.advance(GiltYieldService.TTL);
        feed.failure = new IOException("CNBC answered HTTP 503");
        GiltYieldReport report = service.current();

        assertEquals(good.rows(), report.rows());
        assertEquals("18:33:20", report.fetchedAt());
        assertEquals("CNBC answered HTTP 503", report.error());

        // The failure is not cached: the next request tries again.
        service.current();
        assertEquals(3, feed.calls);
    }

    @Test
    void anAnswerWithNoTenorsIsAFailureNotACachedBlankTable() {
        GiltYieldReport report = service.current();

        assertTrue(report.rows().isEmpty());
        assertEquals("CNBC quoted none of the tenors", report.error());
        service.current();
        assertEquals(2, feed.calls);
    }

    @Test
    void anExceptionWithNoMessageIsNamedByItsType() {
        feed.failure = new IOException();
        assertEquals("IOException", service.current().error());
    }

    @Test
    void anInterruptIsPassedOnRatherThanSwallowed() {
        feed.failure = new InterruptedException();
        try {
            assertEquals("interrupted", service.current().error());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
