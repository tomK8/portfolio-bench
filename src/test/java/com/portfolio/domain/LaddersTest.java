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

class LaddersTest {

    private static Rung rung(String account, String side, String qty, String price,
                             Rung.State state) {
        return new Rung(account + side + price, null, account, side, new BigDecimal(qty),
                price == null ? null : new BigDecimal(price), "USD", state,
                state == Rung.State.FILLED ? LocalDate.parse("2026-09-01") : null, null, null);
    }

    private static List<String> prices(List<Rung> rungs) {
        return rungs.stream().map(r -> r.price() == null ? "—" : r.price().toPlainString()).toList();
    }

    // ---- the price axis ----------------------------------------------------

    /** Highest first, so sells sit above buys and the market can be drawn between them. */
    @Test
    void rungsAreOrderedByPriceHighestFirst() {
        List<Rung> ordered = Ladders.byPrice(List.of(
                rung("Roth IRA", "Buy", "20", "193.73", Rung.State.WORKING),
                rung("II SIPP", "Sell", "30", "246.97", Rung.State.WORKING),
                rung("Roth IRA", "Buy", "20", "203.18", Rung.State.WORKING)));

        assertEquals(List.of("246.97", "203.18", "193.73"), prices(ordered));
    }

    /**
     * Strictly by price, not sells-then-buys. The two only differ when a rung is on the wrong side
     * of the market, and that is the state worth seeing — the same fact the orders table flags in
     * amber. A layout that always drew sells on top would hide it behind a tidy picture.
     */
    @Test
    void aRungOnTheWrongSideOfTheMarketStaysWhereItsPricePutsIt() {
        List<Rung> ordered = Ladders.byPrice(List.of(
                rung("Roth IRA", "Buy", "10", "260.00", Rung.State.WORKING),   // above the market
                rung("II SIPP", "Sell", "30", "246.97", Rung.State.WORKING)));

        assertEquals(List.of("260.00", "246.97"), prices(ordered));
        assertEquals(2, Ladders.markerIndex(ordered, new BigDecimal("212.33")),
                "both sit above a 212.33 market, wrong-sided buy included");
    }

    /** A rung with no price can't be placed on the axis, so it sorts last rather than at zero. */
    @Test
    void anUnpricedRungSortsLast() {
        List<Rung> ordered = Ladders.byPrice(List.of(
                rung("Roth IRA", "Buy", "10", null, Rung.State.INTENDED),
                rung("II SIPP", "Sell", "30", "246.97", Rung.State.WORKING)));

        assertEquals(List.of("246.97", "—"), prices(ordered));
    }

    @Test
    void theMarkerSitsBetweenTheRungsAboveTheMarketAndThoseBelow() {
        List<Rung> ordered = Ladders.byPrice(List.of(
                rung("II SIPP", "Sell", "30", "246.97", Rung.State.WORKING),
                rung("Roth IRA", "Buy", "20", "203.18", Rung.State.WORKING),
                rung("Roth IRA", "Buy", "20", "193.73", Rung.State.WORKING)));

        assertEquals(1, Ladders.markerIndex(ordered, new BigDecimal("212.33")));
    }

    /** No quote, no marker — never one at an invented position. */
    @Test
    void withNoCurrentPriceThereIsNoMarker() {
        List<Rung> ordered = Ladders.byPrice(List.of(
                rung("II SIPP", "Sell", "30", "246.97", Rung.State.WORKING)));

        assertEquals(-1, Ladders.markerIndex(ordered, null));
    }

    // ---- what the ladder comes to -----------------------------------------

    /** Only rungs that can still fill move the position — a filled one already has. */
    @Test
    void netPositionCountsRemainingRungsOnly() {
        Ladders.Totals t = Ladders.totals(List.of(
                rung("II SIPP", "Sell", "30", "246.97", Rung.State.WORKING),
                rung("Roth IRA", "Buy", "20", "203.18", Rung.State.WORKING),
                rung("Roth IRA", "Buy", "25", "175.00", Rung.State.INTENDED),
                rung("Roth IRA", "Buy", "30", "209.85", Rung.State.FILLED),
                rung("II SIPP", "Buy", "99", "210.65", Rung.State.CANCELLED)));

        assertEquals(0, new BigDecimal("15").compareTo(t.netQuantity()), "-30 +20 +25");
        assertEquals(3, t.remainingCount());
        assertEquals(1, t.filledCount());
    }

    /** An intended rung is a commitment like any other — that is the whole reason it is recorded. */
    @Test
    void anIntendedRungCountsInTheNetPositionAndTheCash() {
        Ladders.Totals t = Ladders.totals(List.of(
                rung("Roth IRA", "Buy", "25", "175.00", Rung.State.INTENDED)));

        assertEquals(0, new BigDecimal("25").compareTo(t.netQuantity()));
        assertEquals(1, t.cash().size());
        assertEquals(0, new BigDecimal("4375.00").compareTo(t.cash().get(0).needs()));
    }

