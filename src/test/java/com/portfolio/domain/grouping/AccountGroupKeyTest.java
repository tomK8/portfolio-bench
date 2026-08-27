package com.portfolio.domain.grouping;

import com.portfolio.domain.model.AggHolding;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountGroupKeyTest {

    private final AccountGroupKey key = new AccountGroupKey();

    private static AggHolding held(String symbol, String sources) {
        return new AggHolding(symbol, BigDecimal.ONE, null, new BigDecimal("100"),
                BigDecimal.TEN, null, BigDecimal.ZERO, BigDecimal.TEN, null,
                Currency.getInstance("GBP"), sources, null, null, null, null);
    }

    @Test
    void groupsBySourceLabel() {
        HoldingGroupKey.Group g = key.keyFor(held("AAPL", "Roth IRA"));
        assertEquals("Roth IRA", g.label());
        assertFalse(g.residual());
        assertNull(g.note(), "a single-account position needs no explanation");
    }

    @Test
    void positionsInTheSameAccountShareOneGroup() {
        assertEquals(key.keyFor(held("AAPL", "Roth IRA")).id(),
                key.keyFor(held("MSFT", "Roth IRA")).id());
    }

    /**
     * The ledger view aggregates a symbol across accounts into one row, so a shared position has
     * no per-account half to file. It forms its own group and says so rather than being assigned
     * to one of the two.
     */
    @Test
    void aPositionHeldAcrossAccountsFormsItsOwnGroupAndExplainsItself() {
        HoldingGroupKey.Group shared = key.keyFor(held("GOOG", "AJ Bell SIPP, II SIPP"));

        assertEquals("AJ Bell SIPP + II SIPP", shared.label());
        assertNotNull(shared.note());
        assertFalse(shared.residual(), "a shared position is placed, not unplaceable");
        assertEquals(List.of("aj-bell-sipp", "ii-sipp"),
                List.of(key.keyFor(held("X", "AJ Bell SIPP")).id(),
                        key.keyFor(held("Y", "II SIPP")).id()));
        assertFalse(shared.id().equals(key.keyFor(held("X", "AJ Bell SIPP")).id()));
    }

    /**
     * {@code sources} is filled in trade order, so the same pair of accounts genuinely arrives
     * both ways round — the live book carries "II SIPP, AJ Bell SIPP" on one position and
     * "Roth IRA, AJ Bell SIPP, II SIPP" on another. Keyed as they stand, one set of accounts
     * would split into groups a reader cannot tell apart.
     */
    @Test
    void theSameAccountsInAnyOrderAreOneGroup() {
        HoldingGroupKey.Group a = key.keyFor(held("GOOG", "AJ Bell SIPP, II SIPP"));
        HoldingGroupKey.Group b = key.keyFor(held("MSFT", "II SIPP, AJ Bell SIPP"));

        assertEquals(a.id(), b.id());
        assertEquals(a.label(), b.label());
    }

    @Test
    void aRepeatedAccountDoesNotAppearTwiceInTheLabel() {
        assertEquals("II SIPP", key.keyFor(held("AAA", "II SIPP, II SIPP")).label());
    }

    @Test
    void theNoteCountsTheAccountsItSpans() {
        assertTrue(key.keyFor(held("GOOG", "Roth IRA, AJ Bell SIPP, II SIPP"))
                .note().contains("3 accounts"));
    }

    /** The id becomes a DOM attribute the expand/collapse toggle keys on. */
    @Test
    void idIsSafeForAnAttributeEvenThoughLabelsCarrySpacesAndCommas() {
        assertTrue(key.keyFor(held("GOOG", "AJ Bell SIPP, II SIPP")).id()
                .matches("[a-z0-9-]+"));
    }

    @Test
    void aHoldingWithNoAccountIsResidualRatherThanDropped() {
        HoldingGroupKey.Group blank = key.keyFor(held("MYSTERY", "  "));
        assertTrue(blank.residual());
        assertNotNull(blank.note());
        assertEquals(blank.id(), key.keyFor(held("OTHER", null)).id());
    }
}
