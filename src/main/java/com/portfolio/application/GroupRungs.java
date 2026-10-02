package com.portfolio.application;

import com.portfolio.domain.model.IntendedRung;
import com.portfolio.domain.model.OpenOrder;
import com.portfolio.domain.model.OrderStatus;
import com.portfolio.domain.model.Rung;
import com.portfolio.persistence.OrderGroupRepository;
import com.portfolio.persistence.OrderHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The machinery every group type shares: turning an order key into a {@link Rung}, turning a rung
 * the user has only decided on into an order the price pipeline can enrich, and offering the orders
 * that are not in a group yet.
 *
 * <p>It lives here rather than being written twice because a ladder rung and a switch leg are the
 * same object with the same states, priced by the same pass over the same horizons. Two copies would
 * be two answers eventually — one panel calling a part-filled order FILLED and the other not, one
 * pricing an intended leg and the other leaving it blank — and the panels would disagree about
 * orders the user can see side by side. What each type does with its rungs is entirely its own
 * business; how a rung is <em>built</em> is not.
 *
 * <p>What is deliberately <b>not</b> here is any rule about which orders belong in which group.
 * Membership is never inferred, for either type, and there is no shared code path that could start.
 */
public final class GroupRungs {

    private static final Logger log = LoggerFactory.getLogger(GroupRungs.class);

    /**
     * The two horizons every group panel prices a rung over, in trading sessions.
     *
     * <p><b>Fixed, and named in the column headings</b> — {@code P(10)} and {@code P(30)}, not
     * {@code P(N)}. The orders table prices each row over that order's own deadline, which is the
     * right question for one order sitting on its own; inside a group it is the wrong one, because
     * the rungs are a single decision and the reader is comparing them with each other. A column
     * whose horizon silently changes per row cannot be compared down its own length, and "N" on
     * screen does not say what it is.
     *
     * <p>10 and 30 rather than the 5 and 20 the pre-trade probe uses, on the evidence: group rungs
     * are deliberately placed away from the market, and over 5 sessions a real NVDA ladder priced
     * 1.3% / 42.5% / 11.0% / 0.1% — three rungs reading "nothing happens", which says nothing about
     * the ladder's shape. The same rungs at 10 and 30 sessions spread to 7.8–57.3% and 17.9–74.6%,
     * where the ordering is legible and the near/far pair says which rungs are live now and which
     * are the patient ones.
     *
     * <p>These are horizons, not volatility windows. That RV10 and RV30 share the numbers is a
     * coincidence and not a reason: σ is <em>forecast</em> for a horizon, never a trailing window of
     * the same length — see {@code domain/VolForecast}.
     */
    public static final int NEAR_HORIZON_SESSIONS = 10;
    public static final int FAR_HORIZON_SESSIONS = 30;

    private GroupRungs() {
    }

    /**
     * Prices an enriched order's chance of the market touching its limit within {@code sessions}
     * trading sessions. Supplied by the caller rather than computed here for the reason the whole
     * enrichment is: the first-passage model lives in one place, and a panel computing its own would
     * be a second implementation that eventually disagrees with the table.
     */
    @FunctionalInterface
    public interface TouchPricer {
        BigDecimal at(OpenOrder order, int sessions);
    }

    /**
     * A synthetic order standing in for a rung the user has decided on but not placed, for the
     * caller to enrich alongside the real ones.
     *
     * <p>Stamped with the user's chosen account as the broker and {@code intent:<id>} as the
     * reference, so {@link OpenOrder#identity()} comes back as a key that can never collide with a
     * broker's own. Status is "Intended", which {@link OrderStatus} does not recognise and therefore
     * classifies as UNKNOWN — open, which is what an intent is. Duration is GTC because an intent
     * has no deadline, so the touch probability is priced over the same horizon a GTC order gets.
     *
     * @param symbol   the listing this rung trades. A ladder's rungs all trade the group's one
     *                 security; a switch's leg trades whichever of its two listings its side names.
     * @param currency the listing's own quote currency, asked of the caller rather than stored on
     *                 the intent: the feed publishes it, so storing it would be a second and staler
     *                 answer. Null for a listing that has never priced, and the rung is then visibly
     *                 uncosted rather than costed in a guess.
     */
    static OpenOrder syntheticOrder(IntendedRung r, String symbol, String currency) {
        BigDecimal amount = r.quantity() == null || r.price() == null
                ? null : r.quantity().multiply(r.price());
        return OpenOrder.parsed(r.account(), symbol, null, r.side(), "Limit", r.quantity(),
                r.price(), false, amount, currency, r.createdAt(), null, "GTC", "Intended",
                r.reference());
    }

