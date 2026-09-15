package com.portfolio.persistence;

import com.portfolio.domain.model.OpenOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderHistoryRepositoryTest {

    private static final LocalDate DAY1 = LocalDate.parse("2026-08-01");
    private static final String AJ = "AJ Bell SIPP";

    @TempDir
    Path dbDir;

    private OrderHistoryRepository repo() {
        return new OrderHistoryRepository(new JdbcConnectionFactory(dbDir));
    }

    private static OpenOrder order(String reference, String symbol, String price, String status) {
        return OpenOrder.parsed(AJ, symbol, symbol + " plc", "Buy", "Limit",
                new BigDecimal("100"), new BigDecimal(price), false, new BigDecimal("1000"),
                "GBP", DAY1, null, null, status, reference);
    }

    private static Set<String> openKeys(OpenOrder... os) {
        return java.util.Arrays.stream(os).map(OpenOrder::identity)
                .collect(java.util.stream.Collectors.toSet());
    }

    private OrderHistoryRepository.HistoricalOrder onlyClosed(OrderHistoryRepository r) {
        List<OrderHistoryRepository.HistoricalOrder> closed = r.closed();
        assertEquals(1, closed.size(), () -> "expected one closed order, got " + closed);
        return closed.get(0);
    }

    // ---- what closes an order ---------------------------------------------

    /** An open order is recorded but is not history — it must not show in the closed list. */
    @Test
    void anOpenOrderIsRecordedWithoutBeingClosed() {
        var r = repo();
        OpenOrder o = order("REF1", "LSEG", "80.00", "In progress");
        r.record(AJ, List.of(o), openKeys(o), DAY1);

        assertTrue(r.closed().isEmpty());
        assertEquals(1, r.allKeys().size(), "the row exists so a note has somewhere to live");
    }

    /** Dropping out of the export is how most brokers report a fill or a cancel. */
    @Test
    void anOrderMissingFromTheNextExportIsClosed() {
        var r = repo();
        OpenOrder o = order("REF1", "LSEG", "80.00", "In progress");
        r.record(AJ, List.of(o), openKeys(o), DAY1);

        r.record(AJ, List.of(), Set.of(), DAY1.plusDays(1));

        assertEquals("2026-08-02", onlyClosed(r).closedAt());
    }

    /** The other way: the export restates it with a terminal status. */
    @Test
    void anOrderRestatedWithATerminalStatusIsClosedAndKeepsThatStatus() {
        var r = repo();
        OpenOrder open = order("REF1", "LSEG", "80.00", "In progress");
        r.record(AJ, List.of(open), openKeys(open), DAY1);

        OpenOrder dealt = order("REF1", "LSEG", "80.00", "Dealt");
        r.record(AJ, List.of(dealt), Set.of(), DAY1.plusDays(1));

        var closed = onlyClosed(r);
        assertEquals("Dealt", closed.status(),
                "the unfiltered parse is passed in so a terminal restatement keeps its status");
        assertEquals("2026-08-02", closed.closedAt());
    }

    // ---- what the Status column prints -------------------------------------

    /**
     * Three brokers, six spellings, three outcomes. The raw word stays in the row as evidence;
     * what the panel prints is one vocabulary.
     */
    @Test
    void theBrokersSpellingsAreNormalisedForDisplayButStoredVerbatim() {
        var r = repo();
        OpenOrder dealt = order("REF1", "LSEG", "80.00", "In progress");
        OpenOrder executed = order("REF2", "NVDA", "200.00", "In progress");
        OpenOrder cancelled = order("REF3", "EQQU", "689.30", "In progress");
        r.record(AJ, List.of(dealt, executed, cancelled), openKeys(dealt, executed, cancelled), DAY1);

        r.record(AJ, List.of(order("REF1", "LSEG", "80.00", "Dealt"),
                        order("REF2", "NVDA", "200.00", "EXECUTED"),
                        order("REF3", "EQQU", "689.30", "Canceled")),
                Set.of(), DAY1.plusDays(1));

        var byKey = r.closed().stream().collect(java.util.stream.Collectors.toMap(
                OrderHistoryRepository.HistoricalOrder::symbol, h -> h));
        assertEquals("Filled", byKey.get("LSEG").state());
        assertEquals("Filled", byKey.get("NVDA").state());
        assertEquals("Cancelled", byKey.get("EQQU").state());
        assertEquals("EXECUTED", byKey.get("NVDA").status(), "the broker's own word is the evidence");
        byKey.values().forEach(h -> assertFalse(h.stateInferred(), "the broker said so"));
    }

    /**
     * The third state, and the only inferred one: the broker stopped listing the order without
     * ever restating it, so its last-seen status is still an open one. Printing that "Open" in a
     * table of closed orders said the opposite of the truth.
     */
    @Test
    void anOrderClosedByAbsenceReadsAsNoLongerListedRatherThanOpen() {
        var r = repo();
        OpenOrder o = order("REF1", "NVDA", "203.43", "Open");
        r.record(AJ, List.of(o), openKeys(o), DAY1);
        r.record(AJ, List.of(), Set.of(), DAY1.plusDays(1));

        var closed = onlyClosed(r);
        assertEquals("No longer listed", closed.state());
        assertTrue(closed.stateInferred(), "nothing said it filled or cancelled — we inferred it");
        assertEquals("Open", closed.status(), "the last-seen status is kept for the hover text");
    }

    /** A status this app has never seen is still not reported as filled or cancelled. */
    @Test
    void anUnrecognisedStatusIsNotGuessedAtEither() {
        var r = repo();
        OpenOrder o = order("REF1", "LSEG", "80.00", "Awaiting market");
        r.record(AJ, List.of(o), openKeys(o), DAY1);
        r.record(AJ, List.of(), Set.of(), DAY1.plusDays(1));

        var closed = onlyClosed(r);
        assertEquals("No longer listed", closed.state());
        assertTrue(closed.stateInferred());
    }

    /**
     * The whole point of the feature: a modified order gets a new broker reference, so the version
     * that was replaced keeps its own note and both can be read side by side.
     */
    @Test
    void aModifiedOrderIsANewOrderAndTheReplacedOneKeepsItsNote() {
        var r = repo();
        OpenOrder first = order("REF1", "LSEG", "80.00", "In progress");
        r.record(AJ, List.of(first), openKeys(first), DAY1);
        r.saveNote(first.identity(), "too far below the market");

        OpenOrder replacement = order("REF2", "LSEG", "84.00", "In progress");
        r.record(AJ, List.of(replacement), openKeys(replacement), DAY1.plusDays(1));
        r.saveNote(replacement.identity(), "raised it");

        var closed = onlyClosed(r);
        assertEquals("too far below the market", closed.note());
        assertEquals(0, new BigDecimal("80.00").compareTo(closed.price()));
        assertEquals("raised it", r.notes().get(replacement.identity()));
    }

    /** A broker restating an order it had dropped must come back out of history, not sit in both. */
    @Test
    void anOrderThatReappearsAsOpenIsReopened() {
        var r = repo();
        OpenOrder o = order("REF1", "LSEG", "80.00", "In progress");
        r.record(AJ, List.of(o), openKeys(o), DAY1);
        r.record(AJ, List.of(), Set.of(), DAY1.plusDays(1));
        assertEquals(1, r.closed().size());

        r.record(AJ, List.of(o), openKeys(o), DAY1.plusDays(2));
        assertTrue(r.closed().isEmpty());
    }

    /** One broker's export must never close another broker's orders. */
    @Test
    void oneBrokersExportDoesNotCloseAnothers() {
        var r = repo();
        OpenOrder aj = order("REF1", "LSEG", "80.00", "In progress");
        OpenOrder ii = OpenOrder.parsed("II SIPP", "NVDA", null, "Buy", "Limit",
                new BigDecimal("10"), new BigDecimal("150"), false, new BigDecimal("1500"),
                "USD", DAY1, null, null, "PENDING", "IIREF");
        r.record(AJ, List.of(aj), openKeys(aj), DAY1);
        r.record("II SIPP", List.of(ii), openKeys(ii), DAY1);

        r.record(AJ, List.of(), Set.of(), DAY1.plusDays(1));

        assertEquals(1, r.closed().size());
        assertEquals(AJ, r.closed().get(0).broker());
    }

    // ---- notes -------------------------------------------------------------

    /** A re-parse must never overwrite the user's own text with the broker's export. */
    @Test
    void reRecordingDoesNotDisturbTheNote() {
        var r = repo();
        OpenOrder o = order("REF1", "LSEG", "80.00", "In progress");
        r.record(AJ, List.of(o), openKeys(o), DAY1);
        r.saveNote(o.identity(), "conviction buy");

        r.record(AJ, List.of(o), openKeys(o), DAY1.plusDays(1));

        assertEquals("conviction buy", r.notes().get(o.identity()));
    }

    @Test
    void anEmptyNoteClearsIt() {
        var r = repo();
        OpenOrder o = order("REF1", "LSEG", "80.00", "In progress");
        r.record(AJ, List.of(o), openKeys(o), DAY1);
        r.saveNote(o.identity(), "something");
        r.saveNote(o.identity(), "   ");

        assertNull(r.notes().get(o.identity()));
    }

    /** A stale page whose order has been pruned must not resurrect it as a bare row. */
    @Test
    void aNoteOnAnUnknownOrderIsRejectedRatherThanCreatingOne() {
        var r = repo();
        assertFalse(r.saveNote("AJ Bell SIPP|GONE", "note"));
        assertTrue(r.allKeys().isEmpty());
    }

    // ---- retention ---------------------------------------------------------

    @Test
    void closedOrdersArePrunedAfterTheRetentionWindow() {
        var r = repo();
        OpenOrder o = order("REF1", "LSEG", "80.00", "In progress");
        r.record(AJ, List.of(o), openKeys(o), DAY1);
        r.record(AJ, List.of(), Set.of(), DAY1);

        assertEquals(0, r.prune(DAY1.plusDays(OrderHistoryRepository.RETENTION_DAYS)),
                "still inside the window");
        assertEquals(1, r.closed().size());

        assertEquals(1, r.prune(DAY1.plusDays(OrderHistoryRepository.RETENTION_DAYS + 1)));
        assertTrue(r.closed().isEmpty());
        assertTrue(r.allKeys().isEmpty());
    }

    /** An order resting for months is not history and must survive any prune. */
    @Test
    void aLongRestingOpenOrderIsNeverPruned() {
        var r = repo();
        OpenOrder o = order("REF1", "LSEG", "80.00", "In progress");
        r.record(AJ, List.of(o), openKeys(o), DAY1);
        r.saveNote(o.identity(), "GTC, watching");

        assertEquals(0, r.prune(DAY1.plusYears(1)));
        assertEquals("GTC, watching", r.notes().get(o.identity()));
    }

    /** Retention runs from when the order closed, not from the last import. */
    @Test
    void theRetentionClockStartsWhenTheOrderClosed() {
        var r = repo();
        OpenOrder o = order("REF1", "LSEG", "80.00", "In progress");
        r.record(AJ, List.of(o), openKeys(o), DAY1);
        r.record(AJ, List.of(), Set.of(), DAY1);           // closed on day 1
        r.record(AJ, List.of(), Set.of(), DAY1.plusDays(20));  // later imports must not refresh it

        assertEquals("2026-08-01", onlyClosed(r).closedAt());
        assertEquals(1, r.prune(DAY1.plusDays(OrderHistoryRepository.RETENTION_DAYS + 1)));
    }

    // ---- identity ----------------------------------------------------------

    /** Citi exports no reference, so a Roth order is identified by what describes it. */
    @Test
    void anOrderWithNoReferenceStillGetsAStableIdentity() {
        OpenOrder a = OpenOrder.parsed("Roth IRA", "NVDA", null, "Buy", "Limit",
                new BigDecimal("10"), new BigDecimal("150"), false, new BigDecimal("1500"),
                "USD", DAY1, null, "GTC", "Open", null);
        OpenOrder same = OpenOrder.parsed("Roth IRA", "NVDA", null, "Buy", "Limit",
                new BigDecimal("10.00"), new BigDecimal("150.0"), false, new BigDecimal("1500"),
                "USD", DAY1, null, "GTC", "Open", null);
        OpenOrder repriced = OpenOrder.parsed("Roth IRA", "NVDA", null, "Buy", "Limit",
                new BigDecimal("10"), new BigDecimal("155"), false, new BigDecimal("1550"),
                "USD", DAY1, null, "GTC", "Open", null);

        assertEquals(a.identity(), same.identity(), "trailing zeros are not a different order");
        assertFalse(a.identity().equals(repriced.identity()), "a repriced order is a new order");
    }

    @Test
    void twoBrokersCannotCollideOnTheSameReference() {
        OpenOrder aj = order("REF1", "LSEG", "80.00", "In progress");
        OpenOrder ii = OpenOrder.parsed("II SIPP", "LSEG", null, "Buy", "Limit",
                new BigDecimal("100"), new BigDecimal("80.00"), false, new BigDecimal("1000"),
                "GBP", DAY1, null, null, "PENDING", "REF1");
        assertFalse(aj.identity().equals(ii.identity()));
    }
}
