package com.portfolio.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that reads a vanished order's fate out of the cash ledger.
 *
 * <p>The cases are the ones the live data actually contains — a sell that filled better than its
 * limit while nobody was downloading order statuses, a modified Roth order whose ghost must not
 * claim its twin's trade, and a replacement whose fill landed after the original disappeared.
 */
class OrderFillsTest {

    private static final LocalDate ENTERED = LocalDate.parse("2026-09-16");
    private static final String ROTH = "RothIRA";

    private static OrderFills.Order unexplained(String key, String side, String qty, String price,
                                                String closedAt) {
        return new OrderFills.Order(key, ROTH, "ASML", side, new BigDecimal(qty),
                new BigDecimal(price), "USD", ENTERED, LocalDate.parse(closedAt), false, true);
    }

    private static OrderFills.Order settled(String key, String side, String qty, String price,
                                            String closedAt) {
        return new OrderFills.Order(key, ROTH, "ASML", side, new BigDecimal(qty),
                new BigDecimal(price), "USD", ENTERED, LocalDate.parse(closedAt), true, false);
    }

    /** @param consideration signed cash: negative buys, positive sells. */
    private static OrderFills.Trade trade(long rowid, String date, String qty,
                                          String consideration) {
        return new OrderFills.Trade(rowid, LocalDate.parse(date), ROTH, "ASML",
                new BigDecimal(qty), new BigDecimal(consideration), "USD");
    }

    // ---- the ordinary case -------------------------------------------------

    /**
     * The whole point: the order export was downloaded too late to catch the fill, so all it says
     * is that the order is gone. The ledger still has the trade.
     */
    @Test
    void aSellThatFilledBetterThanItsLimitIsMatched() {
        var order = unexplained("k1", "Sell", "5", "1797.00", "2026-09-30");
        var fill = trade(1, "2026-09-29", "-5", "9062.15");   // $1812.43 — better than the limit

        List<OrderFills.Match> matches = OrderFills.match(List.of(order), List.of(fill), Set.of());

        assertEquals(1, matches.size());
        assertEquals("k1", matches.get(0).orderKey());
        assertEquals(1L, matches.get(0).tradeRowid());
    }

    /** A fill through the limit the wrong way is some other trade, however well the rest fits. */
    @Test
    void aSellFilledFarBelowItsLimitIsNotThatOrdersFill() {
        var order = unexplained("k1", "Sell", "5", "1797.00", "2026-09-30");
        var elsewhere = trade(1, "2026-09-29", "-5", "7500.00");  // $1500, 17% through

        assertTrue(OrderFills.match(List.of(order), List.of(elsewhere), Set.of()).isEmpty());
    }

    /**
     * Fees live inside the ledger's cash amount — stamp duty and commission push a purchase's
     * implied price above the limit it was placed at — so a buy is allowed a little the wrong way.
     */
    @Test
    void aBuyIsAllowedTheFeesInsideItsConsideration() {
        var order = new OrderFills.Order("k1", ROTH, "ASML", "Buy", new BigDecimal("50"),
                new BigDecimal("85.05"), "USD", LocalDate.parse("2026-08-27"),
                LocalDate.parse("2026-09-16"), false, true);
        // 50 × 85.05 = 4252.50, plus 0.5% stamp duty and a flat commission.
        var fill = trade(1, "2026-09-09", "50", "-4276.26");

        assertEquals(1, OrderFills.match(List.of(order), List.of(fill), Set.of()).size());
    }

    // ---- what stops a ghost claiming a real fill ---------------------------

    /**
     * Citi restates a Roth order's price to the fill price, and a Roth order's identity is a
     * composite including its price — so one order arrives as two rows, a ghost at the old limit
     * and the real one carrying "Executed". There is one trade and it belongs to the twin.
     */
    @Test
    void aGhostDoesNotClaimTheTradeItsTwinAlreadyOwns() {
        var ghost = unexplained("ghost", "Sell", "10", "534.87", "2026-09-17");
        var real = settled("real", "Sell", "10", "534.93", "2026-09-21");
        var fill = trade(1, "2026-09-17", "-10", "5349.30");

        assertTrue(OrderFills.match(List.of(ghost, real), List.of(fill), Set.of()).isEmpty(),
                "the executed twin claims the trade first; nothing extra happened");
    }

    /** A verdict already written owns its trade across runs, so a second order cannot take it. */
    @Test
    void aTradeAlreadyClaimedByAStoredVerdictIsNotOfferedAgain() {
        var other = unexplained("k2", "Sell", "5", "1797.00", "2026-09-30");
        var fill = trade(7, "2026-09-29", "-5", "9062.15");

        assertTrue(OrderFills.match(List.of(other), List.of(fill), Set.of(7L)).isEmpty());
    }

    /**
     * A modified order disappears when the replacement is issued; the replacement fills later. The
     * upper bound on the window is what keeps the original from claiming its successor's trade.
     */
    @Test
    void aFillAfterTheOrderWasSeenGoneBelongsToWhateverReplacedIt() {
        var original = unexplained("k1", "Buy", "30", "210.10", "2026-09-11");
        var fill = trade(1, "2026-09-14", "30", "-6295.50");

        assertTrue(OrderFills.match(List.of(original), List.of(fill), Set.of()).isEmpty());
    }

