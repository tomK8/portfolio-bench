package com.portfolio.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.adapter.NasdaqCompanyEarningsFetcher.Forecast;
import com.portfolio.adapter.NasdaqCompanyEarningsFetcher.ForecastRow;
import com.portfolio.adapter.NasdaqCompanyEarningsFetcher.PriorPrint;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NasdaqCompanyEarningsFetcherTest {

    private static com.fasterxml.jackson.databind.JsonNode fixture(String name) throws Exception {
        return new ObjectMapper().readTree(Files.readString(Path.of("src/test/resources/" + name)));
    }

    @Test
    void readsTheReportedDateWhichIsTheWholeReasonForTheCall() throws Exception {
        List<PriorPrint> rows = NasdaqCompanyEarningsFetcher.parseSurprise(
                fixture("nasdaq-earnings-surprise-sample.json"));
        assertEquals(LocalDate.parse("2026-11-02"), rows.get(0).reported());
        assertEquals("Sep 2026", rows.get(0).fiscalQuarterEnd());
        assertEquals(2.05, rows.get(0).eps(), 1e-9);
        assertEquals(1.98, rows.get(0).consensus(), 1e-9);
    }

    @Test
    void bracketedFiguresAreNegativeAndNotAnAbsentValue() throws Exception {
        PriorPrint loss = NasdaqCompanyEarningsFetcher.parseSurprise(
                fixture("nasdaq-earnings-surprise-sample.json")).get(2);
        assertEquals(-0.11, loss.eps(), 1e-9);
        assertEquals(-0.21, loss.consensus(), 1e-9);
    }

    @Test
    void aRowWithNoReportDateIsDroppedBecauseThatIsTheFieldNothingElseSupplies() throws Exception {
        List<PriorPrint> rows = NasdaqCompanyEarningsFetcher.parseSurprise(
                fixture("nasdaq-earnings-surprise-sample.json"));
        assertEquals(3, rows.size());
        assertTrue(rows.stream().allMatch(r -> r.reported() != null));
    }

    @Test
    void forecastRowsCarryTheirFiscalPeriodAndTheFourWeekRevisionCounts() throws Exception {
        Forecast f = NasdaqCompanyEarningsFetcher.parseForecast(
                fixture("nasdaq-earnings-forecast-sample.json"), "ZZZA");
        ForecastRow q = f.quarterly().get(0);
        assertEquals("Dec 2026", q.fiscalEnd());
        assertEquals(2.88, q.consensus(), 1e-9);
        assertEquals(6, q.numEstimates());
        assertEquals(0, q.up());
        assertEquals(1, q.down());

        ForecastRow y = f.yearly().get(0);
        assertEquals("Sep 2027", y.fiscalEnd());
        assertEquals(9.56, y.consensus(), 1e-9);
    }

    @Test
    void anUnnamedPeriodIsDroppedBecauseThePeriodIsTheKey() throws Exception {
        Forecast f = NasdaqCompanyEarningsFetcher.parseForecast(
                fixture("nasdaq-earnings-forecast-sample.json"), "ZZZA");
        assertEquals(2, f.quarterly().size());
    }

    @Test
    void anEmptyPayloadIsAnEmptyReadRatherThanAnException() throws Exception {
        assertTrue(NasdaqCompanyEarningsFetcher.parseSurprise(
                new ObjectMapper().readTree("{\"data\":null}")).isEmpty());
        Forecast f = NasdaqCompanyEarningsFetcher.parseForecast(
                new ObjectMapper().readTree("{\"data\":null}"), "ZZZA");
        assertTrue(f.quarterly().isEmpty());
        assertTrue(f.yearly().isEmpty());
    }

    @Test
    void anUnparseableDateIsNullRatherThanToday() {
        assertNull(NasdaqCompanyEarningsFetcher.parseReported("not a date"));
        assertNull(NasdaqCompanyEarningsFetcher.parseReported(""));
        assertEquals(LocalDate.parse("2026-01-29"),
                NasdaqCompanyEarningsFetcher.parseReported("1/29/2026"));
    }
}