    /**
     * Cash is per account and never netted across them. A ladder that raises in one wrapper and
     * spends in another is not cash-neutral — the money cannot move, and one figure would say it
     * could. This is the mistake the panel exists to stop.
     */
    @Test
    void cashIsBrokenOutPerAccountAndNotNettedAcrossThem() {
        Ladders.Totals t = Ladders.totals(List.of(
                rung("II SIPP", "Sell", "30", "246.97", Rung.State.WORKING),
                rung("Roth IRA", "Buy", "20", "203.18", Rung.State.WORKING),
                rung("Roth IRA", "Buy", "20", "193.73", Rung.State.WORKING)));

        assertEquals(2, t.cash().size());
        Ladders.CashLine ii = t.cash().stream().filter(c -> c.account().equals("II SIPP")).findFirst().orElseThrow();
        Ladders.CashLine roth = t.cash().stream().filter(c -> c.account().equals("Roth IRA")).findFirst().orElseThrow();

        assertEquals(0, new BigDecimal("7409.10").compareTo(ii.raises()));
        assertEquals(0, BigDecimal.ZERO.compareTo(ii.needs()));
        assertEquals(0, new BigDecimal("7938.20").compareTo(roth.needs()));
        assertTrue(roth.net().signum() < 0, "the Roth is short by the whole purchase");
    }

    /** A rung that can't be costed is dropped from the figure and named, never summed as zero. */
    @Test
    void anUnpricedRungIsExcludedFromTheCashAndReported() {
        Ladders.Totals t = Ladders.totals(List.of(
                rung("Roth IRA", "Buy", "20", "203.18", Rung.State.WORKING),
                rung("Roth IRA", "Buy", "25", null, Rung.State.INTENDED)));

        assertEquals(0, new BigDecimal("4063.60").compareTo(t.cash().get(0).needs()),
                "only the priced rung is in the total");
        assertEquals(1, t.excluded().size());
        assertTrue(t.excluded().get(0).contains("no price"), t.excluded().toString());
        assertEquals(0, new BigDecimal("45").compareTo(t.netQuantity()),
                "the quantity is known even when the price is not");
    }

    // ---- the round trip ----------------------------------------------------

    /** The thing the panel is for: a sell that filled and the buy that closed it later. */
    @Test
    void bothSidesFilledMakeARoundTripOnTheMatchedQuantity() {
        Ladders.Totals t = Ladders.totals(List.of(
                rung("II SIPP", "Sell", "20", "233.90", Rung.State.FILLED),
                rung("Roth IRA", "Buy", "30", "209.85", Rung.State.FILLED)));

        Ladders.RoundTrip trip = t.roundTrip();
        assertNotNull(trip);
        assertEquals(0, new BigDecimal("20").compareTo(trip.matchedQuantity()),
                "the smaller side is what actually made a trip");
        assertEquals(0, new BigDecimal("24.05").compareTo(trip.perShare()));
        assertEquals(0, new BigDecimal("481.00").compareTo(trip.total()));
        assertTrue(t.complete(), "nothing left resting and both sides traded");
    }

    /** One side alone is a position that moved, not a trip — and is not priced as one. */
    @Test
    void oneSideFilledIsNotARoundTrip() {
        Ladders.Totals t = Ladders.totals(List.of(
                rung("II SIPP", "Sell", "20", "233.90", Rung.State.FILLED),
                rung("Roth IRA", "Buy", "30", "209.85", Rung.State.WORKING)));

        assertNull(t.roundTrip());
        assertFalse(t.complete());
    }

    /** A trip is only complete when nothing is still waiting to fill. */
    @Test
    void aRestingRungKeepsTheTripIncomplete() {
        Ladders.Totals t = Ladders.totals(List.of(
                rung("II SIPP", "Sell", "20", "233.90", Rung.State.FILLED),
                rung("Roth IRA", "Buy", "30", "209.85", Rung.State.FILLED),
                rung("Roth IRA", "Buy", "25", "175.00", Rung.State.INTENDED)));

        assertNotNull(t.roundTrip());
        assertFalse(t.complete(), "an intended rung is still to come");
    }

    /** Cancelled rungs stay on the ladder for the record and are counted in nothing. */
    @Test
    void aCancelledRungMovesNoPositionAndNeedsNoCash() {
        Ladders.Totals t = Ladders.totals(List.of(
                rung("II SIPP", "Buy", "30", "210.65", Rung.State.CANCELLED),
                rung("II SIPP", "Buy", "20", "193.86", Rung.State.UNLISTED)));

        assertEquals(0, BigDecimal.ZERO.compareTo(t.netQuantity()));
        assertTrue(t.cash().isEmpty());
        assertEquals(0, t.remainingCount());
        assertNull(t.roundTrip());
    }
}
