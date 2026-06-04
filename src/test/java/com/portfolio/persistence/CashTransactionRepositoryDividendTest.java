package com.portfolio.persistence;

import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CashTransactionRepositoryDividendTest {

    @TempDir
    Path dbDir;

    private static CashTransaction roth(String date, String type, String symbol,
                                        double qty, double amount, double fx) {
        return new CashTransaction(date, Account.ROTH_IRA, TransactionType.valueOf(type),
                symbol, qty, amount, "USD", fx, amount / fx, null, null, null);
    }

    private CashTransactionRepository newRepo() {
        JdbcConnectionFactory cf = new JdbcConnectionFactory(dbDir);
        return new CashTransactionRepository(cf, new KeyValueStore(dbDir));
    }

    @Test
    void returnsTransactionAndDividendRowsOrderedOldestFirst() {
        CashTransactionRepository repo = newRepo();
        // Pass newest-first as the RothIRA parser would
        repo.saveRothIra(List.of(
                roth("2026-01-03", "CHARGE", "AAPL", 0, -5, 1.25),      // excluded
                roth("2026-01-02", "DIVIDEND", "AAPL", 0, 50, 1.25),    // included
                roth("2026-01-01", "TRANSACTION", "AAPL", 10, -1000, 1.25) // included
        ), BigDecimal.ZERO);

        List<CashTransaction> rows = repo.loadDividendTransactions();

        assertEquals(2, rows.size(), "CHARGE row excluded");
        assertEquals(TransactionType.TRANSACTION, rows.get(0).type(), "oldest row first");
        assertEquals("2026-01-01", rows.get(0).transactionDate());
        assertEquals(TransactionType.DIVIDEND, rows.get(1).type());
        assertEquals("2026-01-02", rows.get(1).transactionDate());
    }

    @Test
    void emptyWhenNoDatabaseYet() {
        assertTrue(newRepo().loadDividendTransactions().isEmpty());
    }
}
