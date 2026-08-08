package com.portfolio.application;

import com.portfolio.domain.model.PriceBar;
import com.portfolio.domain.swing.EarningsLookup;
import com.portfolio.domain.swing.SwingConfig;
import com.portfolio.domain.swing.SwingSeries;
import com.portfolio.persistence.EarningsDateRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.port.EarningsCalendarProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EarningsServiceTest {

    private static SwingSeries series(int n) {
        return series("X", n);
    }

    private static SwingSeries series(String symbol, int n) {
        List<PriceBar> bars = new ArrayList<>();
        LocalDate d = LocalDate.parse("2026-01-01");
        for (int i = 0; i < n; i++) {
            bars.add(new PriceBar(symbol, d, null, 100.0, 100.0, 100.0, 100.0, 1.0, null, "USD"));
            d = d.plusDays(1);
        }
        return SwingSeries.of(symbol, bars, 5, 200);
    }

    @Test
    void lookupFlagsWindowAndTradingDaysToNearest() {
        SwingSeries s = series(40);
        EarningsLookup el = EarningsService.buildLookup(s, List.of(s.dateAt(20)), 3);

        assertTrue(el.verified());
        assertEquals(0, el.at(20).tradingDaysToNearest());
        assertTrue(el.at(20).withinWindow());
        assertEquals(3, el.at(23).tradingDaysToNearest());
        assertTrue(el.at(23).withinWindow());
        assertEquals(4, el.at(24).tradingDaysToNearest());
        assertFalse(el.at(24).withinWindow(), "4 sessions is outside a ±3 window");
    }

    @Test
    void unverifiedSentinelNeverFlags() {
        assertFalse(EarningsLookup.UNVERIFIED.verified());
        assertFalse(EarningsLookup.UNVERIFIED.at(5).withinWindow());
        assertNull(EarningsLookup.UNVERIFIED.at(5).tradingDaysToNearest());
    }

    /**
     * A CIK-resolving-but-no-10-Q filer (ASML-type) must stay unverified: {@link EarningsService}
     * keys verification off dates actually obtained, not the provider's CIK-presence flag.
     */
    @Test
    void resolvableButEmptySymbolStaysUnverified(@TempDir Path dir) {
        var repo = new EarningsDateRepository(new JdbcConnectionFactory(dir));
        var kv = new KeyValueStore(dir);

        // Both "verifiable" (CIK resolves); ORCL yields a date, ASML yields nothing (files 20-F).
        var provider = new EarningsCalendarProvider() {
            public List<LocalDate> earningsDates(String s) {
                return "ORCL".equals(s) ? List.of(LocalDate.parse("2025-06-22")) : List.of();
            }
            public boolean isVerifiable(String s) { return "ORCL".equals(s) || "ASML".equals(s); }
            public String sourceName() { return "fake"; }
        };
        var svc = new EarningsService(provider, repo, kv);

        svc.ensureEarnings(List.of("ORCL", "ASML"));

        assertTrue(svc.isVerifiable("ORCL"));
        assertFalse(svc.isVerifiable("ASML"), "resolves a CIK but no dates ⇒ unverified");
        assertTrue(svc.lookupFor(series("ORCL", 30), SwingConfig.builder().build()).verified());
        assertFalse(svc.lookupFor(series("ASML", 30), SwingConfig.builder().build()).verified());
    }

    /** Non-forced warm skips already-attempted symbols so we don't re-hit SEC every report. */
    @Test
    void checkedSetCapsRepeatFetches(@TempDir Path dir) {
        var repo = new EarningsDateRepository(new JdbcConnectionFactory(dir));
        var kv = new KeyValueStore(dir);
        AtomicInteger calls = new AtomicInteger();

        var provider = new EarningsCalendarProvider() {
            public List<LocalDate> earningsDates(String s) { calls.incrementAndGet(); return List.of(); }
            public boolean isVerifiable(String s) { return true; }
            public String sourceName() { return "fake"; }
        };
        var svc = new EarningsService(provider, repo, kv);

        svc.ensureEarnings(List.of("ASML"));
        svc.ensureEarnings(List.of("ASML"));                 // second pass must not fetch again
        assertEquals(1, calls.get());

        svc.ensureEarnings(List.of("ASML"), /*force*/ true); // force retries
        assertEquals(2, calls.get());
    }
}
