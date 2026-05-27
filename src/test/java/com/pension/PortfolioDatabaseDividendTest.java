package com.pension;

import com.pension.domain.model.CashTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PortfolioDatabaseDividendTest {

    @TempDir
    Path dbDir;

    private static CashTransaction roth(String date, String symbol, String type,
                                        double qty, double amount, double fx) {
        return new CashTransaction(date, "RothIRA", type, symbol, qty, amount,
                "USD", fx, amount / fx, null, null, date);
    }

    @Test
    void sumsOnlyDividendRowsInGbpKeyedByUpperCaseSymbol() {
        PortfolioDatabase db = new PortfolioDatabase(dbDir);
        db.saveRothIraCashTransactions(List.of(
                roth("2026-01-03", "aaa", "DIVIDEND", 0, 125, 1.25),     // 100 GBP
                roth("2026-01-02", "aaa", "DIVIDEND", 0, 25, 1.25),      // 20 GBP, same symbol
                roth("2026-01-01", "bbb", "TRANSACTION", 10, -500, 1.25) // not a dividend
        ), new BigDecimal("1000"));

        Map<String, BigDecimal> dividends = db.loadDividendsBySymbol();

        assertEquals(0, new BigDecimal("120").compareTo(dividends.get("AAA")), "100 + 20 GBP, summed");
        assertFalse(dividends.containsKey("BBB"), "non-dividend rows excluded");
    }

    @Test
    void emptyWhenNoDatabaseYet() {
        assertTrue(new PortfolioDatabase(dbDir).loadDividendsBySymbol().isEmpty());
    }
}
