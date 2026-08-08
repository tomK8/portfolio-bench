package com.portfolio.persistence;

import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the read-side query methods of {@link CashTransactionRepository}: loadAllTransactions,
 * loadTrades, loadContributions, latestCashBalances, earliestTransactionDate.
 */
class CashTransactionRepositoryReadTest {

    @TempDir
    Path dbDir;

    private CashTransactionRepository repo() {
        return new CashTransactionRepository(new JdbcConnectionFactory(dbDir), new KeyValueStore(dbDir));
    }

    private static CashTransaction row(String date, TransactionType type, double amount, double balance) {
        return new CashTransaction(date, Account.AJBELL, type, "AAPL",
                1.0, amount, "GBP", 1.0, amount, balance, balance, "desc");
    }

    // ---- loadAllTransactions -----------------------------------------------

    @Test
    void loadAllTransactionsReturnsRowsInDateOrder() {
        var r = repo();
        r.saveAjBell(List.of(
                row("2023-03-01", TransactionType.TRANSACTION, -100.0, 900.0),
                row("2023-01-01", TransactionType.DIVIDEND, 10.0, 1000.0)));

        List<CashTransaction> all = r.loadAllTransactions();
        assertEquals(2, all.size());
        assertEquals("2023-01-01", all.get(0).transactionDate()); // oldest first
    }

    @Test
    void loadAllTransactionsIncludesAllTypes() {
        var r = repo();
        r.saveAjBell(List.of(
                row("2023-01-01", TransactionType.TRANSACTION, -100.0, 900.0),
                row("2023-02-01", TransactionType.DIVIDEND, 10.0, 910.0),
                row("2023-03-01", TransactionType.CONTRIBUTION, 500.0, 1410.0)));

        assertEquals(3, r.loadAllTransactions().size());
    }

    @Test
    void loadAllTransactionsEmptyWhenNoDb() {
        assertTrue(repo().loadAllTransactions().isEmpty());
    }

    // ---- loadTrades --------------------------------------------------------

    @Test
    void loadTradesReturnsOnlyTransactionType() {
        var r = repo();
        r.saveAjBell(List.of(
                row("2023-01-01", TransactionType.TRANSACTION, -100.0, 900.0),
                row("2023-02-01", TransactionType.DIVIDEND, 10.0, 910.0),
                row("2023-03-01", TransactionType.CONTRIBUTION, 500.0, 1410.0)));

        List<CashTransactionRepository.TradeRow> trades = r.loadTrades();
        assertEquals(1, trades.size());
        assertEquals("AAPL", trades.get(0).symbol());
    }

    @Test
    void loadTradesNewestFirst() {
        var r = repo();
        r.saveAjBell(List.of(
                row("2023-01-01", TransactionType.TRANSACTION, -100.0, 900.0),
                row("2023-06-01", TransactionType.TRANSACTION, 120.0, 1020.0)));

        var trades = r.loadTrades();
        assertEquals(2, trades.size());
        assertEquals("2023-06-01", trades.get(0).transactionDate()); // newest first
    }

    @Test
    void loadTradesEmptyWhenNoDb() {
        assertTrue(repo().loadTrades().isEmpty());
    }

    // ---- loadContributions -------------------------------------------------

    @Test
    void loadContributionsReturnsOnlyContributions() {
        var r = repo();
        r.saveAjBell(List.of(
                row("2023-01-01", TransactionType.TRANSACTION, -100.0, 900.0),
                row("2023-02-01", TransactionType.CONTRIBUTION, 500.0, 1400.0)));

        List<CashTransaction> contribs = r.loadContributions();
        assertEquals(1, contribs.size());
        assertEquals(TransactionType.CONTRIBUTION, contribs.get(0).type());
    }

    @Test
    void loadContributionsOldestFirst() {
        var r = repo();
        r.saveAjBell(List.of(
                row("2023-06-01", TransactionType.CONTRIBUTION, 200.0, 1200.0),
                row("2023-01-01", TransactionType.CONTRIBUTION, 500.0, 1500.0)));

        var contribs = r.loadContributions();
        assertEquals("2023-01-01", contribs.get(0).transactionDate());
    }

    // ---- earliestTransactionDate -------------------------------------------

    @Test
    void earliestTransactionDateReturnsSmallestDate() {
        var r = repo();
        r.saveAjBell(List.of(
                row("2023-06-01", TransactionType.TRANSACTION, -100.0, 900.0),
                row("2022-01-15", TransactionType.DIVIDEND, 10.0, 1010.0)));

        assertEquals("2022-01-15", r.earliestTransactionDate(Account.AJBELL));
    }

    @Test
    void earliestTransactionDateNullWhenAccountEmpty() {
        var r = repo();
        r.saveAjBell(List.of(row("2023-01-01", TransactionType.TRANSACTION, -100.0, 900.0)));
        assertNull(r.earliestTransactionDate(Account.ROTH_IRA));
    }

    @Test
    void earliestTransactionDateNullWhenNoDb() {
        assertNull(repo().earliestTransactionDate(Account.AJBELL));
    }

    // ---- latestCashBalances ------------------------------------------------

    @Test
    void latestCashBalancesReturnsLastBalancePerAccountCurrency() {
        var r = repo();
        // Two AJBell GBP rows; last row wins.
        r.saveAjBell(List.of(
                row("2023-01-01", TransactionType.CONTRIBUTION, 1000.0, 1000.0),
                row("2023-06-01", TransactionType.TRANSACTION, -200.0, 800.0)));

        List<CashTransactionRepository.CashBalance> balances = r.latestCashBalances();
        assertEquals(1, balances.size());
        assertEquals(800.0, balances.get(0).cashGbp(), 0.01);
        assertEquals("AJBell", balances.get(0).accountDbValue());
    }

    @Test
    void latestCashBalancesEmptyWhenNoDb() {
        assertTrue(repo().latestCashBalances().isEmpty());
    }
}
