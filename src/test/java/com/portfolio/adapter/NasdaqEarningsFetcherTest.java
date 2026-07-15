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

    private List<EarningsEvent> parseSample() throws Exception {
        String json = Files.readString(Path.of("src/test/resources/nasdaq-earnings-sample.json"));
        return NasdaqEarningsFetcher.parse(new ObjectMapper().readTree(json), DATE);
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
}
