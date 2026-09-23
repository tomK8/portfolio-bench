package com.portfolio.application;

import com.portfolio.domain.ShareClasses;
import com.portfolio.domain.Switches;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.OpenOrder;
import com.portfolio.domain.model.Rung;
import com.portfolio.domain.model.TransactionType;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.OrderGroupRepository;
import com.portfolio.persistence.OrderHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assembling a share-class switch against the live order feed.
 *
 * <p>The arithmetic has its own tests in {@code SwitchesTest}; what is pinned here is the
 * assembler's own decisions — which listing puts a leg on which side, what is refused, and the two
 * things the panel must never do: infer a switch, or promote an intended leg by itself.
 */
class SwitchServiceTest {

    private static final LocalDate DAY = LocalDate.parse("2026-09-01");
    private static final List<String> ACCOUNTS = List.of("AJ Bell SIPP", "II SIPP", "Roth IRA");
    private static final Map<String, BigDecimal> RATES = Map.of("USD", new BigDecimal("1.345"));

    private static final Map<String, Switches.LinePrice> LINES = Map.of(
            "EQQQ", new Switches.LinePrice("EQQQ", new BigDecimal("500.00"), "GBP"),
            "EQQU", new Switches.LinePrice("EQQU", new BigDecimal("672.50"), "USD"));

    /** A stand-in for the first-passage model: the probability is the horizon, scaled. */
    private static final GroupRungs.TouchPricer TOUCH =
            (order, sessions) -> order == null || order.price() == null
                    ? null : new BigDecimal(sessions).movePointLeft(2);

    @TempDir
    Path dbDir;

    private OrderGroupRepository groups;
    private OrderHistoryRepository history;
    private CashTransactionRepository cash;

    private SwitchService service() {
        JdbcConnectionFactory conn = new JdbcConnectionFactory(dbDir);
        groups = new OrderGroupRepository(conn);
        history = new OrderHistoryRepository(conn);
        cash = new CashTransactionRepository(conn, new KeyValueStore(dbDir));
        return new SwitchService(groups, history, cash);
    }

    private static OpenOrder order(String broker, String symbol, String side, String qty,
                                   String price, String ccy, String reference) {
        return OpenOrder.parsed(broker, symbol, null, side, "Limit", new BigDecimal(qty),
                new BigDecimal(price), false, new BigDecimal(qty).multiply(new BigDecimal(price)),
                ccy, DAY, null, null, "PENDING", reference);
    }

    private SwitchService.SwitchReport assemble(SwitchService s, List<OpenOrder> live) {
        return s.assemble(live, List.of(), history.closed(), history.notes(), ACCOUNTS, LINES,
                RATES, TOUCH);
    }

    // ---- the shape ---------------------------------------------------------

    /**
     * Which side a leg is on is its <em>direction</em>; the listing decides only what it is priced
     * against. Neither is ever the user choosing a side by hand.
     */
    @Test
    void theSideDecidesWhichWayALegGoesAndTheListingWhatItIsPricedAgainst() {
        SwitchService s = service();
        long id = s.createSwitch("Nasdaq line to USD", "EQQQ", "EQQU");
        OpenOrder sell = order("II SIPP", "EQQQ", "Sell", "100", "530.00", "GBP", "REF1");
        OpenOrder buy = order("Roth IRA", "EQQU", "Buy", "100", "716.00", "USD", "REF2");
        s.addLegs(id, List.of(sell.identity(), buy.identity()));

        SwitchService.Switch sw = assemble(s, List.of(sell, buy)).switches().get(0);

        assertEquals(List.of("II SIPP"), sw.sellLegs().stream().map(Rung::account).toList());
        assertEquals(List.of("Roth IRA"), sw.buyLegs().stream().map(Rung::account).toList());
        assertEquals("EQQQ", sw.from());
        assertEquals("EQQU", sw.to());
        assertTrue(sw.offShape().isEmpty());
    }