    /**
     * The intended rung itself, carrying whatever the enrichment pass made of its synthetic order.
     *
     * @param symbol the listing it trades, resolved by the caller — a ladder's one security, or the
     *               line a switch's leg was recorded against. Passed in rather than read off the
     *               enriched order, which is null whenever the listing has never priced.
     */
    static Rung intendedRung(IntendedRung i, String symbol, OpenOrder enriched, TouchPricer touch) {
        return new Rung(i.reference(), i.id(), i.account(), symbol, i.side(), i.quantity(),
                i.price(), enriched == null ? null : enriched.currency(), Rung.State.INTENDED, null,
                i.note(), enriched, touch.at(enriched, NEAR_HORIZON_SESSIONS),
                touch.at(enriched, FAR_HORIZON_SESSIONS));
    }

    /**
     * A rung that came from a broker. Live first: an order still resting is the richer record and
     * the only one carrying the enrichment. Failing that the history, which is where an order goes
     * when it fills, is cancelled, or stops being listed.
     *
     * <p>Null when the key is in neither. That happens to a rung whose order was pruned before
     * groups existed to protect it, and the caller reports it rather than dropping it silently — a
     * group quietly one rung short is a wrong total.
     */
    static Rung realRung(String key, Map<String, OpenOrder> live,
                         Map<String, OrderHistoryRepository.HistoricalOrder> closed,
                         Map<String, String> notes, TouchPricer touch) {
        OpenOrder o = live.get(key);
        if (o != null) {
            return new Rung(key, null, o.broker(), o.symbol(), o.side(), o.quantity(), o.price(),
                    o.currency(), Rung.State.WORKING, null, notes.get(key), o,
                    touch.at(o, NEAR_HORIZON_SESSIONS), touch.at(o, FAR_HORIZON_SESSIONS));
        }
        OrderHistoryRepository.HistoricalOrder h = closed.get(key);
        if (h == null) return null;
        // A closed rung is priced at nothing: it is not resting, so there is no fill to put odds on.
        return new Rung(key, null, h.broker(), h.symbol(), h.side(), h.quantity(), h.price(),
                h.currency(), stateOf(h), LocalDate.parse(h.closedAt()), h.note(), null, null, null);
    }

    /**
     * How a closed order reads as a rung. The vocabulary is {@link OrderStatus}, the same one the
     * closed-orders table prints, so the two can never disagree about what happened to an order.
     *
     * <p>A part-executed order counts as FILLED here for the reason the open/closed filter already
     * treats it as terminal: it is no longer working. None of the three brokers has yet written that
     * status, and if one does the matched quantity will be its full size rather than the part that
     * traded — the export carries no other number.
     *
     * <p>It reads {@code HistoricalOrder.state()} rather than the raw status, so a verdict recorded
     * about an order the broker never accounted for — matched to its cash-ledger trade, or typed in
     * — reaches the ladder as well as the table. That is the whole point of recording one: a sell
     * that filled while nobody was downloading order statuses is still half of a round trip, and a
     * ladder that cannot see it reports an open position that was closed weeks ago.
     */
    static Rung.State stateOf(OrderHistoryRepository.HistoricalOrder h) {
        OrderStatus settled = h.resolved();
        OrderStatus state = settled != null ? settled : OrderStatus.of(h.status());
        return switch (state) {
            case FILLED, PART_FILLED -> Rung.State.FILLED;
            case CANCELLED, EXPIRED, REJECTED -> Rung.State.CANCELLED;
            // WORKING or UNKNOWN on a closed row means nobody has said what became of it. Not
            // counted as a fill: which it was is what the export declined to say, and no match in
            // the ledger explained it either.
            case WORKING, UNKNOWN -> Rung.State.UNLISTED;
        };
    }

