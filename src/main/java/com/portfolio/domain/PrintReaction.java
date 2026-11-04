package com.portfolio.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;

/**
 * What a company's shares did across its own earnings print, and whether that was a large move
 * <em>for this name</em>.
 *
 * <p>Pure and framework-free: every figure here is a function of a print date, a session marker and
 * a series of daily closes, so the whole rule set can be pinned in tests without a database, a feed
 * or a clock.
 *
 * <h2>One session, chosen by when the news could first be traded</h2>
 *
 * A print is not a day, it is a moment, and the move that belongs to it is the first session that
 * could trade on it:
 *
 * <ul>
 *   <li><b>Before the open</b> ({@code BMO}) — the news is out before the bell, so the reaction is
 *       that same day: the previous close to the print day's close.</li>
 *   <li><b>After the close</b> ({@code AMC}) — nothing can trade on it until tomorrow, so the
 *       reaction is the print day's close to the next close.</li>
 * </ul>
 *
 * <p><b>A session the feed did not state is not a session to guess.</b> Nasdaq publishes
 * {@code time-not-supplied} for a great many rows, and anchoring those on one of the two guesses
 * would be a coin toss that reads as a measurement: a BMO window applied to an AMC print measures
 * the day <em>before</em> the news. {@link #window} returns null rather than pick, and the caller
 * reports the row as unanchored — the same "excluded, and said so" bargain the rest of the app
 * strikes.
 *
 * <p><b>The anchors are trading days this instrument actually has, not calendar arithmetic.</b>
 * {@code printDate.minusDays(1)} is a Sunday one week in five and a public holiday more often than
 * that, and a London line and a New York one do not keep the same holidays. The window is walked
 * over the keys of the price series itself, so a missing bar moves the anchor rather than producing
 * a null close that would quietly be read as a zero.
 *
 * <h2>Net of the market, over the identical window</h2>
 *
 * {@link #excess} subtracts an index's move over <b>the same two dates</b>, never a nearby pair. A
 * market-relative figure built from two different windows is not a difference of returns, it is a
 * difference of windows, and on an AMC print that is most of the number. Where the index has no bar
 * on one of the two dates the figure is withheld rather than stretched to the nearest one.
 *
 * <h2>"Usual" is a median of this name's own prints, and it is withheld when thin</h2>
 *
 * The multiple is {@code |move| ÷ median(|move|)} over the same company's previous prints, each
 * measured by the identical rule. Median rather than mean: a baseline from a handful of
 * observations should not be moved bodily by the one quarter the company blew up.
 *
 * <p><b>Below {@link #MIN_BASELINE_PRINTS} prints there is no baseline and the multiple is
 * withheld with its reason.</b> A "usual move" derived from one or two quarters is not a usual
 * move, and printing ×2.4 beside an n of 1 would dress a single comparison up as a distribution.
 * The count is carried on the result so the screen can always state what it is divided by.
 */
public final class PrintReaction {

    /**
     * How many previous prints a baseline needs before a multiple is published. Three is the
     * smallest number for which a median is a median rather than a midpoint between two
     * observations, and it is reachable from one free fetch of a company's last four quarters.
     */
    public static final int MIN_BASELINE_PRINTS = 3;

    /** Which side of the session a print landed on. The only two a reaction can be anchored to. */
    public enum Session {
        /** Before the market opened — the reaction is the print day itself. */
        BMO,
        /** After the market closed — the reaction is the session after the print day. */
        AMC
    }

    /**
     * One measured move: the two closes it is taken between, named, so the division is reproducible
     * on the screen rather than asserted by it.
     *
     * @param pct the move as a fraction (0.034 = +3.4%), never a percentage — the app's convention
     */
    public record Move(LocalDate from, double fromClose, LocalDate to, double toClose, double pct) {
    }

    /**
     * How a name's print move compares with its own history.
     *
     * @param median  the median absolute move over {@code prints} previous prints, as a fraction
     * @param prints  how many previous prints the median is over — always shown, never implied
     * @param dropped previous prints excluded because their session could not be established
     * @param multiple {@code |move| ÷ median}, or null when there were too few prints or the
     *                 median is zero (a name that has never moved on a print has no scale to
     *                 express this one in)
     */
    public record Baseline(Double median, int prints, int dropped, Double multiple) {

        /** A baseline that could not be built, carrying how many observations it was short of. */
        public static Baseline none(int prints, int dropped) {
            return new Baseline(null, prints, dropped, null);
        }

        public boolean usable() {
            return multiple != null;
        }
    }

