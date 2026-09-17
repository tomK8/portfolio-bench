package com.portfolio.application;

import com.portfolio.domain.ShareClasses;
import com.portfolio.domain.Switches;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.GroupType;
import com.portfolio.domain.model.IntendedRung;
import com.portfolio.domain.model.OpenOrder;
import com.portfolio.domain.model.OrderGroup;
import com.portfolio.domain.model.Rung;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.OrderGroupRepository;
import com.portfolio.persistence.OrderHistoryRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Share-class switches: one holding moving from one listing of a fund to another listing of the
 * same fund.
 *
 * <p>EQQQ and EQQU are the same fund, the same ISIN and the same holdings; the trading currency,
 * the exchange line and the account are all that differ. Selling one to buy the other changes
 * nothing about what is owned, and that is exactly why none of the existing shapes fit it. A ladder
 * assumes one security and one price spine, and £530 and $716 cannot share a price axis. A rotation
 * assumes the exposure changes, and here it does not. So this is its own type with its own
 * assembler, and the arithmetic — the yardstick, the split between index and exchange rate, the
 * progress in units — lives framework-free in {@link Switches}.
 *
 * <h2>What it does not do</h2>
 * <ul>
 *   <li><b>It never infers a switch.</b> A sell of EQQQ beside a buy of EQQU is two orders until the
 *       user says otherwise. Same-fund-ness is not a fact any feed here publishes, and guessing it
 *       would quietly stop counting a real rotation as a change of exposure.</li>
 *   <li><b>It never promotes an intended leg.</b> The buy leg of a switch is normally unplaceable
 *       for days — the cash is in the wrong account and the wrong currency — so an intent is the
 *       ordinary state here, and it becomes a real order when the user names the order it became.</li>
 *   <li><b>It is not for switching between different companies.</b> That is a different shape with
 *       different questions and it is deliberately not built here.</li>
 * </ul>
 *
 * <h2>Which leg an order is on</h2>
 * By <b>listing</b>, never by side: an order on the group's first symbol is on the leg being left,
 * one on its second symbol is on the leg being entered. Side is then checked rather than assumed —
 * a buy of the old line or a sell of the new one is kept (the user assigned it) but left out of
 * every figure and named, because it is not part of moving a holding one way.
 */
public class SwitchService {

    private final OrderGroupRepository groups;

    /** Only for carrying an intended leg's note onto the order that replaces it. */
    private final OrderHistoryRepository orderHistory;

    /**
     * What the cash ledger says each account holds. The panel's whole sequencing story ends in one
     * question — is the money where the buy needs it, in the currency the buy needs — and that is a
     * fact this app already holds rather than one it has to ask the user for.
     */
    private final CashTransactionRepository cash;

    public SwitchService(OrderGroupRepository groups, OrderHistoryRepository orderHistory,
                         CashTransactionRepository cash) {
        this.groups = groups;
        this.orderHistory = orderHistory;
        this.cash = cash;
    }

    // ---- the read the panel renders ----------------------------------------

    /** Every listing named by a declared switch — what the price feed has to keep warm. */
    public Set<String> lineSymbols() {
        Set<String> out = new LinkedHashSet<>();
        for (OrderGroup g : switchGroups()) {
            if (g.symbol() != null) out.add(g.symbol());
            if (g.symbolTo() != null) out.add(g.symbolTo());
        }
        return out;
    }

    /**
     * Synthetic orders standing in for every intended leg, for the caller to enrich alongside the
     * real ones — the same construction, and therefore the same analytics, a ladder's intents get.
     *
     * <p>The listing comes from the <b>side</b>: a switch sells the line it is leaving and buys the
     * line it is entering, so the side names the symbol and nothing has to be stored twice. An
     * intent that goes the other way — a buy of the old line — would be a leg outside the shape, and
     * is rejected at the point it is recorded rather than silently priced against the wrong listing.
     */
    public List<OpenOrder> intendedOrders(Function<String, String> quoteCurrency) {
        List<OpenOrder> out = new ArrayList<>();
        Map<Long, List<IntendedRung>> byGroup = groups.intents();
        if (byGroup.isEmpty()) return out;
        for (OrderGroup g : switchGroups()) {
            for (IntendedRung r : byGroup.getOrDefault(g.id(), List.of())) {
                String symbol = symbolForSide(g, r.side());
                String ccy = symbol == null ? null : quoteCurrency.apply(symbol);
                out.add(GroupRungs.syntheticOrder(r, symbol, ccy));
            }
        }
        return out;
    }