    /**
     * A sell of EQQQ beside a buy of EQQU is two orders until the user says otherwise. Inferring it
     * would quietly stop counting a real rotation as a change of exposure.
     */
    @Test
    void twoOrdersThatLookLikeASwitchAreNotOne() {
        SwitchService s = service();
        s.createSwitch("Nasdaq line to USD", "EQQQ", "EQQU");
        OpenOrder sell = order("II SIPP", "EQQQ", "Sell", "100", "530.00", "GBP", "REF1");
        OpenOrder buy = order("Roth IRA", "EQQU", "Buy", "100", "716.00", "USD", "REF2");

        SwitchService.SwitchReport report = assemble(s, List.of(sell, buy));

        assertFalse(report.switches().get(0).hasLegs(), "nothing is assigned by resemblance");
        assertEquals(2, report.unassigned().size());
    }

    /**
     * Editing an intended leg moves the only figure the two legs can be read against each other in.
     *
     * <p>It is worth more on a switch than on a ladder: the buy leg is an intent for days by
     * construction, and the two limits are chosen against each other rather than against one
     * market, so "where do these two have to sit" has two answers that only make sense together.
     */
    @Test
    void editingAnIntendedLegMovesTheGbpYardstickAndTheCost() {
        SwitchService s = service();
        long id = s.createSwitch("Nasdaq line to USD", "EQQQ", "EQQU");
        OpenOrder sell = order("II SIPP", "EQQQ", "Sell", "100", "530.00", "GBP", "REF1");
        s.addLegs(id, List.of(sell.identity()));
        long intentId = s.addIntent(id, "Roth IRA", "EQQU", "Buy", new BigDecimal("100"),
                new BigDecimal("716.00"), "waiting on the transfer");

        BigDecimal before = s.assemble(List.of(sell), s.intendedOrders(sym -> "USD"),
                history.closed(), history.notes(), ACCOUNTS, LINES, RATES, TOUCH)
                .switches().get(0).summary().resting().costPct();

        s.updateIntent(intentId, new BigDecimal("100"), new BigDecimal("700"));

        SwitchService.Switch sw = s.assemble(List.of(sell), s.intendedOrders(sym -> "USD"),
                history.closed(), history.notes(), ACCOUNTS, LINES, RATES, TOUCH)
                .switches().get(0);
        Switches.LegSide in = sw.summary().resting().buySide();
        assertEquals(0, new BigDecimal("700").compareTo(in.avgPrice()));
        // 700 ÷ 1.345 — the FX convention is units of foreign currency per 1 GBP, so divide.
        assertEquals(0, new BigDecimal("520.45").compareTo(in.avgPriceGbp().setScale(2,
                java.math.RoundingMode.HALF_UP)));
        assertTrue(sw.summary().resting().costPct().compareTo(before) < 0,
                "a lower buy limit has to read as a cheaper switch");
        assertEquals("waiting on the transfer", sw.buyLegs().get(0).note(),
                "the reasoning survives the edit — which re-recording the leg would not have");
    }

    /** The picker offers exactly the two listings — this type's rule, in one place. */
    @Test
    void thePickerOffersBothListingsAndNothingElse() {
        SwitchService s = service();
        s.createSwitch("Nasdaq line to USD", "EQQQ", "EQQU");
        OpenOrder from = order("II SIPP", "EQQQ", "Sell", "100", "530.00", "GBP", "REF1");
        OpenOrder to = order("Roth IRA", "EQQU", "Buy", "100", "716.00", "USD", "REF2");
        OpenOrder other = order("Roth IRA", "NVDA", "Buy", "20", "203.18", "USD", "REF3");

        SwitchService.SwitchReport report = assemble(s, List.of(from, to, other));

        assertEquals(List.of(from.identity(), to.identity()),
                report.assignableFor(report.switches().get(0)).stream()
                        .map(GroupRungs.Candidate::key).toList());
    }

