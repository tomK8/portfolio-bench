package com.portfolio.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;

import static org.junit.jupiter.api.Assertions.*;

class FxRateRepositoryTest {

    @TempDir
    Path dbDir;

    private FxRateRepository repo() {
        return new FxRateRepository(new JdbcConnectionFactory(dbDir));
    }

    private static Map<LocalDate, BigDecimal> rates(String... pairs) {
        Map<LocalDate, BigDecimal> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(LocalDate.parse(pairs[i]), new BigDecimal(pairs[i + 1]));
        }
        return out;
    }

    @Test
    void roundTripsWithinRange() {
        FxRateRepository r = repo();
        r.save("USD", rates("2026-01-02", "1.25", "2026-01-05", "1.30", "2026-02-01", "1.35"));

        NavigableMap<LocalDate, BigDecimal> got = r.load("USD",
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"));

        assertEquals(2, got.size(), "February must be outside the requested range");
        assertEquals(1.25, got.get(LocalDate.parse("2026-01-02")).doubleValue(), 1e-9);
        assertEquals(1.30, got.lastEntry().getValue().doubleValue(), 1e-9);
    }

    @Test
    void currenciesAreIsolated() {
        FxRateRepository r = repo();
        r.save("USD", rates("2026-01-02", "1.25"));
        r.save("EUR", rates("2026-01-02", "1.18"));

        assertEquals(1.18, r.load("EUR", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-03"))
                .firstEntry().getValue().doubleValue(), 1e-9);
    }

    @Test
    void reSaveOverwritesTheSameDate() {
        // The tail is re-fetched while a day is still moving, so last write must win.
        FxRateRepository r = repo();
        r.save("USD", rates("2026-01-02", "1.25"));
        r.save("USD", rates("2026-01-02", "1.27"));

        NavigableMap<LocalDate, BigDecimal> got = r.load("USD",
                LocalDate.parse("2026-01-02"), LocalDate.parse("2026-01-02"));
        assertEquals(1, got.size());
        assertEquals(1.27, got.firstEntry().getValue().doubleValue(), 1e-9);
    }

    @Test
    void reportsCoverageBoundaries() {
        FxRateRepository r = repo();
        assertNull(r.earliest("USD"), "nothing cached yet");
        assertNull(r.latest("USD"));

        r.save("USD", rates("2026-01-02", "1.25", "2026-03-09", "1.31"));

        assertEquals(LocalDate.parse("2026-01-02"), r.earliest("USD"));
        assertEquals(LocalDate.parse("2026-03-09"), r.latest("USD"));
    }

    @Test
    void emptySaveIsANoOp() {
        assertEquals(0, repo().save("USD", Map.of()));
    }
}
