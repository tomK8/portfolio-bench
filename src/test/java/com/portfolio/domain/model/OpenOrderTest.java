package com.portfolio.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class OpenOrderTest {

    /** A limit order with everything the flag reads and nothing it doesn't. */
    private static OpenOrder order(String side, String limit, String current, boolean estimated) {
        return new OpenOrder("AJ Bell SIPP", "SGLN", "iShares Physical Gold", side, "Limit",
                new BigDecimal("50"), limit == null ? null : new BigDecimal(limit), estimated,
                null, "GBP", null, null, "GTC", "In progress", null, null,
                current == null ? null : new BigDecimal(current),
                null, null, null, null, null, null);
    }

    @Test
    void restingBuyBelowTheMarketIsNormal() {
        // The ordinary case: limit 60.71 with the market at 62.44 — it waits for a dip.
        assertFalse(order("Buy", "60.71", "62.44", false).throughLimit());
    }

    @Test
    void buyLimitAboveTheMarketIsFlagged() {
        // SGLN, 2026-08-19: a £63.21 buy limit against a £62.44 quote. A buy fills at the limit
        // or better, so this should already have executed — the quote was a day-old close.
        assertTrue(order("Buy", "63.21", "62.44", false).throughLimit());
    }

    @Test
    void sellLimitBelowTheMarketIsFlagged() {
        assertTrue(order("Sell", "80.00", "85.18", false).throughLimit());
        assertFalse(order("Sell", "89.94", "85.18", false).throughLimit());
    }

    @Test
    void atTheLimitCounts() {
        assertTrue(order("Buy", "62.44", "62.44", false).throughLimit());
        assertTrue(order("Sell", "62.44", "62.44", false).throughLimit());
    }

    @Test
    void estimatedLimitNeedsMarginBeforeItCounts() {
        // II's limit is amount÷qty with the dealing fee inside it, so a buy's implied limit reads
        // high. A hair through must not flag; a real move must.
        assertFalse(order("Buy", "100.20", "100.00", true).throughLimit());
        assertTrue(order("Buy", "101.00", "100.00", true).throughLimit());
        assertFalse(order("Sell", "99.80", "100.00", true).throughLimit());
        assertTrue(order("Sell", "99.00", "100.00", true).throughLimit());
    }

    @Test
    void missingPieceNeverFlags() {
        assertFalse(order("Buy", null, "62.44", false).throughLimit());
        assertFalse(order("Buy", "63.21", null, false).throughLimit());
        assertFalse(order(null, "63.21", "62.44", false).throughLimit());
        assertFalse(order("Buy", "0", "62.44", false).throughLimit());
    }
}
