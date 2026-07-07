package com.portfolio.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void overrideOnlyFetcherResolvesTheCoreUniverse() {
        EdgarEarningsFetcher f = new EdgarEarningsFetcher();   // no registry — override map only
        assertTrue(f.isVerifiable("MU"));
        assertTrue(f.isVerifiable("nvda"));           // case-insensitive
        assertFalse(f.isVerifiable("SEMI.L"));        // UK listing — no SEC filings
        assertFalse(f.isVerifiable("ORCL"));          // not in overrides; only the registry knows it
        assertNotNull(f.sourceName());
    }

    @Test
    void registryResolvesNewNames_verificationIsDataDrivenDownstream(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("sec_company_tickers.json"),
                Files.readString(Path.of("src/test/resources/sec-company-tickers-sample.json")));
        EdgarEarningsFetcher f = new EdgarEarningsFetcher(new CikRegistry(dir));

        assertTrue(f.isVerifiable("MU"), "override still wins");
        assertTrue(f.isVerifiable("ORCL"), "resolved dynamically from the SEC file");
        // ASML resolves a CIK too — isVerifiable is CIK-presence; the 20-F/no-10-Q reality is caught
        // downstream (earningsDates would be empty), where EarningsService keeps it unverified.
        assertTrue(f.isVerifiable("ASML"));
        assertFalse(f.isVerifiable("SEMI.L"));        // absent from the file → unresolvable
    }
}
