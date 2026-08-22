package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.AssetClass;
import com.portfolio.domain.EventMoves;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.PriceStats;
import com.portfolio.domain.VolForecast;
import com.portfolio.persistence.EarningsCalendarRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

/**
 * The scheduled events inside an order's horizon, for the event-variance term of the touch model.
 *
 * <p>Diffusive volatility cannot see the calendar: a name that has been quiet for twenty sessions
 * and reports next month is not a quiet name, and no blend of RV20 toward RV250 will discover that.
 * This supplies the dates; {@link VolForecast} does the arithmetic.
 *
 * <p><b>Nothing is inferred.</b> Earnings dates come from the cached Nasdaq calendar
 * ({@link EarningsCalendarRepository}) and nowhere else. A date we don't have produces no event and
 * a stated reason, never an estimate: a quarterly cadence extrapolated from the last print would
 * land within a week of the truth most of the time and be indistinguishable from a real date on
 * screen, which is exactly the failure mode the panel is built to avoid. An unadjusted probability
 * is a fact a reader can act on; a plausible fabricated one is not.
 *
 * <p>Coverage is therefore reported alongside the events. Three states a blank would conflate:
 * the window is fully fetched and holds no print ({@link Coverage#COMPLETE}), the calendar has been
 * fetched only part of the way out ({@link Coverage#PARTIAL} — a market-wide roster that was never
 * fetched makes <em>every</em> symbol look free of events), and the feed does not carry this
 * listing at all ({@link Coverage#NOT_COVERED} — the Nasdaq calendar is US-only, so a {@code .L}
 * or {@code .PA} line is never in it).
 */
public class OrderEventVariance {

    private static final Logger log = LoggerFactory.getLogger(OrderEventVariance.class);

    /** Calendar days per trading session, for turning a session horizon into a date range. */
    private static final double CALENDAR_DAYS_PER_SESSION = 7.0 / 5.0;

    /** Never scan more than this far ahead, however long a horizon the reader types. */
    private static final int MAX_LOOKAHEAD_DAYS = 400;

    public enum Coverage {
        /** The whole horizon is fetched; the events returned are all the events there are. */
        COMPLETE,
        /** The calendar runs out before the horizon does — beyond that point, unadjusted. */
        PARTIAL,
        /** A US-only feed and a non-US listing: no date will ever arrive. Unadjusted, permanently. */
        NOT_COVERED,
        /** Nothing fetched at all, or the lookup failed. Unadjusted. */
        NONE
    }

    /**
     * @param events         prints inside the horizon, as sessions-away + move size
     * @param coverage       how far the calendar could actually see
     * @param coveredThrough last date the calendar is complete through; null when nothing is
     */
    public record Events(List<VolForecast.Event> events, Coverage coverage, LocalDate coveredThrough) {

        public static final Events NONE = new Events(List.of(), Coverage.NONE, null);

        public boolean adjusted() {
            return !events.isEmpty();
        }
    }

    /** Don't re-warm the calendar more often than this, however many times the tab is opened. */
    private static final Duration WARM_COOLDOWN = Duration.ofMinutes(30);

    private final EarningsCalendarRepository calendar;
    private final YahooTickerMap tickerMap;
    private final EventMoves moves;
    private final IntConsumer calendarWarmer;
    private final AtomicReference<Instant> lastWarm = new AtomicReference<>(Instant.EPOCH);

    public OrderEventVariance(EarningsCalendarRepository calendar, YahooTickerMap tickerMap) {
        this(calendar, tickerMap, new EventMoves(), days -> { });
    }

    /**
     * @param calendarWarmer handed the number of calendar days to fill when the cache runs out
     *                       before the horizon does. Expected to return immediately — the wiring
     *                       hands it to a background thread, because a cold quarter is ~65 HTTP
     *                       fetches and the panel renders at disk speed or not at all. The reader
     *                       sees "unadjusted" now and the adjusted figure on a later reload.
     */
    public OrderEventVariance(EarningsCalendarRepository calendar, YahooTickerMap tickerMap,
                              EventMoves moves, IntConsumer calendarWarmer) {
        this.calendar = calendar;
        this.tickerMap = tickerMap;
        this.moves = moves;
        this.calendarWarmer = calendarWarmer;
    }

