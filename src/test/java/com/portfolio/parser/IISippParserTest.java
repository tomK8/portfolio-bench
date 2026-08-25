package com.portfolio.parser;

import com.portfolio.domain.model.Holding;
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
    void normalise_keepsAlphabetClassesSeparate() {
        assertEquals("GOOGL", IISippParser.normaliseSecurityId("GOOGL"));
        assertEquals("GOOG", IISippParser.normaliseSecurityId("GOOG"));
    }

    @Test
    void normalise_otherTickersUnchanged() {
        assertEquals("MSFT", IISippParser.normaliseSecurityId("MSFT"));
        assertEquals("BRK.B", IISippParser.normaliseSecurityId("BRK.B"));
    }

    // --- securityIdFor() ---

    @Test
    void giltTakesTheCanonicalIdFromItsName() {
        // II's own code for the 4.375% 2030 is "T30", which no price feed knows and which
        // Instruments.isBond cannot recognise — so the position priced per share instead of per
        // £100 nominal and the cash-ledger view dropped it. The name carries what's needed.
        assertEquals("GILT 4.375% 2030",
                IISippParser.securityIdFor("T30", "UNITED KINGDOM 4.375 07/03/2030"));
    }

    @Test
    void equityKeepsItsTicker() {
        assertEquals("GOOGL", IISippParser.securityIdFor("GOOGL", "Alphabet Inc Class A"));
        assertEquals("SEMI", IISippParser.securityIdFor("SEMI", ""));
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

        // Alphabet share class kept as-is (no GOOG/GOOGL merge)
        holdings.stream()
                .filter(h -> h.getSecurityId().equals("GOOGL"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a GOOGL holding"));

        // The gilt row is filed under the id the gilt price feed writes, not under II's "T30".
        Holding gilt = holdings.stream()
                .filter(h -> h.getSecurityId().equals("GILT 4.375% 2030"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected the gilt under its canonical id"));
        assertEquals(new BigDecimal("20000"), gilt.getQuantity(), "quantity is £ nominal");
        assertEquals(new BigDecimal("19902.00"), gilt.getCurrentMarketValue());

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
