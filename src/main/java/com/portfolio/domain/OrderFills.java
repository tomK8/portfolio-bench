package com.portfolio.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads what became of an order the broker stopped listing without ever saying, by looking for the
 * trade it would have produced in the cash ledger.
 *
 * <p><b>Why this exists.</b> An order closes by absence as much as by status — the broker either
 * restates it terminally or simply drops it from the export — and whichever of the two happens
 * depends on when the export was downloaded, not on what the order did. Download the order status
 * a week after a fill and the fill is gone from the file: the order reads "No longer listed", which
 * is honest about the export and useless on a ladder, where a filled sell is half of the round trip
 * the panel exists to show. The cash ledger does not have that problem. A fill moves money, the
 * money is in the statement, and the statement is downloaded on its own schedule.
 *
 * <p><b>What it will and will not conclude.</b> A match is evidence of a fill; the absence of one
 * is <em>not</em> evidence of a cancellation. So this only ever writes {@code FILLED}, and an order
 * it cannot match is left exactly as it was — "No longer listed", which is what the broker actually
 * told us. Saying "cancelled" because we found nothing would turn a ledger that is merely out of
 * date into a verdict.
 *
 * <h2>The rule</h2>
 * An order is read as filled by a ledger trade when every one of these holds:
 * <ul>
 *   <li><b>Same account</b> — the broker label on the order names the account on the ledger row.</li>
 *   <li><b>Same instrument</b>, compared through {@link Instruments#matchKey} so the orders export
 *       and the cash statement may punctuate it differently.</li>
 *   <li><b>Same side.</b> Read off the <em>sign of the cash</em>, never the sign of the quantity:
 *       money out is a buy, money in is a sell. Two of the three brokers write quantity unsigned
 *       (AJ Bell's "Sale 7,605" and II's "100 INTEL" are both positive), so quantity's sign is a
 *       Roth convention rather than a ledger one, and reading it as the side would call every
 *       AJ Bell sale a purchase.</li>
 *   <li><b>Same quantity</b>, exactly. A partial fill is therefore not matched, which is correct:
 *       the order is not the trade in that case, and claiming it is would put a full rung's
 *       quantity on a ladder that only half happened.</li>
 *   <li><b>The trade falls inside the order's own life</b> — on or after the day it was entered,
 *       on or before the day we first saw it gone. The upper bound is what keeps a <em>modified</em>
 *       order from claiming the fill of the order that replaced it: the replacement's trade lands
 *       after the original disappeared.</li>
 *   <li><b>The fill is not far through the limit</b> — see {@link #LIMIT_TOLERANCE}.</li>
 *   <li><b>No other order has already claimed that trade</b> — see below.</li>
 * </ul>
 *
 * <h2>One trade, one order</h2>
 * <p>The claim rule is what makes this safe rather than merely plausible, and it is there for a
 * specific shape in the data. Citi exports no order reference, so a Roth order's identity is a
 * composite of its own fields — including its price — and Citi restates that price to the fill
 * price when the order trades. The same order therefore arrives as two rows: a ghost at the old
 * limit, which looks exactly like an order that vanished, and the real row at the fill price
 * carrying "Executed". There is one trade and two candidates for it.
 *
 * <p>So trades are claimed in two passes. Orders the broker <em>did</em> account for go first —
 * they own their trade by the broker's own word — and only then do the unexplained ones look at
 * what is left. The ghost finds its trade already taken and stays "No longer listed", which is the
 * truth: nothing extra happened. Without that pass the ghost and its twin would both read as
 * fills, and a ladder would show two sells where one was placed.
 *
 * <p>Claims already written down are passed in by the caller and are honoured the same way, so a
 * second run over a ledger that has since grown cannot hand one trade to a second order.
 */
public final class OrderFills {

    /**
     * How far through its own limit a fill may print and still be read as that order's.
     *
     * <p>This is a guard, not a reconciliation. The ledger's cash amount is what the account was
     * actually debited, dealing commission and stamp duty included, so the price implied by it sits
     * a little the wrong side of the limit on every buy — an AJ Bell purchase of 50 LSEG at a
     * £85.05 limit books at £4,276.26, an implied £85.53, because 0.5% stamp duty and a flat
     * commission are inside it. Requiring the limit to be honoured to the penny would reject the
     * genuine fills; 2% absorbs the fees on any order worth placing while still refusing a trade
     * that printed nowhere near the limit, which is the only thing this test is really for.
     *
     * <p>Applied on the unfavourable side only. A fill better than the limit needs no allowance —
     * that is simply what a limit order does.
     */
    public static final BigDecimal LIMIT_TOLERANCE = new BigDecimal("0.02");

    /** Quantities are compared exactly; this only absorbs the double→decimal round trip. */
    private static final BigDecimal QTY_EPSILON = new BigDecimal("0.000001");

    private OrderFills() {
    }

    /**
     * One {@code TRANSACTION} row of the cash ledger, as this matcher needs to see it.
     *
     * @param consideration the signed cash the row moved, in {@link #currency}: negative for a
     *                      purchase, positive for a sale. <b>This is the side</b> — see the class
     *                      doc on why the quantity's sign is not.
     * @param quantity      shares or nominal, whatever sign the broker wrote it with
     */
    public record Trade(long rowid, LocalDate date, String account, String symbol,
                        BigDecimal quantity, BigDecimal consideration, String currency) {

        public boolean buy() {
            return consideration != null && consideration.signum() < 0;
        }

        /**
         * Cash per share, fees and all. Null when either side is missing or the row moved no
         * quantity — a figure we cannot form is absent, not zero, and a null simply means the
         * limit test has nothing to judge.
         */
        public BigDecimal impliedPrice() {
            if (consideration == null || quantity == null || quantity.signum() == 0) return null;
            return consideration.abs().divide(quantity.abs(), MathContext.DECIMAL64);
        }
    }

    /**
     * One row of the order history, as this matcher needs to see it.
     *
     * @param claimsFirst  the broker said this order traded, or we have already recorded that it
     *                     did. It owns its ledger trade and takes it before anything unexplained
     *                     gets to look.
     * @param needsVerdict this is the order we are trying to explain: closed, with the broker never
     *                     having said how. The only kind a match is written for.
     * @param closedAt     the day the order was first seen gone. Null for one still resting, which
     *                     bounds it at today instead — a resting order is only ever a
     *                     {@code claimsFirst} one here, and only if a verdict is already stored.
     */
    public record Order(String key, String account, String symbol, String side,
                        BigDecimal quantity, BigDecimal price, String currency,
                        LocalDate entered, LocalDate closedAt,
                        boolean claimsFirst, boolean needsVerdict) {

        public boolean buy() {
            return "buy".equalsIgnoreCase(side);
        }

        public boolean sell() {
            return "sell".equalsIgnoreCase(side);
        }
    }

    /** An order and the ledger trade that explains it. */
    public record Match(String orderKey, long tradeRowid, LocalDate tradeDate,
                        BigDecimal filledPrice) {
    }

    /**
     * Attribute each unexplained order to the ledger trade that explains it.
     *
     * @param orders  every stored order, explained and not — the explained ones are needed, because
     *                they are what stops a ghost claiming a trade that is already spoken for
     * @param trades  the ledger's buy/sell rows, corrections already removed by the caller
     * @param claimed trade rowids a stored verdict already owns
     * @return one entry per order that could be explained, in a deterministic order
     */
    public static List<Match> match(List<Order> orders, List<Trade> trades, Set<Long> claimed) {
        Set<Long> taken = claimed == null ? new HashSet<>() : new HashSet<>(claimed);
        List<Match> out = new ArrayList<>();

        // Oldest first, so the pass is a replay of what happened rather than of what we last looked
        // at: where two orders of one size could each take a trade, the one that closed first gets
        // the one that printed first.
        Comparator<Order> order = Comparator
                .comparing(Order::closedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Order::key);
        List<Order> settled = orders.stream().filter(Order::claimsFirst).sorted(order).toList();
        List<Order> unexplained = orders.stream().filter(Order::needsVerdict).sorted(order).toList();

        for (Order o : settled) {
            Trade t = bestFit(o, trades, taken);
            if (t != null) taken.add(t.rowid());
        }
        for (Order o : unexplained) {
            Trade t = bestFit(o, trades, taken);
            if (t == null) continue;
            taken.add(t.rowid());
            out.add(new Match(o.key(), t.rowid(), t.date(), t.impliedPrice()));
        }
        return out;
    }

    /**
     * The trade this order most plausibly produced, or null. Earliest first — an order fills at the
     * first moment the market reaches it, so where two trades both fit, the earlier one is the one
     * that ended this order and the later one belongs to whatever replaced it.
     */
    private static Trade bestFit(Order o, List<Trade> trades, Set<Long> taken) {
        Trade best = null;
        for (Trade t : trades) {
            if (taken.contains(t.rowid())) continue;
            if (!fits(o, t)) continue;
            if (best == null || t.date().isBefore(best.date())
                    || (t.date().equals(best.date()) && t.rowid() < best.rowid())) {
                best = t;
            }
        }
        return best;
    }

    private static boolean fits(Order o, Trade t) {
        if (o.account() == null || !o.account().equals(t.account())) return false;
        if (!Instruments.matchKey(o.symbol()).equals(Instruments.matchKey(t.symbol()))) return false;
        if (Instruments.matchKey(o.symbol()).isEmpty()) return false;
        if (!o.buy() && !o.sell()) return false;
        if (o.buy() != t.buy()) return false;
        if (o.quantity() == null || t.quantity() == null) return false;
        if (o.quantity().subtract(t.quantity().abs()).abs().compareTo(QTY_EPSILON) > 0) return false;
        if (t.date() == null) return false;
        if (o.entered() != null && t.date().isBefore(o.entered())) return false;
        LocalDate until = o.closedAt() == null ? LocalDate.now() : o.closedAt();
        if (t.date().isAfter(until)) return false;
        return withinLimit(o, t);
    }

    /**
     * Whether the trade printed on the right side of the order's limit, within
     * {@link #LIMIT_TOLERANCE}.
     *
     * <p>True whenever the test cannot be run — no limit on the order, no price derivable from the
     * row, or two currencies that are not the same one. A comparison we are unable to make is not
     * evidence against the match, and treating it as such would quietly refuse to explain every
     * order whose export omitted the currency.
     */
    private static boolean withinLimit(Order o, Trade t) {
        BigDecimal limit = o.price();
        BigDecimal paid = t.impliedPrice();
        if (limit == null || paid == null || limit.signum() <= 0 || paid.signum() <= 0) return true;
        if (o.currency() == null || t.currency() == null) return true;
        if (!o.currency().equalsIgnoreCase(t.currency())) return true;
        BigDecimal slack = limit.multiply(LIMIT_TOLERANCE);
        return o.buy() ? paid.compareTo(limit.add(slack)) <= 0
                : paid.compareTo(limit.subtract(slack)) >= 0;
    }
}
