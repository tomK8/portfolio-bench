package com.portfolio.application;

import com.portfolio.persistence.FxRateRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.port.HistoricalFxRateProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class CachedHistoricalFxRateProviderTest {

    @TempDir
    Path dbDir;

    /** Records every call and serves a rate for each day in the requested range. */
    private static class RecordingDelegate implements HistoricalFxRateProvider {
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

    /** Clock that can be stepped forward, so TTL boundaries are exercised rather than assumed. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(String iso) { this.now = Instant.parse(iso); }

        void plus(java.time.Duration d) { now = now.plus(d); }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** Seeds a dense weekday cache over [from, to] so no head gap confuses a tail assertion. */
    private void seed(String ccy, String from, String to) {
        TreeMap<LocalDate, BigDecimal> rates = new TreeMap<>();
        for (LocalDate d = LocalDate.parse(from); !d.isAfter(LocalDate.parse(to)); d = d.plusDays(1)) {
            rates.put(d, new BigDecimal("1.25"));
        }
        new FxRateRepository(new JdbcConnectionFactory(dbDir)).save(ccy, rates);
    }

    /** Provider whose "now" is pinned to noon UTC on {@code today}. */
    private CachedHistoricalFxRateProvider providerOn(RecordingDelegate delegate, String today) {
        Clock fixed = Clock.fixed(Instant.parse(today + "T12:00:00Z"), ZoneOffset.UTC);
        return new CachedHistoricalFxRateProvider(
                new FxRateRepository(new JdbcConnectionFactory(dbDir)), delegate, fixed);
    }

    // ---- tail backoff ---------------------------------------------------------------------------

    @Test
    void weekendGapIsNeverFetched() {
        // Sunday 2026-08-09: the series legitimately ends Friday the 7th and no amount of asking
        // will produce a Saturday or Sunday rate. Re-checking every 15 min made one unlucky
        // request per interval pay a Frankfurter round-trip for nothing.
        RecordingDelegate d = new RecordingDelegate();
        seed("USD", "2026-08-01", "2026-08-07");
        CachedHistoricalFxRateProvider p = providerOn(d, "2026-08-09");

        for (int i = 0; i < 3; i++) {
            assertDoesNotThrow(() -> p.fetchRateSeries("USD",
                    LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-09")));
        }

        assertEquals(List.of(), d.calls, "a weekend-only gap must cost no network call at all");
    }

    @Test
    void weekdayGapIsStillFetched() {
        // Same cache, but now it's Monday — there is a publishable day in the gap.
        RecordingDelegate d = new RecordingDelegate();
        seed("USD", "2026-08-01", "2026-08-07");
        CachedHistoricalFxRateProvider p = providerOn(d, "2026-08-10");

        assertDoesNotThrow(() -> p.fetchRateSeries("USD",
                LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-10")));

        assertEquals(1, d.calls.size(), d.calls.toString());
        assertTrue(d.calls.get(0).endsWith("2026-08-07..2026-08-10"), d.calls.get(0));
    }

    @Test
    void aRecheckThatGainsNothingBacksOffPastTheNormalTtl() {
        // Monday, but the source hasn't published today's rate yet, so a re-check returns only what
        // we already hold. The next attempt must land on the long backoff, not the 15-minute TTL.
        RecordingDelegate stuck = new RecordingDelegate() {
            @Override
            public Map<LocalDate, BigDecimal> fetchRateSeries(String ccy, LocalDate from, LocalDate to) {
                calls.add(ccy + " " + from + ".." + to);
                return new TreeMap<>(Map.of(LocalDate.parse("2026-08-07"), new BigDecimal("1.25")));
            }
        };
        seed("USD", "2026-08-01", "2026-08-07");
        MutableClock clock = new MutableClock("2026-08-10T08:00:00Z");
        CachedHistoricalFxRateProvider p = new CachedHistoricalFxRateProvider(
                new FxRateRepository(new JdbcConnectionFactory(dbDir)), stuck, clock);

        assertDoesNotThrow(() -> p.fetchRateSeries("USD",
                LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-10")));
        assertEquals(1, stuck.calls.size());

        clock.plus(java.time.Duration.ofMinutes(30));   // past TAIL_TTL, inside NO_PROGRESS_TTL
        assertDoesNotThrow(() -> p.fetchRateSeries("USD",
                LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-10")));
        assertEquals(1, stuck.calls.size(), "no-progress re-check must not retry on the short TTL");

        clock.plus(java.time.Duration.ofMinutes(31));   // now past NO_PROGRESS_TTL
        assertDoesNotThrow(() -> p.fetchRateSeries("USD",
                LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-10")));
        assertEquals(2, stuck.calls.size(), "backoff must expire, not disable the re-check");
    }

    @Test
    void aRecheckThatAdvancesTheSeriesUsesTheShortTtl() {
        RecordingDelegate d = new RecordingDelegate();
        seed("USD", "2026-08-01", "2026-08-07");
        MutableClock clock = new MutableClock("2026-08-10T08:00:00Z");
        CachedHistoricalFxRateProvider p = new CachedHistoricalFxRateProvider(
                new FxRateRepository(new JdbcConnectionFactory(dbDir)), d, clock);

        // Delegate fills through Monday, so the series advanced.
        assertDoesNotThrow(() -> p.fetchRateSeries("USD",
                LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-10")));
        assertEquals(1, d.calls.size());

        clock.plus(java.time.Duration.ofMinutes(20));   // past TAIL_TTL
        // Cache now ends Monday = today, so there's nothing left to want: still no new call.
        assertDoesNotThrow(() -> p.fetchRateSeries("USD",
                LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-10")));
        assertEquals(1, d.calls.size(), "a fully covered range needs no re-check at all");
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
