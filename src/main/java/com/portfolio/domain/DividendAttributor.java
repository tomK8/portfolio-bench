package com.portfolio.domain;

import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Attributes dividends to the shares <em>currently</em> held, so dividends earned by
 * shares since sold (or trimmed) drop out — a flat per-symbol sum overstates the return
 * of a position that used to be larger.
 *
 * <p>Pure domain logic over the {@code TRANSACTION} and {@code DIVIDEND} rows of the cash
 * ledger. Per {@code (account, symbol)} it replays the timeline, accruing a cumulative
 * dividend-per-share and tracking buy lots FIFO:
 * <ul>
 *   <li><b>Buy</b> ({@code amount < 0}, cash out): opens a lot whose dividend baseline is the
 *       current cumulative-per-share, so it earns nothing from past dividends.</li>
 *   <li><b>Dividend</b>: raises cumulative-per-share by {@code amountGbp / sharesHeld}.</li>
 *   <li><b>Sell</b> ({@code amount > 0}, cash in): removes shares oldest-first (FIFO); the
 *       dividends accrued on those shares leave with them.</li>
 *   <li><b>Split</b> ({@code amount == 0}): scales surviving lots up and the per-share figures
 *       down by the split ratio, holding each lot's accrued dividend invariant.</li>
 *   <li><b>Transfer</b>: hands the lots to the gaining account's timeline with the dividend
 *       they have already accrued, and forwards later payments to them there. Shares that
 *       moved in-specie were not sold, so neither their dividends nor the shares themselves
 *       may drop out here — see below.</li>
 * </ul>
 * The dividend attributed to a symbol is the sum, over surviving lots, of
 * {@code (cumulativePerShare − lotBaseline) × lotQuantity}. FIFO is the conservative choice:
 * it sells the oldest (most-accrued) shares first.
 *
 * <p><b>An in-specie transfer is not a sale.</b> Ignoring {@code TRANSFER} left the losing
 * account holding shares it no longer had — so the gaining account's later sale consumed
 * nothing and the position never closed — while every dividend the gaining account received
 * landed on a timeline with no lots and was attributed to nothing. Both legs are now replayed:
 * the lots move across carrying their accrued dividend per share, and a payment that reaches
 * the losing account after the shares left (the record date precedes the move) follows them to
 * their new home rather than being dropped.
 *
 * <p>Rows are replayed in <b>ledger order</b> across all timelines, not timeline by timeline,
 * so a transfer's legs are always seen leaving-first whichever account appeared in the ledger
 * first. Each timeline still sees only its own rows, in their original relative order.
 */
public class DividendAttributor {

    private static final int SCALE = 12;
    /** Enough for any real chain of in-specie moves; only there to bound a transfer cycle. */
    private static final int MAX_FORWARD_HOPS = 8;

    /**
     * Per-symbol dividend attributed to the current position, plus the share count the
     * cash history reconstructs to (for reconciliation against the holdings file).
     */
    public record Attribution(BigDecimal dividendGbp, BigDecimal shares) {
    }

    /**
     * @param rows {@code TRANSACTION} + {@code DIVIDEND} rows in chronological order
     *             (oldest first), across all accounts and symbols
     * @return map of upper-cased symbol → attribution (summed across accounts)
     */
    public Map<String, Attribution> attributeBySymbol(List<CashTransaction> rows) {
        Map<String, Timeline> byPosition = new LinkedHashMap<>();
        Map<String, Deque<Parcel>> inFlight = new LinkedHashMap<>();
        for (CashTransaction t : rows) {
            String symbol = t.symbol().toUpperCase();
            Timeline timeline = byPosition.computeIfAbsent(
                    t.account().name() + " " + symbol, k -> new Timeline());
            if (t.type() == TransactionType.DIVIDEND) timeline = timeline.holderOfRecord();
            timeline.accept(t, inFlight);
        }

        Map<String, Attribution> result = new LinkedHashMap<>();
        for (Map.Entry<String, Timeline> e : byPosition.entrySet()) {
            String symbol = e.getKey().substring(e.getKey().indexOf(' ') + 1);
            result.merge(symbol, e.getValue().attribution(), (x, y) ->
                    new Attribution(x.dividendGbp().add(y.dividendGbp()), x.shares().add(y.shares())));
        }
        return result;
    }

    /**
     * Lot slices in flight between the two legs of one in-specie transfer, carrying the
     * dividend per share they had accrued at the losing account and a way back to it.
     */
    private record Parcel(BigDecimal qty, BigDecimal accruedPerShare, Timeline from) {
    }

    /** Both legs of a transfer share a date, a symbol and an absolute quantity. */
    private static String parcelKey(CashTransaction t) {
        return t.transactionDate()
                + "|" + (t.symbol() == null ? "" : t.symbol().toUpperCase())
                + "|" + BigDecimal.valueOf(Math.abs(t.quantity()))
                        .stripTrailingZeros().toPlainString();
    }

    private static final class Lot {
        BigDecimal qty;
        BigDecimal baselinePerShare;

        Lot(BigDecimal qty, BigDecimal baselinePerShare) {
            this.qty = qty;
            this.baselinePerShare = baselinePerShare;
        }
    }

    /**
     * Mutable per-position replay state.
     */
    private static final class Timeline {
        private final Deque<Lot> lots = new ArrayDeque<>();
        private BigDecimal cumDivPerShare = BigDecimal.ZERO;
        /** Set once this timeline's shares have gone to another account in-specie. */
        private Timeline movedTo;

