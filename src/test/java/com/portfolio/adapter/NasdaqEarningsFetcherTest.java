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

    @Test
    void anActualWithNoConsensusIsStillAnActual() throws Exception {
        // WSO.B reported a figure no analyst covered. Dropping the row would lose a real report;
        // defaulting the consensus to zero would invent a beat of the whole amount.
        EarningsEvent wso = settled("WSO.B");
        assertEquals(3.77, wso.actualEps(), 1e-9);
        assertNull(wso.epsForecast());
        assertNull(wso.numEstimates());
    }
}
