package com.portfolio.domain;

import com.portfolio.domain.model.Rung;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The arithmetic of a ladder: how its rungs are laid out, what the position becomes if the
 * remaining ones fill, what cash that needs from each account, and what a completed round trip
 * came to.
 *
 * <p>Framework-free and static, and it takes {@link Rung}s rather than orders on purpose - an
 * intended rung and a resting one are the same input to every figure here. The state a rung is in
 * decides only <em>which</em> totals it enters, and that decision is made once, in
 * {@link Rung#remaining()} and {@link Rung#filled()}, rather than re-derived per figure.
 *
 * <p><b>Nothing is summed over a rung it cannot be computed for.</b> A rung with no price, no
 * quantity or no currency is dropped from the figure it would have corrupted and named in
 * {@link Totals#excluded()}, which the panel prints beside the number - the app-wide rule that a
 * missing input is reported rather than absorbed as a zero.
 */
public final class Ladders {

    /** Weighted averages are money-per-share; ten places is far past any broker's precision. */
    private static final int SCALE = 10;

    private Ladders() {
    }

    /**
     * Rungs on the price axis the panel draws: highest price first, so sells sit above buys and the
     * market's own price can be drawn between them.
     *
     * <p>The ordering is <b>strictly by price</b> rather than "sells block, then buys block". In
     * the ordinary case the two are the same picture. They differ exactly when a rung is on the
     * wrong side of the market - a sell below the bid, a buy above the offer - and that is a state
     * worth seeing, not tidying away: it is the same fact the orders table flags in amber, and a
     * layout that always drew sells on top would hide it behind a picture of a ladder that leans
     * the way it is supposed to.
     *
     * <p>A rung with no price sorts last: it cannot be placed on the axis, and guessing a position
     * would misstate which side of the market it is on.
     */
    public static List<Rung> byPrice(List<Rung> rungs) {
        List<Rung> out = new ArrayList<>(rungs);
        out.sort(Comparator
                .comparing(Rung::price, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(r -> r.sell() ? 0 : 1)            // a sell above a buy at one price
                .thenComparing(Rung::account, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Rung::key, Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    /**
     * Where the current-price marker goes in a {@link #byPrice} list: the number of rungs priced
     * strictly above the market. Returns -1 when there is no current price to place - the panel
     * then draws no marker rather than one at an invented position, since the marker's whole job is
     * to say which rungs are above the market and which are below.
     */
    public static int markerIndex(List<Rung> ordered, BigDecimal currentPrice) {
        if (currentPrice == null) return -1;
        int i = 0;
        for (Rung r : ordered) {
            if (r.price() == null || r.price().compareTo(currentPrice) <= 0) break;
            i++;
        }
        return i;
    }

    /**
     * What a ladder comes to.
     *
     * @param netQuantity the position change if every <em>remaining</em> rung fills - buys positive,
     *                    sells negative. Null when a remaining rung has no quantity, which would
     *                    make the figure a partial sum wearing a full one's clothes.
     * @param cash        one line per account and currency, because cash raised in one wrapper
     *                    cannot fund a purchase in another. That is the whole reason this is broken
     *                    out rather than netted to a single number.
     * @param roundTrip   the matched buy-and-sell arithmetic once both sides have filled, or null.
     * @param complete    nothing left to fill and both sides have traded - the round trip is done.
     */
    public record Totals(BigDecimal netQuantity, List<CashLine> cash, RoundTrip roundTrip,
                         int remainingCount, int filledCount, boolean complete,
                         List<String> excluded) {
    }

    /**
     * What one account's side of the ladder does to its cash if the remaining rungs fill.
     *
     * <p>Two figures, never one. A netted number would say a ladder that raises 10k in the SIPP and
     * needs 10k in the Roth is cash-neutral, which is the precise error this panel exists to stop:
     * the money is in the wrong wrapper and cannot move.
     */
    public record CashLine(String account, String currency, BigDecimal raises, BigDecimal needs) {

        /** Positive = this account ends with more cash. */
        public BigDecimal net() {
            return raises.subtract(needs);
        }
    }

    /**
     * A ladder's filled rungs, matched off against each other.
     *
     * @param matchedQuantity the smaller of the two filled sides - the quantity that actually made
     *                        a round trip. The excess on the longer side is a position change, not
     *                        a completed trip, and is deliberately not priced here.
     * @param perShare        average sell price less average buy price, both weighted by quantity.
     *                        Sign is the ordinary one: positive means the sells were higher.
     */
    public record RoundTrip(BigDecimal matchedQuantity, BigDecimal avgBuy, BigDecimal avgSell,
                            BigDecimal perShare, BigDecimal total, String currency) {
    }

    public static Totals totals(List<Rung> rungs) {
        List<String> excluded = new ArrayList<>();

        BigDecimal net = BigDecimal.ZERO;
        boolean netKnown = true;
        int remaining = 0;
        int filled = 0;
        Map<CashKey, CashAcc> cash = new LinkedHashMap<>();

        for (Rung r : rungs) {
            if (r.filled()) filled++;
            if (!r.remaining()) continue;
            remaining++;

            BigDecimal signed = r.signedQuantity();
            if (signed == null) {
                netKnown = false;
                excluded.add(r.describe() + " - no quantity or side, so the net position leaves it out");
            } else if (netKnown) {
                net = net.add(signed);
            }

            BigDecimal value = r.cash();
            if (value == null || r.currency() == null) {
                excluded.add(r.describe() + " - "
                        + (r.price() == null ? "no price"
                        : r.currency() == null ? "no currency yet (nothing has priced this instrument)"
                        : "no quantity")
                        + ", so it is left out of the cash lines");
                continue;
            }
            CashAcc acc = cash.computeIfAbsent(new CashKey(r.account(), r.currency()),
                    k -> new CashAcc(k.account(), k.currency()));
            if (r.sell()) acc.raises = acc.raises.add(value);
            else if (r.buy()) acc.needs = acc.needs.add(value);
        }

        List<CashLine> lines = new ArrayList<>();
        for (CashAcc a : cash.values()) lines.add(new CashLine(a.account, a.currency, a.raises, a.needs));

        RoundTrip trip = roundTrip(rungs, excluded);
        boolean complete = remaining == 0 && trip != null;
        return new Totals(netKnown ? net : null, lines, trip, remaining, filled, complete, excluded);
    }

    /**
     * Match the filled buys against the filled sells. Null unless <em>both</em> sides have filled:
     * one side alone is a position that has moved, and calling that a round trip - at any number -
     * would answer a question the ladder has not finished asking.
     *
     * <p>The prices are the last ones the brokers' exports carried for those orders. No export here
     * carries an execution price; for a limit order the limit is what it filled at or better, and
     * the panel says so beside the figure rather than letting it read as a settled P&amp;L.
     */
    private static RoundTrip roundTrip(List<Rung> rungs, List<String> excluded) {
        BigDecimal buyQty = BigDecimal.ZERO;
        BigDecimal buyCost = BigDecimal.ZERO;
        BigDecimal sellQty = BigDecimal.ZERO;
        BigDecimal sellValue = BigDecimal.ZERO;
        String currency = null;
        boolean mixed = false;

        for (Rung r : rungs) {
            if (!r.filled()) continue;
            BigDecimal value = r.cash();
            if (value == null) {
                excluded.add(r.describe() + " - filled, but with no price on record, so the round "
                        + "trip leaves it out");
                continue;
            }
            if (currency == null) currency = r.currency();
            else if (r.currency() != null && !currency.equals(r.currency())) mixed = true;

            if (r.buy()) {
                buyQty = buyQty.add(r.quantity());
                buyCost = buyCost.add(value);
            } else if (r.sell()) {
                sellQty = sellQty.add(r.quantity());
                sellValue = sellValue.add(value);
            }
        }
        if (buyQty.signum() <= 0 || sellQty.signum() <= 0) return null;
        if (mixed) {
            excluded.add("Filled rungs are in more than one currency, so no round trip is priced");
            return null;
        }

        BigDecimal avgBuy = buyCost.divide(buyQty, SCALE, RoundingMode.HALF_UP);
        BigDecimal avgSell = sellValue.divide(sellQty, SCALE, RoundingMode.HALF_UP);
        BigDecimal matched = buyQty.min(sellQty);
        BigDecimal perShare = avgSell.subtract(avgBuy);
        return new RoundTrip(matched, avgBuy, avgSell, perShare, matched.multiply(perShare), currency);
    }

    /** Cash is grouped by account <em>and</em> currency - one wrapper can hold more than one. */
    private record CashKey(String account, String currency) {
    }

    private static final class CashAcc {
        final String account;
        final String currency;
        BigDecimal raises = BigDecimal.ZERO;
        BigDecimal needs = BigDecimal.ZERO;

        CashAcc(String account, String currency) {
            this.account = account;
            this.currency = currency;
        }
    }
}
