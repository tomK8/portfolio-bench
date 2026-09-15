package com.portfolio.application;

import com.portfolio.domain.Ladders;
import com.portfolio.domain.model.GroupType;
import com.portfolio.domain.model.IntendedRung;
import com.portfolio.domain.model.OpenOrder;
import com.portfolio.domain.model.OrderGroup;
import com.portfolio.domain.model.OrderStatus;
import com.portfolio.domain.model.Rung;
import com.portfolio.persistence.OrderGroupRepository;
import com.portfolio.persistence.OrderHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Ladders: the groups of rungs the user has decided belong together, assembled against the live
 * order feed.
 *
 * <p>Three buy rungs on one name at three prices are one intention, and the orders table - which
 * can sort but not group - cannot say so. Neither can it show the rungs the user has decided on but
 * cannot place, which are real commitments against real cash. This service is where those two
 * facts live; the table stays exactly what it was, a read of what the brokers say.
 *
 * <h2>What it does not do</h2>
 * <ul>
 *   <li><b>It never infers membership.</b> Symbol, date and price proximity are all to hand and all
 *       unused. A ladder is what the user put in it.</li>
 *   <li><b>It never promotes an intended rung.</b> When a real order shows up that looks like one of
 *       the intents, nothing happens: the user links them, through {@link #markPlaced}. Guessing
 *       would move the reasoning written on the intent onto an order that may not be the one it was
 *       written about, and the wrong note on the right order is worse than no note.</li>
 * </ul>
 *
 * <h2>Why intended rungs are priced by someone else</h2>
 * An intended rung has to carry the same analytics as a real one - current price, distance to the
 * limit, the touch probability - and the only way for that to stay true is for it to be the same
 * calculation, not a parallel one. So this service hands {@link OpenOrdersService} a list of
 * synthetic {@link OpenOrder}s ({@link #intendedOrders}) and gets them back enriched by the pass
 * that enriches the table's own rows. Nothing here computes a statistic.
 */
public class LadderService {

    private static final Logger log = LoggerFactory.getLogger(LadderService.class);

    /** The broker a synthetic intent order is stamped with is the account the user chose. */
    private final OrderGroupRepository groups;

    /**
     * Only for carrying an intent's note onto the order that replaces it, in {@link #markPlaced}.
     * The note store is the order history's, and a rung's annotation belongs in the same place
     * every other order's does rather than in a second one this panel would own.
     */
    private final OrderHistoryRepository orderHistory;

    public LadderService(OrderGroupRepository groups, OrderHistoryRepository orderHistory) {
        this.groups = groups;
        this.orderHistory = orderHistory;
    }

    // ---- the read the panel renders ----------------------------------------

    /**
     * Synthetic orders standing in for every intended rung, for the caller to enrich alongside the
     * real ones.
     *
     * <p>Stamped with the user's chosen account as the broker and {@code intent:<id>} as the
     * reference, so {@link OpenOrder#identity()} comes back as a key that can never collide with a
     * broker's own. Status is "Intended", which {@link OrderStatus} does not recognise and therefore
     * classifies as UNKNOWN - open, which is what an intent is. Duration is GTC because an intent
     * has no deadline, so the touch probability is priced over the same horizon a GTC order gets.
     *
     * @param quoteCurrency the instrument's own quote currency, asked of the caller rather than
     *                      stored on the intent: the feed publishes it, so storing it would be a
     *                      second and staler answer. Null for an instrument that has never priced,
     *                      and the rung is then visibly uncosted rather than costed in a guess.
     */
    public List<OpenOrder> intendedOrders(Function<String, String> quoteCurrency) {
        List<OpenOrder> out = new ArrayList<>();
        Map<Long, List<IntendedRung>> byGroup = groups.intents();
        if (byGroup.isEmpty()) return out;
        for (OrderGroup g : groups.groups()) {
            for (IntendedRung r : byGroup.getOrDefault(g.id(), List.of())) {
                String ccy = g.symbol() == null ? null : quoteCurrency.apply(g.symbol());
                BigDecimal amount = r.quantity() == null || r.price() == null
                        ? null : r.quantity().multiply(r.price());
                out.add(OpenOrder.parsed(r.account(), g.symbol(), null, r.side(), "Limit",
                        r.quantity(), r.price(), false, amount, ccy, r.createdAt(), null, "GTC",
                        "Intended", r.reference()));
            }
        }
        return out;
    }

    /**
     * Build the panel: every ladder with its rungs on the price axis, and the open orders that are
     * not in one yet.
     *
     * @param live     the enriched resting orders - the table's own rows
     * @param intents  the synthetic orders from {@link #intendedOrders}, after the same enrichment
     * @param history  closed orders, which is where a filled or cancelled rung comes from
     * @param notes    annotations by order key, so a rung shows the note the table shows
     * @param accounts the account names an intended rung may be placed in - the registered order
     *                 parsers' own labels, so the vocabulary is the one the table already prints and
     *                 a new broker appears here the moment its parser is registered
     */
    public LadderReport assemble(List<OpenOrder> live, List<OpenOrder> intents,
                                 List<OrderHistoryRepository.HistoricalOrder> history,
                                 Map<String, String> notes, List<String> accounts) {
        List<String> warnings = new ArrayList<>();
        List<OrderGroup> all = groups.groups();
        if (all.isEmpty()) {
            return new LadderReport(List.of(), List.of(), accounts, warnings);
        }

        Map<String, OpenOrder> liveByKey = byKey(live);
        Map<String, OpenOrder> intentByKey = byKey(intents);
        Map<String, OrderHistoryRepository.HistoricalOrder> closedByKey = new HashMap<>();
        for (OrderHistoryRepository.HistoricalOrder h : history) closedByKey.put(h.orderKey(), h);

        Map<Long, List<String>> members = groups.members();
        Map<Long, List<IntendedRung>> intentRows = groups.intents();

        List<Ladder> ladders = new ArrayList<>();
        for (OrderGroup g : all) {
            List<Rung> rungs = new ArrayList<>();
            for (String key : members.getOrDefault(g.id(), List.of())) {
                Rung r = realRung(key, liveByKey, closedByKey, notes);
                if (r == null) {
                    warnings.add(g.name() + ": a rung points at an order that is no longer on file "
                            + "(" + key + "). Remove it from the ladder.");
                    continue;
                }
                rungs.add(r);
            }
            for (IntendedRung i : intentRows.getOrDefault(g.id(), List.of())) {
                OpenOrder o = intentByKey.get(i.account() + "|" + i.reference());
                rungs.add(new Rung(i.reference(), i.id(), i.account(), i.side(), i.quantity(),
                        i.price(), o == null ? null : o.currency(), Rung.State.INTENDED, null,
                        i.note(), o));
            }

            List<Rung> ordered = Ladders.byPrice(rungs);
            BigDecimal current = currentPrice(ordered);
            ladders.add(new Ladder(g, ordered, Ladders.markerIndex(ordered, current), current,
                    currencyOf(ordered), Ladders.totals(rungs)));
        }

        return new LadderReport(ladders, unassigned(live, history), accounts, warnings);
    }

    /**
     * A rung that came from a broker. Live first: an order still resting is the richer record and
     * the only one carrying the enrichment. Failing that the history, which is where an order goes
     * when it fills, is cancelled, or stops being listed.
     *
     * <p>Null when the key is in neither. That happens to a rung whose order was pruned before
     * ladders existed to protect it, and it is reported rather than dropped silently - a ladder
     * quietly one rung short is a wrong total.
     */
    private Rung realRung(String key, Map<String, OpenOrder> live,
                          Map<String, OrderHistoryRepository.HistoricalOrder> closed,
                          Map<String, String> notes) {
        OpenOrder o = live.get(key);
        if (o != null) {
            return new Rung(key, null, o.broker(), o.side(), o.quantity(), o.price(), o.currency(),
                    Rung.State.WORKING, null, notes.get(key), o);
        }
        OrderHistoryRepository.HistoricalOrder h = closed.get(key);
        if (h == null) return null;
        return new Rung(key, null, h.broker(), h.side(), h.quantity(), h.price(), h.currency(),
                stateOf(h), LocalDate.parse(h.closedAt()), h.note(), null);
    }

    /**
     * How a closed order reads as a rung. The vocabulary is {@link OrderStatus}, the same one the
     * closed-orders table prints, so the two can never disagree about what happened to an order.
     *
     * <p>A part-executed order counts as FILLED here for the reason the open/closed filter already
     * treats it as terminal: it is no longer working. None of the three brokers has yet written
     * that status, and if one does the round-trip quantity will be its full size rather than the
     * part that traded - the export carries no other number.
     */
    private static Rung.State stateOf(OrderHistoryRepository.HistoricalOrder h) {
        return switch (OrderStatus.of(h.status())) {
            case FILLED, PART_FILLED -> Rung.State.FILLED;
            case CANCELLED, EXPIRED, REJECTED -> Rung.State.CANCELLED;
            // WORKING or UNKNOWN on a closed row means the broker stopped listing it without ever
            // saying what became of it. Not counted as a fill: which it was is what the export
            // declined to say.
            case WORKING, UNKNOWN -> Rung.State.UNLISTED;
        };
    }

    /**
     * The market price the rungs are laid out around: the first live quote any rung carries. Every
     * rung on a ladder is the same security, so they agree by construction; taking the first is
     * cheaper than asserting it. Null when nothing on the ladder could be priced, and the panel then
     * draws no marker rather than one in an invented place.
     */
    private static BigDecimal currentPrice(List<Rung> rungs) {
        for (Rung r : rungs) {
            if (r.order() != null && r.order().currentPrice() != null) return r.order().currentPrice();
        }
        return null;
    }

    private static String currencyOf(List<Rung> rungs) {
        for (Rung r : rungs) {
            if (r.currency() != null) return r.currency();
        }
        return null;
    }

    /**
     * Orders not in any ladder - what the "add rungs" picker offers. Resting ones first, then the
     * recently closed.
     *
     * <p><b>Closed orders are offered too</b>, and that is not a convenience. The ladder a user most
     * wants to see is the completed round trip, and the first one they build will usually be a sell
     * that has already filled paired with a buy still resting: a picker that offered only live
     * orders could never assemble it, and the panel would take weeks to show the thing it exists
     * for. The window is whatever the order history is holding - a month, or longer for anything
     * already a rung, which is never pruned.
     */
    private List<Candidate> unassigned(List<OpenOrder> live,
                                       List<OrderHistoryRepository.HistoricalOrder> history) {
        Set<String> taken = groups.assignedKeys();
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

    private static Map<String, OpenOrder> byKey(List<OpenOrder> orders) {
        Map<String, OpenOrder> out = new LinkedHashMap<>();
        for (OpenOrder o : orders) out.put(o.identity(), o);
        return out;
    }

    // ---- writes ------------------------------------------------------------

    /** Create a ladder. A ladder is rungs on one security, so it needs to know which. */
    public long createLadder(String name, String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalStateException("A ladder needs a security - give it a symbol.");
        }
        return groups.create(GroupType.LADDER, name, symbol, null, LocalDate.now());
    }

    public void deleteLadder(long id) {
        groups.delete(id);
    }

    public void addRungs(long ladderId, Collection<String> orderKeys) {
        groups.addMembers(ladderId, orderKeys, LocalDate.now());
    }

    public void removeRung(String orderKey) {
        groups.removeMember(orderKey);
    }

    public long addIntent(long ladderId, String account, String side, BigDecimal quantity,
                          BigDecimal price, String note) {
        return groups.addIntent(ladderId, account, side, quantity, price, note, LocalDate.now());
    }

    public void deleteIntent(long intentId) {
        groups.deleteIntent(intentId);
    }

    /**
     * The user says they have placed an intended rung, and names the order it became.
     *
     * <p>This is the only path from intended to real, and it exists because the panel deliberately
     * refuses to guess one. The intent's note moves to the order - but <b>only if that order has no
     * note of its own</b>: the reasoning already written against a real order was written about that
     * order, and an intent's text must not overwrite it.
     */
    public void markPlaced(long intentId, String orderKey) {
        if (orderKey == null || orderKey.isBlank()) {
            throw new IllegalStateException("Pick the order this rung became.");
        }
        IntendedRung intent = groups.intent(intentId);
        if (intent == null) {
            throw new IllegalStateException("That intended rung is no longer on file.");
        }
        groups.addMembers(intent.groupId(), List.of(orderKey), LocalDate.now());
        if (intent.note() != null && !intent.note().isBlank() && orderHistory != null) {
            String existing = orderHistory.notes().get(orderKey);
            if (existing == null || existing.isBlank()) {
                try {
                    orderHistory.saveNote(orderKey, intent.note());
                } catch (Exception e) {
                    // The rung is placed either way; losing the note is not worth failing the move.
                    log.warn("Could not carry the intended rung's note onto {}", orderKey, e);
                }
            }
        }
        groups.deleteIntent(intentId);
    }

    /** Every assigned order key - handed to the history pruner so a rung is never deleted. */
    public Set<String> rungOrderKeys() {
        return new LinkedHashSet<>(groups.assignedKeys());
    }

    // ---- what the fragment renders -----------------------------------------

    /** One ladder, laid out. */
    public record Ladder(OrderGroup group, List<Rung> rungs, int markerIndex,
                         BigDecimal currentPrice, String currency, Ladders.Totals totals) {

        /** Rungs above the market, in the price order the panel draws. */
        public List<Rung> above() {
            return markerIndex < 0 ? rungs : rungs.subList(0, markerIndex);
        }

        /** Rungs at or below the market. */
        public List<Rung> below() {
            return markerIndex < 0 ? List.of() : rungs.subList(markerIndex, rungs.size());
        }

        public boolean hasRungs() {
            return !rungs.isEmpty();
        }

        /** What this ladder's money prints as — one security, so one currency across every rung. */
        public String currencySymbol() {
            return OpenOrder.symbolFor(currency);
        }
    }

    /**
     * An order the user could assign to a ladder - resting, or closed inside the history window.
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
     * What the Ladders panel renders.
     *
     * <p>{@link #assignableFor} is the single place the "one group, one security" rule is applied.
     * It is a rule of this group type and not of the storage: a later type whose rungs span two
     * securities changes this method and nothing under it.
     */
    public record LadderReport(List<Ladder> ladders, List<Candidate> unassigned,
                               List<String> accounts, List<String> warnings) {

        public boolean isEmpty() {
            return ladders.isEmpty();
        }

        public List<Candidate> assignableFor(Ladder ladder) {
            String symbol = ladder.group().symbol();
            if (symbol == null) return List.of();
            List<Candidate> out = new ArrayList<>();
            for (Candidate c : unassigned) {
                if (symbol.equalsIgnoreCase(c.symbol())) out.add(c);
            }
            return out;
        }
    }
}
