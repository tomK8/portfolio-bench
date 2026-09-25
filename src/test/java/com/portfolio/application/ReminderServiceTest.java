package com.portfolio.application;

import com.portfolio.adapter.YahooQuoteSummaryFetcher.QuoteSummary;
import com.portfolio.application.ReminderService.EarningsRow;
import com.portfolio.application.ReminderService.ManualRow;
import com.portfolio.application.ReminderService.ReminderReport;
import com.portfolio.domain.model.EarningsEvent;
import com.portfolio.persistence.EarningsCalendarRepository;
import com.portfolio.persistence.FundamentalsRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.ReminderRepository;
import com.portfolio.persistence.WatchlistRepository;
import com.portfolio.port.AlertNotifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reminders screen: stored reminders on one side, derived earnings reminders on the other.
 *
 * <p>What these pin is the derivation, because it is the half with no row to inspect. The universe
 * has to be the union of the three sets the rest of the app already publishes (a fourth, hand-kept
 * list would go stale the first time a position was sold), a symbol the earnings feeds cannot date
 * has to be named rather than quietly dropped, and a mute has to be a standing instruction about a
 * symbol rather than about one print.
 */
class ReminderServiceTest {

    @TempDir
    Path dbDir;

    private static final LocalDate TODAY = LocalDate.now();

    /** Enabled/disabled is all the service asks of the notifier, and nothing here sends mail. */
    private record Notifier(boolean on) implements AlertNotifier {
        @Override public boolean notify(String subject, String body) { return true; }
        @Override public boolean isEnabled() { return on; }
    }

    private ReminderService service(boolean deliveryOn) {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        // nextEarnings reads the calendar cache and the fundamentals snapshot and nothing else —
        // no feed, no allocation snapshot, no ticker map — so those stay null here rather than
        // being stood up to be ignored.
        var earnings = new EarningsCalendarService(
                null, new EarningsCalendarRepository(cf), null, null,
                new FundamentalsRepository(cf), null);
        return new ReminderService(new ReminderRepository(cf), earnings, kv, new Notifier(deliveryOn));
    }

    private void cacheCalendarEvent(String symbol, LocalDate date, String session) {
        new EarningsCalendarRepository(new JdbcConnectionFactory(dbDir))
                .save(date, List.of(new EarningsEvent(date, symbol, symbol + " Inc", session,
                        1_000_000_000L, 1.5, 12, 1.2)));
    }

    private void cacheSnapshotDate(String symbol, LocalDate date, boolean estimated) {
        new FundamentalsRepository(new JdbcConnectionFactory(dbDir)).save(symbol,
                new QuoteSummary(symbol, "GBP", null, null, null, null, null, null, null, null,
                        null, Map.of(),
                        Map.of("earningsDate", date.toString(),
                                "earningsDateEstimate", String.valueOf(estimated),
                                "companyName", symbol + " plc"),
                        false),
                Instant.now());
    }

    private void universe(String heldCsv, String watchedCsv, String orderedCsv) {
        var kv = new KeyValueStore(dbDir);
        kv.putStringSet(PriceFetchSupport.HELD_SYMBOLS_KEY, split(heldCsv));
        kv.putStringSet(WatchlistRepository.WATCHLIST_SYMBOLS_KEY, split(watchedCsv));
        kv.putStringSet(OpenOrdersService.ORDER_SYMBOLS_KEY, split(orderedCsv));
    }

    private static List<String> split(String csv) {
        return csv.isBlank() ? List.of() : List.of(csv.split(","));
    }

    // ---- hand-entered reminders --------------------------------------------

    @Test
    void aSavedReminderComesBackWithItsRuleAndAction() {
        var s = service(true);
        s.add("barc", TODAY.toString(), "5% floor still holding?", "trim to 3% or let it run");

        ManualRow r = s.report().manual().get(0);
        assertEquals("BARC", r.symbol(), "stored uppercase, like every other symbol");
        assertEquals("5% floor still holding?", r.rule());
        assertEquals("trim to 3% or let it run", r.action());
        assertTrue(r.enabled());
        assertTrue(r.due(), "dated today, so it fires today");
    }

    @Test
    void aReminderWithoutASymbolIsAboutThePortfolio() {
        var s = service(true);
        s.add("", TODAY.toString(), "rebalance check", null);
        assertEquals(null, s.report().manual().get(0).symbol());
    }