    /**
     * Both listings take both sides — the case the panel could not record before.
     *
     * <p>Limits go out on each line at once in the ordinary way of working one of these: sells
     * above on both, buys below on both, and which line a fill lands on is whichever was dearer on
     * the day. Binding the sell side to one listing made that unrecordable, and it is the case the
     * panel is for.
     */
    @Test
    void bothListingsTakeBothSides() {
        SwitchService s = service();
        long id = s.createSwitch("Nasdaq line to USD", "EQQQ", "EQQU");
        OpenOrder sellGbp = order("II SIPP", "EQQQ", "Sell", "50", "584.80", "GBP", "REF1");
        OpenOrder sellUsd = order("Roth IRA", "EQQU", "Sell", "20", "800.00", "USD", "REF2");
        OpenOrder buyUsd = order("II SIPP", "EQQU", "Buy", "20", "682.35", "USD", "REF3");
        OpenOrder buyGbp = order("AJ Bell SIPP", "EQQQ", "Buy", "10", "500.00", "GBP", "REF4");
        s.addLegs(id, List.of(sellGbp.identity(), sellUsd.identity(),
                buyUsd.identity(), buyGbp.identity()));

        SwitchService.Switch sw =
                assemble(s, List.of(sellGbp, sellUsd, buyUsd, buyGbp)).switches().get(0);

        // Membership, not order — the ordering is the next test's subject.
        assertEquals(java.util.Set.of("EQQQ", "EQQU"),
                sw.sellLegs().stream().map(Rung::symbol).collect(java.util.stream.Collectors.toSet()));
        assertEquals(java.util.Set.of("EQQQ", "EQQU"),
                sw.buyLegs().stream().map(Rung::symbol).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, sw.sellLegs().size());
        assertEquals(2, sw.buyLegs().size());
        assertTrue(sw.offShape().isEmpty(), "nothing here is off-shape any more");
        assertFalse(sw.oneWay(), "this one is being worked from both ends");
    }

    /**
     * A side holding both lines is ordered on the <b>yardstick</b>. £584.80 is not above $800.00 —
     * it is below it once the rate is divided out — and sorting the raw figures would stack every
     * sterling row above every dollar one and call that a price order.
     */
    @Test
    void asideHoldingBothLinesIsOrderedInGbpNotInRawPrice() {
        SwitchService s = service();
        long id = s.createSwitch("Nasdaq line to USD", "EQQQ", "EQQU");
        OpenOrder cheapInGbp = order("II SIPP", "EQQQ", "Sell", "50", "584.80", "GBP", "REF1");
        OpenOrder dearInGbp = order("Roth IRA", "EQQU", "Sell", "20", "800.00", "USD", "REF2");
        s.addLegs(id, List.of(cheapInGbp.identity(), dearInGbp.identity()));

        SwitchService.Switch sw = assemble(s, List.of(cheapInGbp, dearInGbp)).switches().get(0);

        // $800 ÷ 1.345 = £594.80, above the £584.80 limit, so the dollar leg sorts first.
        assertEquals(List.of("EQQU", "EQQQ"), sw.sellLegs().stream().map(Rung::symbol).toList());
    }

    /**
     * An order on neither listing is still a mis-assignment: kept, because the user assigned it,
     * named in the panel, and counted in nothing. That is all "off-shape" means now — the pair is
     * what a switch is, and there is no third line to put a leg on.
     */
    @Test
    void anOrderOnNeitherListingIsKeptAndCountedInNothing() {
        SwitchService s = service();
        long id = s.createSwitch("Nasdaq line to USD", "EQQQ", "EQQU");
        OpenOrder elsewhere = order("II SIPP", "NVDA", "Buy", "10", "203.18", "USD", "REF1");
        s.addLegs(id, List.of(elsewhere.identity()));

        SwitchService.Switch sw = assemble(s, List.of(elsewhere)).switches().get(0);

        assertTrue(sw.sellLegs().isEmpty());
        assertTrue(sw.buyLegs().isEmpty());
        assertEquals(1, sw.offShape().size());
        assertTrue(sw.offShape().get(0).contains("neither line of this switch"),
                sw.offShape().get(0));
    }

    /** Both listings, and two different ones: a switch with one line is not a switch. */
    @Test
    void aSwitchNeedsTwoDifferentListings() {
        SwitchService s = service();
        assertThrows(IllegalStateException.class, () -> s.createSwitch("x", "EQQQ", ""));
        assertThrows(IllegalStateException.class, () -> s.createSwitch("x", "", "EQQU"));
        assertThrows(IllegalStateException.class, () -> s.createSwitch("x", "EQQQ", "eqqq"));
    }

