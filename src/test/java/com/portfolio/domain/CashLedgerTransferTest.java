package com.portfolio.domain;

import com.portfolio.domain.CashLedgerReconstructor.AccountPosition;
import com.portfolio.domain.CashLedgerReconstructor.Position;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The invariant an in-specie transfer must hold: the account attribution moves, and nothing else
 * does. Quantity, cost basis and therefore gain must come out identical either side of it.
 */
class CashLedgerTransferTest {

    private static CashTransaction buy(String date, Account account, String symbol,
                                       double qty, double costGbp) {
        return new CashTransaction(date, account, TransactionType.TRANSACTION, symbol,
                qty, -costGbp, "GBP", 1.0, -costGbp, null, null, "buy");
    }

    private static CashTransaction sell(String date, Account account, String symbol,
                                        double qty, double proceedsGbp) {
        return new CashTransaction(date, account, TransactionType.TRANSACTION, symbol,
                qty, proceedsGbp, "GBP", 1.0, proceedsGbp, null, null, "sell");
    }

    /** The pair {@code AssetTransferService} writes: cost basis out of one, into the other. */
    private static List<CashTransaction> transfer(String date, Account from, Account to,
                                                  String symbol, double qty, double costGbp) {
        return List.of(
                new CashTransaction(date, from, TransactionType.TRANSFER, symbol,
                        qty, costGbp, "GBP", 1.0, costGbp, 0.0, 0.0, "transfer out"),
                new CashTransaction(date, to, TransactionType.TRANSFER, symbol,
                        qty, -costGbp, "GBP", 1.0, -costGbp, 0.0, 0.0, "transfer in"));
    }