    @Test
    void theDateRuleAndActionAreUpdatable() {
        var s = service(true);
        long id = s.add("AAPL", TODAY.toString(), "old rule", "old action");

        assertTrue(s.update(id, TODAY.plusDays(30).toString(), "new rule", "new action"));

        ManualRow r = s.report().manual().get(0);
        assertEquals(TODAY.plusDays(30), r.dueDate());
        assertEquals("new rule", r.rule());
        assertEquals("new action", r.action());
        assertFalse(r.due(), "pushed into the future, so it stops firing");
    }

    @Test
    void clearingTheActionClearsIt() {
        // An empty action is stored as absent, not as an empty string that would print as a blank
        // "Action:" line in the email.
        var s = service(true);
        long id = s.add("AAPL", TODAY.toString(), "rule", "action");
        s.update(id, TODAY.toString(), "rule", "");
        assertEquals(null, s.report().manual().get(0).action());
    }

    @Test
    void switchingOffKeepsTheRowButStopsIt() {
        var s = service(true);
        long id = s.add("AAPL", TODAY.minusDays(3).toString(), "rule", "action");
        assertTrue(s.report().manual().get(0).due());

        s.setEnabled(id, false);
        ManualRow r = s.report().manual().get(0);
        assertFalse(r.enabled());
        assertFalse(r.due(), "off means silent");
        assertEquals("rule", r.rule(), "and the row is still there to switch back on");

        s.setEnabled(id, true);
        assertTrue(s.report().manual().get(0).due());
    }

    @Test
    void deletingRemovesItOutright() {
        var s = service(true);
        long id = s.add("AAPL", TODAY.toString(), "rule", null);
        assertTrue(s.delete(id));
        assertTrue(s.report().manual().isEmpty());
        assertFalse(s.delete(id), "deleting it twice is not an error, just nothing to do");
    }

    @Test
    void anOverdueReminderReportsHowLongItHasBeenWaiting() {
        var s = service(true);
        s.add("AAPL", TODAY.minusDays(6).toString(), "rule", null);
        ManualRow r = s.report().manual().get(0);
        assertTrue(r.due());
        assertEquals(-6L, r.daysUntil());
    }

    @Test
    void aMissingDateOrRuleIsRefusedRatherThanGuessed() {
        var s = service(true);
        // A blank date defaulted to today would start mailing the instant it was saved, which is
        // almost never what was meant; a blank rule is a reminder that says nothing when it fires.
        assertThrows(IllegalArgumentException.class, () -> s.add("AAPL", "", "rule", null));
        assertThrows(IllegalArgumentException.class, () -> s.add("AAPL", "not-a-date", "rule", null));
        assertThrows(IllegalArgumentException.class, () -> s.add("AAPL", TODAY.toString(), "  ", null));
    }

    // ---- earnings reminders ------------------------------------------------

    @Test
    void theUniverseIsPortfolioPlusWatchlistPlusOpenOrders() {
        var s = service(true);
        universe("HELD", "WATCHED", "ORDERED");
        assertEquals(Set.of("HELD", "WATCHED", "ORDERED"), s.universe());
    }

    @Test
    void aSymbolInTwoOfTheThreeIsCountedOnce() {
        var s = service(true);
        universe("AAPL,MSFT", "AAPL", "MSFT");
        assertEquals(Set.of("AAPL", "MSFT"), s.universe());
    }

    @Test
    void anEarningsRowIsBuiltForEverySymbolWithAKnownDate() {
        var s = service(true);
        universe("AAPL", "", "");
        cacheCalendarEvent("AAPL", TODAY.plusDays(3), "AMC");

        EarningsRow r = s.report().earnings().get(0);
        assertEquals("AAPL", r.symbol());
        assertEquals(TODAY.plusDays(3), r.date());
        assertEquals("AMC", r.time());
        assertEquals("calendar", r.source());
        assertTrue(r.due(), "three days out is inside the week's notice");
        assertTrue(r.enabled(), "on unless muted");
    }

    @Test
    void theCalendarOutranksTheSnapshotWhenBothHaveADate() {
        // Same precedence the Earnings tab's holdings section uses: only the calendar carries a
        // session, so a row must not end up with the snapshot's date and the calendar's geometry.
        var s = service(true);
        universe("AAPL", "", "");
        cacheCalendarEvent("AAPL", TODAY.plusDays(2), "BMO");
        cacheSnapshotDate("AAPL", TODAY.plusDays(9), false);

        EarningsRow r = s.report().earnings().get(0);
        assertEquals(TODAY.plusDays(2), r.date());
        assertEquals("calendar", r.source());
    }

