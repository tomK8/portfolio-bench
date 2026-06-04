package com.portfolio.adapter;

import com.portfolio.domain.model.PriceBar;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hits the real Yahoo Finance API. Tagged {@code integration} so it is excluded from the
 * default {@code mvn test} run (see surefire {@code excludedGroups}). Run explicitly with:
 * {@code mvn test -Dgroups=integration}.
 */
@Tag("integration")
class YahooPriceFetcherIntegrationTest {

    @Test
    void fetchesRecentNvdaBars() {
        LocalDate today = LocalDate.now();
        List<PriceBar> bars = new YahooPriceFetcher().fetch("NVDA", today.minusDays(10), today);

        assertFalse(bars.isEmpty(), "expected at least a few trading days in the last 10 days");
        PriceBar b = bars.get(0);
        assertEquals("NVDA", b.symbol());
        assertEquals("USD", b.currency());
        assertTrue(b.close() > 0);
    }
}