    // ---- the two types stay apart ------------------------------------------

    /**
     * A ladder is not a switch and a switch is not a ladder. They share one store and one member
     * table, and each assembler sees only its own — otherwise a switch's two listings would be laid
     * on a single price axis that means nothing for them.
     */
    @Test
    void eachAssemblerSeesOnlyItsOwnGroups() {
        SwitchService s = service();
        LadderService ladders = new LadderService(groups, history);
        s.createSwitch("Nasdaq line to USD", "EQQQ", "EQQU");
        ladders.createLadder("NVDA round trip", "NVDA");

        assertEquals(1, assemble(s, List.of()).switches().size());
        assertEquals(1, ladders.assemble(List.of(), List.of(), List.of(), Map.of(), ACCOUNTS, TOUCH)
                .ladders().size());
        assertEquals("EQQQ", assemble(s, List.of()).switches().get(0).from());
        assertEquals("NVDA",
                ladders.assemble(List.of(), List.of(), List.of(), Map.of(), ACCOUNTS, TOUCH)
                        .ladders().get(0).group().symbol());
    }

    /** One order, one group — across types too. It is one order and it was placed for one reason. */
    @Test
    void anOrderCannotBeALadderRungAndASwitchLegAtOnce() {
        SwitchService s = service();
        LadderService ladders = new LadderService(groups, history);
        long ladderId = ladders.createLadder("EQQQ ladder", "EQQQ");
        long switchId = s.createSwitch("Nasdaq line to USD", "EQQQ", "EQQU");
        OpenOrder sell = order("II SIPP", "EQQQ", "Sell", "100", "530.00", "GBP", "REF1");

        ladders.addRungs(ladderId, List.of(sell.identity()));
        s.addLegs(switchId, List.of(sell.identity()));

        assertEquals(1, assemble(s, List.of(sell)).switches().get(0).sellLegs().size());
        assertTrue(ladders.assemble(List.of(sell), List.of(), List.of(), Map.of(), ACCOUNTS, TOUCH)
                .ladders().get(0).rungs().isEmpty(), "assigning it elsewhere moved it");
    }

    // ---- intended legs -----------------------------------------------------

    /**
     * On a switch the unplaceable leg is the ordinary state. It becomes a synthetic order so the
     * caller's enrichment prices it exactly as it prices a resting one — on the listing it was
     * recorded against, which is stored, because the side no longer names it.
     */
    @Test
    void anIntendedLegTakesTheListingItWasRecordedOn() {
        SwitchService s = service();
        long id = s.createSwitch("Nasdaq line to USD", "EQQQ", "EQQU");
        // A buy of the line being left, which the old shape had no way to record at all.
        s.addIntent(id, "Roth IRA", "EQQQ", "Buy", new BigDecimal("100"),
                new BigDecimal("530.00"), "buying the sterling line back");
        s.addIntent(id, "II SIPP", "EQQU", "Sell", new BigDecimal("100"),
                new BigDecimal("716.00"), null);

        List<OpenOrder> synthetic = s.intendedOrders(sym -> "EQQU".equals(sym) ? "USD" : "GBP");

        assertEquals(2, synthetic.size());
        OpenOrder buy = synthetic.stream().filter(o -> "Buy".equals(o.side())).findFirst().orElseThrow();
        OpenOrder sell = synthetic.stream().filter(o -> "Sell".equals(o.side())).findFirst().orElseThrow();
        assertEquals("EQQQ", buy.symbol(), "the listing it was recorded on, not the one its side implies");
        assertEquals("GBP", buy.currency());
        assertEquals("EQQU", sell.symbol());
        assertEquals("USD", sell.currency());
    }

