package com.portfolio.domain;

import com.portfolio.domain.model.Rung;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What a ladder's <b>next</b> round trip would be: which way round it goes, which levels the market
 * has to reach and in what order, how much quantity that matches and at what spread.
 *
 * <p>The arithmetic only. No probability is formed here — that is a first passage over the barriers
 * this class lays out, and it lives with every other first passage in {@link VolForecast}. The split
 * is the point: which levels matter is a fact about the ladder, and what the market does about them
 * is a fact about the instrument.
 *
 * <h2>Why the <em>next</em> one</h2>
 * Not "every rung fills". Three rungs a side is a sequence nobody waits out — the ladder is repriced
 * long before it plays through — so a probability covering all of them answers a question that
 * cannot come true. The next round trip is the first completed one, and it is what the reader is
 * actually deciding about: is this worth leaving in the market.
 *
 * <h2>Matched by quantity, not by pairs</h2>
 * A round trip is a quantity sold and the same quantity bought back, or the reverse. <b>Which</b>
 * rung closes <b>which</b> is not a question this class asks or can answer: rungs fill out of order,
 * and selling at one level and buying back at another is a completed trip whatever the two were
 * recorded as. So the opening leg is the nearest remaining rung on its side, and the closing leg is
 * however many rungs on the other side it takes to match that quantity — priced at their
 * quantity-weighted average, with the last one taken part-way if that is all the match needs.
 *
 * <p><b>One barrier for a set of closing rungs</b>, and it is the furthest of them. Price is
 * continuous: reaching the last rung needed means the nearer ones were passed on the way, so the
 * whole closing side collapses to the single level at which the match completes.
 *
 * <h2>Direction</h2>
 * A ladder with rungs on both sides supports two trips, and they are different questions with
 * different answers: {@code SELL_THEN_BUY} is shares already held sold high and bought back lower,
 * {@code BUY_THEN_SELL} is shares not yet owned bought low and sold out higher. Both are reported
 * when both apply. <b>A leg that has already filled settles the direction</b> — with an unmatched
 * filled sell on the ladder the next matched quantity is necessarily the buy that closes it, and the
 * trip needs one event rather than two, which is why a part-executed ladder reads higher than the
 * figure it was created with.
 */
public final class RoundTrips {

    /** Weighted averages are money-per-share; ten places is far past any broker's precision. */
    private static final int SCALE = 10;

    private RoundTrips() {
    }

    /** Which way round the trip goes. The labels are what the panel prints. */
    public enum Direction {
        /** Shares already held: sell high, buy back lower. */
        SELL_THEN_BUY("Sell → buy", "on shares already held"),
        /** Shares not yet owned: buy low, sell out higher. */
        BUY_THEN_SELL("Buy → sell", "on shares not yet owned");

        private final String label;
        private final String gloss;

        Direction(String label, String gloss) {
            this.label = label;
            this.gloss = gloss;
        }

        public String label() {
            return label;
        }

        public String gloss() {
            return gloss;
        }
    }

    /**
     * One way the ladder could complete its next round trip, before any odds are put on it.
     *
     * @param barriers the levels still to be reached, <b>in the order they must be reached</b>,
     *                 starting from {@link #start}. Two for a trip not yet begun; one once a leg has
     *                 filled, or once the opening rung is already through the market and fills on
     *                 the spot; empty when nothing is left to wait for at all. The list is what the
     *                 first passage consumes, and its length is how many events the ladder still
     *                 needs.
     * @param start    the price the sequence begins from — the live market, never the price the
     *                 ladder was created at. That is what makes a part-executed ladder show the odds
     *                 of what remains rather than the ones it was born with.
     * @param quantity the matched quantity: what actually makes a round trip. Capped by whichever
     *                 side offers less, since the excess on the longer one is a position change.
     * @param spread   per share, signed the ordinary way — positive means the trip makes money.
     *                 Never clamped: a ladder whose buy-back sits above its sell really does lose on
     *                 completion, and that is the thing worth seeing.
     * @param underway true when a leg has already filled and only the close is left.
     */
    public record Plan(Direction direction, boolean underway, BigDecimal start,
                       List<BigDecimal> barriers, BigDecimal quantity, BigDecimal openPrice,
                       BigDecimal closePrice, BigDecimal spread, String currency) {

        /** How many events the ladder still needs — what the round trip is a probability of. */
        public int legs() {
            return barriers.size();
        }

        /** Spread × matched quantity: what the trip is worth if it completes. Never null here. */
        public BigDecimal gross() {
            return spread.multiply(quantity);
        }

        /** The sell side's price, whichever leg it is — what the panel labels "sold". */
        public BigDecimal sellPrice() {
            return direction == Direction.SELL_THEN_BUY ? openPrice : closePrice;
        }

        /** The buy side's price, whichever leg it is. */
        public BigDecimal buyPrice() {
            return direction == Direction.SELL_THEN_BUY ? closePrice : openPrice;
        }
    }

