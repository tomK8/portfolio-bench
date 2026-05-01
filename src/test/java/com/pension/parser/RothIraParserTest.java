package com.pension.parser;

import com.pension.model.Holding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Currency;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RothIraParserTest {

    private RothIraParser parser;

    @BeforeEach
    void setUp() {
        parser = new RothIraParser();
    }

    // --- supports() ---

    @Test
    void supports_matchesHoldingsXlsx() {
        assertTrue(parser.supports(Paths.get("Holdings.xlsx")));
        assertTrue(parser.supports(Paths.get("Holdings_20260424.xlsx")));
    }

    @Test
    void supports_rejectsOtherFiles() {
        assertFalse(parser.supports(Paths.get("Statement.xlsx")));
        assertFalse(parser.supports(Paths.get("Holdings.csv")));
    }

    // --- normaliseSecurityId() ---

    @Test
    void normalise_googBecomesGoogGoogl() {
        assertEquals("GOOG/GOOGL", RothIraParser.normaliseSecurityId("GOOG"));
    }

    @Test
    void normalise_googlBecomesGoogGoogl() {
        assertEquals("GOOG/GOOGL", RothIraParser.normaliseSecurityId("GOOGL"));
    }

    @Test
    void normalise_lowercaseGoogIsHandled() {
        assertEquals("GOOG/GOOGL", RothIraParser.normaliseSecurityId("googl"));
    }

    @Test
    void normalise_usd999997BecomesCash() {
        assertEquals("CASH", RothIraParser.normaliseSecurityId("USD999997"));
    }

    @Test
    void normalise_bdpBecomesCash() {
        assertEquals("CASH", RothIraParser.normaliseSecurityId("BDP"));
    }

    @Test
    void normalise_otherTickersUnchanged() {
        assertEquals("AAPL", RothIraParser.normaliseSecurityId("AAPL"));
        assertEquals("MSFT", RothIraParser.normaliseSecurityId("msft"));
    }

    @Test
    void normalise_nullReturnsNull() {
        assertNull(RothIraParser.normaliseSecurityId(null));
    }

    // --- parse() with real file ---

    @Test
    void parse_realFile_returnsExpectedHoldings() throws Exception {
        Path file = Paths.get("src/test/resources/Holdings.xlsx");
        if (!file.toFile().exists()) {
            System.out.println("Skipping integration test — test file not present at: " + file);
            return;
        }

        List<Holding> holdings = parser.parse(file);

        assertFalse(holdings.isEmpty(), "Expected at least one holding");

        // All holdings should be tagged as Roth IRA in USD
        holdings.forEach(h ->
                assertEquals("Roth IRA", h.getSource(), "Unexpected source for: " + h.getSecurityId()));
        holdings.forEach(h ->
                assertEquals(Currency.getInstance("USD"), h.getCurrency(), "Unexpected currency for: " + h.getSecurityId()));

        // GOOGL should be normalised
        holdings.stream()
                .filter(h -> h.getSecurityId().equals("GOOG/GOOGL"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a GOOG/GOOGL holding"));

        // BDP and USD999997 must be merged into exactly one CASH entry
        long cashCount = holdings.stream()
                .filter(h -> h.getSecurityId().equals("CASH"))
                .count();
        assertEquals(1, cashCount, "BDP and USD999997 should be merged into a single CASH entry");

        Holding cash = holdings.stream()
                .filter(h -> h.getSecurityId().equals("CASH"))
                .findFirst().orElseThrow();
        assertTrue(cash.getQuantity().compareTo(BigDecimal.ZERO) > 0, "CASH quantity should be positive");

        // No raw cash tickers should remain
        holdings.forEach(h -> {
            assertNotEquals("BDP",       h.getSecurityId());
            assertNotEquals("USD999997", h.getSecurityId());
        });

        // Non-cash holdings should have a positive quantity and a computable avgPricePaid
        holdings.stream()
                .filter(h -> !h.getSecurityId().equals("CASH"))
                .forEach(h -> {
                    assertTrue(h.getQuantity().compareTo(BigDecimal.ZERO) > 0,
                            "Quantity should be positive for: " + h.getSecurityId());
                    assertNotNull(h.getAvgPricePaid(),
                            "avgPricePaid should not be null for: " + h.getSecurityId());
                    assertTrue(h.getAvgPricePaid().compareTo(BigDecimal.ZERO) > 0,
                            "avgPricePaid should be positive for: " + h.getSecurityId());
                });
    }

    @Test
    void parse_realFile_printHoldings() throws Exception {
        Path file = Paths.get("src/test/resources/Holdings.xlsx");
        if (!file.toFile().exists()) {
            System.out.println("Skipping integration test — test file not present at: " + file);
            return;
        }

        List<Holding> holdings = parser.parse(file);
        for (Holding h : holdings) {
            System.out.println(h);
        }
    }
}