    /**
     * A leg recorded before the listing was stored is read under the rule it was written under —
     * sell is the line being left, buy the line being entered. Reading those rows any other way
     * would move an existing leg onto the other listing and re-price it against the wrong market.
     */
    @Test
    void anIntendedLegWithNoListingFallsBackToTheRuleItWasRecordedUnder() {
        SwitchService s = service();
        long id = s.createSwitch("Nasdaq line to USD", "EQQQ", "EQQU");
        s.addIntent(id, "Roth IRA", null, "Buy", new BigDecimal("100"),
                new BigDecimal("716.00"), null);
        s.addIntent(id, "II SIPP", "", "Sell", new BigDecimal("100"),
                new BigDecimal("530.00"), null);

        List<OpenOrder> synthetic = s.intendedOrders(sym -> "EQQU".equals(sym) ? "USD" : "GBP");

        OpenOrder buy = synthetic.stream().filter(o -> "Buy".equals(o.side())).findFirst().orElseThrow();
        OpenOrder sell = synthetic.stream().filter(o -> "Sell".equals(o.side())).findFirst().orElseThrow();
        assertEquals("EQQU", buy.symbol(), "buying was the line being entered");
        assertEquals("EQQQ", sell.symbol(), "selling was the line being left");
    }

    /** The pair is what a switch is, so a leg cannot be recorded on a third line. */
    @Test
    void anIntendedLegOffThePairIsRefused() {
        SwitchService s = service();
        long id = s.createSwitch("Nasdaq line to USD", "EQQQ", "EQQU");
        assertThrows(IllegalStateException.class, () -> s.addIntent(id, "Roth IRA", "NVDA", "Buy",
                new BigDecimal("100"), new BigDecimal("716.00"), null));
    }

    /**
     * An intended leg is never promoted by a feed match, however exact. Being wrong here attaches
     * one decision's reasoning to a different order, and the user is the only one who knows.
     */
    @Test
    void aMatchingRealOrderNeverAbsorbsTheIntendedLeg() {
        SwitchService s = service();
        long id = s.createSwitch("Nasdaq line to USD", "EQQQ", "EQQU");
        s.addIntent(id, "Roth IRA", "EQQU", "Buy", new BigDecimal("100"), new BigDecimal("716.00"), null);
        OpenOrder identical = order("Roth IRA", "EQQU", "Buy", "100", "716.00", "USD", "REF9");

        List<OpenOrder> intents = s.intendedOrders(sym -> "USD");
        SwitchService.SwitchReport report = s.assemble(List.of(identical), intents, List.of(),
                Map.of(), ACCOUNTS, LINES, RATES, TOUCH);

        SwitchService.Switch sw = report.switches().get(0);
        assertEquals(1, sw.buyLegs().size());
        assertTrue(sw.buyLegs().get(0).intended(), "still an intent");
        assertEquals(1, report.unassigned().size(), "the real order is still unassigned");
    }

    /** The one path across, and the note follows only onto an order that has none of its own. */
    @Test
    void markingAnIntentPlacedMovesItAndItsNoteOntoTheNamedOrder() {
        SwitchService s = service();
        long id = s.createSwitch("Nasdaq line to USD", "EQQQ", "EQQU");
        long intentId = s.addIntent(id, "Roth IRA", "EQQU", "Buy", new BigDecimal("100"),
                new BigDecimal("716.00"), "the dollar leg");
        OpenOrder placed = order("Roth IRA", "EQQU", "Buy", "100", "716.00", "USD", "REF9");
        history.record("Roth IRA", List.of(placed), java.util.Set.of(placed.identity()), DAY);

        s.markPlaced(intentId, placed.identity());

        SwitchService.Switch sw = assemble(s, List.of(placed)).switches().get(0);
        assertEquals(1, sw.buyLegs().size());
        assertFalse(sw.buyLegs().get(0).intended());
        assertEquals("the dollar leg", history.notes().get(placed.identity()));
    }

    // ---- the one-holding declaration ---------------------------------------

    /**
     * Declaring the switch is the same statement as declaring that the two listings are one fund,
     * and that is what stops every exposure view counting them twice.
     */
    @Test
    void declaringASwitchDeclaresTheTwoListingsOneHolding() {
        SwitchService s = service();
        s.createSwitch("Nasdaq line to USD", "EQQQ", "EQQU");

        ShareClasses sc = s.shareClasses();
        assertEquals("EQQQ", sc.canonical("EQQU"));
        assertTrue(sc.isAlias("EQQU"));
    }