    @Test
    void theSnapshotAnswersForListingsTheUsOnlyCalendarNeverCarries() {
        var s = service(true);
        universe("", "BARC", "");
        cacheSnapshotDate("BARC", TODAY.plusDays(4), true);

        EarningsRow r = s.report().earnings().get(0);
        assertEquals(TODAY.plusDays(4), r.date());
        assertEquals("snapshot", r.source());
        assertEquals(null, r.time(), "the weaker source carries no session and does not borrow one");
        assertEquals(Boolean.TRUE, r.estimate(), "and says the day was inferred, not announced");
        assertTrue(r.due());
    }

    @Test
    void aSnapshotDateAlreadyBehindUsProducesNoRow() {
        // Yahoo leaves the last date it knows in place until it learns the next one. Treating it
        // as a forward date would mail a warning about a print that already happened.
        var s = service(true);
        universe("BARC", "", "");
        cacheSnapshotDate("BARC", TODAY.minusDays(10), false);

        ReminderReport report = s.report();
        assertTrue(report.earnings().isEmpty());
        assertEquals(List.of("BARC"), report.undated(), "and it is named, not silently dropped");
    }

    @Test
    void aDateBeyondTheWeekIsListedButNotYetSending() {
        var s = service(true);
        universe("BARC", "", "");
        cacheSnapshotDate("BARC", TODAY.plusDays(30), false);

        EarningsRow r = s.report().earnings().get(0);
        assertFalse(r.due(), "thirty days out: worth seeing, not yet worth mailing");
        assertEquals(30L, r.daysUntil());
    }

    @Test
    void symbolsNoSourceCanDateAreNamed() {
        // The convention the whole app runs on: anything that cannot be resolved is reported by
        // name, never absorbed. A screen that just omitted these would look identical to one where
        // every position is covered.
        var s = service(true);
        universe("AAPL,MYSTERY", "", "");
        cacheCalendarEvent("AAPL", TODAY.plusDays(1), "BMO");

        ReminderReport report = s.report();
        assertEquals(List.of("AAPL"), symbols(report.earnings()));
        assertEquals(List.of("MYSTERY"), report.undated());
    }

    @Test
    void mutingASymbolStopsItButLeavesItListed() {
        var s = service(true);
        universe("AAPL", "", "");
        cacheCalendarEvent("AAPL", TODAY.plusDays(2), "AMC");

        s.setEarningsEnabled("AAPL", false);
        EarningsRow off = s.report().earnings().get(0);
        assertFalse(off.enabled());
        assertFalse(off.due(), "muted means nothing is sent");
        assertEquals(TODAY.plusDays(2), off.date(), "but the row stays, so the mute is visible");

        s.setEarningsEnabled("aapl", true);
        assertTrue(s.report().earnings().get(0).due(), "and lifting it is one click, case-insensitively");
    }

    @Test
    void rowsAreOrderedBySoonestFirst() {
        var s = service(true);
        universe("LATER,SOONER", "", "");
        cacheCalendarEvent("LATER", TODAY.plusDays(20), "BMO");
        cacheCalendarEvent("SOONER", TODAY.plusDays(2), "BMO");
        assertEquals(List.of("SOONER", "LATER"), symbols(s.report().earnings()));
    }

    @Test
    void bondsNeverProduceAnEarningsRowOrAGapReport() {
        // A gilt reports no earnings, so its silence is the right answer rather than a hole — it
        // belongs in neither list.
        var s = service(true);
        universe("GILT 4.25 2032,AAPL", "", "");
        cacheCalendarEvent("AAPL", TODAY.plusDays(1), "BMO");

        ReminderReport report = s.report();
        assertEquals(List.of("AAPL"), symbols(report.earnings()));
        assertFalse(report.undated().stream().anyMatch(x -> x.startsWith("GILT")));
    }

    // ---- the screen's own state --------------------------------------------

    @Test
    void theReportSaysWhetherAnythingIsActuallyBeingDelivered() {
        // With no SMTP configured every row is a note on a page. A due row that silently sends
        // nothing is the one failure a reminder tool cannot afford, so the screen has to be able
        // to say so.
        assertTrue(service(true).report().deliveryEnabled());
        assertFalse(service(false).report().deliveryEnabled());
    }

    @Test
    void anEmptyUniverseIsNotAFailure() {
        ReminderReport report = service(true).report();
        assertTrue(report.earnings().isEmpty());
        assertTrue(report.undated().isEmpty());
        assertTrue(report.warnings().isEmpty());
    }

    private static List<String> symbols(List<EarningsRow> rows) {
        return rows.stream().map(EarningsRow::symbol).collect(Collectors.toList());
    }
}
