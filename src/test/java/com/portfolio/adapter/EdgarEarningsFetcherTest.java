package com.portfolio.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

class EdgarEarningsFetcherTest {

    @Test
    void keepsOnly10qAnd10kFilingDates() throws Exception {
        String json = Files.readString(Path.of("src/test/resources/edgar-submissions-sample.json"));
        var recent = new ObjectMapper().readTree(json).path("filings").path("recent");

        TreeSet<LocalDate> out = new TreeSet<>();
        EdgarEarningsFetcher.collect(recent, out);

        // The 8-K (2025-04-01) is dropped; the four 10-Q/10-K dates survive, sorted ascending.
        assertEquals(List.of(
                LocalDate.parse("2024-11-12"),
                LocalDate.parse("2025-02-18"),
                LocalDate.parse("2025-05-06"),
                LocalDate.parse("2025-08-05")), new ArrayList<>(out));
    }

    @Test
    void verifiabilityFollowsTheCikMap() {
        EdgarEarningsFetcher f = new EdgarEarningsFetcher();
        assertTrue(f.isVerifiable("MU"));
        assertTrue(f.isVerifiable("nvda"));           // case-insensitive
        assertFalse(f.isVerifiable("SEMI.L"));        // UK listing — no SEC filings
        assertFalse(f.isVerifiable("ASML"));          // files 20-F/6-K, not 10-Q/10-K
        assertNotNull(f.sourceName());
    }
}