    /** The feed's own clock. A publication time only names a session when read in the market's. */
    private static final ZoneId US_EASTERN = ZoneId.of("America/New_York");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 30);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(16, 0);

    private PrintReaction() {
    }

    /**
     * The US trading day an instant belongs to. A print stamped 16:30 on 2 November New York is
     * 21:30 UTC the same day, but a US evening release can fall on the following UTC date
     * entirely — so the day is read on the market's clock, the same anchor-on-the-trading-day rule
     * the Live tab applies to Day Δ.
     */
    public static LocalDate usDayOf(Instant at) {
        return at == null ? null : at.atZone(US_EASTERN).toLocalDate();
    }

    /**
     * Which side of the session a publication time falls on, or null when it falls inside it.
     *
     * <p>A stamp between the opening and closing bells is neither of the two windows a print
     * reaction can be measured over, and far more often means the feed does not actually know the
     * time. Returning null is what keeps such a quarter out of a baseline rather than into it
     * backwards; {@link #sessionsConsistent} is the second half of that guard.
     */
    public static Session sessionAt(Instant at) {
        if (at == null) return null;
        LocalTime t = at.atZone(US_EASTERN).toLocalTime();
        if (t.isBefore(MARKET_OPEN)) return Session.BMO;
        if (!t.isBefore(MARKET_CLOSE)) return Session.AMC;
        return null;
    }

    /**
     * The two trading days a print's reaction is measured between, or null when it cannot be
     * anchored — an unstated session, or a series with no bar on one side of the print.
     *
     * @param closes the instrument's own daily closes, keyed by date; only its keys are read here
     */
    public static LocalDate[] window(LocalDate printDate, Session session,
                                     NavigableMap<LocalDate, Double> closes) {
        if (printDate == null || session == null || closes == null || closes.isEmpty()) return null;
        LocalDate from;
        LocalDate to;
        if (session == Session.BMO) {
            // Out before the bell: the print day itself is the reaction. Its own bar is the "to",
            // and if the company reported on a day this listing did not trade (a US print on a
            // London holiday) the first session that could react takes its place.
            from = closes.lowerKey(printDate);
            to = closes.ceilingKey(printDate);
        } else {
            // Out after the bell: nothing trades on it until the next session.
            from = closes.floorKey(printDate);
            to = from == null ? null : closes.higherKey(from);
        }
        if (from == null || to == null || !from.isBefore(to)) return null;
        return new LocalDate[]{from, to};
    }

    /**
     * The move across one print, or null where either anchor has no close. A zero close is treated
     * as no close: a price of zero is a gap in the series, never a measurement.
     */
    public static Move move(LocalDate printDate, Session session,
                            NavigableMap<LocalDate, Double> closes) {
        LocalDate[] w = window(printDate, session, closes);
        if (w == null) return null;
        Double a = closes.get(w[0]);
        Double b = closes.get(w[1]);
        if (a == null || b == null || a <= 0 || b <= 0) return null;
        return new Move(w[0], a, w[1], b, b / a - 1.0);
    }

    /**
     * The same move net of an index over the <b>identical</b> two dates, or null when the index has
     * no close on one of them. Never re-anchored to the index's nearest bar: a market-relative
     * figure measured over a different window is a difference of windows, not of returns.
     */
    public static Double excess(Move move, NavigableMap<LocalDate, Double> indexCloses) {
        if (move == null || indexCloses == null) return null;
        Double a = indexCloses.get(move.from());
        Double b = indexCloses.get(move.to());
        if (a == null || b == null || a <= 0 || b <= 0) return null;
        return move.pct() - (b / a - 1.0);
    }

    /**
     * This print's move expressed in units of the name's usual one.
     *
     * @param move      the print being judged
     * @param previous  the same company's earlier prints, each already measured by {@link #move}
     * @param dropped   earlier prints that could not be measured, for the screen to report
     */
    public static Baseline baseline(Move move, List<Move> previous, int dropped) {
        List<Double> sizes = new ArrayList<>();
        if (previous != null) {
            for (Move m : previous) {
                if (m != null) sizes.add(Math.abs(m.pct()));
            }
        }
        int n = sizes.size();
        if (move == null || n < MIN_BASELINE_PRINTS) return Baseline.none(n, dropped);
        sizes.sort(Comparator.naturalOrder());
        double median = n % 2 == 1
                ? sizes.get(n / 2)
                : (sizes.get(n / 2 - 1) + sizes.get(n / 2)) / 2.0;
        // A median of zero is not a scale. It means every observed print left the close unchanged,
        // which makes "how many usual moves is this" a question with no denominator rather than an
        // infinitely large answer.
        if (median <= 0) return new Baseline(median, n, dropped, null);
        return new Baseline(median, n, dropped, Math.abs(move.pct()) / median);
    }

    /**
     * Whether a set of sessions inferred for one company's past prints can be trusted.
     *
     * <p>A publication timestamp is only a session marker while it sits outside market hours, and
     * the feed is not uniformly careful about it: Novo Nordisk's four stamps read 01:30, 12:47,
     * 22:06 and 17:23 New York for a company that releases at 07:30 Copenhagen every quarter. The
     * ones that fall inside the session are already discarded as unreadable; what this catches is
     * the rest — a company whose surviving stamps claim it reports <em>both</em> before the open and
     * after the close is a company whose stamps are not telling us when it reports.
     *
     * <p>A real change of habit exists and is rare, and the cost of refusing one is a missing
     * baseline on that name. The cost of accepting Novo's is a measured "usual move" built from the
     * wrong sessions, which is the mistake that looks like a finding.
     */
    public static boolean sessionsConsistent(List<Session> sessions) {
        if (sessions == null || sessions.isEmpty()) return false;
        Session first = null;
        for (Session s : sessions) {
            if (s == null) continue;
            if (first == null) first = s;
            else if (first != s) return false;
        }
        return first != null;
    }
}
