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
 * <h2>Which side an order is on</h2>
 * By <b>side</b>: a sell is on the side being sold, a buy on the side being bought, and
 * <em>either</em> may sit on either of the pair's two listings. Limits go out on both lines at once
 * in the ordinary case — sells above on one, buys below on the other — and which line a fill lands
 * on is whichever was dearer on the day. The listing is still read, and it decides the one thing it
 * is evidence for: which market price the leg is measured against, and which currency it is in.
 *
 * <p>The group's declared {@code symbol} and {@code symbolTo} therefore no longer place legs. They
 * are the <b>pair</b> — the two listings this switch is allowed to touch, and the check that an
 * assigned order is one of them — and their order names which listing the holding is counted under
 * everywhere exposure is measured. An order on neither is a mis-assignment, kept and named, counted
 * in nothing.
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
     * <p>The listing is the one recorded on the intent. It has to be stored, because the side no
     * longer names it: a switch can be selling either line and buying either line, so "Sell" says
     * nothing about which of the two the leg is on. An intent written before that was true carries
     * no symbol, and is read under the rule it was written under — sell is the line being left, buy
     * the line being entered.
     */
    public List<OpenOrder> intendedOrders(Function<String, String> quoteCurrency) {
        List<OpenOrder> out = new ArrayList<>();
        Map<Long, List<IntendedRung>> byGroup = groups.intents();
        if (byGroup.isEmpty()) return out;
        for (OrderGroup g : switchGroups()) {
            for (IntendedRung r : byGroup.getOrDefault(g.id(), List.of())) {
                String symbol = listingOf(g, r);
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
            List<Rung> sellLegs = new ArrayList<>();
            List<Rung> buyLegs = new ArrayList<>();
            List<String> offShape = new ArrayList<>();

            for (String key : members.getOrDefault(g.id(), List.of())) {
                Rung r = GroupRungs.realRung(key, liveByKey, closedByKey, notes, touch);
                if (r == null) {
                    warnings.add(g.name() + ": a leg points at an order that is no longer on file ("
                            + key + "). Remove it from the switch.");
                    continue;
                }
                place(g, r, sellLegs, buyLegs, offShape);
            }
            for (IntendedRung i : intentRows.getOrDefault(g.id(), List.of())) {
                Rung r = GroupRungs.intendedRung(i, listingOf(g, i),
                        intentByKey.get(i.account() + "|" + i.reference()), touch);
                place(g, r, sellLegs, buyLegs, offShape);
            }

            Switches.LinePrice outLine = lines.getOrDefault(upper(g.symbol()),
                    Switches.LinePrice.none(g.symbol()));
            Switches.LinePrice inLine = lines.getOrDefault(upper(g.symbolTo()),
                    Switches.LinePrice.none(g.symbolTo()));
            Comparator<Rung> order = legOrder(outLine, inLine, rates);
            sellLegs.sort(order);
            buyLegs.sort(order);
            Switches.Summary summary = Switches.summarise(sellLegs, buyLegs, outLine, inLine, rates,
                    available);
            out.add(new Switch(g, sellLegs, buyLegs, summary, offShape));
        }
        return new SwitchReport(out, unassigned(live, history), accounts, warnings);
    }

    /**
     * Put a leg on the side its <em>direction</em> says, having checked it is on one of the pair's
     * two listings.
     *
     * <p>Both listings take both sides. Selling the sterling line while a buy rests on it, and the
     * dollar line doing the same, is one position being worked from both ends, and it is what the
     * panel had no way to record before. What the listing still decides is which market the leg is
     * priced against — {@link Switches} reads that off the rung.
     *
     * <p>A leg on neither listing is a mis-assignment: kept, because the user assigned it, named in
     * the panel, and counted in nothing. So is one whose side the export never said, which cannot be
     * put on a side without guessing which.
     */
    private static void place(OrderGroup g, Rung r, List<Rung> sellLegs, List<Rung> buyLegs,
                              List<String> offShape) {
        String symbol = r.symbol();
        boolean onPair = symbol != null && (symbol.equalsIgnoreCase(g.symbol())
                || symbol.equalsIgnoreCase(g.symbolTo()));
        if (!onPair) {
            offShape.add(r.describe() + " is on "
                    + (symbol == null ? "an unknown listing" : symbol)
                    + ", which is neither line of this switch, so it is counted in nothing.");
            return;
        }
        if (r.sell()) sellLegs.add(r);
        else if (r.buy()) buyLegs.add(r);
        else offShape.add(r.describe() + " on " + symbol + " — the export does not say which way it "
                + "goes, so it cannot be put on a side and is counted in nothing.");
    }

    /**
     * Highest price first inside a side, on the <b>yardstick</b>.
     *
     * <p>A side now holds both listings, and £584.80 is not above $682.35 — it is below it, once the
     * exchange rate is divided out. Sorting on the raw figures would stack every sterling row above
     * every dollar one and call it a price order. Legs that cannot be converted sort last rather
     * than to one end of the prices, since where they belong is exactly what is unknown.
     */
    private static Comparator<Rung> legOrder(Switches.LinePrice outLine, Switches.LinePrice inLine,
                                             Map<String, BigDecimal> rates) {
        return Comparator
                .comparing((Rung r) -> Switches.gbpPrice(r, outLine, inLine, rates),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Rung::account, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Rung::key, Comparator.nullsLast(Comparator.naturalOrder()));
    }

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

    /**
     * The listing an intended leg trades.
     *
     * <p>Stored on the intent, because a switch can now be selling either line and buying either
     * line, so the side no longer names it. Null means a row written before that was true, and it is
     * read under the rule it was written under — sell is the line being left, buy the line being
     * entered. Reading those rows any other way would silently move an existing leg onto the other
     * listing and re-price it against the wrong market.
     */
    private static String listingOf(OrderGroup g, IntendedRung r) {
        if (r.symbol() != null && !r.symbol().isBlank()) return r.symbol();
        return "sell".equalsIgnoreCase(r.side()) ? g.symbol() : g.symbolTo();
    }

    /** Which of the pair a typed listing is, or null when it is neither. */
    private static String listingOnPair(OrderGroup g, String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        String s = symbol.trim();
        if (s.equalsIgnoreCase(g.symbol())) return g.symbol();
        if (s.equalsIgnoreCase(g.symbolTo())) return g.symbolTo();
        return null;
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
     * <p>The listing is asked for and stored. It used to be read off the side, which worked only
     * while a switch sold one line and bought the other; now that either line can carry either side,
     * the side says nothing about which market the leg's price belongs to, and guessing would price
     * it against the wrong one — a sterling limit judged against a dollar quote.
     *
     * <p>A blank listing falls back to the old rule rather than failing, so the form keeps working
     * for the ordinary one-way case. A listing that is neither of the pair is rejected: the pair is
     * what the switch is, and there is no third line to put a leg on.
     */
    public long addIntent(long switchId, String account, String symbol, String side,
                          BigDecimal quantity, BigDecimal price, String note) {
        OrderGroup g = switchGroups().stream().filter(x -> x.id() == switchId).findFirst()
                .orElseThrow(() -> new IllegalStateException("That switch is no longer on file."));
        String listing = listingOnPair(g, symbol);
        if (listing == null && symbol != null && !symbol.isBlank()) {
            throw new IllegalStateException("A leg has to be on " + g.symbol() + " or "
                    + g.symbolTo() + " — the two listings this switch covers.");
        }
        if (listing == null) {
            listing = "sell".equalsIgnoreCase(side == null ? "" : side.trim())
                    ? g.symbol() : g.symbolTo();
        }
        return groups.addIntent(switchId, account, listing, side, quantity, price, note,
                LocalDate.now());
    }

    public void deleteIntent(long intentId) {
        groups.deleteIntent(intentId);
    }

    /**
     * Re-price or re-size an intended leg in place.
     *
     * <p>The same write a ladder's intended rung takes — and, as there, the only figures this panel
     * can move: a leg that came from a broker export is fixed. It matters more here, because on a
     * switch the buy leg is an intent for days by construction, so it is the leg most likely to need
     * moving as the market does — and re-recording it would throw away its note along with it. The
     * side is untouched, which is what keeps the leg on the listing it was recorded against: a
     * switch reads the listing off the side, never off a stored symbol.
     */
    public void updateIntent(long intentId, BigDecimal quantity, BigDecimal price) {
        GroupRungs.updateIntent(groups, intentId, quantity, price);
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
    public record Switch(OrderGroup group, List<Rung> sellLegs, List<Rung> buyLegs,
                         Switches.Summary summary, List<String> offShape) {

        /** The line the holding is leaving — and the name it is counted under in the exposure views. */
        public String from() {
            return group.symbol();
        }

        /** The line the holding is moving into. */
        public String to() {
            return group.symbolTo();
        }

        public boolean hasLegs() {
            return !sellLegs.isEmpty() || !buyLegs.isEmpty();
        }

        /**
         * True while this switch is the plain one-way move: everything being sold is on the line
         * being left and everything being bought is on the line being entered.
         *
         * <p>Only the wording turns on it. A one-way switch can say "still in EQQQ" and "in EQQU",
         * which is what the units actually mean there; a switch worked from both ends cannot, and
         * says "still to sell" and "bought back" instead. Every figure is computed the same way
         * either way — this decides what the labels are allowed to claim, and nothing else.
         */
        public boolean oneWay() {
            return sellLegs.stream().allMatch(r -> on(r, from()))
                    && buyLegs.stream().allMatch(r -> on(r, to()));
        }

        private static boolean on(Rung r, String symbol) {
            return r.symbol() != null && r.symbol().equalsIgnoreCase(symbol);
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
