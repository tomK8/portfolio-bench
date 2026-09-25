package com.portfolio.application;

import com.portfolio.application.ReminderService.EarningsRow;
import com.portfolio.application.ReminderService.ManualRow;
import com.portfolio.application.ReminderService.ReminderReport;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.port.AlertNotifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The send path: once a day, every day, and never twice.
 *
 * <p>"Daily" is enforced here rather than by the schedule — the job is called every half hour, so
 * without the per-day key the user would be mailed forty-eight times about the same reminder. The
 * other half of the contract is the retry: the sent-set is only written after the mail actually
 * goes, because for a tool whose whole purpose is not to forget, marking a day done and dropping
 * the message is the worst available failure.
 */
class ReminderJobTest {

    @TempDir
    Path dbDir;

    private static final LocalDate TODAY = LocalDate.now();

    /** Records what it was asked to send, and can be told to fail. */
    private static final class FakeNotifier implements AlertNotifier {
        final List<String> sent = new ArrayList<>();
        boolean enabled = true;
        boolean deliver = true;

        @Override public boolean isEnabled() { return enabled; }

        @Override public boolean notify(String subject, String body) {
            if (!deliver) return false;
            sent.add(subject + "\n" + body);
            return true;
        }
    }

    /**
     * Stands in for the real service so the job can be driven by a canned report. The job's whole
     * job is to turn "what is due" into "what to mail once", and the service's own derivation is
     * pinned separately in {@link ReminderServiceTest}.
     */
    private static final class StubService extends ReminderService {
        ReminderReport report = canned(List.of(), List.of());

        StubService() {
            super(null, null, null, null);
        }

        @Override public ReminderReport report() { return report; }
    }

    private static ReminderReport canned(List<ManualRow> manual, List<EarningsRow> earnings) {
        return new ReminderReport(TODAY, 7, 45, true, manual, earnings, List.of(), List.of());
    }

    private static ManualRow manual(long id, boolean due) {
        return new ManualRow(id, "BARC", TODAY, "the 5% floor", "trim or hold", true, due, 0L);
    }

    private static EarningsRow earnings(String symbol, boolean due, Long daysUntil) {
        return new EarningsRow(symbol, symbol + " Inc", TODAY.plusDays(daysUntil == null ? 0 : daysUntil),
                "AMC", "calendar", null, true, due, daysUntil);
    }

    private record Rig(StubService service, FakeNotifier notifier, ReminderJob job) {
    }

    private Rig rig() {
        var service = new StubService();
        var notifier = new FakeNotifier();
        return new Rig(service, notifier,
                new ReminderJob(service, notifier, new KeyValueStore(dbDir)));
    }

    @Test
    void nothingDueSendsNothing() {
        var r = rig();
        r.service().report = canned(List.of(manual(1, false)), List.of(earnings("AAPL", false, 30L)));
        r.job().run();
        assertTrue(r.notifier().sent.isEmpty());
    }

    @Test
    void everythingDueArrivesAsOneEmail() {
        // One message, not one per item: three separate emails for three reminders on the same
        // morning is the flurry that gets a sender filtered into a folder and stops being read.
        var r = rig();
        r.service().report = canned(
                List.of(manual(1, true), manual(2, true)),
                List.of(earnings("AAPL", true, 3L)));
        r.job().run();

        assertEquals(1, r.notifier().sent.size());
        assertTrue(r.notifier().sent.get(0).startsWith("Reminders: 3 items"));
    }

    @Test
    void theSameDayDoesNotSendTwice() {
        // The scheduler calls this every half hour. The dedup, not the cadence, is what makes it
        // daily.
        var r = rig();
        r.service().report = canned(List.of(manual(1, true)), List.of());
        r.job().run();
        r.job().run();
        r.job().run();
        assertEquals(1, r.notifier().sent.size());
    }

    @Test
    void anItemThatBecomesDueLaterInTheDayIsStillSent() {
        // Half a message is not a reason to suppress the other half: the first send covered one
        // reminder, and a second that came due afterwards is news the first email did not carry.
        var r = rig();
        r.service().report = canned(List.of(manual(1, true)), List.of());
        r.job().run();

        r.service().report = canned(List.of(manual(1, true), manual(2, true)), List.of());
        r.job().run();

        assertEquals(2, r.notifier().sent.size());
        assertTrue(r.notifier().sent.get(1).startsWith("Reminders: 1 item"),
                "only the newly-due one, not the one already said today");
    }

    @Test
    void aFailedSendIsRetriedRatherThanMarkedDone() {
        // The sent-set is written only after delivery succeeds. Marking the day done on a dead
        // mail server would silently drop the reminder for good — the one outcome this feature
        // cannot have.
        var r = rig();
        r.service().report = canned(List.of(manual(1, true)), List.of());

        r.notifier().deliver = false;
        r.job().run();
        assertTrue(r.notifier().sent.isEmpty());

        r.notifier().deliver = true;
        r.job().run();
        assertEquals(1, r.notifier().sent.size(), "the next tick picks it up again");
    }

