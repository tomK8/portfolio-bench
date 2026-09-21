package com.portfolio.application;

import com.portfolio.domain.model.OpenOrder;
import com.portfolio.domain.model.Rung;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.OrderGroupRepository;
import com.portfolio.persistence.OrderHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LadderServiceTest {

    private static final LocalDate DAY = LocalDate.parse("2026-09-01");
    private static final List<String> ACCOUNTS = List.of("AJ Bell SIPP", "II SIPP", "Roth IRA");

    /**
     * A stand-in for the real first-passage model: the probability is just the horizon, scaled, so a
     * test can tell the two fixed columns apart without pinning volatility maths that has its own
     * tests. What matters here is that each rung is priced at both horizons and a closed one at
     * neither.
     */
    private static final GroupRungs.TouchPricer TOUCH =
            (order, sessions) -> order == null || order.price() == null
                    ? null : new BigDecimal(sessions).movePointLeft(2);

    @TempDir
    Path dbDir;

    private OrderGroupRepository groups;
    private OrderHistoryRepository history;

    private LadderService service() {
        JdbcConnectionFactory conn = new JdbcConnectionFactory(dbDir);
        groups = new OrderGroupRepository(conn);
        history = new OrderHistoryRepository(conn);
        return new LadderService(groups, history);
    }

    private static OpenOrder order(String broker, String symbol, String side, String qty,
                                   String price, String reference) {
        return OpenOrder.parsed(broker, symbol, null, side, "Limit", new BigDecimal(qty),
                new BigDecimal(price), false, new BigDecimal(qty).multiply(new BigDecimal(price)),
                "USD", DAY, null, null, "PENDING", reference);
    }

    // ---- assembly ----------------------------------------------------------

    /**
     * The shape the panel was asked for: a sell in one account paired with buys in another, laid
     * out by price with the market between them.
     */
    @Test
    void aLadderSpansBrokersAndIsLaidOutByPrice() {
        LadderService s = service();
        long id = s.createLadder("NVDA round trip", "NVDA");
        OpenOrder sell = order("II SIPP", "NVDA", "Sell", "30", "246.97", "REF1");
        OpenOrder buy = order("Roth IRA", "NVDA", "Buy", "20", "203.18", "REF2");
        s.addRungs(id, List.of(sell.identity(), buy.identity()));

        var report = s.assemble(List.of(sell, buy), List.of(), List.of(), Map.of(), ACCOUNTS, TOUCH);

        assertEquals(1, report.ladders().size());
        var ladder = report.ladders().get(0);
        assertEquals(List.of("II SIPP", "Roth IRA"),
                ladder.rungs().stream().map(Rung::account).toList());
        assertEquals(0, new BigDecimal("-10").compareTo(ladder.totals().netQuantity()),
                "-30 sold, +20 bought");
        assertEquals(2, ladder.totals().cash().size(), "one cash line per account, never netted");
    }

    /**
     * Membership is only ever what the user assigned. Two orders on the ladder's own symbol, one
     * assigned and one not, and nothing pulls the second in.
     */
    @Test
    void anOrderOnTheSameSymbolIsNotPulledInByItself() {
        LadderService s = service();
        long id = s.createLadder("NVDA round trip", "NVDA");
        OpenOrder assigned = order("Roth IRA", "NVDA", "Buy", "20", "203.18", "REF1");
        OpenOrder loose = order("Roth IRA", "NVDA", "Buy", "20", "193.73", "REF2");
        s.addRungs(id, List.of(assigned.identity()));

        var report = s.assemble(List.of(assigned, loose), List.of(), List.of(), Map.of(), ACCOUNTS, TOUCH);

        assertEquals(1, report.ladders().get(0).rungs().size());
        assertEquals(List.of(loose.identity()),
                report.unassigned().stream().map(GroupRungs.Candidate::key).toList());
    }

    /** The picker offers this ladder's security only — the rule of this group type, in one place. */
    @Test
    void thePickerOffersOrdersOnTheLaddersOwnSecurity() {
        LadderService s = service();
        long id = s.createLadder("NVDA round trip", "NVDA");
        OpenOrder nvda = order("Roth IRA", "NVDA", "Buy", "20", "193.73", "REF1");
        OpenOrder mrvl = order("Roth IRA", "MRVL", "Buy", "30", "195.10", "REF2");

        var report = s.assemble(List.of(nvda, mrvl), List.of(), List.of(), Map.of(), ACCOUNTS, TOUCH);

        assertEquals(2, report.unassigned().size());
        assertEquals(List.of(nvda.identity()),
                report.assignableFor(report.ladders().get(0)).stream()
                        .map(GroupRungs.Candidate::key).toList());
    }

    // ---- intended rungs ----------------------------------------------------

    /**
     * An intended rung becomes a synthetic order so the caller's enrichment prices it exactly as it
     * prices a resting one — which is what "the same analytics" has to mean to stay true.
     */
    @Test
    void anIntendedRungIsHandedOutAsAnOrderForTheSameEnrichment() {
        LadderService s = service();
        long id = s.createLadder("NVDA round trip", "NVDA");
        long intentId = s.addIntent(id, "Roth IRA", "Buy", new BigDecimal("25"),
                new BigDecimal("175.00"), "needs the II sell to settle first");

        List<OpenOrder> synthetic = s.intendedOrders(sym -> "USD");

        assertEquals(1, synthetic.size());
        OpenOrder o = synthetic.get(0);
        assertEquals("Roth IRA", o.broker());
        assertEquals("NVDA", o.symbol());
        assertEquals("USD", o.currency(), "the currency comes from the quote, not from storage");
        assertEquals(0, new BigDecimal("4375.00").compareTo(o.amount()));
        assertEquals("GTC", o.duration(), "an intent has no deadline, so it is priced like a GTC order");
        assertEquals("Roth IRA|intent:" + intentId, o.identity());
    }

    /** An instrument that has never priced yields no currency — and no guessed one. */
    @Test
    void anIntendedRungOnAnUnpricedInstrumentCarriesNoCurrency() {
        LadderService s = service();
        long id = s.createLadder("New thing", "NOSUCH");
        s.addIntent(id, "Roth IRA", "Buy", new BigDecimal("25"), new BigDecimal("175.00"), null);

        assertEquals(1, s.intendedOrders(sym -> null).size());
        assertEquals(null, s.intendedOrders(sym -> null).get(0).currency());
    }

    /** Intended rungs are rungs like any other once they reach the ladder. */
    @Test
    void anIntendedRungIsARungOnTheLadder() {
        LadderService s = service();
        long id = s.createLadder("NVDA round trip", "NVDA");
        s.addIntent(id, "Roth IRA", "Buy", new BigDecimal("25"), new BigDecimal("175.00"), "why");
        List<OpenOrder> synthetic = s.intendedOrders(sym -> "USD");

        var report = s.assemble(List.of(), synthetic, List.of(), Map.of(), ACCOUNTS, TOUCH);
        Rung r = report.ladders().get(0).rungs().get(0);

        assertTrue(r.intended());
        assertEquals(Rung.State.INTENDED, r.state());
        assertEquals("USD", r.currency());
        assertEquals("why", r.note());
        assertNotNull(r.order(), "it carries the order the enrichment pass would have priced");
    }

    // ---- editable intended rungs -------------------------------------------

    /**
     * The whole rule, from the other side: a rung that came from a broker's export is not editable
     * and there is no path that could make it so. Only an intended rung is rendered with inputs, and
     * a key naming anything else is dropped rather than interpreted — so a stale tab or a hand-made
     * request cannot restate what a broker is holding.
     */
    @Test
    void onlyAnIntendedRungCanBeEdited() {
        OpenOrder resting = order("Roth IRA", "NVDA", "Buy", "20", "203.18", "REF2");

        assertTrue(IntentEdits.of(List.of(resting.identity()), List.of("175"), List.of("30"))
                        .isEmpty(),
                "a broker order key must name nothing this panel can write");
        assertEquals(1, IntentEdits.of(List.of("intent:7"), List.of("175"), List.of("30"))
                .all().size());
    }

    /**
     * An intended rung is the user's own record, so typing over it is the edit — written, and
     * keeping the note that says why the rung is there. Re-recording it was the alternative, and it
     * threw the note away.
     */
    @Test
    void editingAnIntendedRungIsSavedAndKeepsItsNote() {
        LadderService s = service();
        long id = s.createLadder("NVDA round trip", "NVDA");
        long intentId = s.addIntent(id, "Roth IRA", "Buy", new BigDecimal("25"),
                new BigDecimal("175.00"), "the patient rung");

        s.updateIntent(intentId, new BigDecimal("30"), new BigDecimal("168.50"));

        var r = groups.intent(intentId);
        assertEquals(0, new BigDecimal("30").compareTo(r.quantity()));
        assertEquals(0, new BigDecimal("168.50").compareTo(r.price()));
        assertEquals("the patient rung", r.note());
        assertEquals("Buy", r.side(), "a figure being tuned is not a change of side");
    }

    /**
     * The panel posts every cell on screen, so an unchanged one has to write nothing — otherwise a
     * commit anywhere in the block would rewrite every intent in it. A blank is "leave it", never
     * zero, so a half-typed figure cannot empty a commitment against cash.
     */
    @Test
    void anUnchangedOrBlankIntentCellWritesNothing() {
        LadderService s = service();
        long id = s.createLadder("NVDA round trip", "NVDA");
        long intentId = s.addIntent(id, "Roth IRA", "Buy", new BigDecimal("25"),
                new BigDecimal("175.00"), null);

        s.updateIntent(intentId, new BigDecimal("25"), new BigDecimal("175"));
        s.updateIntent(intentId, null, null);

        var r = groups.intent(intentId);
        assertEquals(0, new BigDecimal("25").compareTo(r.quantity()));
        assertEquals(0, new BigDecimal("175.00").compareTo(r.price()));
    }

    /** A rung with no size is not a commitment, so zero is refused rather than stored. */
    @Test
    void anIntendedRungCannotBeResizedToNothing() {
        LadderService s = service();
        long id = s.createLadder("NVDA round trip", "NVDA");
        long intentId = s.addIntent(id, "Roth IRA", "Buy", new BigDecimal("25"),
                new BigDecimal("175.00"), null);

        assertThrows(IllegalStateException.class,
                () -> s.updateIntent(intentId, BigDecimal.ZERO, new BigDecimal("175.00")));
        assertThrows(IllegalStateException.class,
                () -> s.updateIntent(999L, new BigDecimal("1"), new BigDecimal("1")));
    }

    // ---- the two fixed horizons -------------------------------------------

    /**
     * Both fill-probability columns are priced over a horizon that is the same on every rung. The
     * table's P(N) runs on each order's own deadline, which is the right question for one order on
     * its own and the wrong one here: a ladder's rungs are a single decision and are read against
     * each other, so a column whose horizon moves per row cannot be compared down its own length.
     */
    @Test
    void everyRungIsPricedOverTheSameTwoHorizons() {
        LadderService s = service();
        long id = s.createLadder("NVDA round trip", "NVDA");
        OpenOrder sell = order("II SIPP", "NVDA", "Sell", "30", "246.97", "REF1");
        OpenOrder buy = order("Roth IRA", "NVDA", "Buy", "20", "203.18", "REF2");
        s.addRungs(id, List.of(sell.identity(), buy.identity()));

        var ladder = s.assemble(List.of(sell, buy), List.of(), List.of(), Map.of(), ACCOUNTS, TOUCH)
                .ladders().get(0);

        for (Rung r : ladder.rungs()) {
            assertEquals(0, new BigDecimal("0.10").compareTo(r.touchNear()), r.describe());
            assertEquals(0, new BigDecimal("0.30").compareTo(r.touchFar()), r.describe());
        }
        assertEquals(10, LadderService.NEAR_HORIZON_SESSIONS);
        assertEquals(30, LadderService.FAR_HORIZON_SESSIONS);
    }

    /** An intended rung is priced over the same two horizons as everything else on the ladder. */
    @Test
    void anIntendedRungIsPricedOverTheSameHorizonsAsARealOne() {
        LadderService s = service();
        long id = s.createLadder("NVDA round trip", "NVDA");
        s.addIntent(id, "Roth IRA", "Buy", new BigDecimal("25"), new BigDecimal("175.00"), null);

        Rung r = s.assemble(List.of(), s.intendedOrders(sym -> "USD"), List.of(), Map.of(),
                ACCOUNTS, TOUCH).ladders().get(0).rungs().get(0);

        assertEquals(0, new BigDecimal("0.10").compareTo(r.touchNear()));
        assertEquals(0, new BigDecimal("0.30").compareTo(r.touchFar()));
    }

    /** A closed rung is priced at neither: it is not resting, so there is no fill to put odds on. */
    @Test
    void aFilledRungCarriesNoFillProbability() {
        LadderService s = service();
        long id = s.createLadder("NVDA round trip", "NVDA");
        OpenOrder o = order("II SIPP", "NVDA", "Sell", "20", "233.90", "REF1");
        history.record("II SIPP", List.of(o), Set.of(o.identity()), DAY);
        history.record("II SIPP", List.of(dealt(o)), Set.of(), DAY.plusDays(1));
        s.addRungs(id, List.of(o.identity()));

        Rung r = s.assemble(List.of(), List.of(), history.closed(), Map.of(), ACCOUNTS, TOUCH)
                .ladders().get(0).rungs().get(0);

        assertTrue(r.filled());
        assertNull(r.touchNear());
        assertNull(r.touchFar());
    }

    /**
     * What a fixed horizon costs, paid openly. An order with a dated expiry inside the window is
     * marked: the figure prices a window the order will not survive. The two horizons are judged
     * separately, so an order that outlasts the near one and not the far one is marked on the far
     * column only. II, Roth and intended rungs export no dated expiry, so nothing runs out for them.
     */
    @Test
    void aRungWhoseOrderExpiresInsideAWindowIsMarkedOnThatWindowOnly() {
        LadderService s = service();
        long id = s.createLadder("EQQQ", "EQQQ");
        OpenOrder soon = ajBell("AJREF1", "560");      // expires in 8 sessions
        OpenOrder later = ajBell("AJREF2", "570");     // expires in 20
        OpenOrder gtc = order("Roth IRA", "EQQQ", "Buy", "20", "500", "REF3");
        s.addRungs(id, List.of(soon.identity(), later.identity(), gtc.identity()));

        var ladder = s.assemble(
                List.of(soon.withHorizonTouch(null, 8), later.withHorizonTouch(null, 20), gtc),
                List.of(), List.of(), Map.of(), ACCOUNTS, TOUCH).ladders().get(0);
        Rung eight = rungAt(ladder, "560");
        Rung twenty = rungAt(ladder, "570");
        Rung noExpiry = rungAt(ladder, "500");

        assertTrue(eight.lapsesBefore(LadderService.NEAR_HORIZON_SESSIONS), "8 < 10");
        assertTrue(eight.lapsesBefore(LadderService.FAR_HORIZON_SESSIONS), "8 < 30");

        assertFalse(twenty.lapsesBefore(LadderService.NEAR_HORIZON_SESSIONS),
                "20 sessions outlasts the 10-session window");
        assertTrue(twenty.lapsesBefore(LadderService.FAR_HORIZON_SESSIONS), "20 < 30");

        assertFalse(noExpiry.lapsesBefore(LadderService.FAR_HORIZON_SESSIONS),
                "no dated expiry, so nothing runs out");
        assertTrue(eight.touchNear() != null && twenty.touchFar() != null,
                "marked, but still priced — the figure is right for the window");
    }

    private static Rung rungAt(LadderService.Ladder ladder, String price) {
        return ladder.rungs().stream()
                .filter(r -> r.price().compareTo(new BigDecimal(price)) == 0)
                .findFirst().orElseThrow();
    }

    private static OpenOrder ajBell(String reference, String price) {
        return OpenOrder.parsed("AJ Bell SIPP", "EQQQ", null, "Sell", "Limit",
                new BigDecimal("50"), new BigDecimal(price), false, new BigDecimal("28000"),
                "GBP", DAY, DAY.plusDays(14), null, "In progress", reference);
    }

    // ---- no auto-promotion -------------------------------------------------

    /**
     * The rule the spec is most explicit about. A real order appears that matches an intent in every
     * visible way — same symbol, same side, same quantity, same price — and nothing happens.
     * Guessing would attach reasoning written about one decision to a different order.
     */
    @Test
    void aMatchingRealOrderNeverAbsorbsTheIntendedRung() {
        LadderService s = service();
        long id = s.createLadder("NVDA round trip", "NVDA");
        s.addIntent(id, "Roth IRA", "Buy", new BigDecimal("25"), new BigDecimal("175.00"), "mine");
        OpenOrder twin = order("Roth IRA", "NVDA", "Buy", "25", "175.00", "REF-NEW");

        var report = s.assemble(List.of(twin), s.intendedOrders(sym -> "USD"), List.of(),
                Map.of(), ACCOUNTS, TOUCH);

        assertEquals(1, report.ladders().get(0).rungs().size(), "still just the intent");
        assertTrue(report.ladders().get(0).rungs().get(0).intended());
        assertEquals(1, report.unassigned().size(), "the real order is still nobody's rung");
    }

    /** The one path across, and the user drives it. */
    @Test
    void markPlacedLinksTheIntentToTheOrderTheUserNames() {
        LadderService s = service();
        long id = s.createLadder("NVDA round trip", "NVDA");
        long intentId = s.addIntent(id, "Roth IRA", "Buy", new BigDecimal("25"),
                new BigDecimal("175.00"), "needs the II sell to settle first");
        OpenOrder placed = order("Roth IRA", "NVDA", "Buy", "25", "175.00", "REF-NEW");
        history.record("Roth IRA", List.of(placed), Set.of(placed.identity()), DAY);

        s.markPlaced(intentId, placed.identity());

        var report = s.assemble(List.of(placed), s.intendedOrders(sym -> "USD"), List.of(),
                history.notes(), ACCOUNTS, TOUCH);
        List<Rung> rungs = report.ladders().get(0).rungs();
        assertEquals(1, rungs.size());
        assertFalse(rungs.get(0).intended(), "it is the real order now");
        assertEquals("needs the II sell to settle first", rungs.get(0).note(),
                "the reasoning moved with it");
    }

    /** A note already written against the real order is that order's, and is not overwritten. */
    @Test
    void markPlacedDoesNotOverwriteANoteTheOrderAlreadyHas() {
        LadderService s = service();
        long id = s.createLadder("NVDA round trip", "NVDA");
        long intentId = s.addIntent(id, "Roth IRA", "Buy", new BigDecimal("25"),
                new BigDecimal("175.00"), "the intent's reasoning");
        OpenOrder placed = order("Roth IRA", "NVDA", "Buy", "25", "175.00", "REF-NEW");
        history.record("Roth IRA", List.of(placed), Set.of(placed.identity()), DAY);
        history.saveNote(placed.identity(), "written about this order");

        s.markPlaced(intentId, placed.identity());

        assertEquals("written about this order", history.notes().get(placed.identity()));
    }

    @Test
    void markPlacedOnAnIntentThatIsGoneIsRefused() {
        LadderService s = service();
        assertThrows(IllegalStateException.class, () -> s.markPlaced(999, "Roth IRA|REF"));
    }

    // ---- closed rungs ------------------------------------------------------

    /**
     * A filled rung stays on the ladder with the price the export carried, and the round trip is
     * the thing the panel is for.
     */
    @Test
    void filledRungsStayVisibleAndCompleteTheRoundTrip() {
        LadderService s = service();
        long id = s.createLadder("NVDA round trip", "NVDA");
        OpenOrder sell = order("II SIPP", "NVDA", "Sell", "20", "233.90", "REF1");
        OpenOrder buy = order("Roth IRA", "NVDA", "Buy", "20", "209.85", "REF2");
        history.record("II SIPP", List.of(sell), Set.of(sell.identity()), DAY);
        history.record("Roth IRA", List.of(buy), Set.of(buy.identity()), DAY);
        // Both restated as dealt: they filled.
        history.record("II SIPP", List.of(dealt(sell)), Set.of(), DAY.plusDays(1));
        history.record("Roth IRA", List.of(dealt(buy)), Set.of(), DAY.plusDays(2));
        s.addRungs(id, List.of(sell.identity(), buy.identity()));

        var report = s.assemble(List.of(), List.of(), history.closed(), Map.of(), ACCOUNTS, TOUCH);
        var ladder = report.ladders().get(0);

        assertEquals(2, ladder.rungs().size());
        assertTrue(ladder.rungs().stream().allMatch(Rung::filled));
        assertTrue(ladder.totals().complete());
        assertEquals(0, new BigDecimal("481.00").compareTo(ladder.totals().roundTrip().total()));
    }

    /** A cancelled rung is kept for the record and counted in nothing. */
    @Test
    void aCancelledRungIsKeptButNotCounted() {
        LadderService s = service();
        long id = s.createLadder("NVDA round trip", "NVDA");
        OpenOrder o = order("II SIPP", "NVDA", "Buy", "30", "210.65", "REF1");
        history.record("II SIPP", List.of(o), Set.of(o.identity()), DAY);
        history.record("II SIPP", List.of(cancelled(o)), Set.of(), DAY.plusDays(1));
        s.addRungs(id, List.of(o.identity()));

        var ladder = s.assemble(List.of(), List.of(), history.closed(), Map.of(), ACCOUNTS, TOUCH)
                .ladders().get(0);

        assertEquals(Rung.State.CANCELLED, ladder.rungs().get(0).state());
        assertEquals(0, ladder.totals().remainingCount());
        assertTrue(ladder.totals().cash().isEmpty());
    }

    /** A rung whose order is on file nowhere is reported, not silently dropped from the totals. */
    @Test
    void aRungPointingAtAnOrderThatIsGoneIsReported() {
        LadderService s = service();
        long id = s.createLadder("NVDA round trip", "NVDA");
        s.addRungs(id, List.of("Roth IRA|VANISHED"));

        var report = s.assemble(List.of(), List.of(), List.of(), Map.of(), ACCOUNTS, TOUCH);

        assertTrue(report.ladders().get(0).rungs().isEmpty());
        assertEquals(1, report.warnings().size());
        assertTrue(report.warnings().get(0).contains("VANISHED"), report.warnings().toString());
    }

    /** A ladder needs a security; a nameless axis has nothing to lay rungs on. */
    @Test
    void aLadderWithoutASecurityIsRefused() {
        LadderService s = service();
        assertThrows(IllegalStateException.class, () -> s.createLadder("Nameless", " "));
    }

    private static OpenOrder dealt(OpenOrder o) {
        return OpenOrder.parsed(o.broker(), o.symbol(), o.name(), o.side(), o.orderType(),
                o.quantity(), o.price(), false, o.amount(), o.currency(), o.dateEntered(), null,
                null, "Dealt", o.reference());
    }

    private static OpenOrder cancelled(OpenOrder o) {
        return OpenOrder.parsed(o.broker(), o.symbol(), o.name(), o.side(), o.orderType(),
                o.quantity(), o.price(), false, o.amount(), o.currency(), o.dateEntered(), null,
                null, "CANCELLED", o.reference());
    }
}