    /**
     * Scheduled events for {@code symbol} within {@code horizonSessions}, with the coverage that
     * produced them.
     *
     * <p>The horizon is in sessions and the calendar is in dates, so the window is widened by
     * {@link #CALENDAR_DAYS_PER_SESSION} and each date converted back with
     * {@link PriceStats#tradingSessionsBetween} — the same session count the σ scaling uses, so an
     * event lands inside the horizon on exactly the clock the probability is computed on.
     */
    public Events eventsFor(String symbol, int horizonSessions) {
        if (symbol == null || symbol.isBlank() || horizonSessions < 1 || calendar == null) {
            return Events.NONE;
        }
        BigDecimal move = moves.moveFor(symbol, Instruments.assetClassOf(symbol));
        if (move.signum() <= 0) {
            // A fund, gilt or bullion ETC: no scheduled event exists, so the window is complete by
            // definition rather than merely unfetched. Saying COMPLETE here is what stops the panel
            // flagging "unadjusted" on instruments that have nothing to adjust for.
            return new Events(List.of(), Coverage.COMPLETE, null);
        }
        if (!coveredByFeed(symbol)) {
            return new Events(List.of(), Coverage.NOT_COVERED, null);
        }

        LocalDate today = LocalDate.now();
        int lookahead = Math.min(MAX_LOOKAHEAD_DAYS,
                (int) Math.ceil(horizonSessions * CALENDAR_DAYS_PER_SESSION) + 3);
        LocalDate to = today.plusDays(lookahead);

        LocalDate covered = calendar.coveredThrough(today, to);
        List<VolForecast.Event> events = new ArrayList<>();
        for (LocalDate date : calendar.datesForSymbol(symbol, today, to)) {
            int sessionsAway = date.isAfter(today) ? PriceStats.tradingSessionsBetween(today, date) : 0;
            if (sessionsAway <= horizonSessions) events.add(new VolForecast.Event(sessionsAway, move));
        }

        Coverage coverage;
        if (covered == null) {
            coverage = Coverage.NONE;
        } else if (covered.isBefore(to)) {
            coverage = Coverage.PARTIAL;
        } else {
            coverage = Coverage.COMPLETE;
        }
        if (coverage != Coverage.COMPLETE) {
            if (events.isEmpty()) {
                log.info("Event variance: no earnings date for {} within {} sessions (calendar "
                        + "covered through {}) — probability left unadjusted",
                        symbol, horizonSessions, covered);
            }
            warmCalendar(lookahead);
        }
        return new Events(events, coverage, covered);
    }

    /**
     * Ask for the calendar to be filled out to the horizon, at most once every
     * {@link #WARM_COOLDOWN}. The panel re-fetches on every tab click and a cold quarter is dozens
     * of HTTP calls, so without the cooldown a few clicks would queue hundreds.
     */
    private void warmCalendar(int days) {
        Instant now = Instant.now();
        Instant previous = lastWarm.get();
        if (Duration.between(previous, now).compareTo(WARM_COOLDOWN) < 0) return;
        if (!lastWarm.compareAndSet(previous, now)) return;   // another row already asked
        calendarWarmer.accept(days);
    }

    /**
     * Whether the US-only Nasdaq calendar can carry this listing at all — a dotted exchange suffix
     * on the resolved Yahoo ticker means it can't. The same test the Earnings tab uses to separate
     * "nothing due" from "never covered", and it correctly leaves ASML covered: it maps to itself,
     * the Nasdaq ADR line.
     */
    private boolean coveredByFeed(String symbol) {
        String ticker = tickerMap == null ? symbol : tickerMap.tickerFor(symbol);
        return ticker == null || !ticker.contains(".");
    }
}
