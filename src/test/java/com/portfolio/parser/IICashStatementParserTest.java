package com.portfolio.parser;

import com.portfolio.domain.model.CashTransaction;
import com.portfolio.port.HistoricalFxRateProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class IICashStatementParserTest {

    private static final String GBP_FIXTURE = "00000000-0000-0000-0000-000000000002.csv";
    private static final String USD_FIXTURE = "00000000-0000-0000-0000-000000000003.csv";

    private static final HistoricalFxRateProvider FX = (ccy, start, end) -> {
        Map<LocalDate, BigDecimal> s = new TreeMap<>();
        if ("USD".equals(ccy)) {
            s.put(LocalDate.parse("2025-10-10"), new BigDecimal("1.30"));
            s.put(LocalDate.parse("2025-12-11"), new BigDecimal("1.27"));
            s.put(LocalDate.parse("2025-12-15"), new BigDecimal("1.27"));
        }
        return s;
    };

    @TempDir
    Path tempDir;

    private static Path fixture(String name) {
        return Paths.get("src/test/resources").resolve(name).toAbsolutePath();
    }

    private static CashTransaction findRow(List<CashTransaction> rows, String date,
                                           String type, String symbol) {
        return rows.stream()
                .filter(t -> t.transactionDate().equals(date) && t.type().name().equals(type)
                        && t.symbol().equals(symbol))
                .findFirst().orElseThrow(() ->
                        new AssertionError("No " + type + " row for " + symbol + " on " + date));
    }

    @Test
    void supportsCashFileAndRejectsHoldingsFile() {
        IICashStatementParser parser = new IICashStatementParser(FX);
        assertTrue(parser.supports(fixture(GBP_FIXTURE)));
        assertTrue(parser.supports(fixture(USD_FIXTURE)));
        // The existing II holdings fixture is UUID-named but has "Market Value" / "Book Cost" headers.
        assertFalse(parser.supports(fixture("00000000-0000-0000-0000-000000000001.csv")));
    }

    @Test
    void splitsCrossCurrencyTradeIntoTxnPlusCharge() throws Exception {
        // GBP file: USD-listed AAPL bought from GBP cash.
        // qty 10 @ $190 = $1,900; at 1.30 USD/GBP that's £1,461.54; file debit £1,475 → markup £13.46.
        List<CashTransaction> rows = new IICashStatementParser(FX).parse(fixture(GBP_FIXTURE));

        CashTransaction txn = findRow(rows, "2025-10-10", "TRANSACTION", "AAPL");
        CashTransaction charge = findRow(rows, "2025-10-10", "CHARGE", "AAPL");

        assertEquals("GBP", txn.currency(), "TRANSACTION is in file currency");
        assertEquals(10, txn.quantity(), 1e-9);
        assertEquals(-1900.0 / 1.30, txn.amount(), 1e-6, "gross cost at historical FX");
        assertNull(txn.cashBalance(), "intermediate row carries no balance");

        assertEquals("GBP", charge.currency());
        assertEquals(0.0, charge.quantity(), 1e-9);
        assertEquals(-(1475 - 1900.0 / 1.30), charge.amount(), 1e-6, "markup = file debit − gross");
        assertEquals(525.0, charge.cashBalance(), 1e-6, "final row carries the file's Running Balance");

        // Pair reconciles to the file's net debit.
        assertEquals(-1475.0, txn.amount() + charge.amount(), 1e-6);
    }

    @Test
    void backsOutCommissionOnSameCurrencyTrade() throws Exception {
        // USD file: AAPL sold 5 @ $200 for credit $999. Same-currency → $1 commission.
        List<CashTransaction> rows = new IICashStatementParser(FX).parse(fixture(USD_FIXTURE));

        CashTransaction txn = findRow(rows, "2025-12-11", "TRANSACTION", "AAPL");
        CashTransaction charge = findRow(rows, "2025-12-11", "CHARGE", "AAPL");

        assertEquals("USD", txn.currency());
        assertEquals(1000.0, txn.amount(), 1e-9, "gross proceeds = price × qty in file currency");
        assertEquals(-1.0, charge.amount(), 1e-9, "commission = file credit − gross");
        assertEquals(1.27, txn.fxToGbp(), 1e-9, "historical USD/GBP on trade date");
    }

    @Test
    void classifiesNonTradeRows() throws Exception {
        IICashStatementParser parser = new IICashStatementParser(FX);

        CashTransaction trf = findRow(parser.parse(fixture(GBP_FIXTURE)),
                "2025-10-15", "CONTRIBUTION", "GBP");
        assertEquals(5000.0, trf.amount(), 1e-9);
        assertEquals(5525.0, trf.cashBalance(), 1e-9);

        CashTransaction fee = findRow(parser.parse(fixture(GBP_FIXTURE)),
                "2025-10-20", "CHARGE", "GBP");
        assertEquals(-5.99, fee.amount(), 1e-9);

        CashTransaction div = findRow(parser.parse(fixture(USD_FIXTURE)),
                "2025-12-15", "DIVIDEND", "AAPL");
        assertEquals(2.50, div.amount(), 1e-9);
        assertEquals(2.50 / 1.27, div.amountGbp(), 1e-6,
                "USD dividend converted to GBP via historical FX on dividend date");
    }

    @Test
    void abortsOnRunningBalanceMismatch() throws Exception {
        // Same as GBP fixture, but with the final balance tampered with by +£100.
        String tampered = Files.readString(fixture(GBP_FIXTURE))
                .replace("\"£5,519.01\"", "\"£5,619.01\"");
        Path file = tempDir.resolve("00000000-0000-0000-0000-000000000099.csv");
        Files.writeString(file, tampered);

        ParseException ex = assertThrows(ParseException.class,
                () -> new IICashStatementParser(FX).parse(file));
        assertTrue(ex.getMessage().contains("balance mismatch")
                        || ex.getMessage().contains("Running-balance mismatch"),
                "expected balance mismatch message, got: " + ex.getMessage());
    }
}
