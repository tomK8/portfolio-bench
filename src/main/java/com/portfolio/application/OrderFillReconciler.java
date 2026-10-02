package com.portfolio.application;

import com.portfolio.domain.OrderFills;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.OrderStatus;
import com.portfolio.domain.model.TransactionType;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.OrderHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Settles orders the broker stopped listing without ever saying what became of them, by finding the
 * trade they produced in the cash ledger.
 *
 * <p>The rule itself is {@link OrderFills}, which is pure and knows nothing about where any of this
 * is stored. This class is the plumbing either side of it: which stored rows are worth asking
 * about, what the ledger looks like once its correction pairs are taken out, and writing the
 * verdict down.
 *
 * <h2>Why it is written down and not derived</h2>
 * <p>A verdict is persisted, in its own column, for the same reason the annotation beside it is:
 * the next download of the order export must not undo it. It is also the only way this can be
 * cheap. Deriving it on each page load would mean re-reading the ledger every time the Open-orders
 * tab is opened, for an answer that cannot have changed.
 *
 * <h2>When it runs, and how it stays idle</h2>
 * <p>Two triggers, because either half of the evidence can arrive first. A cash import brings the
 * trade for an order already filed as gone; an orders refresh files an order as gone whose trade is
 * already in the ledger. Both call this, and whichever comes second is the one that finds the
 * match.
 *
 * <p>Running on both would be waste if every call did the work. It does not: an order that the
 * ledger could not explain has had its question asked of the data that existed, and the answer
 * cannot change until the ledger moves. So each attempt records the account's latest ledger date
 * against the row ({@code matched_through}), and the next call skips every row whose account has
 * not been imported past it. In the steady state — nothing new closed, nothing new imported — this
 * reads one small table, finds nothing due, and never touches the ledger at all.
 *
 * <h2>What it will not conclude</h2>
 * <p>Only {@code FILLED} is ever written. A match is evidence; the absence of one is not evidence
 * of a cancellation, only of a ledger that may not have been downloaded yet. An order nothing
 * explains keeps saying so, and the user can say outright what happened if they know.
 */
public class OrderFillReconciler {

    private static final Logger log = LoggerFactory.getLogger(OrderFillReconciler.class);

    private final OrderHistoryRepository history;
    private final CashTransactionRepository cash;

    public OrderFillReconciler(OrderHistoryRepository history, CashTransactionRepository cash) {
        this.history = history;
        this.cash = cash;
    }

    public int reconcile() {
        return reconcile(LocalDate.now());
    }

    /**
     * @return how many orders were newly explained; 0 whenever there was nothing due to ask about
     */
    public int reconcile(LocalDate today) {
        List<OrderHistoryRepository.FillCandidate> stored;
        try {
            stored = history.fillCandidates();
        } catch (Exception e) {
            log.warn("Could not read the order history for fill matching", e);
            return 0;
        }
        if (stored.isEmpty()) return 0;

        List<OrderHistoryRepository.FillCandidate> unexplained =
                stored.stream().filter(OrderFillReconciler::unexplained).toList();
        if (unexplained.isEmpty()) return 0;

        // One MAX(transaction_date) per account that actually has a question outstanding — at most
        // three queries, and none at all once every order is accounted for.
        Map<String, String> ledgerThrough = new HashMap<>();
        for (OrderHistoryRepository.FillCandidate c : unexplained) {
            ledgerThrough.computeIfAbsent(c.broker(), b -> {
                Account a = Account.fromDisplayName(b);
                return a == null ? null : cash.latestTransactionDate(a);
            });
        }
        List<OrderHistoryRepository.FillCandidate> due = unexplained.stream()
                .filter(c -> due(c, ledgerThrough.get(c.broker())))
                .toList();
        if (due.isEmpty()) return 0;

        List<OrderFills.Trade> trades = ledgerTrades();
        if (trades.isEmpty()) return 0;

        Set<String> asking = new HashSet<>();
        for (OrderHistoryRepository.FillCandidate c : due) asking.add(c.orderKey());
        List<OrderFills.Order> orders = new ArrayList<>(stored.size());
        Set<Long> claimed = new HashSet<>();
        for (OrderHistoryRepository.FillCandidate c : stored) {
            if (c.resolvedTrade() != null) claimed.add(c.resolvedTrade());
            orders.add(order(c, asking.contains(c.orderKey())));
        }

        int settled = 0;
        for (OrderFills.Match m : OrderFills.match(orders, trades, claimed)) {
            try {
                if (history.saveResolution(m.orderKey(), OrderStatus.FILLED,
                        OrderHistoryRepository.SOURCE_MATCHED, m.tradeRowid(), today)) {
                    settled++;
                    log.info("Order {} read as filled — matched the cash-ledger trade of {}",
                            m.orderKey(), m.tradeDate());
                }
            } catch (Exception e) {
                // One odd row must not fail an import or the Open-orders panel: the order simply
                // stays unexplained, which is what it already was.
                log.warn("Could not record the matched fill for {}", m.orderKey(), e);
            }
        }

        // Every row asked about is marked, matched or not. The unmatched ones are the point of it:
        // without the mark they would be re-asked on every page load for an answer the ledger
        // cannot give until it grows.
        Map<String, String> checked = new LinkedHashMap<>();
        for (OrderHistoryRepository.FillCandidate c : due) {
            checked.put(c.orderKey(), ledgerThrough.get(c.broker()));
        }
        history.markChecked(checked);
        return settled;
    }

