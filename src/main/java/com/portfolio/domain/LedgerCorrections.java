package com.portfolio.domain;

import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;

import java.util.ArrayList;
import java.util.List;

/**
 * Drops a broker's price-correction pairs from a ledger — the rule that a reversal is not a trade.
 *
 * <p>Brokers occasionally book a fill at the wrong price, reverse it, and re-enter it at the right
 * one. The Roth IRA does this routinely ("Cancel Buy …" immediately followed by "Correct Buy …"),
 * and the reversal arrives as an ordinary opposite-signed row: nothing in its shape says it is not
 * a sale. Three rows reach the ledger where one purchase happened.
 *
 * <p><b>Why this is a domain rule and not one view's tidy-up.</b> It used to live as a private
 * static on the Position tab's service, which was the only place that applied it — so that one
 * screen saw the corrected history and every other reader saw a phantom sale. That is exactly how
 * AMZN came to show a <b>−£505.06</b> loss on the cash-ledger view against a real <b>+£537.64</b>
 * gain: {@link CashLedgerReconstructor} replayed the cancellation as a genuine sale, and FIFO
 * retired the <em>oldest</em> lot (45 shares at £165.09, bought 2025-10-03) rather than the
 * 2026-06-02 lot at £191.16 that was actually being cancelled. The expensive lot stayed in the
 * basis and overstated it by £1,042.70 — enough to flip the sign of the position's gain. Twelve
 * such pairs were in the live ledger across seven symbols, overstating cost on the open rows by
 * £4,224.66 in total.
 *
 * <p>So the rule belongs where every lot engine and every display can reach it. A reversal that is
 * invisible to one reader and a sale to the next is not a presentation choice; the two readers
 * disagree about what happened.
 *
 * <p><b>Matched on the native amount, not the GBP one.</b> A cancellation books the same cash in
 * the account's own currency even when FX has moved between the two dates — AMZN's pair is
 * −$10,302 against +$10,302 exactly, while the GBP legs differ by £3.41. Pairing on GBP would miss
 * every correction that straddled a day's FX move, which is most of them.
 *
 * <p><b>Deliberately narrow.</b> Only an exact opposite native amount <em>and</em> a matching
 * absolute quantity pairs, and only within one account and symbol. A genuine sale at a different
 * price is not a correction and must survive: this is why the test is exact equality rather than a
 * tolerance, and why a partial sale can never be mistaken for one. Each row is claimed at most
 * once, so two corrections of the same size on the same name stay two.
 *
 * <p><b>A pair is removed only when the ledger still makes sense without it.</b> The broker dates
 * the replacement booking when it makes the correction, not when the trade happened, so the
 * re-entered buy can sit <em>after</em> a genuine sale that came between the two. SPCX is the live
 * case: bought and sold the same day, with the buy cancelled and re-entered three days later.
 * Dropping that pair alone leaves the ledger selling fifty shares it has not bought yet — and
 * because {@link CashLedgerReconstructor} drains its lots and stops, an oversell is swallowed in
 * silence and the replacement buy lands on an empty timeline, reporting fifty shares of a position
 * that was closed. It is a holding the broker's own export does not list.
 *
 * <p>So each candidate is tested before it is taken: tentatively remove it, replay that account's
 * running quantity, and keep the removal only if the quantity never goes negative. Where it would,
 * the pair stays, and the position reports what the broker's own quantities sum to — zero for
 * SPCX, which is right. The alternative was to re-date the replacement onto the cancelled row's
 * day, which means inferring which row replaces which and then inventing an order for two bookings
 * on one date; this needs neither, and refuses the edit instead of guessing at it.
 */
public final class LedgerCorrections {

    /** Native amounts must agree to the cent; the pair is one booking, not two valuations. */
    private static final double AMOUNT_EPSILON = 0.01;

    /** Quantities are compared as exact share counts, allowing only for binary representation. */
    private static final double QUANTITY_EPSILON = 1e-6;

    private LedgerCorrections() {
    }

    /**
     * {@code rows} without any correction pair, order preserved.
     *
     * <p>Pairs the first unclaimed outflow with the first later unclaimed inflow that reverses it
     * exactly, so scanning oldest-first matches a reversal to the row it reverses rather than to a
     * later one of the same size. Idempotent: a pruned list contains no pairs, so applying this
     * twice costs a scan and changes nothing — which is what lets both an entry point and its
     * caller apply it without having to know about each other.
     */
    public static List<CashTransaction> prune(List<CashTransaction> rows) {
        if (rows == null || rows.isEmpty()) return rows == null ? List.of() : rows;
        int n = rows.size();
        boolean[] drop = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (drop[i]) continue;
            CashTransaction a = rows.get(i);
            if (a.type() != TransactionType.TRANSACTION || a.amount() >= 0) continue;
            for (int j = i + 1; j < n; j++) {
                if (drop[j]) continue;
                CashTransaction b = rows.get(j);
                if (b.type() != TransactionType.TRANSACTION || b.amount() <= 0) continue;
                if (!a.account().equals(b.account())) continue;
                if (a.symbol() == null || !a.symbol().equals(b.symbol())) continue;
                if (Math.abs(a.amount() + b.amount()) > AMOUNT_EPSILON) continue;
                if (Math.abs(Math.abs(a.quantity()) - Math.abs(b.quantity())) > QUANTITY_EPSILON) continue;
                // Take it only if the timeline survives without it. A replacement booked after a
                // genuine sale that fell between the two would otherwise leave the ledger selling
                // shares it has not bought, which the lot engine swallows rather than reports.
                drop[i] = true;
                drop[j] = true;
                if (!staysSolvent(rows, drop, a)) {
                    drop[i] = false;
                    drop[j] = false;
                    continue;
                }
                break;
            }
        }
        List<CashTransaction> kept = new ArrayList<>(n);
        for (int i = 0; i < n; i++) if (!drop[i]) kept.add(rows.get(i));
        return kept;
    }

    /**
     * Whether one {@code (account, symbol)} timeline still holds a non-negative quantity at every
     * point once the rows currently marked for removal are gone.
     *
     * <p>Uses the lot engine's own sign model — a negative amount takes shares on, a positive one
     * gives them up, a zero amount is a split and moves no shares — so "coherent here" means the
     * same thing it means there. Only the pair's own account and symbol are replayed: a correction
     * cannot affect any other position's running total.
     */
    private static boolean staysSolvent(List<CashTransaction> rows, boolean[] drop,
                                        CashTransaction pairRow) {
        double running = 0.0;
        for (int k = 0; k < rows.size(); k++) {
            if (drop[k]) continue;
            CashTransaction t = rows.get(k);
            if (t.type() != TransactionType.TRANSACTION && t.type() != TransactionType.TRANSFER) continue;
            if (!t.account().equals(pairRow.account())) continue;
            if (t.symbol() == null || !t.symbol().equals(pairRow.symbol())) continue;
            if (t.amount() < 0) running += Math.abs(t.quantity());
            else if (t.amount() > 0) running -= Math.abs(t.quantity());
            if (running < -QUANTITY_EPSILON) return false;
        }
        return true;
    }
}