    /**
     * Every round trip this ladder could next complete, and what had to be left out to say so.
     *
     * @param unavailable why there is no plan at all, in the reader's terms — a one-sided ladder has
     *                    nothing to close a trip with, and saying so beats an empty block. Null when
     *                    there is at least one plan.
     * @param excluded    rungs dropped from the matching because a figure they need is missing, named
     *                    one by one. The app-wide rule: never absorbed as a zero, never silent.
     */
    public record Next(List<Plan> plans, List<String> excluded, String unavailable) {

        static Next nothing(List<String> excluded, String why) {
            return new Next(List.of(), excluded, why);
        }

        public boolean any() {
            return !plans.isEmpty();
        }
    }

    /**
     * Work out the next round trip in each direction that is still open to this ladder.
     *
     * @param rungs        every rung on the ladder, in any order. Cancelled ones are counted in
     *                     nothing, as everywhere else in this panel.
     * @param currentPrice the live market price. Null means there is nothing to price a path from —
     *                     the barriers are levels the market has to reach, and "reach from where"
     *                     has no answer — so no plan is formed rather than one from an invented
     *                     starting point.
     */
    public static Next next(List<Rung> rungs, BigDecimal currentPrice) {
        List<String> excluded = new ArrayList<>();
        List<Rung> sells = new ArrayList<>();
        List<Rung> buys = new ArrayList<>();
        Side filledSells = new Side();
        Side filledBuys = new Side();
        String currency = null;

        for (Rung r : rungs) {
            if (r.currency() != null && currency == null) currency = r.currency();
            if (r.gone()) continue;
            boolean usable = (r.buy() || r.sell()) && r.price() != null && r.quantity() != null
                    && r.quantity().signum() > 0 && r.price().signum() > 0;
            if (r.remaining()) {
                if (!usable) {
                    excluded.add(r.describe() + " - " + why(r) + ", so it is not on the path to a "
                            + "round trip");
                    continue;
                }
                (r.sell() ? sells : buys).add(r);
            } else if (r.filled()) {
                if (!usable) {
                    excluded.add(r.describe() + " - filled, but " + why(r) + ", so it is left out of "
                            + "what the next round trip has to close");
                    continue;
                }
                (r.sell() ? filledSells : filledBuys).add(r.quantity(), r.price());
            }
        }

        if (currentPrice == null || currentPrice.signum() <= 0) {
            return Next.nothing(excluded, "No live price for this security, so there is no market "
                    + "to measure a round trip's path from.");
        }

        // What a filled leg leaves open. Matching is by quantity, so the two sides cancel and only
        // the excess is still waiting to be closed - which also means there can never be an open
        // leg on both sides at once, and the direction of the next trip is settled by arithmetic.
        BigDecimal alreadyMatched = filledSells.quantity.min(filledBuys.quantity);
        BigDecimal openSell = filledSells.quantity.subtract(alreadyMatched);
        BigDecimal openBuy = filledBuys.quantity.subtract(alreadyMatched);

        List<Plan> plans = new ArrayList<>();
        String unavailable = null;
        if (openSell.signum() > 0) {
            Plan p = closing(Direction.SELL_THEN_BUY, openSell, filledSells.average(), buys,
                    currentPrice, currency);
            if (p != null) plans.add(p);
            else unavailable = "A sell of " + plain(openSell) + " is open and there is no buy rung "
                    + "left to close it.";
        } else if (openBuy.signum() > 0) {
            Plan p = closing(Direction.BUY_THEN_SELL, openBuy, filledBuys.average(), sells,
                    currentPrice, currency);
            if (p != null) plans.add(p);
            else unavailable = "A buy of " + plain(openBuy) + " is open and there is no sell rung "
                    + "left to close it.";
        } else {
            Plan sellFirst = full(Direction.SELL_THEN_BUY, sells, buys, currentPrice, currency);
            Plan buyFirst = full(Direction.BUY_THEN_SELL, buys, sells, currentPrice, currency);
            if (sellFirst != null) plans.add(sellFirst);
            if (buyFirst != null) plans.add(buyFirst);
            if (plans.isEmpty()) {
                unavailable = sells.isEmpty() && buys.isEmpty()
                        ? "Nothing is resting or intended, so there is no round trip left to make."
                        : "Every rung left is a " + (buys.isEmpty() ? "sell" : "buy") + ", so there "
                          + "is nothing on the other side to complete a round trip with.";
            }
        }
        return new Next(List.copyOf(plans), List.copyOf(excluded), unavailable);
    }

    /**
     * A trip with both legs still to come: the nearest rung on the opening side, then however much
     * of the other side it takes to match it.
     *
     * <p>Nearest, not best — the first rung the market reaches is the one that opens the trip, and
     * that is the lowest sell or the highest buy whether or not it is the one the reader would have
     * picked.
     */
    private static Plan full(Direction direction, List<Rung> openSide, List<Rung> closeSide,
                             BigDecimal current, String currency) {
        if (openSide.isEmpty() || closeSide.isEmpty()) return null;
        boolean openIsSell = direction == Direction.SELL_THEN_BUY;
        Rung open = nearestFirst(openSide, openIsSell).get(0);
        Consumed close = consume(nearestFirst(closeSide, !openIsSell), open.quantity());
        if (close == null) return null;
        return plan(direction, false, current, open.price(), openIsSell, close, close.quantity,
                open.price(), currency);
    }

