package com.portfolio.adapter;

import com.portfolio.domain.model.IntradayBar;
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

    /**
     * The case that motivated {@link YahooPriceFetcher#search}: an unsuffixed European line the
     * chart endpoint 404s on, which the search endpoint resolves. If this ever stops holding, the
     * unresolved-symbol diagnosis degrades to "no candidates" rather than breaking.
     */
    @Test
    void searchResolvesAnUnsuffixedEuropeanLineTheChartEndpointRejects() {
        YahooPriceFetcher fetcher = new YahooPriceFetcher();
        assertTrue(fetcher.fetch("EQQU", LocalDate.now().minusDays(10), LocalDate.now()).isEmpty(),
                "EQQU is expected to 404 on the chart endpoint — that is the premise");

        List<YahooPriceFetcher.Suggestion> hits = fetcher.search("EQQU", 6);
        assertFalse(hits.isEmpty(), "Yahoo search should know EQQU even though the chart endpoint does not");
        assertTrue(hits.stream().anyMatch(h -> "EQQU.L".equals(h.symbol())),
                () -> "expected the LSE line among " + hits);
    }

    @Test
    void searchOfNonsenseReturnsNoCandidatesRatherThanThrowing() {
        assertTrue(new YahooPriceFetcher().search("ZZQXQVNOTATICKER", 6).isEmpty());
    }

    @Test
    void fetchesRecentAaplIntradayBars() {
        java.time.Instant now = java.time.Instant.now();
        List<IntradayBar> bars = new YahooPriceFetcher()
                .fetchIntraday("AAPL", now.minus(java.time.Duration.ofDays(2)), now).bars();

        assertFalse(bars.isEmpty(), "expected at least a few minute bars in the last 2 days");
        IntradayBar b = bars.get(0);
        assertEquals("AAPL", b.symbol());
        assertEquals("USD", b.currency());
        assertTrue(b.close() > 0);
        assertTrue(b.ts().isBefore(now.plusSeconds(60)));
    }
}