    /**
     * Build the panel.
     *
     * @param lines  each declared listing's live market price, from the same feed the table's
     *               Current column reads. Both lines are needed even when one has no orders on it:
     *               the check that the two listings really are one fund is their <em>prices</em>
     *               against the exchange rate, and it is the one piece of live evidence the panel
     *               has that the pair is what the user says it is.
     * @param rates  the FX snapshot, units of foreign currency per 1 GBP
     */
    public SwitchReport assemble(List<OpenOrder> live, List<OpenOrder> intents,
                                 List<OrderHistoryRepository.HistoricalOrder> history,
                                 Map<String, String> notes, List<String> accounts,
                                 Map<String, Switches.LinePrice> lines,
                                 Map<String, BigDecimal> rates,
                                 GroupRungs.TouchPricer touch) {
        List<String> warnings = new ArrayList<>();
        List<OrderGroup> all = switchGroups();
        if (all.isEmpty()) {
            return new SwitchReport(List.of(), List.of(), accounts, warnings);
        }

        Map<String, OpenOrder> liveByKey = GroupRungs.byKey(live);
        Map<String, OpenOrder> intentByKey = GroupRungs.byKey(intents);
        Map<String, OrderHistoryRepository.HistoricalOrder> closedByKey = new HashMap<>();
        for (OrderHistoryRepository.HistoricalOrder h : history) closedByKey.put(h.orderKey(), h);

        Map<Long, List<String>> members = groups.members();
        Map<Long, List<IntendedRung>> intentRows = groups.intents();
        List<Switches.CashAvailability> available = cashAvailability();

        List<Switch> out = new ArrayList<>();
        for (OrderGroup g : all) {
            List<Rung> outLegs = new ArrayList<>();
            List<Rung> inLegs = new ArrayList<>();
            List<String> offShape = new ArrayList<>();

            for (String key : members.getOrDefault(g.id(), List.of())) {
                Rung r = GroupRungs.realRung(key, liveByKey, closedByKey, notes, touch);
                if (r == null) {
                    warnings.add(g.name() + ": a leg points at an order that is no longer on file ("
                            + key + "). Remove it from the switch.");
                    continue;
                }
                OpenOrder src = liveByKey.get(key);
                String symbol = src != null ? src.symbol()
                        : closedByKey.containsKey(key) ? closedByKey.get(key).symbol() : null;
                place(g, r, symbol, outLegs, inLegs, offShape);
            }
            for (IntendedRung i : intentRows.getOrDefault(g.id(), List.of())) {
                Rung r = GroupRungs.intendedRung(
                        i, intentByKey.get(i.account() + "|" + i.reference()), touch);
                place(g, r, symbolForSide(g, i.side()), outLegs, inLegs, offShape);
            }

            outLegs.sort(LEG_ORDER);
            inLegs.sort(LEG_ORDER);
            Switches.LinePrice outLine = lines.getOrDefault(upper(g.symbol()),
                    Switches.LinePrice.none(g.symbol()));
            Switches.LinePrice inLine = lines.getOrDefault(upper(g.symbolTo()),
                    Switches.LinePrice.none(g.symbolTo()));
            Switches.Summary summary = Switches.summarise(outLegs, inLegs, outLine, inLine, rates,
                    available);
            out.add(new Switch(g, outLegs, inLegs, summary, offShape));
        }
        return new SwitchReport(out, unassigned(live, history), accounts, warnings);
    }

