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
     * The terminal set is exactly what the open/closed filter used before this enum existed —
     * normalising the display must not quietly remove orders from the table.
     */
    @Test
    void theTerminalSetIsUnchangedFromTheFilterItReplaced() {
        for (String terminal : new String[]{"dealt", "executed", "filled", "part executed",
                "cancelled", "canceled", "expired", "rejected", "completed", "done", "settled"}) {
            assertFalse(OrderStatus.of(terminal).open(), terminal + " should be terminal");
        }
    }
}
