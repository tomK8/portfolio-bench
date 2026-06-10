package com.portfolio.application;

import com.portfolio.application.PositionDetailService.ClosedLot;
import com.portfolio.application.PositionDetailService.Lots;
import com.portfolio.application.PositionDetailService.OpenLot;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PositionDetailServiceTest {

    @Test
    void pureBuysOnlyOpenLots() {
        List<CashTransaction> rows = List.of(
                buy("2024-01-15", "AAPL", 50, 5000.00, Account.AJBELL),
                buy("2024-03-20", "AAPL", 30, 3300.00, Account.AJBELL)
        );
        Lots lots = PositionDetailService.reconstruct(rows);
        assertEquals(0, lots.totalOpenShares().compareTo(new BigDecimal("80")));
        assertEquals(0, lots.totalOpenCostGbp().compareTo(new BigDecimal("8300.00")));
        assertEquals(0, lots.totalRealizedGbp().compareTo(BigDecimal.ZERO));

        List<OpenLot> open = lots.openForDisplay();
        assertEquals(2, open.size());
        assertEquals("2024-01-15", open.get(0).openDate());
        // 5000 / 50 = 100 GBP/share
        assertEquals(0, open.get(0).costPerShareGbp().compareTo(new BigDecimal("100.000000")));
        assertEquals("2024-03-20", open.get(1).openDate());
        assertEquals(0, open.get(1).costPerShareGbp().compareTo(new BigDecimal("110.000000")));
    }

    @Test
    void partialSellSplitsOneLotEmitsClosedSlice() {
        // Buy 100 @ £10 → cost £1000. Sell 40 @ £15 → proceeds £600, cost £400, realized +£200.
        // Remaining open: 60 shares @ £10 = £600 cost.
        List<CashTransaction> rows = List.of(
                buy("2024-01-15", "X", 100, 1000.00, Account.AJBELL),
                sell("2024-06-15", "X", 40, 600.00, Account.AJBELL)
        );
        Lots lots = PositionDetailService.reconstruct(rows);

        assertEquals(0, lots.totalOpenShares().compareTo(new BigDecimal("60")));
        assertEquals(0, lots.totalOpenCostGbp().compareTo(new BigDecimal("600.00")));
        assertEquals(0, lots.totalRealizedGbp().compareTo(new BigDecimal("200.00")));

        List<ClosedLot> closed = lots.closedForDisplay();
        assertEquals(1, closed.size());
        ClosedLot c = closed.get(0);
        assertEquals("2024-01-15", c.openDate());
        assertEquals("2024-06-15", c.closeDate());
        assertEquals(0, c.shares().compareTo(new BigDecimal("40")));
        assertEquals(0, c.costGbp().compareTo(new BigDecimal("400.00")));
        assertEquals(0, c.proceedsGbp().compareTo(new BigDecimal("600.00")));
        assertEquals(0, c.realizedGbp().compareTo(new BigDecimal("200.00")));
    }

    @Test
    void sellAcrossMultipleLotsEmitsOnePerConsumedSlice() {
        // Buy 50 @ £10 (Jan), buy 50 @ £20 (Feb), sell 70 @ £25 (Mar).
        // FIFO: consume all 50 from Jan-lot, then 20 from Feb-lot.
        // Closed lot 1: 50 × (25-10) = +£750 realized, opened Jan.
        // Closed lot 2: 20 × (25-20) = +£100 realized, opened Feb.
        // Open: 30 @ £20 = £600.
        List<CashTransaction> rows = List.of(
                buy("2024-01-15", "Y", 50, 500.00, Account.AJBELL),
                buy("2024-02-15", "Y", 50, 1000.00, Account.AJBELL),
                sell("2024-03-15", "Y", 70, 1750.00, Account.AJBELL)
        );
        Lots lots = PositionDetailService.reconstruct(rows);

        assertEquals(0, lots.totalOpenShares().compareTo(new BigDecimal("30")));
        assertEquals(0, lots.totalOpenCostGbp().compareTo(new BigDecimal("600.00")));
        assertEquals(0, lots.totalRealizedGbp().compareTo(new BigDecimal("850.00")));

        List<ClosedLot> closed = lots.closedForDisplay();
        assertEquals(2, closed.size());
        // Both sells happened on 2024-03-15; ordering by closeDate ties — original (FIFO) order preserved.
        assertEquals("2024-01-15", closed.get(0).openDate());
        assertEquals(0, closed.get(0).shares().compareTo(new BigDecimal("50")));
        assertEquals(0, closed.get(0).realizedGbp().compareTo(new BigDecimal("750.00")));
        assertEquals("2024-02-15", closed.get(1).openDate());
        assertEquals(0, closed.get(1).shares().compareTo(new BigDecimal("20")));
        assertEquals(0, closed.get(1).realizedGbp().compareTo(new BigDecimal("100.00")));
    }

    @Test
    void fullExitClosesAllLotsLeavesEmptyOpen() {
        List<CashTransaction> rows = List.of(
                buy("2024-01-15", "Z", 100, 1000.00, Account.AJBELL),
                sell("2024-08-15", "Z", 100, 1500.00, Account.AJBELL)
        );
        Lots lots = PositionDetailService.reconstruct(rows);
        assertEquals(0, lots.totalOpenShares().compareTo(BigDecimal.ZERO));
        assertEquals(0, lots.totalOpenCostGbp().compareTo(BigDecimal.ZERO));
        assertEquals(0, lots.totalRealizedGbp().compareTo(new BigDecimal("500.00")));
        assertTrue(lots.openForDisplay().isEmpty());
        assertEquals(1, lots.closedForDisplay().size());
    }

    @Test
    void splitScalesLotsQuantityAndInverseScalesCostPerShare() {
        // Buy 10 @ £100 = £1000 cost. Split 10:1 → 100 shares @ £10 = £1000 cost (unchanged).
        // Sell all 100 at £15/share = £1500 → realized +£500 (matches buy/sell delta).
        List<CashTransaction> rows = List.of(
                buy("2024-01-15", "S", 10, 1000.00, Account.AJBELL),
                split("2024-04-01", "S", 90, Account.AJBELL),   // qty delta = +90 (10 → 100)
                sell("2024-07-01", "S", 100, 1500.00, Account.AJBELL)
        );
        Lots lots = PositionDetailService.reconstruct(rows);
        assertEquals(0, lots.totalOpenShares().compareTo(BigDecimal.ZERO));
        assertEquals(0, lots.totalRealizedGbp().compareTo(new BigDecimal("500.00")));

        // The closed lot reports the post-split share count and the post-split
        // cost-per-share — the buy date is preserved.
        ClosedLot c = lots.closedForDisplay().get(0);
        assertEquals("2024-01-15", c.openDate());
        assertEquals(0, c.shares().compareTo(new BigDecimal("100")));
        assertEquals(0, c.costGbp().compareTo(new BigDecimal("1000.00")));
    }

    @Test
    void multipleAccountsKeepSeparateFifoQueues() {
        // Same symbol in two accounts. FIFO is per-account: the AJBell sell
        // can only consume the AJBell buy, not the Roth buy.
        List<CashTransaction> rows = List.of(
                buy("2024-01-10", "M", 50, 500.00, Account.AJBELL),
                buy("2024-02-10", "M", 50, 700.00, Account.ROTH_IRA),
                sell("2024-06-10", "M", 30, 450.00, Account.AJBELL)
        );
        Lots lots = PositionDetailService.reconstruct(rows);

        // Open: 20 AJBell + 50 Roth = 70 shares
        assertEquals(0, lots.totalOpenShares().compareTo(new BigDecimal("70")));
        // Cost: 20 × £10 (AJBell) + 50 × £14 (Roth) = 200 + 700 = £900
        assertEquals(0, lots.totalOpenCostGbp().compareTo(new BigDecimal("900.00")));
        // Realized: 30 × (15 - 10) = +£150
        assertEquals(0, lots.totalRealizedGbp().compareTo(new BigDecimal("150.00")));

        ClosedLot c = lots.closedForDisplay().get(0);
        assertEquals("AJBell", c.account());
    }

    @Test
    void emptyRowsAreNoOp() {
        Lots lots = PositionDetailService.reconstruct(List.of());
        assertEquals(0, lots.totalOpenShares().compareTo(BigDecimal.ZERO));
        assertEquals(0, lots.totalRealizedGbp().compareTo(BigDecimal.ZERO));
        assertTrue(lots.openForDisplay().isEmpty());
        assertTrue(lots.closedForDisplay().isEmpty());
    }

    // ---- Helpers ----

    private static CashTransaction buy(String date, String sym, double qty,
                                       double cost, Account account) {
        double native_ = -cost;
        return new CashTransaction(date, account, TransactionType.TRANSACTION,
                sym, qty, native_, account == Account.AJBELL ? "GBP" : "USD",
                1.0, -cost, null, null, "buy");
    }

    private static CashTransaction sell(String date, String sym, double qty,
                                        double proceeds, Account account) {
        return new CashTransaction(date, account, TransactionType.TRANSACTION,
                sym, qty, proceeds, account == Account.AJBELL ? "GBP" : "USD",
                1.0, proceeds, null, null, "sell");
    }

    private static CashTransaction split(String date, String sym, double qtyDelta,
                                         Account account) {
        return new CashTransaction(date, account, TransactionType.TRANSACTION,
                sym, qtyDelta, 0.0, account == Account.AJBELL ? "GBP" : "USD",
                1.0, 0.0, null, null, "split");
    }
}
