package com.portfolio.persistence;

import com.portfolio.adapter.YahooQuoteSummaryFetcher.QuoteSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The forward-dividend-yield read behind the open-orders drift netting.
 *
 * <p>Yahoo publishes no forward yield, so it is derived: {@code dividendRate ÷ price}, with the
 * trailing yield as a fallback. What these pin is the derivation, the scale it comes back on, and
 * the distinction between "pays nothing" and "we don't know" — which the caller needs and which a
 * map that answered zero for both would destroy.
 */
class FundamentalsYieldTest {

    private static FundamentalsRepository repo(Path dir) {
        return new FundamentalsRepository(new JdbcConnectionFactory(dir));
    }

    private static QuoteSummary quote(String symbol, String price, Map<String, BigDecimal> extra) {
        return quote(symbol, price, extra, "USD");
    }

    private static QuoteSummary quote(String symbol, String price, Map<String, BigDecimal> extra,
                                      String currency) {
        return new QuoteSummary(symbol, currency, price == null ? null : new BigDecimal(price),
                null, null, null, null, null, null, null, null,
                extra, Map.of(), Map.of(), List.of(), false);
    }

    private static Map<String, BigDecimal> dividends(String rate, String trailingYield) {
        java.util.Map<String, BigDecimal> m = new java.util.LinkedHashMap<>();
        if (rate != null) m.put("dividendRate", new BigDecimal(rate));
        if (trailingYield != null) m.put("dividendYield", new BigDecimal(trailingYield));
        return m;
    }

    @Test
    void forwardYieldIsTheIndicatedRateOverThePriceFromTheSameFetch(@TempDir Path dir) {
        FundamentalsRepository repo = repo(dir);
        // PepsiCo-shaped: $5.42 indicated annual dividend against a $143.48 quote ≈ 3.78%.
        repo.save("PEP", quote("PEP", "143.48", dividends("5.42", null)), Instant.now());

        BigDecimal y = repo.loadForwardYields(Set.of("PEP")).get("PEP");
        assertEquals(5.42 / 143.48, y.doubleValue(), 1e-9);
        // The scale the whole codebase reads this on: a fraction, not a percent. StyleBreakdown
        // multiplies the same field straight into a GBP value to get annual income, and the
        // open-orders plausibility guard is a 0.15 comparison — both are wrong by 100× if this
        // ever comes back percent-scaled.
        assertTrue(y.compareTo(BigDecimal.ONE) < 0, "a yield is a fraction: " + y);
        assertEquals(0.0378, y.doubleValue(), 1e-4);
    }

    @Test
    void theRateRespondsToAMovingQuoteWhichIsWhyItIsPreferred(@TempDir Path dir) {
        FundamentalsRepository repo = repo(dir);
        repo.save("A", quote("A", "100", dividends("5.00", null)), Instant.now());
        repo.save("B", quote("B", "50", dividends("5.00", null)), Instant.now());

        Map<String, BigDecimal> out = repo.loadForwardYields(Set.of("A", "B"));
        // Same declared dividend, half the price, double the yield — and so half the price drift.
        assertEquals(0.05, out.get("A").doubleValue(), 1e-9);
        assertEquals(0.10, out.get("B").doubleValue(), 1e-9);
    }

    @Test
    void trailingYieldIsTheFallbackWhenNoRateOrNoPriceExists(@TempDir Path dir) {
        FundamentalsRepository repo = repo(dir);
        repo.save("NORATE", quote("NORATE", "100", dividends(null, "0.041")), Instant.now());
        repo.save("NOPRICE", quote("NOPRICE", null, dividends("3.00", "0.029")), Instant.now());
        // A zero or negative price cannot be divided by, and must not fall through to an infinity.
        repo.save("ZERO", quote("ZERO", "0", dividends("3.00", "0.031")), Instant.now());

        Map<String, BigDecimal> out = repo.loadForwardYields(Set.of("NORATE", "NOPRICE", "ZERO"));
        assertEquals(0.041, out.get("NORATE").doubleValue(), 1e-9);
        assertEquals(0.029, out.get("NOPRICE").doubleValue(), 1e-9);
        assertEquals(0.031, out.get("ZERO").doubleValue(), 1e-9);
    }