    /** A ladder declares nothing of the sort — one security is not two listings. */
    @Test
    void aLadderDeclaresNoShareClassPair() {
        SwitchService s = service();
        new LadderService(groups, history).createLadder("NVDA round trip", "NVDA");
        assertTrue(s.shareClasses().isEmpty());
    }

    /**
     * The pair outlives the move. It is read off the declaration and never off the legs, so a switch
     * whose orders have all filled still says the two listings are one fund — which they are. The
     * holding's history runs through both, and un-declaring it the moment the last unit lands would
     * put the break back into every chart that had just stopped having one.
     *
     * <p>Deleting the switch is the one thing that does undo it, which is why the panel says so on
     * the confirmation rather than treating it as tidying up an old order group.
     */
    @Test
    void thePairOutlivesTheMoveAndOnlyDeletingTheSwitchUndoesIt() {
        SwitchService s = service();
        long id = s.createSwitch("Nasdaq line to USD", "EQQQ", "EQQU");
        OpenOrder sell = order("II SIPP", "EQQQ", "Sell", "100", "530.00", "GBP", "REF1");
        OpenOrder buy = order("Roth IRA", "EQQU", "Buy", "100", "716.00", "USD", "REF2");
        s.addLegs(id, List.of(sell.identity(), buy.identity()));
        history.record("II SIPP", List.of(sell), java.util.Set.of(), DAY);
        history.record("Roth IRA", List.of(buy), java.util.Set.of(), DAY);

        // Both legs are gone from the live feed; the declaration is untouched.
        assertTrue(assemble(s, List.of()).switches().get(0).hasLegs());
        assertEquals("EQQQ", s.shareClasses().canonical("EQQU"));

        s.deleteSwitch(id);
        assertTrue(s.shareClasses().isEmpty());
    }

    // ---- what actually blocks it -------------------------------------------

    /**
     * The sequencing story ends in the cash ledger: the buy needs dollars in the Roth and the ledger
     * says how many are there, as at the day it was last imported. A shortfall claimed off a stale
     * ledger has to be checkable.
     */
    @Test
    void theBlockerIsReadFromTheCashLedgerAndIsDated() {
        SwitchService s = service();
        long id = s.createSwitch("Nasdaq line to USD", "EQQQ", "EQQU");
        cash.saveRothIra(List.of(new CashTransaction("2026-09-12", Account.ROTH_IRA,
                        TransactionType.CONTRIBUTION, "USD", 0.0, 412.0, "USD", 1.3,
                        316.92, 412.0, null, "2026-09-12")),
                BigDecimal.ZERO);
        s.addIntent(id, "Roth IRA", "EQQU", "Buy", new BigDecimal("100"), new BigDecimal("716.00"), null);
        OpenOrder sold = order("II SIPP", "EQQQ", "Sell", "100", "530.00", "GBP", "REF1");
        s.addLegs(id, List.of(sold.identity()));

        List<OpenOrder> intents = s.intendedOrders(sym -> "EQQU".equals(sym) ? "USD" : "GBP");
        SwitchService.Switch sw = s.assemble(List.of(sold), intents, List.of(), Map.of(), ACCOUNTS,
                LINES, RATES, TOUCH).switches().get(0);

        Switches.Step buy = sw.summary().sequence().get(4);
        assertEquals(Switches.StepState.SHORT, buy.state());
        assertTrue(buy.detail().contains("412.00 USD"), buy.detail());
        assertTrue(buy.detail().contains("2026-09-12"), buy.detail());
        assertNotNull(sw.summary().blockedBy());
    }

    /** Both listings are published so the feed keeps them warm — including one nothing is held in. */
    @Test
    void bothListingsAreNamedForThePriceFeed() {
        SwitchService s = service();
        s.createSwitch("Nasdaq line to USD", "EQQQ", "EQQU");
        assertEquals(java.util.Set.of("EQQQ", "EQQU"), s.lineSymbols());
    }
}
