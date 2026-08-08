package com.portfolio.parser;

import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AJBellCashStatementParserTest {

    /**
     * Committed <b>synthetic</b> AJ Bell cash-statement fixture (repo policy: no real brokerage
     * exports in {@code src/test/resources}). Kept under a subdirectory so its filename is still
     * {@code cashstatements.csv} — the name {@link AJBellCashStatementParser#supports} matches on.
     * It reproduces the export format and carries one row of each classification the assertions
     * below reference (BNP dividend, REL purchase of 100, GILT 0.875% 2033 sale, a charge, a
     * pension contribution, interest) with a consistent balance chain.
     */
    private static final Path FIXTURE =
            Path.of("src/test/resources/ajbell/cashstatements.csv");
    private AJBellCashStatementParser parser;

    // --- supports() ---

    @BeforeEach
    void setUp() {
        parser = new AJBellCashStatementParser();
    }

    @Test
    void supports_exactFilename() {
        assertTrue(parser.supports(Paths.get("cashstatements.csv")));
        assertTrue(parser.supports(FIXTURE));   // the fixture keeps the exact filename
    }

    @Test
    void supports_rejectsOtherFilenames() {
        assertFalse(parser.supports(Paths.get("cashstatements (1).csv")));
        assertFalse(parser.supports(Paths.get("cashstatements_combined.csv")));
        assertFalse(parser.supports(Paths.get("portfolio.csv")));
    }

    // --- parse() against the committed synthetic fixture ---

    @Test
    void parse_fixture_rowCount() throws Exception {
        List<CashTransaction> txns = parser.parse(FIXTURE);
        assertTrue(txns.size() > 0, "Expected at least one transaction");
    }

    @Test
    void parse_fixture_allAccountsAJBell() throws Exception {
        parser.parse(FIXTURE).forEach(t -> assertEquals(Account.AJBELL, t.account()));
    }

    @Test
    void parse_fixture_allCurrencyGBP() throws Exception {
        parser.parse(FIXTURE).forEach(t -> assertEquals("GBP", t.currency()));
    }

    @Test
    void parse_fixture_amountMatchesAmountGbp() throws Exception {
        parser.parse(FIXTURE).forEach(t ->
                assertEquals(t.amount(), t.amountGbp(), 0.001));
    }

    @Test
    void parse_fixture_knownDividendRow() throws Exception {
        List<CashTransaction> txns = parser.parse(FIXTURE);
        CashTransaction bnp = txns.stream()
                .filter(t -> "BNP".equals(t.symbol()) && t.type() == TransactionType.DIVIDEND)
                .findFirst().orElseThrow();
        assertTrue(bnp.quantity() > 0, "Dividend quantity should be positive");
        assertTrue(bnp.amount() > 0, "Dividend should be positive cash in");
    }

    @Test
    void parse_fixture_knownPurchaseRow() throws Exception {
        List<CashTransaction> txns = parser.parse(FIXTURE);
        CashTransaction buy = txns.stream()
                .filter(t -> "REL".equals(t.symbol()) && t.type() == TransactionType.TRANSACTION)
                .findFirst().orElseThrow();
        assertEquals(100.0, buy.quantity(), 0.01);
        assertTrue(buy.amount() < 0, "Purchase should be negative cash out");
    }

    @Test
    void parse_fixture_knownGiltSale() throws Exception {
        List<CashTransaction> txns = parser.parse(FIXTURE);
        assertTrue(txns.stream().anyMatch(
                        t -> "GILT 0.875% 2033".equals(t.symbol()) && t.type() == TransactionType.TRANSACTION && t.amount() > 0),
                "Expected at least one positive GILT 0.875% 2033 sale transaction");
    }

    @Test
    void parse_fixture_chargesAreNegative() throws Exception {
        parser.parse(FIXTURE).stream()
                .filter(t -> t.type() == TransactionType.CHARGE)
                .forEach(t -> assertTrue(t.amount() < 0, "Charges should be negative: " + t.description()));
    }

    @Test
    void parse_fixture_contributionsSymbolIsGBP() throws Exception {
        parser.parse(FIXTURE).stream()
                .filter(t -> t.type() == TransactionType.CONTRIBUTION)
                .forEach(t -> assertEquals("GBP", t.symbol()));
    }

    @Test
    void parse_fixture_datesAreIsoFormat() throws Exception {
        parser.parse(FIXTURE).forEach(t ->
                assertTrue(t.transactionDate().matches("\\d{4}-\\d{2}-\\d{2}"),
                        "Date not ISO: " + t.transactionDate()));
    }
}
