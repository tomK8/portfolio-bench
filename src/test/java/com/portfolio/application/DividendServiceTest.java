package com.portfolio.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.portfolio.domain.model.Account;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.Holding;
import com.portfolio.domain.model.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

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
        return new CashTransaction(date, Account.ROTH_IRA, TransactionType.valueOf(type),
                symbol, qty, amount, "USD", fx, amount / fx, null, null, null);
    }

    private static Holding holding(String symbol, double qty) {
        return Holding.builder(symbol, new BigDecimal(qty), Currency.getInstance("USD"), "TestAccount").build();
    }

    private CashTransactionRepository newRepo() {
        return new CashTransactionRepository(new JdbcConnectionFactory(dbDir), new KeyValueStore(dbDir));
    }

    @Test
    void dividendsBySymbolReturnsAttributedAmounts() {
        CashTransactionRepository repo = newRepo();
        repo.saveRothIra(List.of(
                rothTx("2025-06-01", "DIVIDEND", "AAPL", 0, 125, 1.25),   // £100 GBP
                rothTx("2025-01-01", "TRANSACTION", "AAPL", 10, -1000, 1.25)
        ), BigDecimal.ZERO);

        DividendService service = new DividendService(repo);
        Map<String, BigDecimal> dividends = service.dividendsBySymbol(List.of(holding("AAPL", 10)));

        assertTrue(dividends.containsKey("AAPL"));
        assertTrue(dividends.get("AAPL").compareTo(BigDecimal.ZERO) > 0, "some dividend attributed");
    }

    @Test
    void reconciliationWarnsWhenSharesMismatch() {
        CashTransactionRepository repo = newRepo();
        // Cash history says we bought 10 shares, but holdings file shows 5 (incomplete history)
        repo.saveRothIra(List.of(
                rothTx("2025-06-01", "DIVIDEND", "AAPL", 0, 125, 1.25),
                rothTx("2025-01-01", "TRANSACTION", "AAPL", 10, -1000, 1.25)
        ), BigDecimal.ZERO);

        DividendService service = new DividendService(repo);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DividendService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.dividendsBySymbol(List.of(holding("AAPL", 5)));
        } finally {
            logger.detachAppender(appender);
        }

        boolean hasReconciliationWarning = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .anyMatch(e -> e.getFormattedMessage().contains("[dividends]")
                        && e.getFormattedMessage().contains("AAPL"));
        assertTrue(hasReconciliationWarning, "reconciliation warning logged at WARN");
    }

    @Test
    void noWarningWhenSharesMatch() {
        CashTransactionRepository repo = newRepo();
        repo.saveRothIra(List.of(
                rothTx("2025-06-01", "DIVIDEND", "AAPL", 0, 125, 1.25),
                rothTx("2025-01-01", "TRANSACTION", "AAPL", 10, -1000, 1.25)
        ), BigDecimal.ZERO);

        DividendService service = new DividendService(repo);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DividendService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.dividendsBySymbol(List.of(holding("AAPL", 10)));
        } finally {
            logger.detachAppender(appender);
        }

        boolean anyReconciliationWarning = appender.list.stream()
                .anyMatch(e -> e.getFormattedMessage().contains("[dividends]"));
        assertFalse(anyReconciliationWarning, "no warning when share counts agree");
    }

    @Test
    void emptyMapWhenNoTransactions() {
        DividendService service = new DividendService(newRepo());
        assertTrue(service.dividendsBySymbol(List.of(holding("AAPL", 10))).isEmpty());
    }
}
