package com.portfolio.application;

import com.portfolio.persistence.FxRateRepository;
import com.portfolio.port.HistoricalFxRateProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Disk-backed, fail-soft {@link HistoricalFxRateProvider} — the historical twin of
 * {@link com.portfolio.adapter.CachingFxRateProvider}.
 *
 * <p>Every historical view (attribution, value-over-time, allocation, contributions, currency
 * exposure, scenarios, what-if, range) fetched its own multi-year series from Frankfurter on
 * <em>every</em> request, uncached. Clicking through the Attribution presets fired two calls per
 * click; a handful of concurrent clicks was enough to time the API out, and the swallowed
 * exception silently valued every USD position at £0 on the window's start boundary while the end
 * boundary — which uses the <em>cached</em> live-rate path — stayed correct. The result was a
 * phantom six-figure gain that looked like real data. This class removes the failure mode rather
 * than papering over it:
 *
 * <ul>
 *   <li>Published rates for past dates never change, so {@link FxRateRepository} keeps them
 *       indefinitely. The whole 2000→today history for USD + EUR is ~13,600 rows.</li>
 *   <li>Frankfurter returns dense business days, so stored coverage is the interval
 *       {@code [earliest, latest]}; only an uncovered head or tail is ever fetched. In steady
 *       state a request makes <b>no</b> network call at all.</li>
 *   <li>The tail is re-checked at most once per {@link #TAIL_TTL} per currency, so a burst of tab
 *       clicks can't stampede the API — the very thing that broke it. A gap the source can never
 *       fill costs nothing: a weekend is skipped outright, and a re-check that doesn't advance the
 *       series backs off to {@link #NO_PROGRESS_TTL}.</li>
 *   <li>On a fetch failure, serve whatever is cached and log WARN. Only propagate the exception
 *       when the cache holds nothing for the range, preserving the contract callers already
 *       handle (they catch and warn).</li>
 * </ul>
 *
 * <p>Lives in {@code application} rather than {@code adapter} because it composes a repository
 * with a port — the same shape as {@link EarningsService} and {@link EarningsCalendarService}.
 * No {@code adapter} class imports {@code persistence}, and that stays true.
 */
public class CachedHistoricalFxRateProvider implements HistoricalFxRateProvider {

    private static final Logger log = LoggerFactory.getLogger(CachedHistoricalFxRateProvider.class);

    /** How often the newest end of a currency's series may be re-checked against the API. */
    private static final Duration TAIL_TTL = Duration.ofMinutes(15);

    /**
     * Backoff after a re-check that fetched nothing newer. Today's rate publishes mid-afternoon
     * CET, and bank holidays never publish at all, so before then every request would otherwise
     * pay a pointless round-trip to be told the series still ends yesterday.
     */
    private static final Duration NO_PROGRESS_TTL = Duration.ofHours(1);

    /** Currencies and start date used by {@link #warm()}; everything else fills in on demand. */
    private static final List<String> WARM_CURRENCIES = List.of("USD", "EUR");
    private static final LocalDate WARM_FROM = LocalDate.of(2000, 1, 1);

    private final FxRateRepository repo;
    private final HistoricalFxRateProvider delegate;
    private final Clock clock;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, Instant> nextTailAttempt = new ConcurrentHashMap<>();

    public CachedHistoricalFxRateProvider(FxRateRepository repo, HistoricalFxRateProvider delegate) {
        this(repo, delegate, Clock.systemDefaultZone());
    }

    /** Test seam: pins "now" so the weekend/backoff rules can be exercised on a fixed calendar. */
    CachedHistoricalFxRateProvider(FxRateRepository repo, HistoricalFxRateProvider delegate, Clock clock) {
        this.repo = repo;
        this.delegate = delegate;
        this.clock = clock;
    }

    @Override
    public Map<LocalDate, BigDecimal> fetchRateSeries(String currency, LocalDate start, LocalDate end)
            throws Exception {
        synchronized (locks.computeIfAbsent(currency, c -> new Object())) {
            Exception failure = fill(currency, start, end);
            NavigableMap<LocalDate, BigDecimal> cached = repo.load(currency, start, end);
            if (cached.isEmpty() && failure != null) throw failure;
            return cached;
        }
    }

    /**
     * Bring the cache up to covering {@code [start, end]} where it doesn't already, returning the
     * fetch failure (if any) rather than throwing — a partially-covered range is still worth
     * serving.
     */
    private Exception fill(String currency, LocalDate start, LocalDate end) {
        LocalDate earliest = repo.earliest(currency);
        LocalDate latest = repo.latest(currency);
        if (earliest == null || latest == null) {
            return fetchAndStore(currency, start, end).failure();
        }
        Exception failure = null;
        if (start.isBefore(earliest)) {
            failure = fetchAndStore(currency, start, earliest).failure();
        }
        // Rates only exist up to today; clamp so a caller's `to.plusDays(1)` doesn't make the tail
        // look permanently missing and re-fetch on every request.
        LocalDate today = LocalDate.now(clock);
        LocalDate wantTo = end.isAfter(today) ? today : end;
        if (wantTo.isAfter(latest) && hasWeekday(latest, wantTo) && tailDue(currency)) {
            // Scheduled here and nowhere else: a cold or head fetch covers a range it was actually
            // asked for, so it must not throttle a later, genuinely-new tail. What needs guarding
            // is the repeat case — the cache already ends at yesterday, today's rate isn't
            // published yet, so `latest` never advances and every request would re-ask.
            Fetch tail = fetchAndStore(currency, latest, wantTo);
            boolean advanced = tail.newest() != null && tail.newest().isAfter(latest);
            nextTailAttempt.put(currency,
                    Instant.now(clock).plus(advanced ? TAIL_TTL : NO_PROGRESS_TTL));
            if (tail.failure() != null && failure == null) failure = tail.failure();
        }
        return failure;
    }

    /**
     * Is there any day the source could publish in {@code (after, to]}? Frankfurter follows ECB
     * working days, so a gap made only of weekend dates can never close — on a Sunday the series
     * legitimately ends on Friday, and re-asking every quarter of an hour until Monday just makes
     * one unlucky request per interval pay a round-trip for nothing.
     */
    private static boolean hasWeekday(LocalDate after, LocalDate to) {
        for (LocalDate d = after.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) return true;
        }
        return false;
    }

    private boolean tailDue(String currency) {
        Instant next = nextTailAttempt.get(currency);
        return next == null || !Instant.now(clock).isBefore(next);
    }

    /** Outcome of one delegate call: the failure if it threw, and the newest date it returned. */
    private record Fetch(Exception failure, LocalDate newest) {
    }

    private Fetch fetchAndStore(String currency, LocalDate from, LocalDate to) {
        try {
            Map<LocalDate, BigDecimal> fetched = delegate.fetchRateSeries(currency, from, to);
            repo.save(currency, fetched);
            LocalDate newest = fetched.keySet().stream().max(LocalDate::compareTo).orElse(null);
            return new Fetch(null, newest);
        } catch (Exception e) {
            log.warn("Historical FX fetch failed for {} over {}..{}; serving cached rates", currency, from, to, e);
            return new Fetch(e, null);
        }
    }

    /**
     * Best-effort startup warm of the full history, so a cold start during a Frankfurter outage
     * can still price non-GBP positions. One call per currency (~278 KB for the whole span) and
     * a no-op once the cache is populated.
     */
    public void warm() {
        LocalDate today = LocalDate.now(clock);
        for (String ccy : WARM_CURRENCIES) {
            try {
                fetchRateSeries(ccy, WARM_FROM, today);
            } catch (Exception e) {
                log.warn("Could not warm historical FX cache for {}", ccy, e);
            }
        }
    }
}