    @Test
    void tomorrowSendsItAgain() {
        // What "daily until you stop it" means: the key carries the day, so a new day is a new key.
        var r = rig();
        r.service().report = canned(List.of(manual(1, true)), List.of());
        r.job().run();

        var kv = new KeyValueStore(dbDir);
        assertEquals(List.of("R|1|" + TODAY), new ArrayList<>(kv.getStringSet(ReminderJob.SENT_KEY)));

        // Simulate the day rolling over by leaving only a key from yesterday behind.
        kv.putStringSet(ReminderJob.SENT_KEY, List.of("R|1|" + TODAY.minusDays(1)));
        r.job().run();
        assertEquals(2, r.notifier().sent.size());
    }

    @Test
    void yesterdaysKeysArePrunedEvenOnAQuietDay() {
        // Otherwise the file grows a line per reminder per day, forever.
        var kv = new KeyValueStore(dbDir);
        kv.putStringSet(ReminderJob.SENT_KEY,
                List.of("R|1|" + TODAY.minusDays(1), "E|AAPL|" + TODAY.minusDays(9)));

        var r = rig();
        r.service().report = canned(List.of(manual(1, false)), List.of());
        r.job().run();

        assertTrue(kv.getStringSet(ReminderJob.SENT_KEY).isEmpty());
    }

    @Test
    void withNoMailConfiguredTheJobDoesNothingAtAll() {
        // Not even the dedup keys: nothing was delivered, so nothing should be recorded as sent —
        // otherwise turning SMTP on would start by skipping the day it was turned on.
        var r = rig();
        r.notifier().enabled = false;
        r.service().report = canned(List.of(manual(1, true)), List.of());
        r.job().run();

        assertTrue(r.notifier().sent.isEmpty());
        assertTrue(new KeyValueStore(dbDir).getStringSet(ReminderJob.SENT_KEY).isEmpty());
    }

    @Test
    void theEmailCarriesTheRuleAndTheActionVerbatim() {
        // They are the user's own sentences and the job's part is to deliver them on the right
        // day, not to interpret or summarise them.
        var r = rig();
        r.service().report = canned(List.of(manual(1, true)), List.of());
        r.job().run();

        String body = r.notifier().sent.get(0);
        assertTrue(body.contains("Rule: the 5% floor"), body);
        assertTrue(body.contains("Action: trim or hold"), body);
        assertTrue(body.contains("BARC"), body);
    }

    @Test
    void anEarningsLineSaysHowCloseThePrintIs() {
        var r = rig();
        r.service().report = canned(List.of(), List.of(
                earnings("AAPL", true, 0L), earnings("MSFT", true, 1L), earnings("NVDA", true, 5L)));
        r.job().run();

        String body = r.notifier().sent.get(0);
        assertTrue(body.contains("AAPL (AAPL Inc) reports today"), body);
        assertTrue(body.contains("MSFT (MSFT Inc) reports tomorrow"), body);
        assertTrue(body.contains("NVDA (NVDA Inc) reports in 5 days"), body);
    }

    @Test
    void anEstimatedDateIsMarkedRatherThanPrintedAsFixed() {
        // A day Yahoo inferred from the past reporting rhythm can move by weeks. Printing it like
        // an announced one would state something the source never said.
        var r = rig();
        r.service().report = canned(List.of(), List.of(new EarningsRow(
                "BARC", "Barclays plc", TODAY.plusDays(4), null, "snapshot", true, true, true, 4L)));
        r.job().run();
        assertTrue(r.notifier().sent.get(0).contains("(estimated date)"), r.notifier().sent.get(0));
    }

    @Test
    void anOverdueReminderSaysHowLongItHasBeenWaiting() {
        var r = rig();
        r.service().report = canned(List.of(new ManualRow(
                1, "BARC", TODAY.minusDays(12), "rule", "action", true, true, -12L)), List.of());
        r.job().run();
        assertTrue(r.notifier().sent.get(0).contains("(12 days ago)"), r.notifier().sent.get(0));
    }

    @Test
    void aReminderWithNoSymbolReadsAsAboutThePortfolio() {
        var r = rig();
        r.service().report = canned(List.of(new ManualRow(
                1, null, TODAY, "rebalance", null, true, true, 0L)), List.of());
        r.job().run();
        assertTrue(r.notifier().sent.get(0).contains("Portfolio — due"), r.notifier().sent.get(0));
    }

    @Test
    void manualAndEarningsKeysCannotCollide() {
        // Reminder 1 and a symbol's earnings reminder are different things and must each get their
        // own daily slot.
        var r = rig();
        r.service().report = canned(List.of(manual(1, true)), List.of(earnings("AAPL", true, 2L)));
        r.job().run();

        assertEquals(2, new KeyValueStore(dbDir).getStringSet(ReminderJob.SENT_KEY).size());
        assertFalse(r.notifier().sent.isEmpty());
    }
}
