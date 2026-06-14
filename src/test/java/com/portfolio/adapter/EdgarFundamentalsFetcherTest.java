package com.portfolio.adapter;

import com.portfolio.adapter.EdgarFundamentalsFetcher.EpsQuarter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class EdgarFundamentalsFetcherTest {

    private String sample() throws Exception {
        return Files.readString(Path.of(
                getClass().getResource("/edgar-aapl-sample.json").toURI()));
    }

    /** AAPL fixture mirrors real EDGAR shape: 10-Q quarterlies + a 10-K annual + restated values. */
    @Test
    void extractsOneEpsRecordPerQuarterKeyedByPeriodEnd() throws Exception {
        List<EpsQuarter> quarters = new EdgarFundamentalsFetcher().parse(sample());

        // Quarterly records (~90d) in fixture by period end:
        //   2021-12-31 (FY2022 Q1)
        //   2022-03-31 (FY2022 Q2, with one restatement — 1.99 wins over 1.52)
        //   2022-06-30 (FY2022 Q3)
        //   2022-12-31 (FY2023 Q1)
        //   2023-04-01 (FY2023 Q2)
        // The fixture's 12-month FY record (val 6.11) is rejected by the duration filter.
        // YTD aggregates (~180d, ~270d) are also rejected.
        assertEquals(5, quarters.size(), () -> "Got: " + quarters);

        Map<LocalDate, EpsQuarter> byEnd = quarters.stream()
                .collect(Collectors.toMap(EpsQuarter::periodEnd, q -> q));

        assertEquals(0, byEnd.get(LocalDate.of(2021, 12, 31)).eps().compareTo(new BigDecimal("2.10")));
        assertEquals(0, byEnd.get(LocalDate.of(2022, 3, 31)).eps().compareTo(new BigDecimal("1.52")));
        assertEquals(0, byEnd.get(LocalDate.of(2022, 6, 30)).eps().compareTo(new BigDecimal("1.20")));
        assertEquals(0, byEnd.get(LocalDate.of(2022, 12, 31)).eps().compareTo(new BigDecimal("1.88")));
        assertEquals(0, byEnd.get(LocalDate.of(2023, 4, 1)).eps().compareTo(new BigDecimal("1.99")),
                "Latest filing (10-Q/A) wins over earlier 10-Q for the same period");
    }

    @Test
    void sortsQuartersAscendingByPeriodEnd() throws Exception {
        List<EpsQuarter> quarters = new EdgarFundamentalsFetcher().parse(sample());
        for (int i = 1; i < quarters.size(); i++) {
            assertFalse(quarters.get(i).periodEnd().isBefore(quarters.get(i - 1).periodEnd()),
                    "Series must be sorted by period end ascending");
        }
    }

    @Test
    void recordsCarryTheirSecFilingDateAsAvailableFrom() throws Exception {
        List<EpsQuarter> quarters = new EdgarFundamentalsFetcher().parse(sample());
        EpsQuarter q = quarters.stream()
                .filter(qx -> qx.periodEnd().equals(LocalDate.of(2022, 6, 30)))
                .findFirst().orElseThrow();
        assertEquals(LocalDate.of(2022, 7, 29), q.availableFrom(),
                "availableFrom must be the 10-Q's filing date — that's when it became public");
    }

    @Test
    void availableFromIsTheEarliestFilingDateForRestatedPeriods() throws Exception {
        // The fixture's 2023-04-01 period has two filings: original 10-Q on 2023-05-05 and a
        // 10-Q/A restatement on 2023-06-10. The value should be the restated 1.99 (latest
        // wins), but availableFrom must be the original 2023-05-05 — the quarter was already
        // public then, just on its pre-restatement basis. If we used the restatement date,
        // every chart point between 2023-05-05 and 2023-06-10 would be missing this quarter
        // from its TTM sum and the line would dip.
        List<EpsQuarter> quarters = new EdgarFundamentalsFetcher().parse(sample());
        EpsQuarter q = quarters.stream()
                .filter(qx -> qx.periodEnd().equals(LocalDate.of(2023, 4, 1)))
                .findFirst().orElseThrow();
        assertEquals(0, q.eps().compareTo(new BigDecimal("1.99")), "latest-filed value wins");
        assertEquals(LocalDate.of(2023, 5, 5), q.availableFrom(),
                "availableFrom must be the EARLIEST filing, not the restatement");
    }

    @Test
    void returnsEmptyOnMalformedJson() throws Exception {
        assertTrue(new EdgarFundamentalsFetcher().parse("{}").isEmpty());
        assertTrue(new EdgarFundamentalsFetcher().parse("{\"facts\":{}}").isEmpty());
    }

    @Test
    void cikRegistryLookups() {
        assertTrue(EdgarFundamentalsFetcher.isSupported("AAPL"));
        assertTrue(EdgarFundamentalsFetcher.isSupported("googl"));   // case-insensitive
        assertFalse(EdgarFundamentalsFetcher.isSupported("LGEN.L"));
        assertFalse(EdgarFundamentalsFetcher.isSupported(null));
    }
}