    private static Position position(List<CashTransaction> rows, String symbol) {
        return new CashLedgerReconstructor().reconstruct(rows).stream()
                .filter(p -> p.securityId().equals(symbol)).findFirst().orElseThrow();
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    @Test
    void transferMovesTheAccountAttributionAndNothingElse() {
        List<CashTransaction> before = List.of(
                buy("2023-02-09", Account.AJBELL, "GOOG", 100, 7893.26),
                buy("2024-01-31", Account.AJBELL, "GOOG", 100, 11345.71),
                buy("2025-10-10", Account.AJBELL, "GOOG", 100, 18424.77));

        Position pre = position(before, "GOOG");
        assertAmount("300", pre.quantity());
        assertAmount("37663.74", pre.costGbp());
        assertEquals(Set.of("AJBell"), pre.accounts());

        List<CashTransaction> after = new ArrayList<>(before);
        after.addAll(transfer("2026-08-20", Account.AJBELL, Account.II, "GOOG", 300, 37663.74));

        Position post = position(after, "GOOG");
        assertAmount("300", post.quantity());
        assertAmount("37663.74", post.costGbp());
        assertEquals(Set.of("II"), post.accounts(),
                "the emptied account must drop out of sources on its own");
    }

    /**
     * The receiving account must end up holding the position outright — this is what makes the
     * Sources column, the account grouping and the per-account dividend lots all say II.
     */
    @Test
    void theSourceAccountIsLeftHoldingNothing() {
        List<CashTransaction> rows = new ArrayList<>(List.of(
                buy("2025-10-10", Account.AJBELL, "MSFT", 25, 9897.52)));
        rows.addAll(transfer("2026-08-20", Account.AJBELL, Account.II, "MSFT", 25, 9897.52));

        List<AccountPosition> byAccount = new CashLedgerReconstructor().reconstructByAccount(rows);
        assertTrue(byAccount.stream().noneMatch(p -> p.account() == Account.AJBELL),
                "AJ Bell's lots are emptied, so it must not appear at all");
        AccountPosition ii = byAccount.stream()
                .filter(p -> p.account() == Account.II).findFirst().orElseThrow();
        assertAmount("25", ii.quantity());
        assertAmount("9897.52", ii.costGbp());
    }

    /**
     * The destination may already hold the name — MSFT is at all three accounts on the live book.
     * The transferred shares and cost must add to what is there, not replace it.
     */
    @Test
    void transferIntoAnAccountThatAlreadyHoldsTheNameAdds() {
        List<CashTransaction> rows = new ArrayList<>(List.of(
                buy("2025-01-02", Account.II, "MSFT", 80, 24000.00),
                buy("2025-10-10", Account.AJBELL, "MSFT", 25, 9897.52)));
        rows.addAll(transfer("2026-08-20", Account.AJBELL, Account.II, "MSFT", 25, 9897.52));

        AccountPosition ii = new CashLedgerReconstructor().reconstructByAccount(rows).stream()
                .filter(p -> p.account() == Account.II).findFirst().orElseThrow();
        assertAmount("105", ii.quantity());
        assertAmount("33897.52", ii.costGbp());
        assertAmount("33897.52", position(rows, "MSFT").costGbp());
    }

    /**
     * The cost that moves is the source's own FIFO cost, and the whole point of computing it here
     * rather than reading it off the broker statement. Feeding market value instead is what would
     * destroy the gain, so it is asserted explicitly.
     */
    @Test
    void movingMarketValueInsteadOfCostWouldRewriteTheGain() {
        List<CashTransaction> rows = new ArrayList<>(List.of(
                buy("2025-10-10", Account.AJBELL, "MSFT", 25, 9897.52)));
        rows.addAll(transfer("2026-08-20", Account.AJBELL, Account.II, "MSFT", 25, 14000.00));

        // Cost basis jumps by the difference — a £4,102 phantom loss appearing from nowhere.
        assertAmount("14000.00", position(rows, "MSFT").costGbp());
    }

    /** A partial sale before the transfer must leave the remaining FIFO cost, not the average. */
    @Test
    void theCostThatMovesIsWhatSurvivedEarlierSales() {
        // GLE on the live book: FIFO leaves £1,943.34 where AJ Bell's pooled average says £2,085.35.
        List<CashTransaction> rows = new ArrayList<>(List.of(
                buy("2021-12-01", Account.AJBELL, "GLE", 408, 9986.54),
                buy("2022-03-09", Account.AJBELL, "GLE", 1041, 20230.13),
                sell("2024-11-06", Account.AJBELL, "GLE", 949, 21067.84),
                sell("2026-03-06", Account.AJBELL, "GLE", 400, 22907.57)));

        AccountPosition gle = new CashLedgerReconstructor().reconstructByAccount(rows).stream()
                .filter(p -> p.securityId().equals("GLE")).findFirst().orElseThrow();
        assertAmount("100", gle.quantity());
        assertEquals("1943.34", gle.costGbp().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
    }

    /** Transfers chain: A → B → C must leave the position at C with its cost intact. */
    @Test
    void aPositionCanBeTransferredOnAgain() {
        List<CashTransaction> rows = new ArrayList<>(List.of(
                buy("2025-01-02", Account.AJBELL, "BAC", 80, 2996.39)));
        rows.addAll(transfer("2026-08-20", Account.AJBELL, Account.II, "BAC", 80, 2996.39));
        rows.addAll(transfer("2026-08-25", Account.II, Account.ROTH_IRA, "BAC", 80, 2996.39));

        Position p = position(rows, "BAC");
        assertAmount("80", p.quantity());
        assertAmount("2996.39", p.costGbp());
        assertEquals(Set.of("RothIRA"), p.accounts());
    }

    /** Removing the pair puts the position back — the undo the screen offers. */
    @Test
    void deletingBothLegsRestoresTheOriginalAttribution() {
        List<CashTransaction> before = List.of(buy("2025-01-02", Account.AJBELL, "RHM", 3, 4191.90));
        List<CashTransaction> after = new ArrayList<>(before);
        after.addAll(transfer("2026-08-20", Account.AJBELL, Account.II, "RHM", 3, 4191.90));

        assertEquals(Set.of("II"), position(after, "RHM").accounts());
        assertEquals(Set.of("AJBell"), position(before, "RHM").accounts());
        assertAmount(position(before, "RHM").costGbp().toPlainString(),
                position(after, "RHM").costGbp());
    }

    /** A transfer is not a trade: it must not register as a flow for the IRR column. */
    @Test
    void transferLegsAreNotCashFlows() {
        List<CashTransaction> rows = new ArrayList<>(List.of(
                buy("2025-10-10", Account.AJBELL, "MSFT", 25, 9897.52)));
        rows.addAll(transfer("2026-08-20", Account.AJBELL, Account.II, "MSFT", 25, 9897.52));

        PositionIrr.Result irr = PositionIrr.compute("MSFT", rows,
                new BigDecimal("14000"), null, "GBP", java.time.LocalDate.parse("2026-08-27"));
        // Two flows only — the purchase and today's valuation. The transfer contributes neither.
        assertEquals(2, irr.flowCount(),
                "a transfer moves no money and must not appear in the IRR flow series");
        assertFalse(irr.reentered(), "a transfer is not an exit and re-entry");
    }
}