    /**
     * A trip already half done: a filled leg is open and only the close is left.
     *
     * <p>The open leg's price is the quantity-weighted average of <em>every</em> filled rung on that
     * side, and that is a consequence of matching by quantity rather than a shortcut. With no pairing
     * there is no "the" unmatched lot to read a price off, and the side's own average is the only
     * figure that does not require inventing one.
     */
    private static Plan closing(Direction direction, BigDecimal open, BigDecimal openPrice,
                                List<Rung> closeSide, BigDecimal current, String currency) {
        if (closeSide.isEmpty() || openPrice == null) return null;
        boolean openIsSell = direction == Direction.SELL_THEN_BUY;
        Consumed close = consume(nearestFirst(closeSide, !openIsSell), open);
        if (close == null) return null;
        return plan(direction, true, current, null, openIsSell, close, close.quantity, openPrice,
                currency);
    }

    /**
     * Lay the legs on the path the market would have to walk, and drop the ones it has already
     * walked past.
     *
     * <p>A rung on the wrong side of the market — a sell below the bid, a buy above the offer — is
     * marketable: it is not something to wait for, it fills. So it contributes no barrier, and the
     * <b>next</b> leg then starts from the market price rather than from that rung's limit, because
     * the market is where the market is. The panel keeps such rungs visible on its price axis for
     * exactly this reason, and this is the same fact read as a path.
     *
     * @param openLimit the opening rung's price, or null when the opening leg has already filled and
     *                  there is no barrier for it to contribute.
     */
    private static Plan plan(Direction direction, boolean underway, BigDecimal current,
                             BigDecimal openLimit, boolean openIsSell, Consumed close,
                             BigDecimal quantity, BigDecimal openPrice, String currency) {
        List<BigDecimal> barriers = new ArrayList<>();
        BigDecimal from = current;
        if (openLimit != null && !through(openLimit, from, openIsSell)) {
            barriers.add(openLimit);
            from = openLimit;
        }
        if (!through(close.barrier, from, !openIsSell)) barriers.add(close.barrier);

        BigDecimal spread = openIsSell
                ? openPrice.subtract(close.average)
                : close.average.subtract(openPrice);
        return new Plan(direction, underway, current, List.copyOf(barriers), quantity, openPrice,
                close.average, spread, currency);
    }

    /** Whether the market has already passed this limit, so it fills rather than waits. */
    private static boolean through(BigDecimal limit, BigDecimal from, boolean sell) {
        return sell ? limit.compareTo(from) <= 0 : limit.compareTo(from) >= 0;
    }

    /**
     * The order the market reaches them in: sells cheapest first (the price has to rise, so the
     * lowest is met first), buys dearest first.
     */
    private static List<Rung> nearestFirst(List<Rung> side, boolean sell) {
        List<Rung> out = new ArrayList<>(side);
        out.sort(sell ? Comparator.comparing(Rung::price)
                : Comparator.comparing(Rung::price).reversed());
        return out;
    }

    /**
     * Take rungs in the order the market reaches them until {@code need} is matched. The barrier is
     * the last one taken — the level at which the match completes, and therefore the only one the
     * first passage has to reach.
     *
     * <p>The last rung is taken part-way when that is all the match needs: the rest of it is a
     * position change rather than part of this trip, and pricing the whole rung in would overstate
     * both the size and the spread.
     */
    private static Consumed consume(List<Rung> closeSide, BigDecimal need) {
        BigDecimal got = BigDecimal.ZERO;
        BigDecimal value = BigDecimal.ZERO;
        BigDecimal barrier = null;
        for (Rung r : closeSide) {
            BigDecimal take = r.quantity().min(need.subtract(got));
            if (take.signum() <= 0) break;
            got = got.add(take);
            value = value.add(take.multiply(r.price()));
            barrier = r.price();
            if (got.compareTo(need) >= 0) break;
        }
        if (barrier == null || got.signum() <= 0) return null;
        return new Consumed(barrier, got, value.divide(got, SCALE, RoundingMode.HALF_UP));
    }

    /** What is missing from a rung that keeps it off the path, in the words the panel prints. */
    private static String why(Rung r) {
        if (!r.buy() && !r.sell()) return "no side";
        if (r.price() == null || r.price().signum() <= 0) return "no price";
        return "no quantity";
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    /** The closing side, collapsed: where the match completes and what it averages. */
    private record Consumed(BigDecimal barrier, BigDecimal quantity, BigDecimal average) {
    }

    /** One side's filled rungs, accumulated for the quantity match and its average price. */
    private static final class Side {
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal value = BigDecimal.ZERO;

        void add(BigDecimal qty, BigDecimal price) {
            quantity = quantity.add(qty);
            value = value.add(qty.multiply(price));
        }

        BigDecimal average() {
            return quantity.signum() <= 0 ? null : value.divide(quantity, SCALE, RoundingMode.HALF_UP);
        }
    }
}
