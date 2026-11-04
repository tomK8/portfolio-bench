package com.portfolio.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.adapter.YahooQuoteSummaryFetcher.EarningsDetail;
import com.portfolio.adapter.YahooQuoteSummaryFetcher.ReportedQuarter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class YahooEarningsDetailTest {

    private static EarningsDetail parse() throws Exception {
        String json = Files.readString(
                Path.of("src/test/resources/yahoo-earnings-detail-sample.json"));
        return YahooQuoteSummaryFetcher.parseEarningsDetail("ZZZA",
                new ObjectMapper().readTree(json).path("quoteSummary").path("result").get(0));
    }

    @Test
    void revenueIsJoinedToItsQuarterByLabelNotByPosition() throws Exception {
        EarningsDetail d = parse();
        ReportedQuarter q = d.reportedOn(LocalDate.parse("2026-11-02"));
        assertNotNull(q);
        assertEquals("3Q2026", q.label());
        assertEquals(114477000000L, q.revenue().longValue());
        assertEquals("USD", d.financialCurrency());
    }

    @Test
    void aQuarterIsFoundByTheDayItWasReportedOnTheMarketsClock() throws Exception {
        EarningsDetail d = parse();
        assertNotNull(d.reportedOn(LocalDate.parse("2026-07-30")));
        assertNull(d.reportedOn(LocalDate.parse("2026-07-31")));
    }

    @Test
    void theConsensusComesWithTheDateItBelongsTo() throws Exception {
        EarningsDetail d = parse();
        assertEquals(LocalDate.parse("2026-11-02"), d.nextEarningsDate());
        assertEquals(1.98, d.nextEpsConsensus().doubleValue(), 1e-9);
        assertEquals(112500000000L, d.nextRevenueConsensus().longValue());
        assertFalse(d.missing());
    }
}
