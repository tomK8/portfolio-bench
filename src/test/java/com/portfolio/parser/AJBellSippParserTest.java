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

class AJBellSippParserTest {

    private AJBellSippParser parser;

    @BeforeEach
    void setUp() {
        parser = new AJBellSippParser();
    }

    // --- supports() ---

    @Test
    void supports_matchesPortfolioCsv() {
        assertTrue(parser.supports(Paths.get("portfolio.csv")));
        assertTrue(parser.supports(Paths.get("portfolio-XXXXXXX-SIPP (4).csv")));
    }

    @Test
    void supports_rejectsOtherFiles() {
        assertFalse(parser.supports(Paths.get("Holdings.xlsx")));
        assertFalse(parser.supports(Paths.get("statement.csv")));
    }

    // --- extractBondId() ---

    @Test
    void extractBondId_hmTreasuryGilt() {
        assertEquals("GILT 0.875% 2033",
                AJBellSippParser.extractBondId("HM TREASURY GILT 0.875% (31/07/33) (SEDOL:BM8Z2S2)"));
        assertEquals("GILT 3.25% 2044",
                AJBellSippParser.extractBondId("HM TREASURY GILT 3.25% (22/01/44) (SEDOL:B84Z9V0)"));
        assertEquals("GILT 4.75% 2038",
                AJBellSippParser.extractBondId("UK(GOVT OF) 4.75% SNR 07/12/38 GBP0.01 (SEDOL:B00NY17)"));
    }

    // --- normaliseSecurityId() ---

    @Test
    void normalise_keepsAlphabetClassesSeparate() {
        assertEquals("GOOG", AJBellSippParser.normaliseSecurityId("GOOG"));
        assertEquals("GOOGL", AJBellSippParser.normaliseSecurityId("GOOGL"));
        assertEquals("GOOGL", AJBellSippParser.normaliseSecurityId("googl"));
    }

    @Test
    void normalise_otherTickersUnchanged() {
        assertEquals("MSFT", AJBellSippParser.normaliseSecurityId("MSFT"));
        assertEquals("AV.", AJBellSippParser.normaliseSecurityId("AV."));
    }

    // --- parse() with real file ---

    @Test
    void parse_realFile_returnsExpectedHoldings() throws Exception {
        Path file = findTestFile();
        if (file == null) return;

        List<Holding> holdings = parser.parse(file);

        assertFalse(holdings.isEmpty(), "Expected at least one holding");

        // All holdings should be tagged AJ Bell SIPP
        holdings.forEach(h ->
                assertEquals("AJ Bell SIPP", h.getSource(), "Unexpected source for: " + h.getSecurityId()));

        // Mixed currencies present
        assertTrue(holdings.stream().anyMatch(h -> h.getCurrency().equals(Currency.getInstance("GBP"))));
        assertTrue(holdings.stream().anyMatch(h -> h.getCurrency().equals(Currency.getInstance("USD"))));
        assertTrue(holdings.stream().anyMatch(h -> h.getCurrency().equals(Currency.getInstance("EUR"))));

        // Alphabet class kept as-is (no GOOG/GOOGL merge)
        holdings.stream()
                .filter(h -> h.getSecurityId().equals("GOOG") || h.getSecurityId().equals("GOOGL"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a GOOG or GOOGL holding"));

        // Exactly one CASH entry
        long cashCount = holdings.stream().filter(h -> h.getSecurityId().equals("CASH")).count();
        assertEquals(1, cashCount, "Expected exactly one CASH entry");

        // Bonds use human-readable IDs derived from description, not SEDOL
        holdings.stream()
                .filter(h -> h.getSecurityId().equals("GILT 0.875% 2033"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected GILT 0.875% 2033 holding"));
        assertTrue(holdings.stream().noneMatch(h -> h.getSecurityId().equals("BM8Z2S2")),
                "Raw SEDOL ticker should not appear");

        // Comma-formatted large quantity parsed correctly (HM TREASURY GILT has 299,802 shares)
        Holding gilt = holdings.stream()
                .filter(h -> h.getSecurityId().equals("GILT 0.875% 2033"))
                .findFirst().orElseThrow();
        assertEquals(new BigDecimal("299802"), gilt.getQuantity());

        // All equity holdings have a positive avgPricePaid and currentMarketValue
        holdings.stream()
                .filter(h -> !h.getSecurityId().equals("CASH"))
                .forEach(h -> {
                    assertNotNull(h.getAvgPricePaid(),
                            "avgPricePaid should not be null for: " + h.getSecurityId());
                    assertTrue(h.getAvgPricePaid().compareTo(BigDecimal.ZERO) > 0,
                            "avgPricePaid should be positive for: " + h.getSecurityId());
                    assertNotNull(h.getCurrentMarketValue(),
                            "currentMarketValue should not be null for: " + h.getSecurityId());
                    assertTrue(h.getCurrentMarketValue().compareTo(BigDecimal.ZERO) > 0,
                            "currentMarketValue should be positive for: " + h.getSecurityId());
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
                    .filter(p -> p.getFileName().toString().startsWith("portfolio")
                            && p.getFileName().toString().endsWith(".csv"))
                    .findFirst()
                    .orElseGet(() -> {
                        System.out.println("Skipping integration test — no portfolio*.csv found in src/test/resources");
                        return null;
                    });
        } catch (Exception e) {
            System.out.println("Skipping integration test — " + e.getMessage());
            return null;
        }
    }
}
