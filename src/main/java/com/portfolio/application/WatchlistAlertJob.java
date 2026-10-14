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
 * screen and alert never disagree. Each firing is deduplicated <em>once per day per symbol per
 * trigger</em> using a KV set of {@code SYMBOL|TRIGGER|yyyy-MM-dd} keys — so a spike that keeps
 * qualifying all afternoon mails you once, not every tick. All of a tick's new firings are
 * batched into a single email to avoid a flurry. A symbol with a note has it printed once beneath
 * its own firings — the whole reason to write one is to be told the plan at the moment the signal
 * arrives, rather than having to open the tab to remember it.
 *
 * <p>The fired-set is only persisted after a successful send, so a mail failure re-attempts on
 * the next tick rather than silently dropping the alert. Stale keys (prior days) are pruned each
 * run to keep the file tiny.
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
     * fresh the same day instead of being suppressed by the earlier firing, and a stale firing for
     * a now-disabled trigger stops lingering in the fired set.
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
        String today = LocalDate.now().toString();

        // Prune the fired-set to today's keys only.
        Set<String> firedToday = new LinkedHashSet<>();
        for (String k : kv.getStringSet(FIRED_KEY)) {
            if (k.endsWith("|" + today)) firedToday.add(k);
        }

        List<String> newLines = new ArrayList<>();
        int signals = 0;                                   // note lines are not signals (see subject)
        Set<String> updated = new LinkedHashSet<>(firedToday);
        for (Row r : view.rows()) {
            int before = newLines.size();
            if (r.nearHigh()) {
                String key = r.symbol() + "|HIGH|" + today;
                if (updated.add(key) && !firedToday.contains(key)) newLines.add(describeHigh(r));
            }
            if (r.bigMove()) {
                String key = r.symbol() + "|MOVE|" + today;
                if (updated.add(key) && !firedToday.contains(key)) newLines.add(describeMove(r));
            }
            if (r.aboveLevel()) {
                String key = r.symbol() + "|ABOVE|" + today;
                if (updated.add(key) && !firedToday.contains(key)) newLines.add(describeLevel(r, true));
            }
            if (r.belowLevel()) {
                String key = r.symbol() + "|BELOW|" + today;
                if (updated.add(key) && !firedToday.contains(key)) newLines.add(describeLevel(r, false));
            }
            signals += newLines.size() - before;
            // The note goes under the symbol's firings, once — it is the plan for the signal, not
            // for one trigger of it, and a name that fires on both the near-high and the move does
            // not want the same sentence printed twice. Keyed off nothing: the dedup above decides
            // what is in this email, so a symbol whose only trigger was already mailed today adds
            // no lines here and gets no note either.
            if (newLines.size() > before && noteOf(r) != null) newLines.add(NOTE_PREFIX + noteOf(r));
        }

        if (newLines.isEmpty()) {
            kv.putStringSet(FIRED_KEY, firedToday);        // just drop stale keys
            return;
        }

        String subject = "Watchlist alert: " + signals + (signals == 1 ? " signal" : " signals");
        String body = String.join("\n", newLines)
                + "\n\n— portfolio-bench watchlist, " + today;
        if (notifier.notify(subject, body)) {
            kv.putStringSet(FIRED_KEY, updated);
        } else {
            log.warn("Watchlist alert email not sent ({} signal(s)); will retry next tick", signals);
        }
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

    private static String describeLevel(Row r, boolean above) {
        BigDecimal level = above ? r.priceAbove() : r.priceBelow();
        // Name the column that fired ("Above"/"Below") so the email can't be read as the opposite
        // direction — the whole point of the rename.
        String dir = above ? "hit Above target" : "hit Below target";
        String ccy = r.currency() == null ? "" : " " + r.currency();
        String target = level == null ? "?" : level.stripTrailingZeros().toPlainString() + ccy;
        return r.symbol() + " " + dir + " " + target + " — now " + price(r);
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
