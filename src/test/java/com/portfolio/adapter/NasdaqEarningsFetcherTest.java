package com.portfolio.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.domain.model.EarningsEvent;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NasdaqEarningsFetcherTest {

    private static final LocalDate DATE = LocalDate.parse("2026-07-15");
    private static final LocalDate SETTLED_DATE = LocalDate.parse("2026-10-27");

    private List<EarningsEvent> parseSample() throws Exception {
        return parse("nasdaq-earnings-sample.json", DATE);
    }

    private List<EarningsEvent> parseSettled() throws Exception {
        return parse("nasdaq-earnings-settled-sample.json", SETTLED_DATE);
    }

    private List<EarningsEvent> parse(String fixture, LocalDate date) throws Exception {
        String json = Files.readString(Path.of("src/test/resources/" + fixture));
        return NasdaqEarningsFetcher.parse(new ObjectMapper().readTree(json), date);
    }

    private EarningsEvent settled(String symbol) throws Exception {
        return parseSettled().stream().filter(e -> e.symbol().equals(symbol))
                .findFirst().orElseThrow();
    }

    @Test
    void parsesRowsAndSkipsMissingSymbol() throws Exception {
        List<EarningsEvent> events = parseSample();
        // Five rows in the fixture; the one with a blank symbol is dropped.
        assertEquals(4, events.size());
        assertTrue(events.stream().noneMatch(e -> e.symbol() == null || e.symbol().isEmpty()));
        assertTrue(events.stream().allMatch(e -> e.date().equals(DATE)));
    }

    @Test
    void mapsFieldsAndNormalisesMoneyEpsAndTime() throws Exception {
        EarningsEvent big = parseSample().stream().filter(e -> e.symbol().equals("BIGCO")).findFirst().orElseThrow();
        assertEquals("Big Company Inc.", big.companyName());
        assertEquals("BMO", big.time());
        assertEquals(665_246_306_497L, big.marketCap());
        assertEquals(7.98, big.epsForecast(), 1e-9);
        assertEquals(12, big.numEstimates());
        assertEquals(4.55, big.lastYearEps(), 1e-9);
    }

    @Test
    void parenthesesMeanNegativeEps() throws Exception {
        EarningsEvent loss = parseSample().stream().filter(e -> e.symbol().equals("LOSSCO")).findFirst().orElseThrow();
        assertEquals("AMC", loss.time());
        assertEquals(1_025_141L, loss.marketCap());   // small-cap, comma-separated
        assertEquals(-0.11, loss.epsForecast(), 1e-9);
        assertEquals(-0.20, loss.lastYearEps(), 1e-9);
    }

    @Test
    void zeroAndUnsuppliedTimeParseCleanly() throws Exception {
        EarningsEvent flat = parseSample().stream().filter(e -> e.symbol().equals("FLATCO")).findFirst().orElseThrow();
        assertEquals("TBD", flat.time());             // time-not-supplied
        assertEquals(0.0, flat.epsForecast(), 1e-9);  // "$0" is a real zero, not null
        assertEquals(5_000_000_000L, flat.marketCap());
    }

    @Test
    void blankAndNaFieldsBecomeNull() throws Exception {
        EarningsEvent sparse = parseSample().stream().filter(e -> e.symbol().equals("SPARSE")).findFirst().orElseThrow();
        assertNull(sparse.marketCap());     // ""
        assertNull(sparse.epsForecast());   // ""
        assertNull(sparse.numEstimates());  // ""
        assertNull(sparse.lastYearEps());   // "N/A"
    }

    @Test
    void noReportsDayYieldsEmptyList() throws Exception {
        // Nasdaq's shape for a weekend / empty day: data is null.
        var root = new ObjectMapper().readTree("{\"data\":null,\"status\":{\"rCode\":200}}");
        assertTrue(NasdaqEarningsFetcher.parse(root, DATE).isEmpty());
    }

    // ---- the settled row shape ------------------------------------------------

    @Test
    void aScheduledRowCarriesNoActualFigure() throws Exception {
        // The test everything downstream leans on: a row's actual is what separates a report that
        // has happened from one that is merely booked. Every row of a date still ahead must be the
        // latter, including the one whose session the feed declines to state.
        assertTrue(parseSample().stream().noneMatch(EarningsEvent::reported),
                "a date still ahead has reported nothing");
    }

    @Test
    void aSettledRowCarriesWhatWasActuallyReported() throws Exception {
        assertTrue(parseSettled().stream().allMatch(EarningsEvent::reported),
                "the feed flips a whole date at once, so every settled row has an actual");
        assertEquals(4.89, settled("MSFT").actualEps(), 1e-9);
        assertEquals(4.71, settled("MSFT").epsForecast(), 1e-9);
    }

    @Test
    void aSettledRowLosesItsSessionAndLastYearFigure() throws Exception {
        // Not an incidental gap — it is the reason a settled re-fetch is merged over the roster
        // already cached rather than replacing it. If this ever starts carrying a session, the
        // merge becomes unnecessary; if this test fails the other way, the merge is load-bearing.
        EarningsEvent msft = settled("MSFT");
        assertEquals("TBD", msft.time(), "the feed supplies no session once the date has passed");
        assertNull(msft.lastYearEps(), "nor last year's figure");
    }

    @Test
    void parenthesisedActualsAreNegative() throws Exception {
        // A loss reported as "($0.34)" is −0.34, like every other parenthesised figure here.
        assertEquals(-0.34, settled("BA").actualEps(), 1e-9);
        assertEquals(-0.21, settled("BA").epsForecast(), 1e-9);
    }

    // ---- the transitional shape: passed, but not flipped yet --------------------

    private List<EarningsEvent> parseTransitional() throws Exception {
        return parse("nasdaq-earnings-transitional-sample.json", LocalDate.parse("2026-10-29"));
    }

    private EarningsEvent transitional(String symbol) throws Exception {
        return parseTransitional().stream().filter(e -> e.symbol().equals(symbol))
                .findFirst().orElseThrow();
    }

    @Test
    void anEpsOnAStillScheduledRowIsNotAnActual() throws Exception {
        // The feed fills in an eps column for a date that has passed before it flips the roster,
        // and fills it with LAST YEAR's figure. Reading it as an actual published SNDK's year-ago
        // $0.90 against a $46.19 consensus as a 98% miss on a quarter that beat at $48.92.
        EarningsEvent sndk = transitional("SNDK");
        assertFalse(sndk.reported(), "the row still carries the last-year pair, so it is scheduled");
        assertNull(sndk.actualEps());
        assertEquals(0.90, sndk.lastYearEps(), 1e-9, "which is where that figure belongs");
        assertEquals(46.19, sndk.epsForecast(), 1e-9);
        assertEquals("AMC", sndk.time());
    }

    @Test
    void aBlankLastYearPairIsStillTheScheduledShape() throws Exception {
        // MRDN has no year-ago quarter to show, so lastYearEPS is empty — but the keys are there,
        // which is the shape. Its "$0" eps is the manufactured zero the structural test avoids:
        // taken as an actual it is a company reporting exactly nothing against a $0.14 consensus.
        EarningsEvent mrdn = transitional("MRDN");
        assertNull(mrdn.lastYearEps());
        assertNull(mrdn.actualEps(), "a blank last-year value does not make the row settled");
        assertFalse(mrdn.reported());
    }

    @Test
    void theShapeIsJudgedOnTheKeysNotTheNumbers() throws Exception {
        // Deliberately not "eps equals lastYearEPS, so distrust it": a company that earns exactly
        // what it earned a year ago is an ordinary quarter. The settled fixture has no last-year
        // pair at all, so every row there keeps its actual — including ECL, which matched its
        // consensus to the cent.
        assertTrue(parseTransitional().stream().noneMatch(EarningsEvent::reported));
        assertTrue(parseSettled().stream().allMatch(EarningsEvent::reported));
        assertEquals(2.18, settled("ECL").actualEps(), 1e-9);
    }

    @Test
    void anActualWithNoConsensusIsStillAnActual() throws Exception {
        // WSO.B reported a figure no analyst covered. Dropping the row would lose a real report;
        // defaulting the consensus to zero would invent a beat of the whole amount.
        EarningsEvent wso = settled("WSO.B");
        assertEquals(3.77, wso.actualEps(), 1e-9);
        assertNull(wso.epsForecast());
        assertNull(wso.numEstimates());
    }

    /**
     * The 2026-11-03 roster: the last-year pair already dropped, the session marker still there,
     * and {@code eps} holding the year-ago quarter. AMD's $0.97 is its Sep-2025 figure to the
     * cent, and reading it as an actual publishes a 42% miss against a $1.68 consensus on a
     * quarter whose figures were not out. This is the shape that got through the last-year test.
     */
    @Test
    void aRowThatStillNamesItsSessionIsNotSettledEvenWithTheLastYearPairGone() throws Exception {
        List<EarningsEvent> events = parse("nasdaq-earnings-transitional-nolastyear-sample.json",
                LocalDate.parse("2026-11-03"));
        assertEquals(3, events.size());
        for (EarningsEvent e : events) {
            assertNull(e.actualEps(), e.symbol() + " must not carry last year's EPS as an actual");
            assertFalse(e.reported(), e.symbol() + " has not reported as far as this feed says");
        }
        // Everything the row genuinely states is still read.
        EarningsEvent amd = events.get(0);
        assertEquals("AMC", amd.time());
        assertEquals(1.68, amd.epsForecast(), 1e-9);
        assertEquals(13, amd.numEstimates());
    }

    @Test
    void aSettledRowThatKeptItsSessionWithholdsItsFigureRatherThanRiskingTheYearAgoOne()
            throws Exception {
        // GRAB and AEHR on 2026-11-02: an otherwise-flipped date, these rows left unflipped.
        String json = "{\"data\":{\"rows\":[{\"eps\":\"$0.01\",\"surprise\":\"N/A\","
                + "\"time\":\"time-after-hours\",\"symbol\":\"GRAB\",\"epsForecast\":\"$0.02\"}]}}";
        List<EarningsEvent> events = NasdaqEarningsFetcher.parse(
                new ObjectMapper().readTree(json), LocalDate.parse("2026-11-02"));
        assertNull(events.get(0).actualEps());
    }

    @Test
    void aFlippedRowWithNoSessionAndNoLastYearPairIsReadAsAnActual() throws Exception {
        String json = "{\"data\":{\"rows\":[{\"eps\":\"$2.05\",\"surprise\":\"3.54\","
                + "\"time\":\"time-not-supplied\",\"symbol\":\"AAPL\",\"epsForecast\":\"$1.98\"}]}}";
        List<EarningsEvent> events = NasdaqEarningsFetcher.parse(
                new ObjectMapper().readTree(json), LocalDate.parse("2026-11-02"));
        assertEquals(2.05, events.get(0).actualEps(), 1e-9);
        assertTrue(events.get(0).reported());
    }

    /**
     * The 42 rows on 2026-11-03 that neither per-row signature could catch: a company that never
     * stated a session sends {@code time-not-supplied} while still scheduled, and the last-year
     * pair had already been dropped from the whole roster. Only the roster says the date has not
     * flipped — nothing on the row does.
     */
    @Test
    void aScheduledRowWithNoSessionIsCaughtByTheRosterHavingNoSurprisesAtAll() throws Exception {
        String json = "{\"data\":{\"rows\":["
                + "{\"eps\":\"$0.75\",\"surprise\":\"N/A\",\"time\":\"time-not-supplied\","
                + "\"symbol\":\"AES\",\"epsForecast\":\"\"},"
                + "{\"eps\":\"$0.97\",\"surprise\":\"N/A\",\"time\":\"time-after-hours\","
                + "\"symbol\":\"AMD\",\"epsForecast\":\"$1.68\"}]}}";
        List<EarningsEvent> events = NasdaqEarningsFetcher.parse(
                new ObjectMapper().readTree(json), LocalDate.parse("2026-11-03"));
        assertEquals(2, events.size());
        for (EarningsEvent e : events) assertNull(e.actualEps(), e.symbol() + " has not reported");
    }

    @Test
    void oneRealSurpriseAnywhereOnTheRosterIsWhatOpensTheRestOfIt() throws Exception {
        // A flipped date: most rows carry a number, and the one that met consensus exactly
        // publishes a blank -- which must not cost it its figure.
        String json = "{\"data\":{\"rows\":["
                + "{\"eps\":\"$2.05\",\"surprise\":\"3.54\",\"time\":\"time-not-supplied\","
                + "\"symbol\":\"AAPL\",\"epsForecast\":\"$1.98\"},"
                + "{\"eps\":\"$0.32\",\"surprise\":\"N/A\",\"time\":\"time-not-supplied\","
                + "\"symbol\":\"PLTR\",\"epsForecast\":\"$0.32\"}]}}";
        List<EarningsEvent> events = NasdaqEarningsFetcher.parse(
                new ObjectMapper().readTree(json), LocalDate.parse("2026-11-02"));
        assertEquals(2.05, events.get(0).actualEps(), 1e-9);
        assertEquals(0.32, events.get(1).actualEps(), 1e-9);
    }
}
