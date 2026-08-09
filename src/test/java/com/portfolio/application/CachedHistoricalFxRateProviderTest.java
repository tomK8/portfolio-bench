package com.portfolio.application;

import com.portfolio.persistence.FxRateRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.port.HistoricalFxRateProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class CachedHistoricalFxRateProviderTest {

    @TempDir
    Path dbDir;

    /** Records every call and serves a rate for each day in the requested range. */
    private static final class RecordingDelegate implements HistoricalFxRateProvider {
        final List<String> calls = new ArrayList<>();
        boolean broken = false;

        @Override
        public Map<LocalDate, BigDecimal> fetchRateSeries(String ccy, LocalDate from, LocalDate to) {
            calls.add(ccy + " " + from + ".." + to);
            if (broken) throw new IllegalStateException("request timed out");
            TreeMap<LocalDate, BigDecimal> out = new TreeMap<>();
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                out.put(d, new BigDecimal("1.25"));
            }
            return out;
        }
    }

    private CachedHistoricalFxRateProvider provider(RecordingDelegate delegate) {
        return new CachedHistoricalFxRateProvider(
                new FxRateRepository(new JdbcConnectionFactory(dbDir)), delegate);
    }

    @Test
    void coldCacheFetchesThenServesFromDiskWithNoFurtherCalls() throws Exception {
        RecordingDelegate d = new RecordingDelegate();
        CachedHistoricalFxRateProvider p = provider(d);
        LocalDate from = LocalDate.parse("2026-01-01"), to = LocalDate.parse("2026-01-10");

        assertEquals(10, p.fetchRateSeries("USD", from, to).size());
        assertEquals(1, d.calls.size());

        // Second identical request is fully covered — the whole point.
        assertEquals(10, p.fetchRateSeries("USD", from, to).size());
        assertEquals(1, d.calls.size(), "covered range must not hit the network again");
    }

    @Test
    void widerRequestFetchesOnlyTheUncoveredHead() throws Exception {
        RecordingDelegate d = new RecordingDelegate();
        CachedHistoricalFxRateProvider p = provider(d);
        p.fetchRateSeries("USD", LocalDate.parse("2026-01-10"), LocalDate.parse("2026-01-20"));
        d.calls.clear();

        Map<LocalDate, BigDecimal> got = p.fetchRateSeries("USD",
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-20"));

        assertEquals(20, got.size());
        assertEquals(1, d.calls.size());
        assertTrue(d.calls.get(0).endsWith("2026-01-01..2026-01-10"), d.calls.get(0));
    }

    @Test
    void fetchFailureServesWhateverIsCached() throws Exception {
        // The regression this class exists for: a timeout must never present as "no rates", which
        // downstream turned into every USD position valued at £0 on one boundary.
        RecordingDelegate d = new RecordingDelegate();
        CachedHistoricalFxRateProvider p = provider(d);
        p.fetchRateSeries("USD", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-10"));

        d.broken = true;
        Map<LocalDate, BigDecimal> got = p.fetchRateSeries("USD",
                LocalDate.parse("2025-12-01"), LocalDate.parse("2026-01-10"));

        assertEquals(10, got.size(), "cached window still served despite the failed head fetch");
        assertEquals(1.25, got.get(LocalDate.parse("2026-01-05")).doubleValue(), 1e-9);
    }

    @Test
    void coldCacheAndFailedFetchStillThrows() {
        RecordingDelegate d = new RecordingDelegate();
        d.broken = true;
        CachedHistoricalFxRateProvider p = provider(d);

        // Nothing to fall back on — callers already handle this and log a warning.
        assertThrows(Exception.class, () -> p.fetchRateSeries("USD",
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-10")));
    }

    @Test
    void tailRecheckIsThrottled() throws Exception {
        RecordingDelegate d = new RecordingDelegate();
        CachedHistoricalFxRateProvider p = provider(d);
        LocalDate today = LocalDate.now();
        p.fetchRateSeries("USD", today.minusDays(30), today.minusDays(5));
        d.calls.clear();

        // A burst of clicks all wanting "up to today": the tail may be re-checked at most once.
        for (int i = 0; i < 5; i++) p.fetchRateSeries("USD", today.minusDays(30), today);

        assertEquals(1, d.calls.size(), "repeated requests must not stampede the API");
    }
}
