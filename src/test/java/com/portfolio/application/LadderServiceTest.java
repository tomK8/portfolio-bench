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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LadderServiceTest {

    private static final LocalDate DAY = LocalDate.parse("2026-09-01");
    private static final List<String> ACCOUNTS = List.of("AJ Bell SIPP", "II SIPP", "Roth IRA");

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

        var report = s.assemble(List.of(sell, buy), List.of(), List.of(), Map.of(), ACCOUNTS);

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

        var report = s.assemble(List.of(assigned, loose), List.of(), List.of(), Map.of(), ACCOUNTS);

        assertEquals(1, report.ladders().get(0).rungs().size());
        assertEquals(List.of(loose.identity()),
                report.unassigned().stream().map(LadderService.Candidate::key).toList());
    }

    /** The picker offers this ladder's security only — the rule of this group type, in one place. */
    @Test
    void thePickerOffersOrdersOnTheLaddersOwnSecurity() {
        LadderService s = service();
        long id = s.createLadder("NVDA round trip", "NVDA");
        OpenOrder nvda = order("Roth IRA", "NVDA", "Buy", "20", "193.73", "REF1");
        OpenOrder mrvl = order("Roth IRA", "MRVL", "Buy", "30", "195.10", "REF2");

        var report = s.assemble(List.of(nvda, mrvl), List.of(), List.of(), Map.of(), ACCOUNTS);

        assertEquals(2, report.unassigned().size());
        assertEquals(List.of(nvda.identity()),
                report.assignableFor(report.ladders().get(0)).stream()
                        .map(LadderService.Candidate::key).toList());
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

        var report = s.assemble(List.of(), synthetic, List.of(), Map.of(), ACCOUNTS);
        Rung r = report.ladders().get(0).rungs().get(0);

        assertTrue(r.intended());
        assertEquals(Rung.State.INTENDED, r.state());
        assertEquals("USD", r.currency());
        assertEquals("why", r.note());
        assertNotNull(r.order(), "it carries the order the enrichment pass would have priced");
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
                Map.of(), ACCOUNTS);

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
                history.notes(), ACCOUNTS);
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

        var report = s.assemble(List.of(), List.of(), history.closed(), Map.of(), ACCOUNTS);
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

        var ladder = s.assemble(List.of(), List.of(), history.closed(), Map.of(), ACCOUNTS)
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

        var report = s.assemble(List.of(), List.of(), List.of(), Map.of(), ACCOUNTS);

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
