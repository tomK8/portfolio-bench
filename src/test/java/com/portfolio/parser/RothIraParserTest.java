package com.portfolio.parser;

import com.portfolio.domain.model.Holding;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
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
    void normalise_keepsAlphabetClassesSeparate() {
        assertEquals("GOOG", RothIraParser.normaliseSecurityId("GOOG"));
        assertEquals("GOOGL", RothIraParser.normaliseSecurityId("GOOGL"));
        assertEquals("GOOGL", RothIraParser.normaliseSecurityId("googl"));
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

    // --- isCashRow() ---

    @Test
    void isCashRow_readsTheBrokersOwnClassification() {
        // WAGI is a government money fund the sweep started paying into on 2026-10-30. It is cash
        // because the broker classifies it as cash, not because its ticker is listed here.
        assertTrue(RothIraParser.isCashRow("WAGI", "Cash, Money Funds and Bank Deposits"));
        assertTrue(RothIraParser.isCashRow("BDP", "Cash, Money Funds and Bank Deposits"));
        assertTrue(RothIraParser.isCashRow("USD999997", "Cash, Money Funds and Bank Deposits"));
    }

    @Test
    void isCashRow_leavesEquitiesAlone() {
        assertFalse(RothIraParser.isCashRow("AAPL", "Equities"));
        assertFalse(RothIraParser.isCashRow("NVDA", "Fixed Income"));
        assertFalse(RothIraParser.isCashRow("MSFT", null));
        assertFalse(RothIraParser.isCashRow("MSFT", ""));
    }

    @Test
    void isCashRow_fallsBackToTheKnownTickersWhenNoClassificationIsStated() {
        // Older exports carry no Asset Classification column, and History.xlsx never does.
        assertTrue(RothIraParser.isCashRow("BDP", null));
        assertTrue(RothIraParser.isCashRow("USD999997", null));
        // The fallback is a ticker list, so it cannot know about the next sweep vehicle.
        assertFalse(RothIraParser.isCashRow("WAGI", null));
    }

    // --- parse() drops rows for positions that have been sold out ---

    /**
     * Writes a minimal Holdings.xlsx in the layout the parser expects: eleven rows of metadata
     * preamble, column headers on row 11, data from row 12. Built here rather than by extending the
     * shared fixture, which other assertions in this class depend on the exact shape of.
     */
    private Path writeHoldings(Path dir, Object[][] rows) throws Exception {
        return writeHoldings(dir, rows, false);
    }

    /**
     * {@code withClassification} appends the broker's Asset Classification as a fifth column, read
     * from a fifth element on each row. Left off, the file states no classification at all — the
     * shape of an older export, and what makes the ticker fallback the only test available.
     */
    private Path writeHoldings(Path dir, Object[][] rows, boolean withClassification) throws Exception {
        Path file = dir.resolve("Holdings.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Holdings");
            for (int r = 0; r < 11; r++) sheet.createRow(r).createCell(0).setCellValue("preamble");
            Row header = sheet.createRow(11);
            header.createCell(0).setCellValue("Security ID");
            header.createCell(1).setCellValue("Quantity");
            header.createCell(2).setCellValue("Market Value");
            header.createCell(3).setCellValue("Gain/Loss $");
            if (withClassification) header.createCell(4).setCellValue("Asset Classification");
            for (int i = 0; i < rows.length; i++) {
                Row row = sheet.createRow(12 + i);
                row.createCell(0).setCellValue((String) rows[i][0]);
                for (int c = 1; c <= 3; c++) {
                    row.createCell(c).setCellValue(((Number) rows[i][c]).doubleValue());
                }
                if (withClassification) row.createCell(4).setCellValue((String) rows[i][4]);
            }
            try (var out = Files.newOutputStream(file)) {
                wb.write(out);
            }
        }
        return file;
    }

    @Test
    void parse_dropsSoldOutPositionsThatStillExportWithZeroShares(@TempDir Path dir) throws Exception {
        // Citi kept listing MRNA and MRVL on 2026-08-21 after both were sold out: zero shares, zero
        // value, but a live price. Those rows sat between real positions reading 0.00 in every
        // column. They are rows about something no longer owned, not holdings.
        Path file = writeHoldings(dir, new Object[][]{
                {"META", 14, 7699.23, -1713.71},
                {"MRNA", 0, 0, 0},
                {"MRVL", 0, 0, 0},
                {"MSFT", 140, 67585.00, 2227.40},
        });

        List<Holding> holdings = parser.parse(file);

        assertEquals(List.of("META", "MSFT"),
                holdings.stream().map(Holding::getSecurityId).toList(),
                "sold-out positions are dropped, the rest are untouched and in order");
    }

    @Test
    void parse_keepsAZeroCashBalance(@TempDir Path dir) throws Exception {
        // Cash is exempt from the zero filter: an empty cash balance is a fact about the account,
        // not a stale row, and the view has a CASH line that must not vanish when it hits zero.
        Path file = writeHoldings(dir, new Object[][]{
                {"META", 14, 7699.23, -1713.71},
                {"BDP", 0, 0, 0},
        });

        List<Holding> holdings = parser.parse(file);

        Holding cash = holdings.stream()
                .filter(h -> h.getSecurityId().equals("CASH"))
                .findFirst().orElseThrow(() -> new AssertionError("CASH entry should survive at zero"));
        assertEquals(0, cash.getQuantity().compareTo(BigDecimal.ZERO));
    }

    @Test
    void parse_keepsShortPositions(@TempDir Path dir) throws Exception {
        // Only exactly-zero is a sold-out marker. A negative quantity is a real position.
        Path file = writeHoldings(dir, new Object[][]{{"META", -14, -7699.23, 120.00}});

        List<Holding> holdings = parser.parse(file);

        assertEquals(1, holdings.size());
        assertEquals(0, holdings.get(0).getQuantity().compareTo(new BigDecimal("-14")));
    }

    // --- parse() folds the sweep vehicle into CASH ---

    @Test
    void parse_mergesTheMoneyFundIntoCash(@TempDir Path dir) throws Exception {
        // The Roth's sweep moved out of a bank deposit (BDP) and into a government money fund
        // (WAGI) on 2026-10-30. Both are spendable balances the broker totals as the account's
        // cash, so neither is a position: one CASH row carries them and the dollar cash beside it.
        Path file = writeHoldings(dir, new Object[][]{
                {"AAPL", 23, 7926.49, 1992.49, "Equities"},
                {"USD999997", 0, 0, 0, "Cash, Money Funds and Bank Deposits"},
                {"WAGI", 31721.03, 31721.03, 0, "Cash, Money Funds and Bank Deposits"},
        }, true);

        List<Holding> holdings = parser.parse(file);

        assertEquals(List.of("AAPL", "CASH"),
                holdings.stream().map(Holding::getSecurityId).toList(),
                "the money fund is not a position of its own");

        Holding cash = holdings.stream()
                .filter(h -> h.getSecurityId().equals("CASH"))
                .findFirst().orElseThrow();
        assertEquals(0, cash.getCurrentMarketValue().compareTo(new BigDecimal("31721.03")),
                "CASH is the money fund plus the dollar balance, matching the export's own "
                        + "'Cash, Money Funds and Bank Deposits' header total");
        assertEquals(0, cash.getQuantity().compareTo(new BigDecimal("31721.03")));
    }

    @Test
    void parse_doesNotAbsorbAnEquityThatTheBrokerClassifiesAsOne(@TempDir Path dir) throws Exception {
        // The classification is the whole test, so it has to hold both ways round.
        Path file = writeHoldings(dir, new Object[][]{
                {"MSFT", 35, 18266.85, 101.85, "Equities"},
                {"WAGI", 100, 100, 0, "Cash, Money Funds and Bank Deposits"},
        }, true);

        List<Holding> holdings = parser.parse(file);

        assertEquals(List.of("MSFT", "CASH"),
                holdings.stream().map(Holding::getSecurityId).toList());
        assertEquals(0, holdings.get(0).getCurrentMarketValue().compareTo(new BigDecimal("18266.85")));
    }

    @Test
    void parse_keepsAZeroMoneyFundBalance(@TempDir Path dir) throws Exception {
        // The zero-quantity drop is about sold-out positions. A cash row the broker classifies as
        // cash is exempt whatever its ticker, exactly as BDP and USD999997 are.
        Path file = writeHoldings(dir, new Object[][]{
                {"META", 14, 7699.23, -1713.71, "Equities"},
                {"WAGI", 0, 0, 0, "Cash, Money Funds and Bank Deposits"},
        }, true);

        List<Holding> holdings = parser.parse(file);

        Holding cash = holdings.stream()
                .filter(h -> h.getSecurityId().equals("CASH"))
                .findFirst().orElseThrow(() -> new AssertionError("CASH entry should survive at zero"));
        assertEquals(0, cash.getQuantity().compareTo(BigDecimal.ZERO));
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

        // Alphabet share class kept as-is (no GOOG/GOOGL merge)
        holdings.stream()
                .filter(h -> h.getSecurityId().equals("GOOGL"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a GOOGL holding"));

        // Every cash-classified row must be merged into exactly one CASH entry
        long cashCount = holdings.stream()
                .filter(h -> h.getSecurityId().equals("CASH"))
                .count();
        assertEquals(1, cashCount,
                "BDP, USD999997 and the WAGI money fund should be merged into a single CASH entry");

        Holding cash = holdings.stream()
                .filter(h -> h.getSecurityId().equals("CASH"))
                .findFirst().orElseThrow();
        assertTrue(cash.getQuantity().compareTo(BigDecimal.ZERO) > 0, "CASH quantity should be positive");

        // No raw cash tickers should remain
        holdings.forEach(h -> {
            assertNotEquals("BDP", h.getSecurityId());
            assertNotEquals("USD999997", h.getSecurityId());
            assertNotEquals("WAGI", h.getSecurityId());
        });

        // Non-cash holdings should have positive quantity, avgPricePaid, and currentMarketValue
        holdings.stream()
                .filter(h -> !h.getSecurityId().equals("CASH"))
                .forEach(h -> {
                    assertTrue(h.getQuantity().compareTo(BigDecimal.ZERO) > 0,
                            "Quantity should be positive for: " + h.getSecurityId());
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
