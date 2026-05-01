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

class IISippParserTest {

    private IISippParser parser;

    @BeforeEach
    void setUp() {
        parser = new IISippParser();
    }

    // --- supports() ---

    @Test
    void supports_matchesUuidCsv() {
        assertTrue(parser.supports(Paths.get("1f072c5f-a547-4589-9003-4ee13cba7ddd.csv")));
        assertTrue(parser.supports(Paths.get("c1612c3d-1305-4857-af07-53de3c0b9eae.csv")));
    }

    @Test
    void supports_rejectsOtherFiles() {
        assertFalse(parser.supports(Paths.get("Holdings.xlsx")));
        assertFalse(parser.supports(Paths.get("portfolio-XXXXXXX-SIPP.csv")));
        assertFalse(parser.supports(Paths.get("not-a-uuid.csv")));
    }

    // --- normaliseSecurityId() ---

    @Test
    void normalise_googlBecomesGoogGoogl() {
        assertEquals("GOOG/GOOGL", IISippParser.normaliseSecurityId("GOOGL"));
        assertEquals("GOOG/GOOGL", IISippParser.normaliseSecurityId("GOOG"));
    }

    @Test
    void normalise_otherTickersUnchanged() {
        assertEquals("MSFT", IISippParser.normaliseSecurityId("MSFT"));
        assertEquals("BRK.B", IISippParser.normaliseSecurityId("BRK.B"));
    }

    // --- parse() with real file ---

    @Test
    void parse_realFile_returnsExpectedHoldings() throws Exception {
        Path file = findTestFile();
        if (file == null) return;

        List<Holding> holdings = parser.parse(file);

        assertFalse(holdings.isEmpty(), "Expected at least one holding");

        // All holdings tagged as II SIPP
        holdings.forEach(h ->
                assertEquals("II SIPP", h.getSource(), "Unexpected source for: " + h.getSecurityId()));

        // Mixed currencies
        assertTrue(holdings.stream().anyMatch(h -> h.getCurrency().equals(Currency.getInstance("USD"))));
        assertTrue(holdings.stream().anyMatch(h -> h.getCurrency().equals(Currency.getInstance("GBP"))));

        // GOOGL should be normalised
        holdings.stream()
                .filter(h -> h.getSecurityId().equals("GOOG/GOOGL"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a GOOG/GOOGL holding"));

        // All holdings have positive quantity, avgPricePaid, and currentMarketValue
        holdings.forEach(h -> {
            assertTrue(h.getQuantity().compareTo(BigDecimal.ZERO) > 0,
                    "Quantity should be positive for: " + h.getSecurityId());
            assertNotNull(h.getAvgPricePaid(),
                    "avgPricePaid should not be null for: " + h.getSecurityId());
            assertTrue(h.getAvgPricePaid().compareTo(BigDecimal.ZERO) > 0,
                    "avgPricePaid should be positive for: " + h.getSecurityId());
            assertNotNull(h.getCurrentMarketValue(),
                    "currentMarketValue should not be null for: " + h.getSecurityId());
        });
    }

    @Test
    void parse_realFile_printHoldings() throws Exception {
        Path file = findTestFile();
        if (file == null) return;

        List<Holding> holdings = parser.parse(file);
        for (Holding h : holdings) {
            System.out.println(h);
        }
    }

    // -------------------------------------------------------------------------

    private Path findTestFile() {
        try (var stream = java.nio.file.Files.list(Paths.get("src/test/resources"))) {
            return stream
                    .filter(p -> parser.supports(p))
                    .findFirst()
                    .orElseGet(() -> {
                        System.out.println("Skipping integration test — no UUID CSV found in src/test/resources");
                        return null;
                    });
        } catch (Exception e) {
            System.out.println("Skipping integration test — " + e.getMessage());
            return null;
        }
    }
}
