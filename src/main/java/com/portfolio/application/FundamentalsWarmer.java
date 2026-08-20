package com.portfolio.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Off-cycle fundamentals fetch for held symbols the scheduled job hasn't reached yet.
 *
 * <p>{@link FundamentalsFetchJob} runs every four hours, and its universe comes from the
 * {@code held_symbols} set that {@code /sync} writes. A name bought today therefore has no
 * {@code fundamentals_snapshot} row until the next tick — and with no row there is no Yahoo sector
 * and no fund sector-weights, so the sector block files it under
 * {@link com.portfolio.domain.SectorBucket#UNCLASSIFIED} and the style block under
 * {@link com.portfolio.domain.StyleBucket#UNKNOWN}. Both then shout about a position that is
 * perfectly classifiable and merely un-fetched: XWIS (Xtrackers MSCI World Industrials, bought
 * 2026-08-20) sat in a red "excluded from every sector" strip for the four hours between the sync
 * and the next refresh, though its sibling XWHS resolves cleanly to {@code {"healthcare": 1.0}}.
 *
 * <p>So the breakdown services hand their un-placed symbols here and the fetch happens on a
 * background thread. <b>The render never waits</b> — the value appears on the next load, which is
 * the same bargain the Open-orders panel strikes when it warms a lagging quote feed.
 *
 * <p><b>One warmer serves both blocks on purpose.</b> They load together in Allocation → Breakdown
 * and are un-placed by the same missing row, so a per-service cooldown would fetch every new
 * symbol twice, concurrently, on the very first paint.
 */
public class FundamentalsWarmer {

    private static final Logger log = LoggerFactory.getLogger(FundamentalsWarmer.class);

    /**
     * Both panels re-fetch on every tab click, and a symbol Yahoo genuinely has nothing for keeps
     * asking to be warmed (its row is written {@code missing=true}, which is a state we retry).
     * Without a cooldown that is one Yahoo round-trip per click, forever.
     */
    static final Duration COOLDOWN = Duration.ofMinutes(5);

    private final Consumer<Collection<String>> fetcher;
    private final Map<String, Instant> lastWarmed = new ConcurrentHashMap<>();

    public FundamentalsWarmer(Consumer<Collection<String>> fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * Queue a fetch for each symbol not warmed within {@link #COOLDOWN}. Never throws: a warm-up
     * failing must not take down the panel that asked for it.
     *
     * @return the symbols actually handed on, for logging and tests
     */
    public List<String> warm(Collection<String> symbols) {
        if (symbols == null || symbols.isEmpty()) return List.of();
        Instant now = Instant.now();
        List<String> due = new ArrayList<>();
        for (String s : symbols) {
            if (s == null || s.isBlank()) continue;
            Instant last = lastWarmed.get(s);
            if (last == null || last.isBefore(now.minus(COOLDOWN))) {
                lastWarmed.put(s, now);
                due.add(s);
            }
        }
        if (due.isEmpty()) return List.of();
        log.info("Warming fundamentals for un-placed held symbol(s): {}", due);
        try {
            fetcher.accept(due);
        } catch (RuntimeException e) {
            log.warn("Fundamentals warm failed for {}", due, e);
        }
        return List.copyOf(due);
    }

    /** A warmer that does nothing — for tests and any wiring that has no fetcher to offer. */
    public static FundamentalsWarmer disabled() {
        return new FundamentalsWarmer(symbols -> { });
    }
}
