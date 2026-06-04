package com.portfolio;

import com.portfolio.domain.model.CashTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PortfolioDatabaseDividendTest {

    @TempDir
    Path dbDir;

    private static CashTransaction roth(String date, String type, String symbol,
                                        double qty, double amount, double fx) {
        return new CashTransaction(date, "RothIRA", type, symbol, qty, amount,
                "USD", fx, amount / fx, null, null, null);
    }

    @Test
    void returnsTransactionAndDividendRowsOrderedOldestFirst() {
        PortfolioDatabase db = new PortfolioDatabase(dbDir);
        // Pass newest-first as the RothIRA parser would
        db.saveRothIraCashTransactions(List.of(
                roth("2026-01-03", "CHARGE", "AAPL", 0, -5, 1.25),      // excluded
                roth("2026-01-02", "DIVIDEND", "AAPL", 0, 50, 1.25),    // included
                roth("2026-01-01", "TRANSACTION", "AAPL", 10, -1000, 1.25) // included
        ), BigDecimal.ZERO);

        List<CashTransaction> rows = db.loadDividendTransactions();

        assertEquals(2, rows.size(), "CHARGE row excluded");
        assertEquals("TRANSACTION", rows.get(0).type(), "oldest row first");
        assertEquals("2026-01-01", rows.get(0).transactionDate());
        assertEquals("DIVIDEND", rows.get(1).type());
        assertEquals("2026-01-02", rows.get(1).transactionDate());
    }

    @Test
    void emptyWhenNoDatabaseYet() {
        assertTrue(new PortfolioDatabase(dbDir).loadDividendTransactions().isEmpty());
    }
}