    /**
     * Put a leg on the side its <em>listing</em> says, and check the direction rather than assume
     * it.
     *
     * <p>A leg on neither listing is a mis-assignment and is named; a leg going the wrong way — a
     * buy of the line being left, a sell of the line being entered — is a real order the user
     * assigned, so it is kept and shown, but it is not part of moving a holding one way and is left
     * out of every figure. Absorbing it would make the progress read as further along, or further
     * behind, than the holding actually is.
     */
    private static void place(OrderGroup g, Rung r, String symbol, List<Rung> outLegs,
                              List<Rung> inLegs, List<String> offShape) {
        if (symbol != null && symbol.equalsIgnoreCase(g.symbol())) {
            if (r.sell() || r.gone()) outLegs.add(r);
            else offShape.add(r.describe() + " on " + g.symbol()
                    + " — a buy of the line being left is not part of this switch, so it is "
                    + "counted in nothing.");
            return;
        }
        if (symbol != null && symbol.equalsIgnoreCase(g.symbolTo())) {
            if (r.buy() || r.gone()) inLegs.add(r);
            else offShape.add(r.describe() + " on " + g.symbolTo()
                    + " — a sell of the line being entered is not part of this switch, so it is "
                    + "counted in nothing.");
            return;
        }
        offShape.add(r.describe() + " is on " + (symbol == null ? "an unknown listing" : symbol)
                + ", which is neither line of this switch, so it is counted in nothing.");
    }

