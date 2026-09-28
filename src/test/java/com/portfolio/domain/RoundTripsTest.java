package com.portfolio.domain;

import com.portfolio.domain.model.Rung;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape of a ladder's next round trip: which way round it goes, which levels the market has to
 * reach, how much quantity that matches and at what spread.
 *
 * <p>No probabilities here — the odds are a first passage over the barriers this class lays out and
 * are pinned in {@code PriceStatsTest} and {@code VolForecastTest}. What these tests are about is
 * the thing a probability cannot be checked without: that the barriers are the right levels.
 */
class RoundTripsTest {

    private static final BigDecimal MARKET = new BigDecimal("100");

    private static Rung rung(String side, String qty, String price, Rung.State state) {
        return new Rung(side + price, null, "II SIPP", "NVDA", side,
                qty == null ? null : new BigDecimal(qty),
                price == null ? null : new BigDecimal(price), "USD", state,
                state == Rung.State.FILLED ? LocalDate.parse("2026-09-01") : null,
                null, null, null, null);
    }

    private static Rung resting(String side, String qty, String price) {
        return rung(side, qty, price, Rung.State.WORKING);
    }

    private static Rung filled(String side, String qty, String price) {
        return rung(side, qty, price, Rung.State.FILLED);
    }

    private static RoundTrips.Plan of(RoundTrips.Next next, RoundTrips.Direction d) {
        return next.plans().stream().filter(p -> p.direction() == d).findFirst().orElse(null);
    }

    // ---- direction ---------------------------------------------------------

    @Test
    void aTwoSidedLadderSupportsBothDirectionsAndTheyAreDifferentPaths() {
        // Sells above, buys below, nothing filled. Sell-then-buy walks up to 106 and back down to
        // 96; buy-then-sell walks down to 96 and back up to 106. Same two rungs, opposite orders,
        // and the panel has to be able to show both — they are different questions.
        RoundTrips.Next next = RoundTrips.next(
                List.of(resting("Sell", "10", "106"), resting("Buy", "10", "96")), MARKET);

        assertEquals(2, next.plans().size());
        RoundTrips.Plan up = of(next, RoundTrips.Direction.SELL_THEN_BUY);
        RoundTrips.Plan down = of(next, RoundTrips.Direction.BUY_THEN_SELL);
        assertEquals(List.of(new BigDecimal("106"), new BigDecimal("96")), up.barriers());
        assertEquals(List.of(new BigDecimal("96"), new BigDecimal("106")), down.barriers());
        // Both make the same money if they complete: the spread is the two levels, not the order.
        assertEquals(0, new BigDecimal("10").compareTo(up.spread()));
        assertEquals(0, new BigDecimal("10").compareTo(down.spread()));
        assertFalse(up.underway());
    }

    @Test
    void aOneSidedLadderHasNoRoundTripAndSaysWhy() {
        RoundTrips.Next next = RoundTrips.next(
                List.of(resting("Buy", "10", "96"), resting("Buy", "10", "92")), MARKET);

        assertFalse(next.any());
        assertNotNull(next.unavailable());
        assertTrue(next.unavailable().contains("nothing on the other side"), next.unavailable());
    }

    // ---- what a filled leg does --------------------------------------------

    @Test
    void aFilledLegLeavesOneEventRatherThanTwo() {
        // The ask in one test: when a leg fills the ladder needs one event, not two, and the figure
        // has to be about what remains. The sell is gone; all that is left is the buy back.
        RoundTrips.Next next = RoundTrips.next(
                List.of(filled("Sell", "10", "106"), resting("Buy", "10", "96")), MARKET);

        assertEquals(1, next.plans().size());
        RoundTrips.Plan p = next.plans().get(0);
        assertTrue(p.underway());
        assertEquals(RoundTrips.Direction.SELL_THEN_BUY, p.direction());
        assertEquals(1, p.legs());
        assertEquals(List.of(new BigDecimal("96")), p.barriers());
        // And it starts from the market, not from the price the sell went off at.
        assertEquals(0, MARKET.compareTo(p.start()));
        assertEquals(0, new BigDecimal("106").compareTo(p.openPrice()));
    }

    @Test
    void aFilledBuyOpensTheOtherDirection() {
        RoundTrips.Next next = RoundTrips.next(
                List.of(filled("Buy", "10", "96"), resting("Sell", "10", "106")), MARKET);

        RoundTrips.Plan p = next.plans().get(0);
        assertEquals(RoundTrips.Direction.BUY_THEN_SELL, p.direction());
        assertTrue(p.underway());
        assertEquals(List.of(new BigDecimal("106")), p.barriers());
    }

