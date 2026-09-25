package com.portfolio.web;

import com.portfolio.application.ReminderJob;
import com.portfolio.application.ReminderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Serves the Reminders tab (under Tools) — the calendar of things to look at again, and the
 * week-ahead earnings notice for everything held, watched or under an order.
 *
 * <p>Thin, like every other controller here: the screen is one {@code GET}, each edit one
 * {@code POST}. A bad date or an empty rule comes back as {@code ok:false} with the reason so the
 * form can show it inline; only an unexpected failure is left to {@link GlobalExceptionAdvice}.
 * That split matters on this screen because mistyping a date is an ordinary thing to do and does
 * not deserve a full-page error.
 *
 * <p>{@code /reminders/send} exists so the mail path can be proved without waiting for the
 * scheduler — it runs the same job the scheduler runs, dedup and all, so pressing it twice in a day
 * correctly sends nothing the second time.
 */
@RestController
public class RemindersController {

    private final ReminderService service;
    private final ReminderJob job;

    public RemindersController(ReminderService service, ReminderJob job) {
        this.service = service;
        this.job = job;
    }

    @GetMapping("/reminders")
    public ReminderService.ReminderReport view() {
        return service.report();
    }

    @PostMapping("/reminders/add")
    public Map<String, Object> add(@RequestParam(required = false) String symbol,
                                   @RequestParam String date,
                                   @RequestParam String rule,
                                   @RequestParam(required = false) String action) {
        try {
            long id = service.add(symbol, date, rule, action);
            return Map.of("ok", true, "id", id, "message", "Reminder saved.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Map.of("ok", false, "message", String.valueOf(e.getMessage()));
        }
    }

    @PostMapping("/reminders/update")
    public Map<String, Object> update(@RequestParam long id,
                                      @RequestParam String date,
                                      @RequestParam String rule,
                                      @RequestParam(required = false) String action) {
        try {
            boolean updated = service.update(id, date, rule, action);
            return Map.of("ok", updated, "message",
                    updated ? "Reminder updated." : "That reminder is no longer there.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Map.of("ok", false, "message", String.valueOf(e.getMessage()));
        }
    }

    @PostMapping("/reminders/toggle")
    public Map<String, Object> toggle(@RequestParam long id, @RequestParam boolean enabled) {
        boolean changed = service.setEnabled(id, enabled);
        return Map.of("ok", changed, "message", changed
                ? (enabled ? "Reminder switched on." : "Reminder switched off.")
                : "That reminder is no longer there.");
    }

    @PostMapping("/reminders/delete")
    public Map<String, Object> delete(@RequestParam long id) {
        boolean deleted = service.delete(id);
        return Map.of("ok", deleted, "message",
                deleted ? "Reminder deleted." : "That reminder is no longer there.");
    }

    /**
     * Switch a symbol's earnings reminder off or back on. Keyed by symbol rather than by an id
     * because there is no row: the reminder is derived, and the mute is the only part of it that is
     * the user's to state.
     */
    @PostMapping("/reminders/earnings/toggle")
    public Map<String, Object> toggleEarnings(@RequestParam String symbol,
                                              @RequestParam boolean enabled) {
        try {
            service.setEarningsEnabled(symbol, enabled);
            return Map.of("ok", true, "message", enabled
                    ? "Earnings reminders for " + symbol + " switched on."
                    : "Earnings reminders for " + symbol + " switched off.");
        } catch (IllegalStateException e) {
            return Map.of("ok", false, "message", String.valueOf(e.getMessage()));
        }
    }

    /** Run the mail job now. Same dedup as the scheduled run, so it cannot double-send. */
    @PostMapping("/reminders/send")
    public Map<String, Object> send() {
        job.run();
        return Map.of("ok", true, "message",
                "Reminder run finished — anything due and not already sent today has been mailed.");
    }
}
