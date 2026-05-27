package com.pension.application;

import com.pension.PortfolioDatabase;
import com.pension.domain.model.CashTransaction;
import com.pension.domain.model.Holding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Currency;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DividendServiceTest {

    @TempDir
    Path dbDir;

    private static CashTransaction rothTx(String date, String type, String symbol,
                                          double qty, double amount, double fx) {
        return new CashTransaction(date, "RothIRA", type, symbol, qty, amount,
                "USD", fx, amount / fx, null, null, null);
    }

    private static Holding holding(String symbol, double qty) {
        return Holding.builder(symbol, new BigDecimal(qty), Currency.getInstance("USD"), "TestAccount").build();
    }

    @Test
    void dividendsBySymbolReturnsAttributedAmounts() {
        PortfolioDatabase db = new PortfolioDatabase(dbDir);
        db.saveRothIraCashTransactions(List.of(
                rothTx("2025-06-01", "DIVIDEND", "AAPL", 0, 125, 1.25),   // £100 GBP
                rothTx("2025-01-01", "TRANSACTION", "AAPL", 10, -1000, 1.25)
        ), BigDecimal.ZERO);

        DividendService service = new DividendService(db);
        Map<String, BigDecimal> dividends = service.dividendsBySymbol(List.of(holding("AAPL", 10)));

        assertTrue(dividends.containsKey("AAPL"));
        assertTrue(dividends.get("AAPL").compareTo(BigDecimal.ZERO) > 0, "some dividend attributed");
    }

    @Test
    void reconciliationWarnsWhenSharesMismatch() {
        PortfolioDatabase db = new PortfolioDatabase(dbDir);
        // Cash history says we bought 10 shares, but holdings file shows 5 (incomplete history)
        db.saveRothIraCashTransactions(List.of(
                rothTx("2025-06-01", "DIVIDEND", "AAPL", 0, 125, 1.25),
                rothTx("2025-01-01", "TRANSACTION", "AAPL", 10, -1000, 1.25)
        ), BigDecimal.ZERO);

        DividendService service = new DividendService(db);

        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured));
        try {
            service.dividendsBySymbol(List.of(holding("AAPL", 5)));
        } finally {
            System.setErr(originalErr);
        }

        String warning = captured.toString();
        assertTrue(warning.contains("[dividends]"), "reconciliation warning printed to stderr");
        assertTrue(warning.contains("AAPL"), "symbol mentioned in warning");
    }

    @Test
    void noWarningWhenSharesMatch() {
        PortfolioDatabase db = new PortfolioDatabase(dbDir);
        db.saveRothIraCashTransactions(List.of(
                rothTx("2025-06-01", "DIVIDEND", "AAPL", 0, 125, 1.25),
                rothTx("2025-01-01", "TRANSACTION", "AAPL", 10, -1000, 1.25)
        ), BigDecimal.ZERO);

        DividendService service = new DividendService(db);

        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured));
        try {
            service.dividendsBySymbol(List.of(holding("AAPL", 10)));
        } finally {
            System.setErr(originalErr);
        }

        assertFalse(captured.toString().contains("[dividends]"), "no warning when share counts agree");
    }

    @Test
    void emptyMapWhenNoTransactions() {
        DividendService service = new DividendService(new PortfolioDatabase(dbDir));
        assertTrue(service.dividendsBySymbol(List.of(holding("AAPL", 10))).isEmpty());
    }
}
