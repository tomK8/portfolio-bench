package com.portfolio.application;

import com.portfolio.application.WatchlistService.Row;
import com.portfolio.application.WatchlistService.WatchlistView;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.port.AlertNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Evaluates the watchlist triggers on a schedule and emails newly-firing signals.
 *
 * <p>Reads the very same {@link Row}s the dashboard renders (via {@link WatchlistService}), so
 * screen and alert never disagree. All of a tick's new firings are batched into a single email to
 * avoid a flurry. A symbol with a note has it printed once beneath its own firings — the whole
 * reason to write one is to be told the plan at the moment the signal arrives, rather than having
 * to open the tab to remember it.
 *
 * <p><b>A firing is deduplicated by state, not by the calendar.</b> A price level or a near-high
 * is a condition the market is <em>in</em>: it mails on the crossing ({@code SYMBOL|ABOVE},
 * {@code SYMBOL|BELOW}, {@code SYMBOL|HIGH}) and says nothing more until the price comes back
 * through it — at which point the key is dropped and the next crossing is news again. A big move
 * is an <em>event</em>, and it belongs to one session, so it is keyed by the trading day of the
 * price that fired it ({@code SYMBOL|MOVE|yyyy-MM-dd}) and two big days running still mail twice.
 *
 * <p>Keying everything on {@code LocalDate.now()} instead got both of those wrong, and the second
 * one is the trap {@code docs/live-tab.md} already names for Day Δ: the day rolled at London
 * midnight while the price being judged belonged to a US session. A level crossed once
 * re-announced itself every calendar day until it un-crossed — three mornings running for the
 * same three names, the third of them a Saturday reporting Friday's close — and on a weekday the
 * first tick after midnight spent that day's key before the session anyone could deal in had
 * opened.
 *
 * <p>The fired-set is only persisted after a successful send, so a mail failure re-attempts on
 * the next tick rather than silently dropping the alert. It is rebuilt each run from what the
 * current prices justify rather than pruned by age, so it stays the size of what is actually
 * firing and a symbol dropped from the watchlist takes its keys with it.
 */
public class WatchlistAlertJob {

    private static final Logger log = LoggerFactory.getLogger(WatchlistAlertJob.class);

    static final String FIRED_KEY = "watchlist_alerts_fired";

    /** Indent, so a note reads as attached to the lines above it rather than as another signal. */
    private static final String NOTE_PREFIX = "    \u21b3 ";

    private final WatchlistService watchlistService;
    private final AlertNotifier notifier;
    private final KeyValueStore kv;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public WatchlistAlertJob(WatchlistService watchlistService, AlertNotifier notifier, KeyValueStore kv) {
        this.watchlistService = watchlistService;
        this.notifier = notifier;
        this.kv = kv;
    }

    public void run() {
        if (!notifier.isEnabled()) {
            log.debug("Watchlist alerts: notifier disabled — skipping");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("Watchlist alerts: previous run still in flight — skipping");
            return;
        }
        try {
            evaluateAndNotify();
        } catch (RuntimeException e) {
            log.warn("Watchlist alert run failed", e);
        } finally {
            running.set(false);
        }
    }

    /**
     * Drop every fired-dedup key for {@code symbol} (all triggers, all days) from the KV set.
     * Called when a symbol's thresholds/levels change or it's removed: the dedup exists to avoid
     * re-mailing the <em>same</em> setting, so a changed setting must re-arm. This fixes two
     * things at once — a corrected target (e.g. an Above level moved to the Below column) can fire
     * fresh immediately instead of being suppressed by the earlier firing, and a stale firing for
     * a now-disabled trigger stops lingering in the fired set. It is the only way a level the
     * market is still through can mail a second time, which is what makes it the hook every
     * threshold write calls.
     */
    public void clearFiredFor(String symbol) {
        if (symbol == null) return;
        String prefix = symbol.trim().toUpperCase() + "|";
        Set<String> kept = new LinkedHashSet<>();
        boolean removed = false;
        for (String k : kv.getStringSet(FIRED_KEY)) {
            if (k.startsWith(prefix)) removed = true;
            else kept.add(k);
        }
        if (removed) kv.putStringSet(FIRED_KEY, kept);
    }

