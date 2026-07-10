package com.portfolio.adapter;

import com.portfolio.port.FxRateProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CachingFxRateProviderTest {

    /** Counts calls and can be flipped to fail, to exercise the fallback paths. */
    private static final class StubProvider implements FxRateProvider {
        int calls;
        boolean fail;
        final Map<String, BigDecimal> rates = Map.of("GBP", BigDecimal.ONE, "USD", new BigDecimal("1.36"));

        @Override
        public Map<String, BigDecimal> fetchRates() {
            calls++;
            if (fail) throw new IllegalStateException("boom");
            return rates;
        }
    }

    @Test
    void servesCachedRatesWithinTtlWithoutHittingDelegate() throws Exception {
        StubProvider stub = new StubProvider();
        CachingFxRateProvider caching = new CachingFxRateProvider(stub, Duration.ofMinutes(15));

        assertEquals(stub.rates, caching.fetchRates());
        assertEquals(stub.rates, caching.fetchRates());

        assertEquals(1, stub.calls, "second read should be served from cache");
    }

    @Test
    void servesStaleRatesWhenFetchFailsAfterAWarmCache() throws Exception {
        StubProvider stub = new StubProvider();
        // Zero TTL forces a delegate call on every read, so the failure path is exercised.
        CachingFxRateProvider caching = new CachingFxRateProvider(stub, Duration.ZERO);

        Map<String, BigDecimal> good = caching.fetchRates(); // warms the cache
        stub.fail = true;

        assertEquals(good, caching.fetchRates(), "stale-but-usable rates, not empty");
        assertEquals(2, stub.calls);
    }

    @Test
    void propagatesFailureOnColdStartWithNoCache() {
        StubProvider stub = new StubProvider();
        stub.fail = true;
        CachingFxRateProvider caching = new CachingFxRateProvider(stub, Duration.ofMinutes(15));

        assertThrows(IllegalStateException.class, caching::fetchRates);
    }

    @Test
    void refetchesOnceTtlExpires() throws Exception {
        StubProvider stub = new StubProvider();
        CachingFxRateProvider caching = new CachingFxRateProvider(stub, Duration.ZERO);

        caching.fetchRates();
        caching.fetchRates();

        assertEquals(2, stub.calls, "expired TTL should trigger a fresh fetch each time");
    }
}
