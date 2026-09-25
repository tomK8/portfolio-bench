package com.portfolio.application;

import com.portfolio.application.ReminderService.EarningsRow;
import com.portfolio.application.ReminderService.ManualRow;
import com.portfolio.application.ReminderService.ReminderReport;
import com.portfolio.domain.Reminders;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.port.AlertNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mails whatever is due today, once, and goes quiet until tomorrow.
 *
 * <p>Reads the very same {@link ReminderReport} the tab renders, so the screen and the mail can
 * never disagree about what is due — the same reason {@link WatchlistAlertJob} reads
 * {@code WatchlistService} rather than re-deriving the triggers.
 *
 * <p><b>"Daily" is enforced by the dedup key, not by the schedule.</b> The job is called every half
 * hour and each reminder is keyed {@code …|yyyy-MM-dd} for the day it was sent, so a tick that finds
 * nothing new does nothing at all. That separation is what makes the cadence a question of latency
 * rather than volume: this is a laptop that is shut more often than it is open, and a fixed morning
 * cron would simply skip any day the machine happened to be asleep at that minute. Checking often
 * and de-duplicating by day means the reminder lands within half an hour of the laptop next being
 * used, whatever time that is — the same reasoning as the monthly backup's due-by-elapsed-time
 * check, for the same reason.
 *
 * <p><b>The sent-set is persisted only after a successful send.</b> A mail failure therefore retries
 * on the next tick rather than marking the day done and silently dropping the reminder — which for a
 * tool whose whole purpose is not to forget would be the worst available failure. Yesterday's keys
 * are pruned each run so the file stays small.
 *
 * <p>Everything here degrades: a broken report, a dead mail server or a malformed row costs the tick
 * and a WARN, never the scheduler.
 */
public class ReminderJob {

    private static final Logger log = LoggerFactory.getLogger(ReminderJob.class);

    static final String SENT_KEY = "reminders_sent";

    private final ReminderService service;
    private final AlertNotifier notifier;
    private final KeyValueStore kv;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ReminderJob(ReminderService service, AlertNotifier notifier, KeyValueStore kv) {
        this.service = service;
        this.notifier = notifier;
        this.kv = kv;
    }

    public void run() {
        if (!notifier.isEnabled()) {
            log.debug("Reminders: notifier disabled — skipping");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("Reminders: previous run still in flight — skipping");
            return;
        }
        try {
            sendDue();
        } catch (RuntimeException e) {
            log.warn("Reminder run failed", e);
        } finally {
            running.set(false);
        }
    }

    private void sendDue() {
        ReminderReport report = service.report();
        LocalDate today = report.today();

        // Prune to today's keys. Anything older has served its purpose — it only ever existed to
        // stop a second mail on the day it names.
        Set<String> sentToday = new LinkedHashSet<>();
        for (String k : kv.getStringSet(SENT_KEY)) {
            if (Reminders.isForDay(k, today)) sentToday.add(k);
        }

        List<String> lines = new ArrayList<>();
        Set<String> updated = new LinkedHashSet<>(sentToday);

        for (ManualRow r : report.manual()) {
            if (!r.due()) continue;
            String key = Reminders.manualKey(r.id(), today);
            if (sentToday.contains(key) || !updated.add(key)) continue;
            lines.add(describeManual(r));
        }
        for (EarningsRow r : report.earnings()) {
            if (!r.due()) continue;
            String key = Reminders.earningsKey(r.symbol(), today);
            if (sentToday.contains(key) || !updated.add(key)) continue;
            lines.add(describeEarnings(r));
        }

        if (lines.isEmpty()) {
            kv.putStringSet(SENT_KEY, sentToday);   // just drop yesterday's keys
            return;
        }

        String subject = "Reminders: " + lines.size() + (lines.size() == 1 ? " item" : " items");
        String body = String.join("\n\n", lines) + "\n\n— portfolio-bench reminders, " + today;
        if (notifier.notify(subject, body)) {
            kv.putStringSet(SENT_KEY, updated);
        } else {
            log.warn("Reminder email not sent ({} item(s)); will retry next tick", lines.size());
        }
    }

    /**
     * A hand-entered reminder as one paragraph. The rule and the action are printed verbatim and
     * unlabelled beyond their two words — they are the user's own sentences, and the job's part is
     * to deliver them on the right day, not to interpret them.
     */
    private static String describeManual(ManualRow r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.symbol() == null || r.symbol().isBlank() ? "Portfolio" : r.symbol());
        sb.append(" — due ").append(r.dueDate());
        Long days = r.daysUntil();
        if (days != null && days < 0) {
            long overdue = -days;
            sb.append(" (").append(overdue).append(overdue == 1 ? " day" : " days").append(" ago)");
        }
        if (r.rule() != null) sb.append("\n  Rule: ").append(r.rule());
        if (r.action() != null) sb.append("\n  Action: ").append(r.action());
        return sb.toString();
    }

    /**
     * An earnings reminder. The date's firmness travels with it — an estimated day is marked, never
     * printed as though the company had announced it — for the same reason the Fundamentals cell
     * carries its trailing marker: a day that can still move by weeks is not the same fact as a
     * confirmed one.
     */
    private static String describeEarnings(EarningsRow r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.symbol());
        if (r.companyName() != null && !r.companyName().isBlank()) {
            sb.append(" (").append(r.companyName()).append(")");
        }
        sb.append(" reports ").append(whenPhrase(r.daysUntil())).append(", ").append(r.date());
        if (r.time() != null && !r.time().isBlank() && !"TBD".equals(r.time())) {
            sb.append(" ").append(r.time());
        }
        if (Boolean.TRUE.equals(r.estimate())) sb.append(" (estimated date)");
        return sb.toString();
    }

    private static String whenPhrase(Long days) {
        if (days == null) return "soon";
        if (days == 0) return "today";
        if (days == 1) return "tomorrow";
        return "in " + days + " days";
    }
}
