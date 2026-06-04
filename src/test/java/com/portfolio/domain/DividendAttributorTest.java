package com.portfolio.domain;

import com.portfolio.domain.model.CashTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DividendAttributorTest {

    private final DividendAttributor attributor = new DividendAttributor();

    private static CashTransaction tx(String date, String account, String type,
                                      String symbol, double qty, double amount, double amountGbp) {
        return new CashTransaction(date, account, type, symbol, qty, amount,
                "GBP", 1.0, amountGbp, null, null, null);
    }

    private static CashTransaction buy(String date, String symbol, double qty, double costGbp) {
        return tx(date, "AJBell", "TRANSACTION", symbol, qty, -costGbp, -costGbp);
    }

    private static CashTransaction sell(String date, String symbol, double qty, double proceedsGbp) {
        return tx(date, "AJBell", "TRANSACTION", symbol, qty, proceedsGbp, proceedsGbp);
    }

    private static CashTransaction div(String date, String symbol, double amountGbp) {
        return tx(date, "AJBell", "DIVIDEND", symbol, 0, amountGbp, amountGbp);
    }

    private static CashTransaction split(String date, String symbol, double deltaQty) {
        return tx(date, "AJBell", "TRANSACTION", symbol, deltaQty, 0, 0);
    }

    private BigDecimal attributed(String symbol) {
        return attributed(symbol, List.of());
    }

    private BigDecimal attributed(String symbol, List<CashTransaction> rows) {
        Map<String, DividendAttributor.Attribution> result = attributor.attributeBySymbol(rows);
        DividendAttributor.Attribution a = result.get(symbol.toUpperCase());
        return a == null ? BigDecimal.ZERO : a.dividendGbp();
    }

    @Test
    void allSharesHeld_fullDividendAttributed() {
        List<CashTransaction> rows = List.of(
                buy("2024-01-01", "AAPL", 100, 10000),
                div("2024-06-01", "AAPL", 100)
        );

        Map<String, DividendAttributor.Attribution> result = attributor.attributeBySymbol(rows);
        DividendAttributor.Attribution a = result.get("AAPL");

        assertEquals(0, new BigDecimal("100").compareTo(a.dividendGbp()), "full dividend");
        assertEquals(0, new BigDecimal("100").compareTo(a.shares()), "100 shares held");
    }

    @Test
    void sellHalf_onlyRemainingSharesAttributed() {
        // Buy 100, earn £100 dividend (£1/share), sell 50 FIFO, earn £60 dividend (£1.20/share on 50)
        List<CashTransaction> rows = List.of(
                buy("2024-01-01", "AAPL", 100, 10000),
                div("2024-04-01", "AAPL", 100),     // £1/share, 100 shares → cumPerShare = £1
                sell("2024-06-01", "AAPL", 50, 6000), // FIFO removes first 50
                div("2024-09-01", "AAPL", 60)         // £1.20/share, 50 remaining → cumPerShare = £2.20
        );

        Map<String, DividendAttributor.Attribution> result = attributor.attributeBySymbol(rows);
        DividendAttributor.Attribution a = result.get("AAPL");

        // Remaining 50 shares: (£2.20 - £0 baseline) × 50 = £110
        assertEquals(0, new BigDecimal("110").compareTo(a.dividendGbp().setScale(2, java.math.RoundingMode.HALF_UP)),
                "£1 × 50 remaining from first div + £1.20 × 50 from second");
        assertEquals(0, new BigDecimal("50").compareTo(a.shares().setScale(0, java.math.RoundingMode.HALF_UP)));
    }

    @Test
    void sellAllThenRebuy_onlyPostRebuyDividendsCount() {
        List<CashTransaction> rows = List.of(
                buy("2024-01-01", "GLE", 100, 5000),
                div("2024-03-01", "GLE", 200),       // £2/share on 100 shares
                sell("2024-05-01", "GLE", 100, 6000), // sell all
                buy("2024-07-01", "GLE", 50, 3000),   // rebuy smaller position
                div("2024-09-01", "GLE", 100)          // £2/share on 50 shares
        );

        DividendAttributor.Attribution a = attributor.attributeBySymbol(rows).get("GLE");

        // Only the post-rebuy dividend on 50 shares counts: £100
        assertEquals(0, new BigDecimal("100").compareTo(a.dividendGbp().setScale(2, java.math.RoundingMode.HALF_UP)),
                "pre-sell dividends drop out entirely");
        assertEquals(0, new BigDecimal("50").compareTo(a.shares().setScale(0, java.math.RoundingMode.HALF_UP)));
    }

    @Test
    void splitScaling_preservesAttributedAmount() {
        // Buy 10 shares, earn £50 dividend (£5/share), then 2-for-1 split (+10 shares)
        List<CashTransaction> rows = List.of(
                buy("2024-01-01", "MSFT", 10, 1000),
                div("2024-04-01", "MSFT", 50),       // cumPerShare = £5
                split("2024-06-01", "MSFT", 10)       // 2-for-1: qty becomes 20, cumPerShare becomes £2.50
        );

        DividendAttributor.Attribution a = attributor.attributeBySymbol(rows).get("MSFT");

        // (£2.50 - £0 baseline) × 20 = £50 — invariant maintained
        assertEquals(0, new BigDecimal("50").compareTo(a.dividendGbp().setScale(2, java.math.RoundingMode.HALF_UP)),
                "split should not change total attributed dividend");
        assertEquals(0, new BigDecimal("20").compareTo(a.shares().setScale(0, java.math.RoundingMode.HALF_UP)));
    }

    @Test
    void multipleAccountsSameSymbol_dividendsSummed() {
        List<CashTransaction> rows = List.of(
                tx("2024-01-01", "AJBell", "TRANSACTION", "VOO", 10, -1000, -1000),
                tx("2024-04-01", "AJBell", "DIVIDEND", "VOO", 0, 40, 40),
                tx("2024-01-15", "RothIRA", "TRANSACTION", "VOO", 5, -500, -400),
                tx("2024-04-01", "RothIRA", "DIVIDEND", "VOO", 0, 20, 16)
        );

        DividendAttributor.Attribution a = attributor.attributeBySymbol(rows).get("VOO");

        assertEquals(0, new BigDecimal("56").compareTo(a.dividendGbp().setScale(2, java.math.RoundingMode.HALF_UP)),
                "£40 + £16 summed across accounts");
        assertEquals(0, new BigDecimal("15").compareTo(a.shares().setScale(0, java.math.RoundingMode.HALF_UP)));
    }

    @Test
    void noHoldings_returnsEmpty() {
        assertTrue(attributor.attributeBySymbol(List.of()).isEmpty());
    }
}
