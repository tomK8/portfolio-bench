package com.portfolio.adapter;

import com.portfolio.adapter.EdgarFundamentalsFetcher.EpsFact;
import com.portfolio.adapter.EdgarFundamentalsFetcher.EpsHistory;
import com.portfolio.adapter.EdgarXbrlFetcher.Status;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class EdgarFundamentalsFetcherTest {

    private final EdgarFundamentalsFetcher fetcher = new EdgarFundamentalsFetcher(null);

    private String sample() throws Exception {
        return Files.readString(Path.of(
                getClass().getResource("/edgar-aapl-sample.json").toURI()));
    }

    private Map<LocalDate, EpsFact> byEnd(List<EpsFact> facts) {
        return facts.stream().collect(Collectors.toMap(EpsFact::periodEnd, Function.identity(),
                (a, b) -> a));
    }

    /** One fact by its whole window — two of them can end on the same day. */
    private EpsFact window(List<EpsFact> facts, String start, String end) {
        return facts.stream()
                .filter(f -> f.start().equals(LocalDate.parse(start))
                        && f.periodEnd().equals(LocalDate.parse(end)))
                .findFirst().orElseThrow();
    }

    /**
     * Fixture mirrors the real {@code companyconcept} shape: quarterly 10-Q figures, the 10-K's
     * annual, a nine-month year-to-date, a restatement, and one record on a form a 10-K/10-Q
     * series may not use.
     */
    @Test
    void keepsEveryFiledWindowOnADomesticForm() throws Exception {
        List<EpsFact> facts = fetcher.parse(sample()).facts();

        // 5 quarters + the 10-K's year + two year-to-date windows = 8. The 8-K record is
        // dropped on its form.
        assertEquals(8, facts.size(), () -> "Got: " + facts);
        assertTrue(facts.stream().noneMatch(f -> f.periodEnd().equals(LocalDate.of(2021, 6, 30))),
                "a record filed on a form outside 10-K/10-Q is not part of a domestic series");

        Map<LocalDate, EpsFact> q = byEnd(facts);
        assertEquals(0, q.get(LocalDate.of(2021, 12, 31)).eps().compareTo(new BigDecimal("2.10")));
        assertEquals(0, q.get(LocalDate.of(2022, 3, 31)).eps().compareTo(new BigDecimal("1.52")));
        assertEquals(0, q.get(LocalDate.of(2022, 6, 30)).eps().compareTo(new BigDecimal("1.20")));
        assertEquals(0, q.get(LocalDate.of(2022, 12, 31)).eps().compareTo(new BigDecimal("1.88")));
    }

    /**
     * The annual and the nine-month year-to-date are kept, not filtered out by their length.
     * They are what recovers the fourth quarter a 10-K leaves implicit — the whole reason this
     * adapter stopped returning quarters only.
     */
    @Test
    void keepsTheAnnualAndTheYearToDateTheQuarterIsRecoveredFrom() throws Exception {
        List<EpsFact> facts = fetcher.parse(sample()).facts();

        EpsFact annual = byEnd(facts).get(LocalDate.of(2022, 9, 30));
        assertNotNull(annual, "the 10-K's own twelve months must survive the parse");
        assertEquals(LocalDate.of(2021, 10, 1), annual.start());
        assertEquals(0, annual.eps().compareTo(new BigDecimal("6.11")));

        assertTrue(facts.stream().anyMatch(f -> f.start().equals(LocalDate.of(2022, 10, 1))
                        && f.periodEnd().equals(LocalDate.of(2023, 4, 1))),
                "the half-year year-to-date is a window in its own right, kept for the same reason");
    }

    @Test
    void sortsByPeriodEndAscending() throws Exception {
        List<EpsFact> facts = fetcher.parse(sample()).facts();
        for (int i = 1; i < facts.size(); i++) {
            assertFalse(facts.get(i).periodEnd().isBefore(facts.get(i - 1).periodEnd()),
                    "Series must be sorted by period end ascending");
        }
    }

    @Test
    void recordsCarryTheirSecFilingDateAsAvailableFrom() throws Exception {
        EpsFact q = byEnd(fetcher.parse(sample()).facts()).get(LocalDate.of(2022, 6, 30));
        assertEquals(LocalDate.of(2022, 7, 29), q.availableFrom(),
                "availableFrom must be the 10-Q's filing date — that's when it became public");
    }

    @Test
    void availableFromIsTheEarliestFilingDateForRestatedPeriods() throws Exception {
        // The fixture's 2023-01-01..2023-04-01 window has two filings: the original 10-Q on
        // 2023-05-05 and a 10-Q/A restatement on 2023-06-10. The value is the restated 1.99
        // (latest wins), but availableFrom must be the original 2023-05-05 — the quarter was
        // already public then, just on its pre-restatement basis. Using the restatement date
        // would drop this quarter out of the TTM at every chart point in between, and the line
        // would dip through a window where the market plainly knew the number.
        EpsFact q = window(fetcher.parse(sample()).facts(), "2023-01-01", "2023-04-01");
        assertEquals(0, q.eps().compareTo(new BigDecimal("1.99")), "latest-filed value wins");
        assertEquals(LocalDate.of(2023, 5, 5), q.availableFrom(),
                "availableFrom must be the EARLIEST filing, not the restatement");
        assertEquals(LocalDate.of(2023, 6, 10), q.valueFiled(),
                "valueFiled is the basis the kept value is in — the split pivot");
    }

    /**
     * Two windows can share an end date — a fiscal Q2 10-Q tags both the quarter and the
     * half-year ending that day — so periods are keyed by (start, end) rather than by end alone.
     */
    @Test
    void twoWindowsEndingTheSameDayAreBothKept() throws Exception {
        List<EpsFact> facts = fetcher.parse(sample()).facts();
        long endingApril2023 = facts.stream()
                .filter(f -> f.periodEnd().equals(LocalDate.of(2023, 4, 1))).count();
        assertEquals(2, endingApril2023, "the quarter and the half-year are different windows");
    }

    @Test
    void anEmptyOrMalformedPayloadIsUntaggedRatherThanAnError() throws Exception {
        assertEquals(Status.UNTAGGED, fetcher.parse("{}").status());
        assertEquals(Status.UNTAGGED, fetcher.parse("{\"units\":{}}").status());
        assertEquals(Status.UNTAGGED, fetcher.parse("{\"units\":{\"USD/shares\":[]}}").status(),
                "Cadence's real answer: the concept exists with no figures under it");
    }

    /**
     * ARM's and BABA's real shape — figures on file, every one of them on a foreign form. That is
     * a different fact from "never tagged", and the panel says which.
     */
    @Test
    void figuresOnlyOnForeignFormsAreNotDomestic() throws Exception {
        String json = """
                {"units":{"USD/shares":[
                  {"start":"2025-04-01","end":"2026-03-31","val":0.4,"form":"20-F","filed":"2026-05-20"},
                  {"start":"2024-04-01","end":"2025-03-31","val":0.3,"form":"20-F","filed":"2025-05-21"}
                ]}}""";
        EpsHistory h = fetcher.parse(json);
        assertTrue(h.isEmpty());
        assertEquals(Status.NOT_DOMESTIC, h.status());
    }

    @Test
    void aSymbolWithNoCikIsUnavailableRatherThanAVerdict() {
        // No registry wired in, so nothing resolves — the fetch must not claim the filer tags
        // nothing, because it never asked.
        EpsHistory h = fetcher.fetch("AAPL");
        assertTrue(h.isEmpty());
        assertEquals(Status.UNAVAILABLE, h.status());
    }
}