    /**
     * An order the user could assign to a group — resting, or closed inside the history window.
     *
     * @param state    what it is doing, in {@link OrderStatus}'s vocabulary, so the picker reads the
     *                 same way the closed-orders table does
     * @param closedAt null for a resting order; the date it went for a closed one
     */
    public record Candidate(String key, String broker, String symbol, String side,
                            BigDecimal quantity, BigDecimal price, String currency,
                            String state, String closedAt) {

        public boolean closed() {
            return closedAt != null;
        }
    }

    /**
     * Orders in no group at all — what every picker starts from, before each type narrows it to the
     * securities its own shape allows. Resting ones first, then the recently closed.
     *
     * <p><b>Closed orders are offered too</b>, and that is not a convenience. The group a user most
     * wants to see is the one that has already happened in part — a sell that filled paired with a
     * buy still resting — and a picker of live orders only could never assemble it, so the panel
     * would take weeks to show the thing it exists for. The window is whatever the order history is
     * holding: a month, or longer for anything already in a group, which is never pruned.
     */
    static List<Candidate> unassigned(List<OpenOrder> live,
                                      List<OrderHistoryRepository.HistoricalOrder> history,
                                      Set<String> taken) {
        List<Candidate> out = new ArrayList<>();
        for (OpenOrder o : live) {
            String key = o.identity();
            if (taken.contains(key)) continue;
            out.add(new Candidate(key, o.broker(), o.symbol(), o.side(), o.quantity(), o.price(),
                    o.currency(), OrderStatus.WORKING.label(), null));
        }
        for (OrderHistoryRepository.HistoricalOrder h : history) {
            if (taken.contains(h.orderKey())) continue;
            out.add(new Candidate(h.orderKey(), h.broker(), h.symbol(), h.side(), h.quantity(),
                    h.price(), h.currency(), h.state(), h.closedAt()));
        }
        return out;
    }

    static Map<String, OpenOrder> byKey(List<OpenOrder> orders) {
        Map<String, OpenOrder> out = new LinkedHashMap<>();
        for (OpenOrder o : orders) out.put(o.identity(), o);
        return out;
    }

    /**
     * Write a new price and/or quantity onto a rung the user recorded here, whichever kind of group
     * it belongs to.
     *
     * <p>Shared for the reason everything in this class is: an intended ladder rung and an intended
     * switch leg are the same row of the same table, and two copies of the rule about what a blank
     * cell means would eventually be two rules. What is <em>not</em> shared, and stays in each
     * service, is anything about where a rung may belong.
     *
     * <p>A figure that matches what is stored is not written at all. The panel posts every editable
     * cell on screen rather than tracking which were touched, so without this a commit anywhere in
     * the block would rewrite every intent in it.
     *
     * <p>There is deliberately no counterpart for a rung that came from a broker export. Those are
     * what the broker is holding, they are re-read from the file on every load, and this app has no
     * business restating them.
     */
    static void updateIntent(OrderGroupRepository groups, long intentId,
                             BigDecimal quantity, BigDecimal price) {
        IntendedRung r = groups.intent(intentId);
        if (r == null) {
            throw new IllegalStateException("That intended rung is no longer on file.");
        }
        boolean movedQty = IntentEdits.differs(quantity, r.quantity());
        boolean movedPrice = IntentEdits.differs(price, r.price());
        if (!movedQty && !movedPrice) return;
        groups.updateIntent(intentId, movedQty ? quantity : r.quantity(),
                movedPrice ? price : r.price());
    }

    /**
     * Carry an intended rung's note onto the order it became — but <b>only if that order has no note
     * of its own</b>. The reasoning already written against a real order was written about that
     * order, and an intent's text must not overwrite it.
     *
     * <p>Losing the note is never worth failing the link: the rung is placed either way.
     */
    static void carryNote(OrderHistoryRepository orderHistory, String orderKey, String note) {
        if (orderHistory == null || note == null || note.isBlank()) return;
        String existing = orderHistory.notes().get(orderKey);
        if (existing != null && !existing.isBlank()) return;
        try {
            orderHistory.saveNote(orderKey, note);
        } catch (Exception e) {
            log.warn("Could not carry the intended rung's note onto {}", orderKey, e);
        }
    }
}
