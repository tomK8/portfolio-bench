package com.portfolio.domain;

import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reconstructs current security positions from cash-transaction history alone — no
 * broker holdings file required. Mirror image of {@link PortfolioAggregator}'s pass-1,
 * but the input is the {@code cash_transactions} ledger (buys + sells + splits) rather
 * than a parsed holdings export.
 *
 * <p>Per {@code (account, symbol)} the timeline is replayed FIFO over the
 * {@code TRANSACTION} rows. Each buy opens a lot carrying GBP cost-per-share (the row's
 * historical {@code amount_gbp} divided by quantity); sells remove shares oldest-first
 * and their cost leaves with them; splits scale every surviving lot's quantity by the
 * ratio and the cost-per-share inversely so cost basis is invariant under the split.
 *
 * <p>Surviving lots → current quantity + remaining cost basis. Accounts are then summed
 * per symbol (the same NVDA position split between AJBell and RothIRA collapses to one
 * row), and the broker names are merged into the {@code sources} set.
 *
 * <p>Cost is tracked in GBP only — historical FX at trade time. Native cost is not kept
 * because the AJBell ledger never sees the listing currency (every trade settles in
 * GBP regardless of whether the security is USD- or GBP-listed), so native cost would
 * be undefined across account boundaries. GBP at row time is consistent everywhere.
 */
public class CashLedgerReconstructor {

    private static final int SCALE = 12;

    /**
     * One reconstructed position. {@code tradeCurrency} is the currency the cash row
     * settled in (GBP for AJBell, USD for RothIRA, mixed for II) — the caller may
     * override this with the listing currency from the intraday price.
     */
    public record Position(
            String securityId,
            BigDecimal quantity,
            BigDecimal costGbp,
            String tradeCurrency,
            Set<String> accounts) {
    }

    /**
     * One reconstructed position <em>before</em> accounts are merged — what a single
     * {@code (account, symbol)} timeline came to. Only positions with a positive quantity are
     * returned, so an account that has sold out of a name does not appear for it.
     *
     * <p>{@code costGbp} is the surviving lots' remaining FIFO cost. It is the figure an in-specie
     * transfer must carry with the shares: move that exact number and the portfolio's total cost —
     * and therefore its gain — is unchanged by the move.
     */
    public record AccountPosition(
            String securityId,
            Account account,
            BigDecimal quantity,
            BigDecimal costGbp,
            String tradeCurrency) {
    }

    /**
     * Per-{@code (account, symbol)} positions, the intermediate step {@link #reconstruct} then
     * merges by symbol. Exposed because two callers legitimately need the un-merged form: the
     * transfer screen, which moves one account's holding of a symbol and needs that account's own
     * quantity and cost, and anything asking "which account is this actually in".
     */
    public List<AccountPosition> reconstructByAccount(List<CashTransaction> rows) {
        List<AccountPosition> out = new ArrayList<>();
        for (Timeline tl : timelines(rows).values()) {
            BigDecimal qty = tl.totalQuantity();
            if (qty.signum() <= 0) continue;
            out.add(new AccountPosition(tl.symbol, tl.account, qty, tl.totalCostGbp(),
                    tl.tradeCurrency));
        }
        return out;
    }

    public List<Position> reconstruct(List<CashTransaction> rows) {
        Map<String, Aggregated> bySymbol = new LinkedHashMap<>();
        for (AccountPosition p : reconstructByAccount(rows)) {
            String key = p.securityId().toUpperCase();
            bySymbol.computeIfAbsent(key, k -> new Aggregated(p.securityId(), p.tradeCurrency()))
                    .add(p.quantity(), p.costGbp(), p.account());
        }
        List<Position> out = new ArrayList<>();
        for (Aggregated a : bySymbol.values()) {
            out.add(new Position(a.symbol, a.qty, a.costGbp, a.tradeCcy, a.accounts));
        }
        return out;
    }