    /** Highest price first inside a leg, so a leg of several rungs reads like a ladder's would. */
    private static final Comparator<Rung> LEG_ORDER = Comparator
            .comparing(Rung::price, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Rung::account, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Rung::key, Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * What each account holds, in the vocabulary the orders use.
     *
     * <p>The ledger keys accounts by their stored value and the orders by their broker label; they
     * are two spellings of one thing and {@link Account#displayName()} is where that is settled.
     * A balance with no native figure is skipped rather than read as its GBP equivalent: the buy leg
     * needs dollars, and dollars is not a number this row carries.
     */
    private List<Switches.CashAvailability> cashAvailability() {
        List<Switches.CashAvailability> out = new ArrayList<>();
        if (cash == null) return out;
        Map<Account, String> asOf = new HashMap<>();
        for (CashTransactionRepository.CashBalance b : cash.latestCashBalances()) {
            if (b.cashNative() == null) continue;
            Account account;
            try {
                account = Account.fromDbValue(b.accountDbValue());
            } catch (IllegalArgumentException e) {
                continue;
            }
            String date = asOf.computeIfAbsent(account, cash::latestTransactionDate);
            out.add(new Switches.CashAvailability(account.displayName(), b.currency(),
                    BigDecimal.valueOf(b.cashNative()), date));
        }
        return out;
    }

    private List<GroupRungs.Candidate> unassigned(
            List<OpenOrder> live, List<OrderHistoryRepository.HistoricalOrder> history) {
        return GroupRungs.unassigned(live, history, groups.assignedKeys());
    }

    private List<OrderGroup> switchGroups() {
        return groups.groups().stream().filter(OrderGroup::isSwitch).toList();
    }

    private static String symbolForSide(OrderGroup g, String side) {
        return "sell".equalsIgnoreCase(side) ? g.symbol() : g.symbolTo();
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase();
    }

    // ---- the one-holding declaration ---------------------------------------

    /**
     * The listing pairs every exposure view folds into one holding.
     *
     * <p>This is the second thing creating a switch does, and it is not a side effect: declaring
     * that EQQQ and EQQU are one fund is the same statement as declaring the switch, and a view that
     * kept counting them as two positions would contradict the panel sitting on the Open orders tab.
     * A half-finished switch would otherwise read as two half-sized positions — a concentration
     * figure saying the book is more diversified than it is, and an allocation chart with one line
     * falling as another rises while nothing the user owns has changed.
     *
     * <p>The pair survives the switch completing. It has to: the holding's history runs through both
     * listings, and un-declaring it the moment the last unit lands would put the break back into
     * every chart that just stopped having one.
     */
    public ShareClasses shareClasses() {
        List<ShareClasses.Pair> pairs = new ArrayList<>();
        for (OrderGroup g : switchGroups()) {
            if (g.symbol() == null || g.symbolTo() == null) continue;
            pairs.add(new ShareClasses.Pair(g.symbol(), g.symbolTo()));
        }
        return ShareClasses.of(pairs);
    }

    // ---- writes ------------------------------------------------------------

    /**
     * Declare a switch. Both listings are required and they must differ: a switch with one line is
     * not a switch, and there is no sensible half-declared state to store.
     */
    public long createSwitch(String name, String from, String to) {
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            throw new IllegalStateException(
                    "A share-class switch needs both listings — the line you are leaving and the "
                            + "line you are moving into.");
        }
        if (from.trim().equalsIgnoreCase(to.trim())) {
            throw new IllegalStateException("The two listings have to be different.");
        }
        return groups.create(GroupType.SHARE_CLASS_SWITCH, name, from, to, null, LocalDate.now());
    }

    public void deleteSwitch(long id) {
        groups.delete(id);
    }

    public void addLegs(long switchId, Collection<String> orderKeys) {
        groups.addMembers(switchId, orderKeys, LocalDate.now());
    }

    public void removeLeg(String orderKey) {
        groups.removeMember(orderKey);
    }

    /**
     * Record a leg decided on but not placed — which on a switch is the buy leg, nearly always, and
     * for days at a time.
     *
     * <p>The side names the listing — Sell is the line being left, Buy the line being entered — so
     * nothing extra is stored and the two can never drift apart.
     */
    public long addIntent(long switchId, String account, String side, BigDecimal quantity,
                          BigDecimal price, String note) {
        return groups.addIntent(switchId, account, side, quantity, price, note, LocalDate.now());
    }

    public void deleteIntent(long intentId) {
        groups.deleteIntent(intentId);
    }

    /**
     * The user says an intended leg has been placed, and names the order it became — the only path
     * from intended to real, for the same reason a ladder has no other one.
     */
    public void markPlaced(long intentId, String orderKey) {
        if (orderKey == null || orderKey.isBlank()) {
            throw new IllegalStateException("Pick the order this leg became.");
        }
        IntendedRung intent = groups.intent(intentId);
        if (intent == null) {
            throw new IllegalStateException("That intended leg is no longer on file.");
        }
        groups.addMembers(intent.groupId(), List.of(orderKey), LocalDate.now());
        GroupRungs.carryNote(orderHistory, orderKey, intent.note());
        groups.deleteIntent(intentId);
    }

    // ---- what the fragment renders -----------------------------------------

    /**
     * One switch, assembled.
     *
     * @param offShape legs the user assigned that are not part of moving the holding one way —
     *                 kept and listed, counted in nothing.
     */
    public record Switch(OrderGroup group, List<Rung> outLegs, List<Rung> inLegs,
                         Switches.Summary summary, List<String> offShape) {

        /** The line the holding is leaving. */
        public String from() {
            return group.symbol();
        }

        /** The line the holding is moving into. */
        public String to() {
            return group.symbolTo();
        }

        public boolean hasLegs() {
            return !outLegs.isEmpty() || !inLegs.isEmpty();
        }
    }

    /**
     * What the Switches panel renders.
     *
     * <p>{@link #assignableFor} is where "a switch spans exactly two listings" is applied, and it is
     * a rule of this type rather than of the storage — the same seam the ladder's one-security rule
     * sits on, one inch along.
     */
    public record SwitchReport(List<Switch> switches, List<GroupRungs.Candidate> unassigned,
                               List<String> accounts, List<String> warnings) {

        public boolean isEmpty() {
            return switches.isEmpty();
        }

        public List<GroupRungs.Candidate> assignableFor(Switch s) {
            List<GroupRungs.Candidate> out = new ArrayList<>();
            for (GroupRungs.Candidate c : unassigned) {
                if (c.symbol() == null) continue;
                if (c.symbol().equalsIgnoreCase(s.from()) || c.symbol().equalsIgnoreCase(s.to())) {
                    out.add(c);
                }
            }
            return out;
        }
    }
}
