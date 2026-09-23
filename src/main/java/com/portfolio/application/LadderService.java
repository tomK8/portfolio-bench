package com.portfolio.application;

import com.portfolio.domain.Ladders;
import com.portfolio.domain.model.GroupType;
import com.portfolio.domain.model.IntendedRung;
import com.portfolio.domain.model.OpenOrder;
import com.portfolio.domain.model.OrderGroup;
import com.portfolio.domain.model.Rung;
import com.portfolio.persistence.OrderGroupRepository;
import com.portfolio.persistence.OrderHistoryRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
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

    /**
     * The two fixed horizons a rung is priced over. They live on {@link GroupRungs} because every
     * group panel shares them — see that class for why they are fixed and why they are 10 and 30.
     */
    public static final int NEAR_HORIZON_SESSIONS = GroupRungs.NEAR_HORIZON_SESSIONS;
    public static final int FAR_HORIZON_SESSIONS = GroupRungs.FAR_HORIZON_SESSIONS;

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
     * <p>The construction is {@link GroupRungs#syntheticOrder}, shared with every other group type
     * so an intended rung is the same object wherever it is shown.
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
            if (!g.isLadder()) continue;
            for (IntendedRung r : byGroup.getOrDefault(g.id(), List.of())) {
                String ccy = g.symbol() == null ? null : quoteCurrency.apply(g.symbol());
                out.add(GroupRungs.syntheticOrder(r, g.symbol(), ccy));
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
     * @param touch    prices one enriched order's chance of being touched over a given number of
     *                 sessions. Supplied by the caller for the same reason the enrichment is: the
     *                 first-passage model lives in one place, and a ladder column computing its own
     *                 would be a second implementation that eventually disagrees with the table.
     *                 See {@link GroupRungs.TouchPricer}.
     */
    public LadderReport assemble(List<OpenOrder> live, List<OpenOrder> intents,
                                 List<OrderHistoryRepository.HistoricalOrder> history,
                                 Map<String, String> notes, List<String> accounts,
                                 GroupRungs.TouchPricer touch) {
        List<String> warnings = new ArrayList<>();
        // Ladders only. A group of another type has its own assembler with its own shape, and
        // reading one here would lay two listings of a fund on a single price axis that means
        // nothing for them - the exact mistake GroupType exists to prevent.
        List<OrderGroup> all = groups.groups().stream().filter(OrderGroup::isLadder).toList();
        if (all.isEmpty()) {
            return new LadderReport(List.of(), List.of(), accounts, warnings);
        }

        Map<String, OpenOrder> liveByKey = GroupRungs.byKey(live);
        Map<String, OpenOrder> intentByKey = GroupRungs.byKey(intents);
        Map<String, OrderHistoryRepository.HistoricalOrder> closedByKey = new HashMap<>();
        for (OrderHistoryRepository.HistoricalOrder h : history) closedByKey.put(h.orderKey(), h);

        Map<Long, List<String>> members = groups.members();
        Map<Long, List<IntendedRung>> intentRows = groups.intents();

        List<Ladder> ladders = new ArrayList<>();
        for (OrderGroup g : all) {
            List<Rung> rungs = new ArrayList<>();
            for (String key : members.getOrDefault(g.id(), List.of())) {
                Rung r = GroupRungs.realRung(key, liveByKey, closedByKey, notes, touch);
                if (r == null) {
                    warnings.add(g.name() + ": a rung points at an order that is no longer on file "
                            + "(" + key + "). Remove it from the ladder.");
                    continue;
                }
                rungs.add(r);
            }
            for (IntendedRung i : intentRows.getOrDefault(g.id(), List.of())) {
                rungs.add(GroupRungs.intendedRung(i, g.symbol(),
                        intentByKey.get(i.account() + "|" + i.reference()), touch));
            }

            List<Rung> ordered = Ladders.byPrice(rungs);
            BigDecimal current = currentPrice(ordered);
            ladders.add(new Ladder(g, ordered, Ladders.markerIndex(ordered, current), current,
                    currencyOf(ordered), Ladders.totals(rungs)));
        }

        return new LadderReport(ladders, unassigned(live, history), accounts, warnings);
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
     * Orders in no group - what the "add rungs" picker starts from, before
     * {@link LadderReport#assignableFor} narrows it to this ladder's own security. The shared list
     * is built once in {@link GroupRungs#unassigned}; who may be assigned where is this type's rule
     * and stays here.
     */
    private List<GroupRungs.Candidate> unassigned(
            List<OpenOrder> live, List<OrderHistoryRepository.HistoricalOrder> history) {
        return GroupRungs.unassigned(live, history, groups.assignedKeys());
    }

    // ---- writes ------------------------------------------------------------

    /** Create a ladder. A ladder is rungs on one security, so it needs to know which. */
    public long createLadder(String name, String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalStateException("A ladder needs a security - give it a symbol.");
        }
        return groups.create(GroupType.LADDER, name, symbol, null, null, LocalDate.now());
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
        // Null listing: a ladder's rungs are the group's one security by definition, so storing
        // it on every rung would be a second answer to a question the group already settles.
        return groups.addIntent(ladderId, account, null, side, quantity, price, note,
                LocalDate.now());
    }

    public void deleteIntent(long intentId) {
        groups.deleteIntent(intentId);
    }

    /**
     * Re-price or re-size an intended rung in place.
     *
     * <p>An intended rung is the <b>only</b> kind this panel can edit, and that is the whole rule:
     * it is the user's own record, so typing over its price or quantity <em>is</em> the edit and is
     * written straight through. A rung that came from a broker export is what the broker is holding
     * and is fixed - see {@code GroupRungs.updateIntent}.
     *
     * <p>The alternative was delete and re-record, which threw away the note: the one part of a rung
     * that says <em>why</em> it is there, and the reason intended rungs exist at all.
     *
     * <p>Blank means "leave it" and never zero, the app-wide rule, so tabbing through a row commits
     * nothing and a half-typed figure cannot silently empty a commitment. Nothing else about the
     * rung moves: an intent's side names its account's role in the ladder, and its note is the
     * reasoning, neither of which is a figure being tuned here.
     */
    public void updateIntent(long intentId, BigDecimal quantity, BigDecimal price) {
        GroupRungs.updateIntent(groups, intentId, quantity, price);
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
        GroupRungs.carryNote(orderHistory, orderKey, intent.note());
        groups.deleteIntent(intentId);
    }

    /**
     * Every order in <em>any</em> group - handed to the history pruner so a grouped order is never
     * deleted. The member table is shared across group types (one order, one group, whatever kind),
     * so this is deliberately not filtered to ladders: a switch's sell leg has to survive until the
     * buy leg that completes it arrives, which can be months.
     */
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
     * What the Ladders panel renders.
     *
     * <p>{@link #assignableFor} is the single place the "one group, one security" rule is applied.
     * It is a rule of this group type and not of the storage: a later type whose rungs span two
     * securities changes this method and nothing under it.
     */
    public record LadderReport(List<Ladder> ladders, List<GroupRungs.Candidate> unassigned,
                               List<String> accounts, List<String> warnings) {

        public boolean isEmpty() {
            return ladders.isEmpty();
        }

        public List<GroupRungs.Candidate> assignableFor(Ladder ladder) {
            String symbol = ladder.group().symbol();
            if (symbol == null) return List.of();
            List<GroupRungs.Candidate> out = new ArrayList<>();
            for (GroupRungs.Candidate c : unassigned) {
                if (symbol.equalsIgnoreCase(c.symbol())) out.add(c);
            }
            return out;
        }
    }
}
