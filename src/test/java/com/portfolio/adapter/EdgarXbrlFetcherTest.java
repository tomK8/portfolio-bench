package com.portfolio.adapter;

import com.portfolio.adapter.EdgarXbrlFetcher.Series;
import com.portfolio.adapter.EdgarXbrlFetcher.Status;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class EdgarXbrlFetcherTest {

    private final EdgarXbrlFetcher fetcher = new EdgarXbrlFetcher(null);

    @Test
    void parsesQuarterlyFactsWithTheirOwnWindows() throws Exception {
        Series s = fetcher.parse("""
                {"units":{"USD":[
                  {"start":"2026-01-01","end":"2026-03-31","val":30,"form":"10-Q","filed":"2026-04-20"},
                  {"start":"2026-04-01","end":"2026-06-30","val":40,"form":"10-Q","filed":"2026-07-20"}
                ]}}""");

        assertEquals(Status.OK, s.status());
        assertEquals("USD", s.unit());
        assertEquals(2, s.facts().size());
        assertEquals(LocalDate.parse("2026-01-01"), s.facts().get(0).start());
        assertEquals(0, s.facts().get(1).value().compareTo(new BigDecimal("40")));
    }

    /**
     * A later filing re-tags an earlier period — a 10-Q/A amendment, or a 10-K restating a
     * comparative. The latest filing is the one that stands, or the panel would show a figure
     * the company has since corrected.
     */
    @Test
    void latestFilingWinsForTheSameWindow() throws Exception {
        Series s = fetcher.parse("""
                {"units":{"USD":[
                  {"start":"2026-01-01","end":"2026-03-31","val":30,"form":"10-Q","filed":"2026-04-20"},
                  {"start":"2026-01-01","end":"2026-03-31","val":28,"form":"10-K","filed":"2027-02-10"}
                ]}}""");

        assertEquals(1, s.facts().size());
        assertEquals(0, s.facts().get(0).value().compareTo(new BigDecimal("28")),
                "the restated figure, not the one it replaced");
    }

    /**
     * ASML's real shape: 51 facts, every one a 20-F, denominated in EUR. A foreign private
     * issuer resolves a CIK but reports on a basis none of the other rows share, which is a
     * different answer from "this company reports no operating income" — so the status says so
     * and the panel can give the reason rather than a bare dash.
     */
    @Test
    void reportsAForeignFilerSeparatelyFromAnUntaggedOne() throws Exception {
        Series foreign = fetcher.parse("""
                {"units":{"EUR":[
                  {"start":"2025-01-01","end":"2025-12-31","val":9000,"form":"20-F","filed":"2026-02-11"},
                  {"start":"2024-01-01","end":"2024-12-31","val":8000,"form":"20-F/A","filed":"2025-03-01"}
                ]}}""");
        assertEquals(Status.NOT_DOMESTIC, foreign.status());
        assertTrue(foreign.isEmpty());

        // V's real shape: HTTP 200 with a unit key and no facts under it at all.
        Series hollow = fetcher.parse("{\"units\":{\"USD\":[]}}");
        assertEquals(Status.UNTAGGED, hollow.status());
        assertTrue(hollow.isEmpty());

        assertEquals(Status.UNTAGGED, fetcher.parse("{\"units\":{}}").status());
        assertEquals(Status.UNTAGGED, fetcher.parse("{}").status());
    }

    /** A filer reporting in one non-USD unit is read in it, not assumed to be dollars. */
    @Test
    void takesTheUnitAsFiled() throws Exception {
        Series s = fetcher.parse("""
                {"units":{"CAD":[
                  {"start":"2026-01-01","end":"2026-03-31","val":30,"form":"10-Q","filed":"2026-04-20"}
                ]}}""");
        assertEquals("CAD", s.unit());
        assertEquals(Status.OK, s.status());
    }

    @Test
    void skipsFactsMissingAWindowOrAValue() throws Exception {
        Series s = fetcher.parse("""
                {"units":{"USD":[
                  {"end":"2026-03-31","val":30,"form":"10-Q","filed":"2026-04-20"},
                  {"start":"2026-04-01","end":"2026-06-30","form":"10-Q","filed":"2026-07-20"},
                  {"start":"2026-07-01","end":"2026-09-30","val":50,"form":"10-Q","filed":"2026-10-20"}
                ]}}""");
        assertEquals(1, s.facts().size());
        assertEquals(0, s.facts().get(0).value().compareTo(new BigDecimal("50")));
    }

    @Test
    void noRegistryMeansNoClaimEitherWay() {
        Series s = fetcher.series("AAPL", "OperatingIncomeLoss");
        assertEquals(Status.UNAVAILABLE, s.status(), "no CIK resolver — says nothing about AAPL");
        assertTrue(s.isEmpty());
    }
}
