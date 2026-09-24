package com.portfolio.domain;

import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DividendAttributorTest {

    private final DividendAttributor attributor = new DividendAttributor();

    private static CashTransaction tx(String date, String account, String type,
                                      String symbol, double qty, double amount, double amountGbp) {
        return new CashTransaction(date, Account.fromDbValue(account), TransactionType.valueOf(type),
                symbol, qty, amount, "GBP", 1.0, amountGbp, null, null, null);
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
    void transfer_movesSharesAndTheDividendTheyAccrued() {
        // 100 shares earn £100 (£1/share) at AJ Bell, then move to II in-specie. The shares
        // were not sold, so neither they nor their dividend may drop out.
        List<CashTransaction> rows = List.of(
                buy("2024-01-01", "GOOG", 100, 10000),
                div("2024-04-01", "GOOG", 100),
                tx("2026-08-20", "AJBell", "TRANSFER", "GOOG", 100, 10000, 10000),
                tx("2026-08-20", "II", "TRANSFER", "GOOG", 100, -10000, -10000)
        );

        DividendAttributor.Attribution a = attributor.attributeBySymbol(rows).get("GOOG");

        assertEquals(0, new BigDecimal("100").compareTo(a.shares().setScale(0, java.math.RoundingMode.HALF_UP)),
                "the shares are counted once, at their new account");
        assertEquals(0, new BigDecimal("100").compareTo(a.dividendGbp().setScale(2, java.math.RoundingMode.HALF_UP)),
                "the accrued dividend travels with them");
    }

    @Test
    void transfer_thenSoldAtTheGainingAccount_leavesNothingHeld() {
        // The live INTC case. Without TRANSFER the losing account kept the shares and the
        // gaining account's sale consumed nothing, so the position never closed.
        List<CashTransaction> rows = List.of(
                buy("2024-08-02", "INTC", 300, 4986.06),
                sell("2026-03-06", "INTC", 200, 6590.79),
                tx("2026-08-20", "AJBell", "TRANSFER", "INTC", 100, 1662.02, 1662.02),
                tx("2026-08-20", "II", "TRANSFER", "INTC", 100, -1662.02, -1662.02),
                tx("2026-09-17", "II", "TRANSACTION", "INTC", 100, 8139.20, 8139.20)
        );

        DividendAttributor.Attribution a = attributor.attributeBySymbol(rows).get("INTC");

        assertEquals(0, BigDecimal.ZERO.compareTo(a.shares().setScale(2, java.math.RoundingMode.HALF_UP)),
                "sold out of the account it had moved to");
        assertEquals(0, BigDecimal.ZERO.compareTo(a.dividendGbp().setScale(2, java.math.RoundingMode.HALF_UP)),
                "no shares survive, so no dividend is attributed to them");
    }

    @Test
    void dividendAtTheGainingAccountIsAttributed_notDroppedForWantOfLots() {
        // The live GOOG case: II held none of its own, received the 300 transferred shares,
        // and was paid on them. With no lots there the payment raised nothing and was lost.
        List<CashTransaction> rows = List.of(
                buy("2024-01-01", "GOOG", 300, 37663.74),
                tx("2026-08-20", "AJBell", "TRANSFER", "GOOG", 300, 37663.74, 37663.74),
                tx("2026-08-20", "II", "TRANSFER", "GOOG", 300, -37663.74, -37663.74),
                tx("2026-09-15", "II", "DIVIDEND", "GOOG", 0, 48.95, 48.95)
        );

        DividendAttributor.Attribution a = attributor.attributeBySymbol(rows).get("GOOG");

        assertEquals(0, new BigDecimal("48.95").compareTo(a.dividendGbp().setScale(2, java.math.RoundingMode.HALF_UP)));
        assertEquals(0, new BigDecimal("300").compareTo(a.shares().setScale(0, java.math.RoundingMode.HALF_UP)));
    }

    @Test
    void dividendReachingTheLosingAccountAfterTheMoveFollowsTheShares() {
        // The live MSFT case: AJ Bell was paid on 2026-09-10 for 25 shares that left on
        // 2026-08-20 — the record date precedes the move. It belongs to those shares at II.
        List<CashTransaction> rows = List.of(
                buy("2025-10-10", "MSFT", 25, 9897.52),
                tx("2026-08-20", "AJBell", "TRANSFER", "MSFT", 25, 9897.52, 9897.52),
                tx("2026-08-20", "II", "TRANSFER", "MSFT", 25, -9897.52, -9897.52),
                tx("2026-09-10", "AJBell", "DIVIDEND", "MSFT", 25, 16.87, 16.87)
        );

        DividendAttributor.Attribution a = attributor.attributeBySymbol(rows).get("MSFT");

        assertEquals(0, new BigDecimal("16.87").compareTo(a.dividendGbp().setScale(2, java.math.RoundingMode.HALF_UP)),
                "paid to the old account, earned by shares now at the new one");
        assertEquals(0, new BigDecimal("25").compareTo(a.shares().setScale(0, java.math.RoundingMode.HALF_UP)));
    }

    @Test
    void accountThatBoughtTheNameAgainKeepsItsOwnDividend() {
        // Forwarding applies only to a timeline the transfer emptied. AJ Bell moved 25 shares
        // out, then bought 10 more: the later payment is its own and stays put.
        List<CashTransaction> rows = List.of(
                buy("2025-10-10", "MSFT", 25, 9897.52),
                tx("2026-08-20", "AJBell", "TRANSFER", "MSFT", 25, 9897.52, 9897.52),
                tx("2026-08-20", "II", "TRANSFER", "MSFT", 25, -9897.52, -9897.52),
                buy("2026-09-01", "MSFT", 10, 4000.00),
                tx("2026-09-10", "AJBell", "DIVIDEND", "MSFT", 10, 6.75, 6.75)
        );

        DividendAttributor.Attribution a = attributor.attributeBySymbol(rows).get("MSFT");

        assertEquals(0, new BigDecimal("35").compareTo(a.shares().setScale(0, java.math.RoundingMode.HALF_UP)));
        assertEquals(0, new BigDecimal("6.75").compareTo(a.dividendGbp().setScale(2, java.math.RoundingMode.HALF_UP)));
    }

    @Test
    void transferLegsSeenGainingAccountFirstStillPairUp() {
        // The gaining account's timeline can be the older one (it already traded the name),
        // so replay must be in ledger order, not account by account.
        List<CashTransaction> rows = List.of(
                tx("2023-01-01", "II", "TRANSACTION", "BAC", 40, -2000, -2000),
                tx("2023-06-01", "II", "TRANSACTION", "BAC", 40, 2400, 2400),
                buy("2024-01-01", "BAC", 80, 2996.39),
                div("2024-04-01", "BAC", 80),
                tx("2026-08-20", "AJBell", "TRANSFER", "BAC", 80, 2996.39, 2996.39),
                tx("2026-08-20", "II", "TRANSFER", "BAC", 80, -2996.39, -2996.39)
        );

        DividendAttributor.Attribution a = attributor.attributeBySymbol(rows).get("BAC");

        assertEquals(0, new BigDecimal("80").compareTo(a.shares().setScale(0, java.math.RoundingMode.HALF_UP)));
        assertEquals(0, new BigDecimal("80").compareTo(a.dividendGbp().setScale(2, java.math.RoundingMode.HALF_UP)));
    }

    @Test
    void noHoldings_returnsEmpty() {
        assertTrue(attributor.attributeBySymbol(List.of()).isEmpty());
    }
}
