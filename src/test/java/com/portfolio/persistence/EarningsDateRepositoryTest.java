package com.portfolio.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EarningsDateRepositoryTest {

    @TempDir
    Path dbDir;

    @Test
    void roundTripsDatesSortedAndDeduped() {
        var repo = new EarningsDateRepository(new JdbcConnectionFactory(dbDir));
        assertFalse(repo.hasAny("MU"));

        repo.saveDates("MU", List.of(
                LocalDate.parse("2025-06-25"),
                LocalDate.parse("2025-03-20"),
                LocalDate.parse("2025-06-25")), "test");    // duplicate collapses on the PK

        assertTrue(repo.hasAny("MU"));
        assertEquals(List.of(LocalDate.parse("2025-03-20"), LocalDate.parse("2025-06-25")),
                repo.datesFor("MU"));
        assertTrue(repo.datesFor("AMAT").isEmpty());
    }

    @Test
    void emptySaveIsNoOp() {
        var repo = new EarningsDateRepository(new JdbcConnectionFactory(dbDir));
        repo.saveDates("MU", List.of(), "test");
        assertFalse(repo.hasAny("MU"));
    }
}