    @Test
    void aCompletedTripLeavesNothingOpenAndTheNextOneNeedsBothLegsAgain() {
        // 10 sold and 10 bought back is a finished round trip: it is counted in Ladders.totals and
        // has no bearing on the next one, which starts from scratch over the rungs still resting.
        RoundTrips.Next next = RoundTrips.next(List.of(
                filled("Sell", "10", "106"), filled("Buy", "10", "96"),
                resting("Sell", "10", "110"), resting("Buy", "10", "92")), MARKET);

        RoundTrips.Plan p = of(next, RoundTrips.Direction.SELL_THEN_BUY);
        assertFalse(p.underway());
        assertEquals(2, p.legs());
        assertEquals(List.of(new BigDecimal("110"), new BigDecimal("92")), p.barriers());
    }

    @Test
    void anUnmatchedRemainderIsWhatIsStillOpenRatherThanTheWholeFilledSide() {
        // 20 sold against 4 bought back: 4 are a completed trip and 16 are still open. The next trip
        // closes the 16, and the only rung that can is the buy of 20 — matched at 16, since the
        // excess is a position change and not part of this trip.
        RoundTrips.Next next = RoundTrips.next(List.of(
                filled("Sell", "20", "106"), filled("Buy", "4", "96"),
                resting("Buy", "20", "94")), MARKET);

        RoundTrips.Plan p = next.plans().get(0);
        assertTrue(p.underway());
        assertEquals(0, new BigDecimal("16").compareTo(p.quantity()));
    }

    // ---- matching by quantity ----------------------------------------------

    @Test
    void theClosingBarrierIsTheFurthestRungTheMatchNeeds() {
        // Sell 30, and the buys are 10 at 98, 10 at 96 and 10 at 94. Matching 30 needs all three, so
        // the level the trip completes at is 94 — the furthest. Price is continuous, so reaching 94
        // means 98 and 96 were passed on the way, which is why one barrier stands for three rungs.
        RoundTrips.Next next = RoundTrips.next(List.of(
                resting("Sell", "30", "106"), resting("Buy", "10", "98"),
                resting("Buy", "10", "96"), resting("Buy", "10", "94")), MARKET);

        RoundTrips.Plan p = of(next, RoundTrips.Direction.SELL_THEN_BUY);
        assertEquals(List.of(new BigDecimal("106"), new BigDecimal("94")), p.barriers());
        assertEquals(0, new BigDecimal("30").compareTo(p.quantity()));
        // Bought back at the average of the three, not at the barrier: 96.
        assertEquals(0, new BigDecimal("96").compareTo(p.buyPrice().stripTrailingZeros()));
        assertEquals(0, new BigDecimal("10").compareTo(p.spread()));
    }

    @Test
    void aPartRungIsTakenWhenThatIsAllTheMatchNeeds() {
        // Sell 15 against buys of 10 at 98 and 10 at 96. The match completes 5 into the second rung,
        // so the barrier is 96 and the average is (10×98 + 5×96)/15 — the rest of that rung is a
        // position change, and pricing the whole of it in would overstate the size and the spread.
        RoundTrips.Next next = RoundTrips.next(List.of(
                resting("Sell", "15", "106"), resting("Buy", "10", "98"),
                resting("Buy", "10", "96")), MARKET);

        RoundTrips.Plan p = of(next, RoundTrips.Direction.SELL_THEN_BUY);
        assertEquals(0, new BigDecimal("15").compareTo(p.quantity()));
        assertEquals(new BigDecimal("96"), p.barriers().get(1));
        assertEquals(97.3333, p.buyPrice().doubleValue(), 1e-4);
    }

    @Test
    void theMatchIsCappedByWhicheverSideOffersLess() {
        // Sell 30 against a single buy of 10: only 10 make a round trip. The other 20 are a position
        // change and are deliberately not priced as part of one.
        RoundTrips.Next next = RoundTrips.next(List.of(
                resting("Sell", "30", "106"), resting("Buy", "10", "96")), MARKET);

        RoundTrips.Plan p = of(next, RoundTrips.Direction.SELL_THEN_BUY);
        assertEquals(0, new BigDecimal("10").compareTo(p.quantity()));
    }

