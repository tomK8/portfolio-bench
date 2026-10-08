package com.portfolio.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderStatusTest {

    /** The six spellings that were on screen at once, and the three outcomes behind them. */
    @Test
    void theSixSpellingsThreeBrokersWriteCollapseToThreeStates() {
        assertEquals(OrderStatus.FILLED, OrderStatus.of("Dealt"));          // AJ Bell
        assertEquals(OrderStatus.FILLED, OrderStatus.of("Executed"));       // Citi Roth
        assertEquals(OrderStatus.FILLED, OrderStatus.of("EXECUTED"));       // II
        assertEquals(OrderStatus.CANCELLED, OrderStatus.of("CANCELLED"));   // II
        assertEquals(OrderStatus.CANCELLED, OrderStatus.of("Canceled"));    // Citi Roth, one l
        assertEquals(OrderStatus.WORKING, OrderStatus.of("Open"));          // Citi Roth

        assertEquals("Filled", OrderStatus.FILLED.label());
        assertEquals("Cancelled", OrderStatus.CANCELLED.label());
    }

    /** Case, padding and whichever separator the broker chose are noise, not a different status. */
    @Test
    void spellingIsMatchedIgnoringCaseSpacingAndPunctuation() {
        assertEquals(OrderStatus.WORKING, OrderStatus.of("  in progress "));
        assertEquals(OrderStatus.WORKING, OrderStatus.of("In-Progress"));
        assertEquals(OrderStatus.PART_FILLED, OrderStatus.of("Part-Executed"));
        assertEquals(OrderStatus.PART_FILLED, OrderStatus.of("PART  EXECUTED"));
    }

    /**
     * The denylist invariant: anything unrecognised is open, so a broker's unseen "still working"
     * wording is shown rather than silently dropped from the panel.
     */
    @Test
    void anUnrecognisedOrAbsentStatusIsUnknownAndCountsAsOpen() {
        assertEquals(OrderStatus.UNKNOWN, OrderStatus.of("Awaiting market"));
        assertEquals(OrderStatus.UNKNOWN, OrderStatus.of(null));
        assertEquals(OrderStatus.UNKNOWN, OrderStatus.of("   "));
        assertTrue(OrderStatus.UNKNOWN.open());
        assertTrue(OrderStatus.WORKING.open());
    }

    /**
     * The state that is open but not resting. Citi writes it on the order you have just modified
     * and sends the replacement in the same export, so the panel listed one decision as two.
     */
    @Test
    void aPendingCancelIsOpenButNotResting() {
        assertEquals(OrderStatus.PENDING_CANCEL, OrderStatus.of("Pending Cancel"));
        assertEquals(OrderStatus.PENDING_CANCEL, OrderStatus.of("PENDING CANCEL"));
        assertEquals(OrderStatus.PENDING_CANCEL, OrderStatus.of("Pending-Cancel"));
        assertEquals("Cancelling", OrderStatus.PENDING_CANCEL.label());

        // Open: the broker has not said the order is gone, so it is never filed into the history as
        // a cancellation nobody confirmed, the ledger is still asked to explain it, and a
        // marketable limit can still trade before the cancel takes.
        assertTrue(OrderStatus.PENDING_CANCEL.open());
        assertFalse(OrderStatus.PENDING_CANCEL.terminal());
        // Not resting: it is not one of the orders the user is working.
        assertFalse(OrderStatus.PENDING_CANCEL.resting());
    }

    /**
     * AJ Bell writes a bare "Pending" on an order awaiting the market — nothing to do with a
     * cancellation. The two must not collapse, or every AJ Bell order would leave the table.
     */
    @Test
    void aBarePendingIsStillAWorkingOrder() {
        assertEquals(OrderStatus.WORKING, OrderStatus.of("Pending"));
        assertTrue(OrderStatus.WORKING.resting());
        assertTrue(OrderStatus.UNKNOWN.resting(), "an unseen wording is shown, not hidden");
    }

    /**
     * The terminal set is exactly what the open/closed filter used before this enum existed —
     * normalising the display must not quietly remove orders from the table.
     */
    @Test
    void theTerminalSetIsUnchangedFromTheFilterItReplaced() {
        for (String terminal : new String[]{"dealt", "executed", "filled", "part executed",
                "cancelled", "canceled", "expired", "rejected", "completed", "done", "settled"}) {
            assertFalse(OrderStatus.of(terminal).open(), terminal + " should be terminal");
        }
        // And nothing has been added to it since: "Pending Cancel" narrowed what the table lists,
        // not what the history treats as settled. Removing an order from the panel on a word the
        // broker has not finished saying is the thing this set exists to prevent.
        for (OrderStatus s : OrderStatus.values()) {
            if (s.terminal()) assertFalse(s.resting(), s + " is over, so it cannot be resting");
        }
        assertFalse(OrderStatus.PENDING_CANCEL.terminal());
    }
}