    /** Closed, with neither the broker nor us having said how it ended. */
    private static boolean unexplained(OrderHistoryRepository.FillCandidate c) {
        return c.closedAt() != null
                && OrderStatus.of(c.status()).open()
                && OrderStatus.named(c.resolvedStatus()) == null;
    }

    /**
     * Whether this row is worth asking about again. Only when its account's ledger now runs past
     * the day the row was last checked against — an unchanged ledger cannot give a different
     * answer, and an account with no ledger at all cannot give one either.
     */
    private static boolean due(OrderHistoryRepository.FillCandidate c, String ledgerThrough) {
        if (ledgerThrough == null) return false;
        return c.matchedThrough() == null || c.matchedThrough().compareTo(ledgerThrough) < 0;
    }

    private static OrderFills.Order order(OrderHistoryRepository.FillCandidate c,
                                          boolean needsVerdict) {
        OrderStatus broker = OrderStatus.of(c.status());
        // A cancelled order produced no trade, so it claims none; only an order someone says
        // traded can own one. "Someone" includes us: a verdict already written owns its trade
        // through `claimed`, which is why a resolved row needs no flag here.
        boolean claimsFirst = broker == OrderStatus.FILLED || broker == OrderStatus.PART_FILLED;
        Account account = Account.fromDisplayName(c.broker());
        return new OrderFills.Order(c.orderKey(),
                account == null ? null : account.dbValue(),
                c.symbol(), c.side(), c.quantity(), c.price(), c.currency(),
                date(c.dateEntered()), date(c.closedAt()), claimsFirst, needsVerdict);
    }

    /**
     * The ledger's buy/sell rows as the matcher sees them, with broker correction pairs removed.
     *
     * <p>A reversal and its re-entry are not two trades — the reversal books as cash <em>in</em> on
     * a purchase, so left in it reads as a sale of exactly the size that was bought, which is the
     * shape most likely to be mistaken for a resting sell order's fill. The pair is identified by
     * {@link PositionDetailService#pruneCorrections}, deliberately rather than by a second rule
     * here: two definitions of what a correction is would eventually disagree, and this view of the
     * ledger must be the same one the position engine uses.
     */
    private List<OrderFills.Trade> ledgerTrades() {
        List<CashTransactionRepository.TradeRow> rows;
        try {
            rows = cash.loadTrades();
        } catch (Exception e) {
            log.warn("Could not read the cash ledger for fill matching", e);
            return List.of();
        }
        // Oldest first: pruneCorrections pairs a reversal with the row it reverses by scanning
        // forward, and the reversal always comes after what it reverses.
        List<CashTransactionRepository.TradeRow> ordered = rows.stream()
                .sorted(Comparator.comparing(CashTransactionRepository.TradeRow::transactionDate)
                        .thenComparing(CashTransactionRepository.TradeRow::rowid))
                .toList();
        List<CashTransaction> asTransactions = new ArrayList<>(ordered.size());
        Map<CashTransaction, Long> rowIds = new IdentityHashMap<>();
        for (CashTransactionRepository.TradeRow r : ordered) {
            CashTransaction t = new CashTransaction(r.transactionDate(), r.account(),
                    TransactionType.TRANSACTION, r.symbol(), r.quantity(), r.amount(),
                    r.currency(), r.amountGbp() == 0 ? 1.0 : r.amount() / r.amountGbp(),
                    r.amountGbp(), null, null, r.description());
            asTransactions.add(t);
            rowIds.put(t, r.rowid());
        }
        List<OrderFills.Trade> out = new ArrayList<>();
        for (CashTransaction t : PositionDetailService.pruneCorrections(asTransactions)) {
            out.add(new OrderFills.Trade(rowIds.get(t), date(t.transactionDate()),
                    t.account().dbValue(), t.symbol(),
                    BigDecimal.valueOf(t.quantity()), BigDecimal.valueOf(t.amount()),
                    t.currency()));
        }
        return out;
    }

    /** A stored ISO date, or null when it is absent or unreadable — never today as a stand-in. */
    private static LocalDate date(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return LocalDate.parse(iso.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
