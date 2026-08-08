package com.portfolio.adapter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CikRegistryTest {

    private static String sample() throws Exception {
        return Files.readString(Path.of("src/test/resources/sec-company-tickers-sample.json"));
    }

    @Test
    void parsesTickerToCikMap() throws Exception {
        Map<String, Integer> map = CikRegistry.parse(sample());
        assertEquals(1341439, map.get("ORCL"));
        assertEquals(937966, map.get("ASML"));   // present with a CIK even though it files 20-F
        assertEquals(4, map.size());
    }

    @Test
    void resolvesFromFreshDiskCacheWithoutFetching(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("sec_company_tickers.json"), sample());

        // fetch() would throw if the fresh cache were bypassed.
        CikRegistry registry = new CikRegistry(dir) {
            @Override String fetch() { throw new AssertionError("fresh cache must not trigger a fetch"); }
        };

        assertEquals(1341439, registry.cik("ORCL").orElseThrow());
        assertEquals(1341439, registry.cik("orcl").orElseThrow());   // case-insensitive
        assertTrue(registry.cik("SEMI.L").isEmpty());                // not a US filer → unresolvable
        assertTrue(registry.cik(null).isEmpty());
    }

    @Test
    void fetchesAndCachesWhenNoDiskCopy(@TempDir Path dir) throws Exception {
        String json = sample();
        CikRegistry registry = new CikRegistry(dir) {
            @Override String fetch() { return json; }
        };

        assertEquals(937966, registry.cik("ASML").orElseThrow());
        assertTrue(Files.exists(dir.resolve("sec_company_tickers.json")), "fetch result is cached to disk");
    }

    @Test
    void emptyWhenFetchFailsAndNoCache(@TempDir Path dir) {
        CikRegistry registry = new CikRegistry(dir) {
            @Override String fetch() { return null; }
        };
        assertTrue(registry.cik("ORCL").isEmpty());
    }
}