    private static Map<String, Timeline> timelines(List<CashTransaction> rows) {
        Map<String, Timeline> byPosition = new LinkedHashMap<>();
        for (CashTransaction t : rows) {
            if (t.symbol() == null || t.symbol().isBlank()) continue;
            String key = t.account().dbValue() + "|" + t.symbol().toUpperCase();
            byPosition.computeIfAbsent(key,
                    k -> new Timeline(t.symbol(), t.currency(), t.account())).accept(t);
        }
        return byPosition;
    }

    private static final class Aggregated {
        final String symbol;
        final String tradeCcy;
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal costGbp = BigDecimal.ZERO;
        final Set<String> accounts = new LinkedHashSet<>();

        Aggregated(String symbol, String tradeCcy) {
            this.symbol = symbol;
            this.tradeCcy = tradeCcy;
        }

        void add(BigDecimal qty, BigDecimal costGbp, Account account) {
            this.qty = this.qty.add(qty);
            this.costGbp = this.costGbp.add(costGbp);
            this.accounts.add(account.dbValue());
        }
    }

    private static final class Lot {
        BigDecimal qty;
        BigDecimal costPerShareGbp;

        Lot(BigDecimal qty, BigDecimal costPerShareGbp) {
            this.qty = qty;
            this.costPerShareGbp = costPerShareGbp;
        }
    }

    private static final class Timeline {
        final String symbol;
        final String tradeCurrency;
        final Account account;
        final Deque<Lot> lots = new ArrayDeque<>();

        Timeline(String symbol, String tradeCurrency, Account account) {
            this.symbol = symbol;
            this.tradeCurrency = tradeCurrency;
            this.account = account;
        }

        void accept(CashTransaction t) {
            // TRANSFER rides the same lot engine as TRANSACTION, which is why it was given the
            // same sign convention: a positive amount takes shares (and their cost) out of the
            // losing account's lots, a negative amount opens a lot in the gaining account's at
            // the cost that travelled with them. Anything else would need a second lot engine
            // that could drift from this one.
            if (t.type() != TransactionType.TRANSACTION && t.type() != TransactionType.TRANSFER) {
                return;
            }
            BigDecimal qty = BigDecimal.valueOf(Math.abs(t.quantity()));
            if (t.amount() < 0) {
                if (qty.signum() <= 0) return;
                BigDecimal cpsGbp = BigDecimal.valueOf(Math.abs(t.amountGbp()))
                        .divide(qty, SCALE, RoundingMode.HALF_UP);
                lots.addLast(new Lot(qty, cpsGbp));
            } else if (t.amount() > 0) {
                sell(qty);
            } else {
                split(BigDecimal.valueOf(t.quantity()));
            }
        }

        private void sell(BigDecimal qty) {
            BigDecimal toRemove = qty;
            while (toRemove.signum() > 0 && !lots.isEmpty()) {
                Lot head = lots.peekFirst();
                if (head.qty.compareTo(toRemove) <= 0) {
                    toRemove = toRemove.subtract(head.qty);
                    lots.removeFirst();
                } else {
                    head.qty = head.qty.subtract(toRemove);
                    toRemove = BigDecimal.ZERO;
                }
            }
        }

        private void split(BigDecimal delta) {
            BigDecimal current = totalQuantity();
            if (current.signum() <= 0) {
                if (delta.signum() > 0) lots.addLast(new Lot(delta, BigDecimal.ZERO));
                return;
            }
            BigDecimal newTotal = current.add(delta);
            if (newTotal.signum() <= 0) return;
            BigDecimal ratio = newTotal.divide(current, SCALE, RoundingMode.HALF_UP);
            if (ratio.signum() == 0) return;
            for (Lot lot : lots) {
                lot.qty = lot.qty.multiply(ratio);
                lot.costPerShareGbp = lot.costPerShareGbp.divide(ratio, SCALE, RoundingMode.HALF_UP);
            }
        }

        BigDecimal totalQuantity() {
            BigDecimal s = BigDecimal.ZERO;
            for (Lot l : lots) s = s.add(l.qty);
            return s;
        }

        BigDecimal totalCostGbp() {
            BigDecimal s = BigDecimal.ZERO;
            for (Lot l : lots) s = s.add(l.qty.multiply(l.costPerShareGbp));
            return s;
        }
    }
}
