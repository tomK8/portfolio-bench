package com.pension.parser;

import com.pension.domain.model.CashTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AJBellCashStatementParserTest {

    private static final Path REAL_FILE =
            Path.of(System.getProperty("user.home"), "Downloads", "cashstatements.csv");
    private AJBellCashStatementParser parser;

    // --- supports() ---

    @BeforeEach
    void setUp() {
        parser = new AJBellCashStatementParser();
    }

    @Test
    void supports_exactFilename() {
        assertTrue(parser.supports(Paths.get("cashstatements.csv")));
    }

    // --- parse() against the real file ---

    @Test
    void supports_rejectsOtherFilenames() {
        assertFalse(parser.supports(Paths.get("cashstatements (1).csv")));
        assertFalse(parser.supports(Paths.get("cashstatements_combined.csv")));
        assertFalse(parser.supports(Paths.get("portfolio.csv")));
    }

    @Test
    void parse_realFile_rowCount() throws Exception {
        if (!REAL_FILE.toFile().exists()) return; // skip on CI without file
        List<CashTransaction> txns = parser.parse(REAL_FILE);
        assertTrue(txns.size() > 0, "Expected at least one transaction");
    }

    @Test
    void parse_realFile_allAccountsAJBell() throws Exception {
        if (!REAL_FILE.toFile().exists()) return;
        parser.parse(REAL_FILE).forEach(t -> assertEquals("AJBell", t.account()));
    }

    @Test
    void parse_realFile_allCurrencyGBP() throws Exception {
        if (!REAL_FILE.toFile().exists()) return;
        parser.parse(REAL_FILE).forEach(t -> assertEquals("GBP", t.currency()));
    }

    @Test
    void parse_realFile_amountMatchesAmountGbp() throws Exception {
        if (!REAL_FILE.toFile().exists()) return;
        parser.parse(REAL_FILE).forEach(t ->
                assertEquals(t.amount(), t.amountGbp(), 0.001));
    }

    @Test
    void parse_realFile_knownDividendRow() throws Exception {
        if (!REAL_FILE.toFile().exists()) return;
        List<CashTransaction> txns = parser.parse(REAL_FILE);
        CashTransaction bnp = txns.stream()
                .filter(t -> "BNP".equals(t.symbol()) && "DIVIDEND".equals(t.type()))
                .findFirst().orElseThrow();
        assertTrue(bnp.quantity() > 0, "Dividend quantity should be positive");
        assertTrue(bnp.amount() > 0, "Dividend should be positive cash in");
    }

    @Test
    void parse_realFile_knownGiltDividend() throws Exception {
        if (!REAL_FILE.toFile().exists()) return;
        List<CashTransaction> txns = parser.parse(REAL_FILE);
        assertTrue(txns.stream().anyMatch(
                        t -> "GILT 0.875% 2033".equals(t.symbol()) && "DIVIDEND".equals(t.type())),
                "Expected GILT 0.875% 2033 dividend row");
    }

    @Test
    void parse_realFile_knownPurchaseRow() throws Exception {
        if (!REAL_FILE.toFile().exists()) return;
        List<CashTransaction> txns = parser.parse(REAL_FILE);
        CashTransaction buy = txns.stream()
                .filter(t -> "REL".equals(t.symbol()) && "TRANSACTION".equals(t.type()))
                .findFirst().orElseThrow();
        assertEquals(100.0, buy.quantity(), 0.01);
        assertTrue(buy.amount() < 0, "Purchase should be negative cash out");
    }

    @Test
    void parse_realFile_knownGiltSale() throws Exception {
        if (!REAL_FILE.toFile().exists()) return;
        List<CashTransaction> txns = parser.parse(REAL_FILE);
        assertTrue(txns.stream().anyMatch(
                        t -> "GILT 0.875% 2033".equals(t.symbol()) && "TRANSACTION".equals(t.type()) && t.amount() > 0),
                "Expected at least one positive GILT 0.875% 2033 sale transaction");
    }

    @Test
    void parse_realFile_chargesAreNegative() throws Exception {
        if (!REAL_FILE.toFile().exists()) return;
        parser.parse(REAL_FILE).stream()
                .filter(t -> "CHARGE".equals(t.type()))
                .forEach(t -> assertTrue(t.amount() < 0, "Charges should be negative: " + t.description()));
    }

    @Test
    void parse_realFile_contributionsSymbolIsGBP() throws Exception {
        if (!REAL_FILE.toFile().exists()) return;
        parser.parse(REAL_FILE).stream()
                .filter(t -> "CONTRIBUTION".equals(t.type()))
                .forEach(t -> assertEquals("GBP", t.symbol()));
    }

    @Test
    void parse_realFile_datesAreIsoFormat() throws Exception {
        if (!REAL_FILE.toFile().exists()) return;
        parser.parse(REAL_FILE).forEach(t ->
                assertTrue(t.transactionDate().matches("\\d{4}-\\d{2}-\\d{2}"),
                        "Date not ISO: " + t.transactionDate()));
    }
}
