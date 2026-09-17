package com.portfolio.persistence;

import com.portfolio.domain.model.GroupType;
import com.portfolio.domain.model.IntendedRung;
import com.portfolio.domain.model.OrderGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderGroupRepositoryTest {

    private static final LocalDate DAY = LocalDate.parse("2026-09-01");

    @TempDir
    Path dbDir;

    private OrderGroupRepository repo() {
        return new OrderGroupRepository(new JdbcConnectionFactory(dbDir));
    }

    private long ladder(OrderGroupRepository r, String name, String symbol) {
        return r.create(GroupType.LADDER, name, symbol, null, null, DAY);
    }

    @Test
    void aLadderRoundTripsWithItsSecurity() {
        var r = repo();
        long id = ladder(r, "NVDA round trip", "nvda");

        List<OrderGroup> all = r.groups();
        assertEquals(1, all.size());
        assertEquals(id, all.get(0).id());
        assertEquals(GroupType.LADDER, all.get(0).type());
        assertEquals("NVDA", all.get(0).symbol(), "symbols are stored upper-cased, as everywhere else");
    }

    /**
     * A rung counted in two ladders would be counted twice in the cash each needs — the figure the
     * panel exists to get right — so assigning an order somewhere else moves it.
     */
    @Test
    void anOrderBelongsToOneLadderAndMovesRatherThanDuplicating() {
        var r = repo();
        long first = ladder(r, "First", "NVDA");
        long second = ladder(r, "Second", "NVDA");

        r.addMembers(first, List.of("II SIPP|REF1"), DAY);
        r.addMembers(second, List.of("II SIPP|REF1"), DAY);

        assertEquals(List.of("II SIPP|REF1"), r.members().get(second));
        assertNull(r.members().get(first), "it moved, it was not copied");
        assertEquals(1, r.assignedKeys().size());
    }

    /** A ladder can span brokers: the sells in one account, the paired buys in another. */
    @Test
    void oneLadderHoldsRungsFromDifferentBrokers() {
        var r = repo();
        long id = ladder(r, "NVDA round trip", "NVDA");
        r.addMembers(id, List.of("II SIPP|REF1", "Roth IRA|NVDA|Buy|20|193.73|2026-08-28"), DAY);

        assertEquals(2, r.members().get(id).size());
    }

    @Test
    void anIntendedRungKeepsItsOwnSideQuantityPriceAndNote() {
        var r = repo();
        long id = ladder(r, "NVDA round trip", "NVDA");
        long intentId = r.addIntent(id, "Roth IRA", "buy", new BigDecimal("25"),
                new BigDecimal("175.00"), "needs the II sell to settle first", DAY);

        IntendedRung i = r.intent(intentId);
        assertEquals("Roth IRA", i.account());
        assertEquals("Buy", i.side(), "the side is normalised on the way in");
        assertEquals(0, new BigDecimal("25").compareTo(i.quantity()));
        assertEquals("needs the II sell to settle first", i.note());
        assertEquals("intent:" + intentId, i.reference());
        assertEquals(1, r.intents().get(id).size());
    }

    /** Both writes are rejected rather than stored half-formed. */
    @Test
    void aRungWithNoQuantityOrNoAccountIsRefused() {
        var r = repo();
        long id = ladder(r, "NVDA round trip", "NVDA");

        assertThrows(IllegalStateException.class,
                () -> r.addIntent(id, "Roth IRA", "Buy", null, new BigDecimal("175"), null, DAY));
        assertThrows(IllegalStateException.class,
                () -> r.addIntent(id, " ", "Buy", new BigDecimal("25"), new BigDecimal("175"), null, DAY));
        assertTrue(r.intents().isEmpty());
    }

    /**
     * Deleting a ladder takes its members and its intended rungs. The orders themselves live in
     * order_history and were only ever pointed at — nothing there is touched.
     */
    @Test
    void deletingALadderTakesItsMembershipsAndItsIntendedRungsOnly() {
        var r = repo();
        long id = ladder(r, "NVDA round trip", "NVDA");
        long keep = ladder(r, "MRVL", "MRVL");
        r.addMembers(id, List.of("II SIPP|REF1"), DAY);
        r.addMembers(keep, List.of("II SIPP|REF2"), DAY);
        r.addIntent(id, "Roth IRA", "Buy", new BigDecimal("25"), new BigDecimal("175"), null, DAY);

        r.delete(id);

        assertEquals(1, r.groups().size());
        assertEquals(List.of("II SIPP|REF2"), r.members().get(keep));
        assertTrue(r.intents().isEmpty());
        assertEquals(1, r.assignedKeys().size());
    }

    @Test
    void removingARungLeavesTheLadderAndTheOtherRungs() {
        var r = repo();
        long id = ladder(r, "NVDA round trip", "NVDA");
        r.addMembers(id, List.of("II SIPP|REF1", "II SIPP|REF2"), DAY);

        r.removeMember("II SIPP|REF1");

        assertEquals(List.of("II SIPP|REF2"), r.members().get(id));
        assertEquals(1, r.groups().size());
    }

    /** A switch carries both listings; a ladder has one security and no second line at all. */
    @Test
    void aSwitchRoundTripsWithBothListingsAndALadderWithNeither() {
        var r = repo();
        long switchId = r.create(GroupType.SHARE_CLASS_SWITCH, "Nasdaq line to USD", "eqqq",
                "eqqu", null, DAY);
        ladder(r, "NVDA round trip", "NVDA");

        OrderGroup sw = r.groups().stream().filter(g -> g.id() == switchId).findFirst().orElseThrow();
        assertEquals(GroupType.SHARE_CLASS_SWITCH, sw.type());
        assertEquals("EQQQ", sw.symbol(), "the line being left");
        assertEquals("EQQU", sw.symbolTo(), "the line being entered, upper-cased like the first");
        assertTrue(sw.isSwitch());

        OrderGroup ladder = r.groups().stream().filter(g -> g.id() != switchId).findFirst().orElseThrow();
        assertNull(ladder.symbolTo(), "a ladder has one security by definition");
        assertTrue(ladder.isLadder());
    }

    /**
     * A database written before switches existed has no second-symbol column, and opening it adds
     * one rather than rebuilding the table. Every row already there is a ladder, whose second
     * listing is null anyway.
     */
    @Test
    void aDatabaseWithoutTheSecondSymbolColumnGainsItWithoutLosingItsGroups() throws Exception {
        try (var conn = new JdbcConnectionFactory(dbDir).open();
             var st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS order_group");
            st.execute("CREATE TABLE order_group (id INTEGER PRIMARY KEY, type TEXT NOT NULL, "
                    + "name TEXT NOT NULL, symbol TEXT, note TEXT, created_at TEXT NOT NULL)");
            st.executeUpdate("INSERT INTO order_group (type, name, symbol, created_at) "
                    + "VALUES ('LADDER', 'Older ladder', 'NVDA', '2026-09-01')");
        }

        var r = repo();
        assertEquals(1, r.groups().size());
        assertEquals("Older ladder", r.groups().get(0).name());
        assertNull(r.groups().get(0).symbolTo());
        // And the column is now really there — a switch can be written into the same database.
        assertTrue(r.create(GroupType.SHARE_CLASS_SWITCH, "Nasdaq line to USD", "EQQQ", "EQQU",
                null, DAY) > 0);
        assertEquals(2, r.groups().size());
    }

    /** A group written by a build that knows a type this one does not is skipped, never guessed. */
    @Test
    void aGroupOfAnUnknownTypeIsNotReadAsALadder() throws Exception {
        var r = repo();
        ladder(r, "Real ladder", "NVDA");
        try (var conn = new JdbcConnectionFactory(dbDir).open();
             var st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO order_group (type, name, symbol, created_at) "
                    + "VALUES ('ROTATION', 'Some future thing', 'NVDA', '2026-09-01')");
        }

        assertEquals(1, r.groups().size());
        assertEquals("Real ladder", r.groups().get(0).name());
    }
}
