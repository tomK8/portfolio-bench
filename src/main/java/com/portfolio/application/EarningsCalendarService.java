package com.portfolio.application;

import com.portfolio.domain.model.EarningsEvent;
import com.portfolio.persistence.EarningsCalendarRepository;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.port.EarningsCalendarFeed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Assembles the Earnings tab report — the market-wide reporting calendar over a
 * {@code [today−back, today+forward]} window with the user's own US holdings tagged.
 *
 * <p>Cache policy ({@link EarningsCalendarRepository}): a <b>past</b> date's roster is settled, so any
 * cached copy is used forever; <b>today/future</b> dates re-fetch once their cache ages past
 * {@link #REFRESH_TTL} (estimates and the roster still move as companies confirm). On a live-fetch
 * failure the service degrades to whatever is cached and adds a warning — never throws, matching the
 * read-degrades convention.
 *
 * <p>"My holdings" comes from the {@code held_symbols} KV set that {@code SyncPortfolioService}
 * persists after each /sync. Non-US listings never appear on the US calendar, so matching held
 * internal symbols (which equal their US tickers) against the feed's symbols is sufficient — no FX,
 * no ticker map.
 */
public class EarningsCalendarService {

    private static final Logger log = LoggerFactory.getLogger(EarningsCalendarService.class);

    /** Today/future rosters re-fetch once their cache is older than this; past dates cache forever. */
    static final Duration REFRESH_TTL = Duration.ofHours(3);

    /** Guardrails so a stray query param can't fan out into dozens of live Nasdaq calls. */
    static final int MAX_BACK = 14;
    static final int MAX_FORWARD = 14;

    private final EarningsCalendarFeed feed;
    private final EarningsCalendarRepository repo;
    private final KeyValueStore keyValueStore;

    public EarningsCalendarService(EarningsCalendarFeed feed,
                                   EarningsCalendarRepository repo,
                                   KeyValueStore keyValueStore) {
        this.feed = feed;
        this.repo = repo;
        this.keyValueStore = keyValueStore;
    }

    /**
     * Build the calendar. {@code minMarketCap} (whole dollars, nullable) drops smaller names from the
     * per-day tables — the "major earnings" filter — but never from {@code myHoldings}.
     * {@code forceRefresh} bypasses the cache freshness check (the Refresh button) so even today's
     * cached roster is re-pulled.
     */
    public EarningsCalendarReport report(int back, int forward, Long minMarketCap, boolean forceRefresh) {
        int b = clamp(back, 0, MAX_BACK);
        int f = clamp(forward, 0, MAX_FORWARD);
        LocalDate today = LocalDate.now();
        Set<String> held = heldSymbols();
        List<String> warnings = new ArrayList<>();

        List<EarningsCalendarReport.Day> days = new ArrayList<>();
        List<EarningsEvent> mine = new ArrayList<>();

        for (int off = -b; off <= f; off++) {
            LocalDate date = today.plusDays(off);
            List<EarningsEvent> all = eventsFor(date, today, forceRefresh, warnings);

            // Held events are collected pre-filter (holdings show regardless of size).
            for (EarningsEvent e : all) {
                if (e.symbol() != null && held.contains(e.symbol().toUpperCase())) mine.add(e);
            }

            List<EarningsEvent> shown = new ArrayList<>();
            for (EarningsEvent e : all) {
                if (minMarketCap != null && (e.marketCap() == null || e.marketCap() < minMarketCap)) continue;
                shown.add(e);
            }
            shown.sort(BY_CAP_DESC);

            days.add(new EarningsCalendarReport.Day(
                    date,
                    date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                    relativeLabel(off),
                    shown.size(),
                    shown));
        }

        mine.sort(Comparator.comparing(EarningsEvent::date).thenComparing(BY_CAP_DESC));

        List<String> heldList = new ArrayList<>(new TreeSet<>(held));
        return new EarningsCalendarReport(today, b, f, minMarketCap, days, heldList, mine,
                feed.sourceName(), warnings);
    }

    /**
     * Events for one date: served from cache when fresh, otherwise live-fetched and cached. A live
     * failure degrades to cached rows (with a warning) so the tab still renders last-known data.
     */
    private List<EarningsEvent> eventsFor(LocalDate date, LocalDate today, boolean forceRefresh,
                                          List<String> warnings) {
        Instant fetchedAt = repo.lastFetched(date);
        boolean cached = fetchedAt != null;
        boolean fresh = cached && !forceRefresh
                && (date.isBefore(today)
                    || Duration.between(fetchedAt, Instant.now()).compareTo(REFRESH_TTL) < 0);
        if (fresh) return repo.load(date);

        List<EarningsEvent> live = feed.forDate(date);
        if (live != null) {
            repo.save(date, live);
            return live;
        }
        // Live fetch failed — fall back to cache if we have it, else nothing for this day.
        List<EarningsEvent> fallback = cached ? repo.load(date) : List.of();
        warnings.add(cached
                ? date + ": live fetch failed — showing cached data (" + fallback.size() + ")"
                : date + ": live fetch failed and nothing cached");
        return fallback;
    }

    private Set<String> heldSymbols() {
        Set<String> out = new LinkedHashSet<>();
        for (String s : keyValueStore.getStringSet(PriceFetchSupport.HELD_SYMBOLS_KEY)) {
            if (s != null && !s.isBlank()) out.add(s.trim().toUpperCase());
        }
        return out;
    }

    private static final Comparator<EarningsEvent> BY_CAP_DESC = (a, c) -> {
        long ca = a.marketCap() == null ? Long.MIN_VALUE : a.marketCap();
        long cc = c.marketCap() == null ? Long.MIN_VALUE : c.marketCap();
        int byCap = Long.compare(cc, ca); // descending
        if (byCap != 0) return byCap;
        return nullSafe(a.symbol()).compareTo(nullSafe(c.symbol()));
    };

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String relativeLabel(int offset) {
        return switch (offset) {
            case 0 -> "Today";
            case -1 -> "Yesterday";
            case 1 -> "Tomorrow";
            default -> offset < 0 ? (-offset) + " days ago" : "In " + offset + " days";
        };
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