    /** Nor can a trade that predates the order be the one the order produced. */
    @Test
    void aTradeFromBeforeTheOrderWasEnteredIsNotItsFill() {
        var order = unexplained("k1", "Sell", "5", "1797.00", "2026-09-30");
        var earlier = trade(1, "2026-09-01", "-5", "9062.15");

        assertTrue(OrderFills.match(List.of(order), List.of(earlier), Set.of()).isEmpty());
    }

    // ---- the fields that have to agree -------------------------------------

    /**
     * Two of the three brokers write quantity unsigned — AJ Bell's "Sale 7,605" and II's "100
     * INTEL" are both positive — so the side is the sign of the <em>cash</em>. Reading it off the
     * quantity would call every AJ Bell sale a purchase.
     */
    @Test
    void theSideIsReadFromTheCashNotFromTheQuantitySign() {
        var sell = unexplained("k1", "Sell", "5", "1797.00", "2026-09-30");
        // Money in, quantity written positive — a sale, in the convention two brokers use.
        var unsignedSale = trade(1, "2026-09-29", "5", "9062.15");

        assertEquals(1, OrderFills.match(List.of(sell), List.of(unsignedSale), Set.of()).size());

        var buy = unexplained("k2", "Buy", "5", "1900.00", "2026-09-30");
        assertTrue(OrderFills.match(List.of(buy), List.of(unsignedSale), Set.of()).isEmpty(),
                "cash came in, so it cannot be a purchase whatever the quantity's sign says");
    }

    /** A partial fill is not this order: claiming it would put a full rung's size on a ladder. */
    @Test
    void aDifferentQuantityIsADifferentTrade() {
        var order = unexplained("k1", "Sell", "5", "1797.00", "2026-09-30");
        var partial = trade(1, "2026-09-29", "-3", "5437.29");

        assertTrue(OrderFills.match(List.of(order), List.of(partial), Set.of()).isEmpty());
    }

    /** The two sides punctuate an instrument differently, so both go through {@code matchKey}. */
    @Test
    void theInstrumentIsComparedThroughMatchKey() {
        var order = new OrderFills.Order("k1", ROTH, "GILT 4.375% 2030", "Sell",
                new BigDecimal("20000"), null, "GBP", ENTERED, LocalDate.parse("2026-09-30"),
                false, true);
        var fill = new OrderFills.Trade(1, LocalDate.parse("2026-09-29"), ROTH, "gilt 4.375 2030",
                new BigDecimal("20000"), new BigDecimal("19936.40"), "GBP");

        assertEquals(1, OrderFills.match(List.of(order), List.of(fill), Set.of()).size());
    }

    /** Another account's trade of the same name and size is not this order's fill. */
    @Test
    void anotherAccountsTradeIsNotThisOrdersFill() {
        var order = unexplained("k1", "Sell", "5", "1797.00", "2026-09-30");
        var elsewhere = new OrderFills.Trade(1, LocalDate.parse("2026-09-29"), "AJBell", "ASML",
                new BigDecimal("-5"), new BigDecimal("9062.15"), "USD");

        assertTrue(OrderFills.match(List.of(order), List.of(elsewhere), Set.of()).isEmpty());
    }

    // ---- what it refuses to decide -----------------------------------------

    /**
     * A limit test it cannot run is not evidence against a match. An II order whose export carried
     * no currency would otherwise be permanently unexplainable.
     */
    @Test
    void anUncomparablePriceDoesNotVetoAnOtherwiseExactMatch() {
        var order = new OrderFills.Order("k1", ROTH, "ASML", "Sell", new BigDecimal("5"),
                new BigDecimal("1797.00"), null, ENTERED, LocalDate.parse("2026-09-30"),
                false, true);
        var fill = trade(1, "2026-09-29", "-5", "9062.15");

        assertEquals(1, OrderFills.match(List.of(order), List.of(fill), Set.of()).size());
    }

    /** Where two trades both fit, the earlier one ended this order and the later one is the next. */
    @Test
    void theEarliestFittingTradeIsTheOneTaken() {
        var order = unexplained("k1", "Sell", "5", "1797.00", "2026-09-30");
        var later = trade(2, "2026-09-29", "-5", "9062.15");
        var earlier = trade(1, "2026-09-20", "-5", "9100.00");

        List<OrderFills.Match> matches =
                OrderFills.match(List.of(order), List.of(later, earlier), Set.of());

        assertEquals(1, matches.size());
        assertEquals(1L, matches.get(0).tradeRowid());
    }

    /** An order nobody is asking about is never given a verdict, however well a trade fits. */
    @Test
    void onlyOrdersAskedAboutGetAVerdict() {
        var notAsked = new OrderFills.Order("k1", ROTH, "ASML", "Sell", new BigDecimal("5"),
                new BigDecimal("1797.00"), "USD", ENTERED, LocalDate.parse("2026-09-30"),
                false, false);
        var fill = trade(1, "2026-09-29", "-5", "9062.15");

        assertTrue(OrderFills.match(List.of(notAsked), List.of(fill), Set.of()).isEmpty());
    }
}