    @Test
    void aSymbolWithNoDividendDataIsAbsentRatherThanZero(@TempDir Path dir) {
        FundamentalsRepository repo = repo(dir);
        // The accumulating-fund / never-fetched case. Absent is the only honest answer: the caller
        // has to tell "pays nothing" from "we don't know", and both would be 0.00 in the arithmetic.
        repo.save("EQQQ", quote("EQQQ", "512.30", Map.of()), Instant.now());

        Map<String, BigDecimal> out = repo.loadForwardYields(Set.of("EQQQ", "NEVERFETCHED"));
        assertFalse(out.containsKey("EQQQ"), "no dividend fields means no yield, not a zero yield");
        assertFalse(out.containsKey("NEVERFETCHED"));
        assertNull(out.get("EQQQ"));

        // A genuine zero, on the other hand, is a resolved figure and does come back.
        repo.save("NOPAYER", quote("NOPAYER", "50", dividends("0", null)), Instant.now());
        assertEquals(0, repo.loadForwardYields(Set.of("NOPAYER")).get("NOPAYER").signum());
    }

    @Test
    void itReadsOnlyTheSymbolsAskedForAndDegradesRatherThanThrowing(@TempDir Path dir) {
        FundamentalsRepository repo = repo(dir);
        repo.save("PEP", quote("PEP", "143.48", dividends("5.42", null)), Instant.now());
        repo.save("KO", quote("KO", "60.00", dividends("1.94", null)), Instant.now());

        assertEquals(Set.of("PEP"), repo.loadForwardYields(Set.of("PEP")).keySet());
        assertTrue(repo.loadForwardYields(Set.of()).isEmpty());
        assertTrue(repo.loadForwardYields(null).isEmpty());

        // No database at all is the cold-start case: empty, never an exception.
        assertTrue(new FundamentalsRepository(new JdbcConnectionFactory(
                dir.resolve("absent"))).loadForwardYields(Set.of("PEP")).isEmpty());
    }

    @Test
    void aLondonListingsPenceQuoteIsShiftedToPoundsBeforeDividing(@TempDir Path dir) {
        FundamentalsRepository repo = repo(dir);
        // The units differ and nothing says so: Yahoo prices a .L listing in GBp but quotes its
        // dividend rate in pounds. Dividing them raw made LGEN 0.076% against a real 7.71% — and a
        // plausibility bound cannot catch that, because it errs *small*. Real stored values:
        repo.save("LGEN", quote("LGEN", "288.2", dividends("0.22", "0.0771"), "GBp"), Instant.now());
        repo.save("LSEG", quote("LSEG", "8840.0", dividends("1.58", "0.0184"), "GBp"), Instant.now());
        // A US row must be untouched by the shift.
        repo.save("NVDA", quote("NVDA", "208.48", dividends("1.0", "0.0047"), "USD"), Instant.now());

        Map<String, BigDecimal> out = repo.loadForwardYields(Set.of("LGEN", "LSEG", "NVDA"));
        assertEquals(0.0763, out.get("LGEN").doubleValue(), 1e-4);
        assertEquals(0.0179, out.get("LSEG").doubleValue(), 1e-4);
        assertEquals(0.0048, out.get("NVDA").doubleValue(), 1e-4);

        // The derived figure should land near Yahoo's own trailing yield. That agreement is the
        // check that the units are right — it is what was absent when the pence bug was live.
        for (String sym : List.of("LGEN", "LSEG", "NVDA")) {
            BigDecimal derived = out.get(sym);
            assertTrue(derived.doubleValue() > 0.3 * yieldOf(sym) && derived.doubleValue() < 3 * yieldOf(sym),
                    sym + " derived " + derived + " is nowhere near its trailing yield " + yieldOf(sym));
        }
    }

    private static double yieldOf(String symbol) {
        return switch (symbol) {
            case "LGEN" -> 0.0771;
            case "LSEG" -> 0.0184;
            default -> 0.0047;
        };
    }
}