    private void evaluateAndNotify() {
        WatchlistView view = watchlistService.view();
        Set<String> onDisk = kv.getStringSet(FIRED_KEY);
        Set<String> fired = normalise(onDisk);

        List<String> newLines = new ArrayList<>();
        int signals = 0;                                   // note lines are not signals (see subject)
        // The set is rebuilt from what the prices justify right now rather than pruned: a trigger
        // the market is no longer through simply isn't carried, and that is the whole re-arm.
        Set<String> carried = new LinkedHashSet<>();
        for (Row r : view.rows()) {
            String sym = r.symbol();
            if (r.currentPrice() == null) {
                // Nothing was judged, so nothing re-arms. A backfill gap or a ticker Yahoo has
                // stopped answering must not turn a signal already mailed into a new one the
                // moment its prices come back.
                for (String k : fired) if (k.startsWith(sym + "|")) carried.add(k);
                continue;
            }
            int before = newLines.size();
            if (r.nearHigh() && isNew(sym + "|HIGH", fired, carried)) newLines.add(describeHigh(r));
            if (r.bigMove() && r.priceDay() != null
                    && isNew(sym + "|MOVE|" + r.priceDay(), fired, carried)) newLines.add(describeMove(r));
            if (r.aboveLevel() && isNew(sym + "|ABOVE", fired, carried)) newLines.add(describeLevel(r, true));
            if (r.belowLevel() && isNew(sym + "|BELOW", fired, carried)) newLines.add(describeLevel(r, false));
            signals += newLines.size() - before;
            // The note goes under the symbol's firings, once — it is the plan for the signal, not
            // for one trigger of it, and a name that fires on both the near-high and the move does
            // not want the same sentence printed twice. Keyed off nothing: the dedup above decides
            // what is in this email, so a symbol whose only trigger was already mailed adds no
            // lines here and gets no note either.
            if (newLines.size() > before && noteOf(r) != null) newLines.add(NOTE_PREFIX + noteOf(r));
        }

        if (newLines.isEmpty()) {
            // Quiet tick, but a level that has come back through still has to re-arm here — the
            // crossing that follows is the next thing worth mailing. Compared against the stored
            // set rather than the normalised one, so a legacy key is also rewritten in passing.
            if (!carried.equals(onDisk)) kv.putStringSet(FIRED_KEY, carried);
            return;
        }

        String subject = "Watchlist alert: " + signals + (signals == 1 ? " signal" : " signals");
        String body = String.join("\n", newLines)
                + "\n\n— portfolio-bench watchlist, " + LocalDate.now();
        if (notifier.notify(subject, body)) {
            kv.putStringSet(FIRED_KEY, carried);
        } else {
            log.warn("Watchlist alert email not sent ({} signal(s)); will retry next tick", signals);
        }
    }

    /**
     * Carry {@code key} forward and say whether it is a firing we have not mailed yet.
     * Carrying is unconditional: a condition still true keeps its key, which is what keeps it
     * quiet, and only a condition that has <em>ended</em> leaves its key behind to be dropped.
     */
    private static boolean isNew(String key, Set<String> fired, Set<String> carried) {
        carried.add(key);
        return !fired.contains(key);
    }

    /**
     * The stored set, with keys written by the earlier dated scheme read as their current
     * equivalent — {@code NVDA|BELOW|2026-11-07} is {@code NVDA|BELOW}. Without that, the first
     * run after the change re-mails every level the market is already through, which is the exact
     * noise the change is here to stop. Nothing writes the dated form for a state trigger any
     * more, so the set takes its new shape on the first successful send. A {@code MOVE} key keeps
     * its date — it means something different now (the price's trading day, not the wall clock)
     * but a stale one is dropped by the next session's rebuild either way.
     */
    private static Set<String> normalise(Set<String> stored) {
        Set<String> out = new LinkedHashSet<>();
        for (String k : stored) {
            String[] parts = k.split("\\|", -1);        // no symbol contains a pipe
            out.add(parts.length == 3 && !"MOVE".equals(parts[1]) ? parts[0] + "|" + parts[1] : k);
        }
        return out;
    }

    /** The symbol's note, or null when none is written — blank text is not a note. */
    private static String noteOf(Row r) {
        String n = r.note();
        return (n == null || n.isBlank()) ? null : n.trim();
    }

    private static String describeHigh(Row r) {
        // pctFromHigh is (current − high)/high: negative below the high, ≥0 at/above it.
        BigDecimal fh = r.pctFromHigh();
        String where;
        if (fh == null) {
            where = "near 52-week high";
        } else if (fh.signum() >= 0) {
            where = "at/above 52-week high (+" + pct(fh) + ")";
        } else {
            where = pct(fh.negate()) + " below 52-week high";
        }
        return r.symbol() + " " + where + " — " + price(r) + " (threshold " + plain(r.highThresholdPct()) + "%)";
    }

    private static String describeMove(Row r) {
        BigDecimal mv = r.todayPct();
        String dir = (mv != null && mv.signum() < 0) ? "down " : "up ";
        String amount = mv == null ? "" : pct(mv.abs());
        return r.symbol() + " " + dir + amount + " today — " + price(r)
                + " (threshold " + plain(r.moveThresholdPct()) + "%)";
    }

    /**
     * One price-level firing. Two numbers can be in play and they are not the same thing: the
     * <b>trigger</b> that fired, and the <b>order target</b> the user has decided to act at, which
     * is normally further out. The target is printed when one is declared, because the whole
     * reason this email exists is to get an order placed — and it is never printed as though it
     * were the level that fired.
     */
    private static String describeLevel(Row r, boolean above) {
        BigDecimal level = above ? r.priceAbove() : r.priceBelow();
        BigDecimal target = above ? r.targetAbove() : r.targetBelow();
        // Name the column that fired ("Above"/"Below") so the email can't be read as the opposite
        // direction — the whole point of the rename.
        String dir = above ? "hit Above trigger" : "hit Below trigger";
        String line = r.symbol() + " " + dir + " " + amount(level, r) + " — now " + price(r);
        return target == null ? line : line + "; order target " + amount(target, r);
    }

    /** A native-currency level, with its currency when we know it. */
    private static String amount(BigDecimal level, Row r) {
        if (level == null) return "?";
        return level.stripTrailingZeros().toPlainString() + (r.currency() == null ? "" : " " + r.currency());
    }

    private static String price(Row r) {
        if (r.currentPrice() == null) return "n/a";
        String ccy = r.currency() == null ? "" : " " + r.currency();
        return r.currentPrice().stripTrailingZeros().toPlainString() + ccy;
    }

    /** Fraction → percent string, e.g. {@code 0.123} → {@code "12.3%"}. */
    private static String pct(BigDecimal frac) {
        return frac.movePointRight(2).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static String plain(BigDecimal v) {
        return v == null ? "?" : v.stripTrailingZeros().toPlainString();
    }
}
