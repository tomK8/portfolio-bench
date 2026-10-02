package com.portfolio.application;

import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.OpenOrder;
import com.portfolio.domain.model.OrderStatus;
import com.portfolio.domain.model.TransactionType;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.OrderHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two halves meeting: an order the Roth export stopped listing, and the trade it left in the
 * cash statement.
 */
class OrderFillReconcilerTest {

    private static final String ROTH = "Roth IRA";
    private static final LocalDate ENTERED = LocalDate.parse("2026-09-16");
    private static final LocalDate GONE = LocalDate.parse("2026-09-30");

    @TempDir
    Path dbDir;

    private OrderHistoryRepository history;
    private CashTransactionRepository cash;

    private OrderFillReconciler reconciler() {
        JdbcConnectionFactory connections = new JdbcConnectionFactory(dbDir);
        history = new OrderHistoryRepository(connections);
        cash = new CashTransactionRepository(connections, new KeyValueStore(dbDir));
        return new OrderFillReconciler(history, cash);
    }

    private static OpenOrder sell(String symbol, String qty, String price, String status) {
        return OpenOrder.parsed(ROTH, symbol, null, "Sell", "Limit", new BigDecimal(qty),
                new BigDecimal(price), false, null, "USD", ENTERED, null, "GTC", status, null);
    }

    /** A Roth statement row: USD, cash positive on a sale. */
    private static CashTransaction trade(String date, String symbol, double qty, double amount) {
        return new CashTransaction(date, Account.ROTH_IRA, TransactionType.TRANSACTION, symbol,
                qty, amount, "USD", 1.3, amount / 1.3, null, null,
                (qty < 0 ? "Sell " : "Buy ") + qty + " Share(s) Of " + symbol);
    }

    /** An order that vanished, with the trade that explains it already in the ledger. */
    private OrderFillReconciler withVanishedSellAndItsTrade() {
        OrderFillReconciler r = reconciler();
        OpenOrder order = sell("ASML", "5", "1797.00", "Open");
        history.record(ROTH, List.of(order), Set.of(order.identity()), ENTERED);
        history.record(ROTH, List.of(), Set.of(), GONE);
        cash.saveRothIra(List.of(trade("2026-09-29", "ASML", -5, 9062.15)), BigDecimal.ZERO);
        return r;
    }

    private OrderHistoryRepository.HistoricalOrder only() {
        List<OrderHistoryRepository.HistoricalOrder> closed = history.closed();
        assertEquals(1, closed.size(), () -> "expected one closed order, got " + closed);
        return closed.get(0);
    }

    @Test
    void anOrderTheLedgerExplainsIsRecordedAsFilled() {
        OrderFillReconciler r = withVanishedSellAndItsTrade();

        assertEquals(OrderHistoryRepository.HistoricalOrder.NO_LONGER_LISTED, only().state(),
                "nothing has read the ledger yet");
        assertEquals(1, r.reconcile(GONE));

        var h = only();
        assertEquals("Filled", h.state());
        assertTrue(h.stateResolved());
        assertFalse(h.resolvedByUser(), "the ledger said so, not the user");
    }

    /** It is written down, so the next pass has nothing left to do. */
    @Test
    void asecondRunFindsNothingToDo() {
        OrderFillReconciler r = withVanishedSellAndItsTrade();
        assertEquals(1, r.reconcile(GONE));

        assertEquals(0, r.reconcile(GONE));
        assertEquals("Filled", only().state());
    }

    /**
     * An order the ledger cannot explain is asked about once per ledger, not once per page load.
     * Without the high-water mark this is the row that would re-read the statement forever.
     */
    @Test
    void anUnexplainableOrderIsNotReAskedUntilTheLedgerMoves() {
        OrderFillReconciler r = reconciler();
        OpenOrder order = sell("TSM", "17", "439.59", "Open");
        history.record(ROTH, List.of(order), Set.of(order.identity()), ENTERED);
        history.record(ROTH, List.of(), Set.of(), GONE);
        cash.saveRothIra(List.of(trade("2026-09-20", "NVDA", -10, 2000.00)), BigDecimal.ZERO);

        assertEquals(0, r.reconcile(GONE));
        assertEquals("2026-09-20", history.fillCandidates().get(0).matchedThrough());

        // The statement grows, and now it carries the trade.
        cash.saveRothIra(List.of(trade("2026-09-21", "TSM", -17, 7517.40)), BigDecimal.ZERO);
        assertEquals(1, r.reconcile(GONE));
        assertEquals("Filled", only().state());
    }

    /** A verdict the user wrote is never second-guessed by a later pass. */
    @Test
    void theUsersOwnVerdictIsLeftAlone() {
        OrderFillReconciler r = withVanishedSellAndItsTrade();
        history.saveResolution(only().orderKey(), OrderStatus.CANCELLED,
                OrderHistoryRepository.SOURCE_MANUAL, null, GONE);

        assertEquals(0, r.reconcile(GONE));

        var h = only();
        assertEquals("Cancelled", h.state());
        assertTrue(h.resolvedByUser());
    }

    /**
     * A broker's reversal of a misbooked fill books as cash <em>in</em> on a purchase, so left in
     * the ledger it reads as a sale of exactly the size that was bought — the shape most likely to
     * be mistaken for a resting sell order's fill.
     */
    @Test
    void aBrokersCorrectionPairIsNotMistakenForAFill() {
        OrderFillReconciler r = reconciler();
        OpenOrder order = sell("PEP", "50", "150.00", "Open");
        history.record(ROTH, List.of(order), Set.of(order.identity()), ENTERED);
        history.record(ROTH, List.of(), Set.of(), GONE);
        cash.saveRothIra(List.of(
                trade("2026-09-20", "PEP", 50, -7312.40),     // the purchase
                trade("2026-09-21", "PEP", -50, 7312.40),     // its reversal
                trade("2026-09-21", "PEP", 50, -7305.00)),    // and the corrected re-entry
                BigDecimal.ZERO);

        assertEquals(0, r.reconcile(GONE));
        assertEquals(OrderHistoryRepository.HistoricalOrder.NO_LONGER_LISTED, only().state());
    }
}
