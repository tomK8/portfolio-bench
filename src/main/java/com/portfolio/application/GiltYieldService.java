package com.portfolio.application;

import com.portfolio.domain.model.GiltYield;
import com.portfolio.port.GiltYieldFeed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The 2-, 5-, 10- and 30-year gilt yields shown on the Reference page, fetched when the page asks
 * and never on a schedule: nothing stores them, so there is nothing for a job to keep up to date.
 *
 * <p>A read, so it degrades rather than throws. A failed refresh keeps the last good figures on
 * screen with the failure named beside them — each row carries the source's own timestamp, so
 * an old figure still says how old it is. A tenor the source did not answer is named in
 * {@link GiltYieldReport#missing()}, never filled in.
 */
public class GiltYieldService {

    private static final Logger log = LoggerFactory.getLogger(GiltYieldService.class);

    /** The four points the gilt curve is usually read at. */
    static final List<Integer> TENORS = List.of(2, 5, 10, 30);

    /**
     * How long one fetch answers every request. The page re-polls once a minute; this only stops
     * a second tab or a reload sending its own request for figures seconds old.
     */
    static final Duration TTL = Duration.ofSeconds(30);

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final DateTimeFormatter AS_OF =
            DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter FETCHED =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ENGLISH);
    private static final DateTimeFormatter MATURES =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final GiltYieldFeed feed;
    private final Clock clock;

    private GiltYieldReport lastGood;
    private Instant lastGoodAt;

    public GiltYieldService(GiltYieldFeed feed) {
        this(feed, Clock.systemUTC());
    }

    GiltYieldService(GiltYieldFeed feed, Clock clock) {
        this.feed = feed;
        this.clock = clock;
    }

    /** Synchronized so two tabs polling together share one request rather than sending two. */
    public synchronized GiltYieldReport current() {
        Instant now = clock.instant();
        if (lastGood != null && Duration.between(lastGoodAt, now).compareTo(TTL) < 0) {
            return lastGood;
        }
        try {
            GiltYieldReport fresh = report(feed.benchmarkYields(TENORS), now);
            lastGood = fresh;
            lastGoodAt = now;
            return fresh;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed("interrupted");
        } catch (Exception e) {
            log.warn("Gilt yields from {} unavailable", feed.sourceName(), e);
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return failed(reason);
        }
    }

    private GiltYieldReport failed(String reason) {
        if (lastGood == null) {
            List<String> all = TENORS.stream().map(GiltYieldService::label).toList();
            return new GiltYieldReport(List.of(), all, feed.sourceName(), null, reason);
        }
        return new GiltYieldReport(lastGood.rows(), lastGood.missing(), lastGood.source(),
                lastGood.fetchedAt(), reason);
    }

    private GiltYieldReport report(List<GiltYield> yields, Instant now) {
        Map<Integer, GiltYield> byTenor = new HashMap<>();
        for (GiltYield y : yields) byTenor.putIfAbsent(y.tenorYears(), y);

        List<GiltYieldReport.Row> rows = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (int tenor : TENORS) {
            GiltYield y = byTenor.get(tenor);
            if (y == null) missing.add(label(tenor));
            else rows.add(row(y));
        }
        // Nothing answered is a failed read, not a successful empty one: caching it would
        // replace the last good figures with a blank table for the whole TTL.
        if (rows.isEmpty()) {
            throw new IllegalStateException(feed.sourceName() + " quoted none of the tenors");
        }
        return new GiltYieldReport(rows, missing, feed.sourceName(),
                FETCHED.format(now.atZone(LONDON)), null);
    }

    private static GiltYieldReport.Row row(GiltYield y) {
        return new GiltYieldReport.Row(
                label(y.tenorYears()),
                percent(y.yieldPct()),
                basisPoints(y.changeBp()),
                y.previousClosePct() == null ? null : percent(y.previousClosePct()),
                y.benchmark(),
                y.maturity() == null ? null : MATURES.format(y.maturity()),
                y.asOf() == null ? null : AS_OF.format(y.asOf().atZone(LONDON)),
                y.sourceUrl());
    }

    static String label(int tenorYears) {
        return tenorYears + "-year";
    }

    /** Three decimals, the precision gilt yields are quoted to. */
    static String percent(BigDecimal pct) {
        return pct.setScale(3, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /** Signed, to a tenth of a basis point; a move that rounds to nothing reads 0.0, not -0.0. */
    static String basisPoints(BigDecimal bp) {
        if (bp == null) return null;
        BigDecimal r = bp.setScale(1, RoundingMode.HALF_UP);
        if (r.signum() == 0) return "0.0 bp";
        return (r.signum() > 0 ? "+" : "-") + r.abs().toPlainString() + " bp";
    }
}