    @Test
    void theOpeningLegIsTheRungTheMarketReachesFirst() {
        // Three sells above the market: the trip opens at 102, the nearest, whether or not that is
        // the one the reader would have picked. The first fill is not a choice.
        RoundTrips.Next next = RoundTrips.next(List.of(
                resting("Sell", "10", "110"), resting("Sell", "10", "102"),
                resting("Sell", "10", "106"), resting("Buy", "10", "96")), MARKET);

        RoundTrips.Plan p = of(next, RoundTrips.Direction.SELL_THEN_BUY);
        assertEquals(new BigDecimal("102"), p.barriers().get(0));
    }

    // ---- rungs on the wrong side of the market ------------------------------

    @Test
    void aRungAlreadyThroughTheMarketIsNotSomethingToWaitFor() {
        // A sell at 98 with the market at 100 is marketable: it fills, it is not a barrier. So the
        // trip has one leg left — the buy back — and that leg starts from the market rather than
        // from the sell's limit, because the market is where the market is.
        RoundTrips.Next next = RoundTrips.next(List.of(
                resting("Sell", "10", "98"), resting("Buy", "10", "94")), MARKET);

        RoundTrips.Plan p = of(next, RoundTrips.Direction.SELL_THEN_BUY);
        assertEquals(List.of(new BigDecimal("94")), p.barriers());
        assertEquals(0, MARKET.compareTo(p.start()));
        assertFalse(p.underway(), "nothing has filled — it is one leg because one is marketable");
    }

    @Test
    void bothLegsThroughTheMarketIsNothingLeftToWaitFor() {
        RoundTrips.Next next = RoundTrips.next(List.of(
                resting("Sell", "10", "98"), resting("Buy", "10", "102")), MARKET);

        RoundTrips.Plan p = of(next, RoundTrips.Direction.SELL_THEN_BUY);
        assertEquals(0, p.legs());
        // And it loses money, which is exactly why it is shown rather than clamped away.
        assertTrue(p.spread().signum() < 0);
    }

    @Test
    void aLosingRoundTripIsPricedRatherThanHidden() {
        // The buy-back sits above the sell. Completing this trip costs money, and a floor at zero
        // would hide the one arrangement the reader most needs to see.
        RoundTrips.Next next = RoundTrips.next(List.of(
                resting("Sell", "10", "104"), resting("Buy", "10", "108")), MARKET);

        RoundTrips.Plan p = of(next, RoundTrips.Direction.SELL_THEN_BUY);
        assertEquals(0, new BigDecimal("-4").compareTo(p.spread()));
        assertEquals(0, new BigDecimal("-40").compareTo(p.gross()));
    }

    // ---- what cannot be computed -------------------------------------------

    @Test
    void aRungMissingAFigureIsNamedRatherThanAbsorbed() {
        RoundTrips.Next next = RoundTrips.next(List.of(
                resting("Sell", "10", "106"), resting("Buy", null, "96"),
                resting("Buy", "10", "94")), MARKET);

        assertEquals(1, next.excluded().size());
        assertTrue(next.excluded().get(0).contains("no quantity"), next.excluded().get(0));
        // The rung is dropped from the matching, not the whole figure: 94 is the barrier now.
        assertEquals(new BigDecimal("94"),
                of(next, RoundTrips.Direction.SELL_THEN_BUY).barriers().get(1));
    }

    @Test
    void noMarketPriceMeansNoPathToPrice() {
        RoundTrips.Next next = RoundTrips.next(
                List.of(resting("Sell", "10", "106"), resting("Buy", "10", "96")), null);

        assertFalse(next.any());
        assertTrue(next.unavailable().contains("No live price"), next.unavailable());
    }

    @Test
    void aCancelledRungIsCountedInNothing() {
        RoundTrips.Next next = RoundTrips.next(List.of(
                resting("Sell", "10", "106"), rung("Buy", "10", "96", Rung.State.CANCELLED)), MARKET);

        assertFalse(next.any());
        assertTrue(next.excluded().isEmpty(), "a cancelled rung is not an exclusion, it is over");
    }

    @Test
    void anOpenLegWithNothingToCloseItSaysSo() {
        RoundTrips.Next next = RoundTrips.next(List.of(filled("Sell", "10", "106")), MARKET);

        assertFalse(next.any());
        assertNotNull(next.unavailable());
        assertTrue(next.unavailable().contains("no buy rung"), next.unavailable());
    }

    @Test
    void anEmptyLadderIsNotAnError() {
        RoundTrips.Next next = RoundTrips.next(List.of(), MARKET);
        assertFalse(next.any());
        assertNull(of(next, RoundTrips.Direction.SELL_THEN_BUY));
        assertNotNull(next.unavailable());
    }
}
