package com.portfolio.domain;

import com.portfolio.domain.CashLedgerReconstructor.Position;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CashLedgerReconstructorTest {

    private final CashLedgerReconstructor reconstructor = new CashLedgerReconstructor();

    private static CashTransaction buy(String date, Account account, String symbol,
                                       double qty, double amountNative, String ccy, double fxToGbp) {
        double amountGbp = amountNative / fxToGbp;
        return new CashTransaction(date, account, TransactionType.TRANSACTION,
                symbol, qty, -amountNative, ccy, fxToGbp, -amountGbp,
                null, null, null);
    }

    private static CashTransaction sell(String date, Account account, String symbol,
                                        double qty, double proceedsNative, String ccy, double fxToGbp) {
        double proceedsGbp = proceedsNative / fxToGbp;
        return new CashTransaction(date, account, TransactionType.TRANSACTION,
                symbol, qty, proceedsNative, ccy, fxToGbp, proceedsGbp,
                null, null, null);
    }

    private static CashTransaction splitDelta(String date, Account account, String symbol, double deltaQty) {
        return new CashTransaction(date, account, TransactionType.TRANSACTION,
                symbol, deltaQty, 0, "GBP", 1.0, 0, null, null, null);
    }

    private static CashTransaction dividend(String date, Account account, String symbol, double amountGbp) {
        return new CashTransaction(date, account, TransactionType.DIVIDEND,
                symbol, 0, amountGbp, "GBP", 1.0, amountGbp, null, null, null);
    }

    private Position get(List<Position> positions, String symbol) {
        Map<String, Position> bySymbol = positions.stream()
                .collect(Collectors.toMap(p -> p.securityId().toUpperCase(), p -> p));
        return bySymbol.get(symbol.toUpperCase());
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    @Test
    void emptyInput_returnsEmpty() {
        assertTrue(reconstructor.reconstruct(List.of()).isEmpty());
    }

    @Test
    void buyAndHold_qtyAndCostMatchInputs() {
        List<CashTransaction> rows = List.of(
                buy("2024-01-01", Account.AJBELL, "AAPL", 100, 10000, "GBP", 1.0));
        Position p = get(reconstructor.reconstruct(rows), "AAPL");
        assertEquals(0, bd("100").compareTo(p.quantity()));
        assertEquals(0, bd("10000").compareTo(p.costGbp()));
        assertEquals("GBP", p.tradeCurrency());
        assertEquals(Set.of("AJBell"), p.accounts());
    }

    @Test
    void buyMultipleLots_costAndQuantityAccumulate() {
        List<CashTransaction> rows = List.of(
                buy("2024-01-01", Account.AJBELL, "AAPL", 100, 10000, "GBP", 1.0),
                buy("2024-02-01", Account.AJBELL, "AAPL", 50, 6000, "GBP", 1.0));
        Position p = get(reconstructor.reconstruct(rows), "AAPL");
        assertEquals(0, bd("150").compareTo(p.quantity()));
        assertEquals(0, bd("16000").compareTo(p.costGbp()));
    }

    @Test
    void sellPartialFifo_dropsOldestLotCost() {
        // Buy 100 @ £100 = £10,000, then 50 @ £120 = £6,000. Sell 100 FIFO.
        // FIFO removes the 100 @ £100 lot first → remaining: 50 shares @ £120 = £6,000.
        List<CashTransaction> rows = List.of(
                buy("2024-01-01", Account.AJBELL, "AAPL", 100, 10000, "GBP", 1.0),
                buy("2024-02-01", Account.AJBELL, "AAPL", 50, 6000, "GBP", 1.0),
                sell("2024-03-01", Account.AJBELL, "AAPL", 100, 12000, "GBP", 1.0));
        Position p = get(reconstructor.reconstruct(rows), "AAPL");
        assertEquals(0, bd("50").compareTo(p.quantity()));
        assertEquals(0, bd("6000").compareTo(p.costGbp().setScale(0, RoundingMode.HALF_UP)));
    }

    @Test
    void sellAll_positionDroppedFromOutput() {
        List<CashTransaction> rows = List.of(
                buy("2024-01-01", Account.AJBELL, "GLE", 100, 5000, "GBP", 1.0),
                sell("2024-05-01", Account.AJBELL, "GLE", 100, 6000, "GBP", 1.0));
        assertNull(get(reconstructor.reconstruct(rows), "GLE"));
    }

    @Test
    void splitDoublesShares_costBasisInvariant() {
        // Buy 10 @ £100 = £1,000, then 2-for-1 split (+10). Cost basis should stay £1,000, qty 20.
        List<CashTransaction> rows = List.of(
                buy("2024-01-01", Account.AJBELL, "MSFT", 10, 1000, "GBP", 1.0),
                splitDelta("2024-06-01", Account.AJBELL, "MSFT", 10));
        Position p = get(reconstructor.reconstruct(rows), "MSFT");
        assertEquals(0, bd("20").compareTo(p.quantity()));
        assertEquals(0, bd("1000").compareTo(p.costGbp().setScale(0, RoundingMode.HALF_UP)));
    }

    @Test
    void positionAcrossAccounts_aggregatedBySymbol() {
        // Same symbol in two accounts; should collapse to one position with both sources.
        List<CashTransaction> rows = List.of(
                buy("2024-01-01", Account.AJBELL, "VOO", 10, 4000, "GBP", 1.0),
                // RothIRA buys 5 shares at $500 each, FX 1.25 → cost £2,000.
                buy("2024-01-15", Account.ROTH_IRA, "VOO", 5, 2500, "USD", 1.25));
        Position p = get(reconstructor.reconstruct(rows), "VOO");
        assertEquals(0, bd("15").compareTo(p.quantity()));
        assertEquals(0, bd("6000").compareTo(p.costGbp().setScale(0, RoundingMode.HALF_UP)));
        assertEquals(2, p.accounts().size());
        assertTrue(p.accounts().contains("AJBell"));
        assertTrue(p.accounts().contains("RothIRA"));
    }

    @Test
    void dividendsAndOtherTypes_ignoredByReconstructor() {
        // Dividends + interest etc. shouldn't affect quantity or cost.
        List<CashTransaction> rows = List.of(
                buy("2024-01-01", Account.AJBELL, "AAPL", 10, 1000, "GBP", 1.0),
                dividend("2024-03-01", Account.AJBELL, "AAPL", 25));
        Position p = get(reconstructor.reconstruct(rows), "AAPL");
        assertEquals(0, bd("10").compareTo(p.quantity()));
        assertEquals(0, bd("1000").compareTo(p.costGbp()));
    }

    @Test
    void historicalFxPreservedInCost_notRecalculated() {
        // Buy at FX 1.25 (USD per GBP) — cost basis should reflect 1.25, not whatever is current.
        List<CashTransaction> rows = List.of(
                buy("2024-01-01", Account.ROTH_IRA, "AAPL", 10, 1000, "USD", 1.25));
        Position p = get(reconstructor.reconstruct(rows), "AAPL");
        // $1,000 / 1.25 = £800.
        assertEquals(0, bd("800").compareTo(p.costGbp().setScale(0, RoundingMode.HALF_UP)));
    }
}
