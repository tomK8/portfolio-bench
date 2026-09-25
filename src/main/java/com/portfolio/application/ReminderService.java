package com.portfolio.application;

import com.portfolio.application.EarningsCalendarService.NextEarnings;
import com.portfolio.domain.Reminders;
import com.portfolio.domain.model.Reminder;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.ReminderRepository;
import com.portfolio.persistence.WatchlistRepository;
import com.portfolio.port.AlertNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Assembles the Reminders tab and serves its edits — the two kinds of reminder side by side, plus
 * the one thing the screen must say about itself: whether anything is actually being delivered.
 *
 * <p><b>Hand-entered reminders are stored; earnings reminders are derived.</b> That asymmetry is the
 * design. A reminder you typed exists nowhere but the database. An earnings reminder is the join of
 * two things that both move without you — the symbols you hold, watch or have an order resting on,
 * and when those companies next report — so it is recomputed on every read and the only thing stored
 * is the mute. A stored row per earnings reminder would be a hand-maintained copy of two live
 * sources, and a maintained list can only ever be wrong.
 *
 * <p><b>The universe is the union of three sets the app already publishes</b> — {@code held_symbols}
 * from the last /sync, {@code watchlist_symbols}, and {@code order_symbols} from the Open orders tab
 * — which is exactly "in the portfolio, on a watchlist, or under an order". No fourth source and no
 * hand-kept list: a name reaches this screen because it is already somewhere that matters, and
 * leaves when it stops being there.
 *
 * <p><b>Symbols with no date are named, not dropped.</b> A company the two earnings sources cannot
 * date will never produce a reminder, and a screen that simply omitted it would look identical to one
 * where everything is covered. They come back in {@code undated} and the tab prints them, the same
 * bargain the Earnings tab's {@code NOT_COVERED} strip strikes. Funds and bonds are not among them —
 * they report no earnings, so their silence is an answer rather than a gap.
 *
 * <p>Reads degrade throughout: a failure anywhere in the earnings lookup costs the earnings section
 * and a warning, never the hand-entered reminders, which are the half the user cannot reconstruct.
 */
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    /**
     * How far ahead the screen lists earnings, as opposed to how far ahead it <em>reminds</em>
     * ({@link Reminders#EARNINGS_LEAD_DAYS}). Deliberately longer: the tab answers "what is coming"
     * as well as "what fires today", and a row four weeks out is worth seeing even though it is
     * three weeks from mailing anything.
     */
    static final int LOOKAHEAD_DAYS = 45;

    private final ReminderRepository repo;
    private final EarningsCalendarService earnings;
    private final KeyValueStore kv;
    private final AlertNotifier notifier;

    public ReminderService(ReminderRepository repo, EarningsCalendarService earnings,
                           KeyValueStore kv, AlertNotifier notifier) {
        this.repo = repo;
        this.earnings = earnings;
        this.kv = kv;
        this.notifier = notifier;
    }

    // ---- the screen --------------------------------------------------------

    public ReminderReport report() {
        LocalDate today = LocalDate.now();
        List<String> warnings = new ArrayList<>();

        List<ManualRow> manual = new ArrayList<>();
        for (Reminder r : repo.loadAll()) {
            manual.add(new ManualRow(
                    r.id(), r.symbol(), r.dueDate(), r.rule(), r.action(), r.enabled(),
                    Reminders.manualDue(r.dueDate(), r.enabled(), today),
                    Reminders.daysUntil(r.dueDate(), today)));
        }

        EarningsSection section = earningsSection(today, warnings);

        return new ReminderReport(today, Reminders.EARNINGS_LEAD_DAYS, LOOKAHEAD_DAYS,
                notifier.isEnabled(), manual, section.rows(), section.undated(), warnings);
    }

    /**
     * The derived half. Degrades as a unit: if the lookup fails the tab still renders its stored
     * reminders with a warning, because those are the ones nothing else could rebuild.
     */
    private EarningsSection earningsSection(LocalDate today, List<String> warnings) {
        Set<String> universe = universe();
        if (universe.isEmpty()) return EarningsSection.empty();
        try {
            EarningsCalendarService.Lookup lookup = earnings.nextEarnings(universe, LOOKAHEAD_DAYS);
            Set<String> muted = repo.mutedSymbols();

            List<EarningsRow> rows = new ArrayList<>();
            for (NextEarnings ne : lookup.dated().values()) {
                boolean enabled = !muted.contains(ne.symbol());
                rows.add(new EarningsRow(
                        ne.symbol(), ne.companyName(), ne.date(), ne.time(), ne.source(),
                        ne.estimate(), enabled,
                        Reminders.earningsDue(ne.date(), enabled, today),
                        Reminders.daysUntil(ne.date(), today)));
            }
            rows.sort((a, b) -> {
                int byDate = a.date().compareTo(b.date());
                return byDate != 0 ? byDate : a.symbol().compareTo(b.symbol());
            });

            // Named, not absorbed: a symbol neither source can date produces no reminder, and the
            // tab has to be able to say which ones those are. The list comes from the lookup rather
            // than from "everything the map didn't answer", because funds and bonds are in neither
            // — they report no earnings, so calling them undated would bury the real gaps under
            // every gilt in the ledger.
            return new EarningsSection(rows, lookup.undated());
        } catch (RuntimeException e) {
            log.warn("Could not build the earnings reminders", e);
            warnings.add("Earnings dates unavailable — the earnings reminders are not shown. "
                    + "Your own reminders are unaffected.");
            return EarningsSection.empty();
        }
    }

    /**
     * Every symbol in the portfolio, on a watchlist, or named by a resting order. Uppercased and
     * de-duplicated; the three KV sets are each maintained by the feature that owns them, so this
     * stays a read and never a fourth list to keep.
     */
    Set<String> universe() {
        Set<String> out = new LinkedHashSet<>();
        addAll(out, kv.getStringSet(PriceFetchSupport.HELD_SYMBOLS_KEY));
        addAll(out, kv.getStringSet(WatchlistRepository.WATCHLIST_SYMBOLS_KEY));
        addAll(out, kv.getStringSet(OpenOrdersService.ORDER_SYMBOLS_KEY));
        return out;
    }

    private static void addAll(Set<String> out, Set<String> in) {
        for (String s : in) {
            if (s != null && !s.isBlank()) out.add(s.trim().toUpperCase());
        }
    }

    // ---- edits -------------------------------------------------------------

    /**
     * Add one reminder. A blank date is rejected rather than defaulted to today: a reminder that
     * starts mailing the moment it is saved is almost never what was meant, and a date is the one
     * field the screen cannot guess.
     */
    public long add(String symbol, String date, String rule, String action) {
        LocalDate due = parseDate(date);
        if (rule == null || rule.isBlank()) {
            throw new IllegalArgumentException("A reminder needs a rule — what is it you are watching for?");
        }
        return repo.add(symbol, due, rule, action);
    }

    /** Update the three editable fields of an existing reminder. */
    public boolean update(long id, String date, String rule, String action) {
        LocalDate due = parseDate(date);
        if (rule == null || rule.isBlank()) {
            throw new IllegalArgumentException("A reminder needs a rule — what is it you are watching for?");
        }
        if (repo.find(id) == null) return false;
        return repo.update(id, due, rule, action);
    }

    public boolean setEnabled(long id, boolean enabled) {
        return repo.setEnabled(id, enabled);
    }

    public boolean delete(long id) {
        return repo.delete(id);
    }

    /** Switch a symbol's earnings reminder off or back on. */
    public void setEarningsEnabled(String symbol, boolean enabled) {
        repo.setEarningsMuted(symbol, !enabled);
    }

    private static LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) {
            throw new IllegalArgumentException("A reminder needs a date.");
        }
        try {
            return LocalDate.parse(date.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Could not read the date '" + date + "' — expected yyyy-MM-dd.");
        }
    }

    // ---- DTOs --------------------------------------------------------------

    /**
     * The tab's payload. {@code deliveryEnabled} is the screen's own honesty check: with no SMTP
     * configured every reminder here is a note on a page that will never mail anything, and the tab
     * says so rather than letting the user believe a due row means a sent email.
     *
     * <p>{@code leadDays} and {@code lookaheadDays} are both published because they are different
     * numbers doing different jobs — one is when a reminder starts firing, the other is how far the
     * table looks — and a client that conflated them would either under-show or over-promise.
     */
    public record ReminderReport(LocalDate today,
                                 int leadDays,
                                 int lookaheadDays,
                                 boolean deliveryEnabled,
                                 List<ManualRow> manual,
                                 List<EarningsRow> earnings,
                                 List<String> undated,
                                 List<String> warnings) {
    }

    /**
     * One hand-entered reminder. {@code due} is whether it fires today; {@code daysUntil} is
     * negative once it is overdue, which is the ordinary state of a reminder doing its job.
     */
    public record ManualRow(long id, String symbol, LocalDate dueDate, String rule, String action,
                            boolean enabled, boolean due, Long daysUntil) {
    }

    /**
     * One derived earnings reminder. It has no id — there is no row to point at — so the symbol is
     * the handle the toggle uses. {@code source} names which of the two feeds dated it and
     * {@code estimate} is the snapshot's "inferred, not announced" flag, both carried so the screen
     * can show how firm the date is instead of printing every date alike.
     */
    public record EarningsRow(String symbol, String companyName, LocalDate date, String time,
                              String source, Boolean estimate, boolean enabled, boolean due,
                              Long daysUntil) {
    }

    private record EarningsSection(List<EarningsRow> rows, List<String> undated) {
        static EarningsSection empty() {
            return new EarningsSection(List.of(), List.of());
        }
    }
}