        void accept(CashTransaction t, Map<String, Deque<Parcel>> inFlight) {
            switch (t.type()) {
                case TRANSACTION -> {
                    BigDecimal qty = BigDecimal.valueOf(Math.abs(t.quantity()));
                    if (t.amount() < 0) buy(qty);
                    else if (t.amount() > 0) sell(qty);
                    else split(BigDecimal.valueOf(t.quantity()));  // amount == 0 → split, signed delta
                }
                case DIVIDEND -> dividend(BigDecimal.valueOf(t.amountGbp()));
                case TRANSFER -> transfer(t, inFlight);
                case INTEREST, CHARGE, CONTRIBUTION -> {
                }  // not relevant to dividend attribution
            }
        }

        /**
         * Where the shares this timeline used to hold now live, so a dividend that arrives
         * after they left in-specie reaches them. The payer pays whoever held the shares on
         * the record date, which can precede the move by weeks; attributing it here would
         * attribute it to nothing, because there are no lots left to raise. A timeline that
         * still holds shares — it bought the name again, or moved only part of it — is the
         * holder of record itself and keeps its own payment.
         */
        Timeline holderOfRecord() {
            Timeline t = this;
            // Bounded: a name transferred out and later back again would otherwise cycle.
            for (int hop = 0; hop < MAX_FORWARD_HOPS && t.movedTo != null && t.isEmpty(); hop++) {
                t = t.movedTo;
            }
            return t;
        }

        /**
         * Shares leaving for, or arriving from, another account. Positive amount = leaving,
         * negative = arriving — the {@code TRANSACTION} sign convention, see docs/transfers.md.
         * The accrued dividend travels with the lots: re-basing each arriving slice against
         * this timeline's own cumulative preserves {@code (cumulative − baseline)}, which is
         * what the shares have earned and get to keep.
         */
        private void transfer(CashTransaction t, Map<String, Deque<Parcel>> inFlight) {
            BigDecimal qty = BigDecimal.valueOf(Math.abs(t.quantity()));
            if (qty.signum() == 0) return;
            String key = parcelKey(t);
            if (t.amount() > 0) {
                inFlight.computeIfAbsent(key, k -> new ArrayDeque<>()).addAll(detach(qty));
                return;
            }
            if (t.amount() == 0) return;
            Deque<Parcel> parcel = inFlight.get(key);
            if (parcel == null || parcel.isEmpty()) {
                // Half a pair, or the losing account's rows are out of view: the shares arrive
                // carrying no accrued dividend, which is the conservative reading.
                buy(qty);
                return;
            }
            while (!parcel.isEmpty()) {
                Parcel p = parcel.removeFirst();
                lots.addLast(new Lot(p.qty(), cumDivPerShare.subtract(p.accruedPerShare())));
                if (p.from() != null) p.from().movedTo = this;
            }
            inFlight.remove(key);
        }

        /**
         * Removes {@code qty} shares oldest-first and returns the slices removed, each with the
         * dividend per share it had accrued. The mirror of {@link #sell}, which lets those
         * dividends leave with the shares because a sale ends their claim; a transfer does not.
         */
        private List<Parcel> detach(BigDecimal qty) {
            List<Parcel> moved = new ArrayList<>();
            BigDecimal remaining = qty;
            while (remaining.signum() > 0 && !lots.isEmpty()) {
                Lot head = lots.peekFirst();
                BigDecimal use = head.qty.min(remaining);
                moved.add(new Parcel(use, cumDivPerShare.subtract(head.baselinePerShare), this));
                head.qty = head.qty.subtract(use);
                remaining = remaining.subtract(use);
                if (head.qty.signum() == 0) lots.removeFirst();
            }
            return moved;
        }

        private boolean isEmpty() {
            return totalShares().signum() <= 0;
        }

        Attribution attribution() {
            BigDecimal dividend = BigDecimal.ZERO;
            BigDecimal shares = BigDecimal.ZERO;
            for (Lot lot : lots) {
                dividend = dividend.add(cumDivPerShare.subtract(lot.baselinePerShare).multiply(lot.qty));
                shares = shares.add(lot.qty);
            }
            return new Attribution(dividend, shares);
        }

        private void buy(BigDecimal qty) {
            if (qty.signum() > 0) lots.addLast(new Lot(qty, cumDivPerShare));
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
            // toRemove > 0 here means the ledger sold more than it bought — a gap; ignore the remainder.
        }

        private void dividend(BigDecimal amountGbp) {
            BigDecimal shares = totalShares();
            if (shares.signum() > 0) {
                cumDivPerShare = cumDivPerShare.add(amountGbp.divide(shares, SCALE, RoundingMode.HALF_UP));
            }
            // A dividend with no reconstructed shares means the cash history is incomplete;
            // it is attributed to nothing and surfaces via the share-count reconciliation warning.
        }

        private void split(BigDecimal delta) {
            BigDecimal current = totalShares();
            if (current.signum() <= 0) {
                // No prior shares to scale (e.g. an opening transfer-in): treat as opening a lot.
                buy(delta.abs());
                return;
            }
            BigDecimal newTotal = current.add(delta);
            if (newTotal.signum() <= 0) return;
            BigDecimal ratio = newTotal.divide(current, SCALE, RoundingMode.HALF_UP);
            if (ratio.signum() == 0) return;
            for (Lot lot : lots) {
                lot.qty = lot.qty.multiply(ratio);
                lot.baselinePerShare = lot.baselinePerShare.divide(ratio, SCALE, RoundingMode.HALF_UP);
            }
            cumDivPerShare = cumDivPerShare.divide(ratio, SCALE, RoundingMode.HALF_UP);
        }

        private BigDecimal totalShares() {
            BigDecimal s = BigDecimal.ZERO;
            for (Lot lot : lots) s = s.add(lot.qty);
            return s;
        }
    }
}
