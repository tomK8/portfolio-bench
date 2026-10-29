package com.portfolio.domain;

import com.portfolio.domain.CashLedgerReconstructor.AccountPosition;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A broker's cancel-and-rebook pair is not a trade.
 *
 * <p>The regression these exist for is the one that put a <b>−£505.06</b> loss on AMZN against a
 * real <b>+£537.64</b> gain: the rule lived in one view's service, so every other reader replayed
 * the reversal as a genuine sale and FIFO retired the wrong lot.
 */
class LedgerCorrectionsTest {

    private static CashTransaction buy(String date, String sym, double qty,
                                       double nativeAmount, double gbp, Account account) {
        return new CashTransaction(date, account, TransactionType.TRANSACTION, sym, qty,
                nativeAmount, account == Account.AJBELL ? "GBP" : "USD", 1.0, gbp, null, null, "buy");
    }

    private static CashTransaction sell(String date, String sym, double qty,
                                        double nativeAmount, double gbp, Account account) {
        return new CashTransaction(date, account, TransactionType.TRANSACTION, sym, qty,
                nativeAmount, account == Account.AJBELL ? "GBP" : "USD", 1.0, gbp, null, null, "sell");
    }

    /**
     * AMZN exactly as the live ledger held it. The 2026-06-02 buy at $257.55 was cancelled three
     * days later at the same native cash and re-entered at $255.00; the GBP legs of the pair differ
     * by £3.41 because the rate moved, which is why pairing is on the native amount.
     */
    private static List<CashTransaction> amzn() {
        return List.of(
                buy("2025-10-03", "AMZN", 45, -9990.0, -7429.166357, Account.ROTH_IRA),
                buy("2026-06-02", "AMZN", 40, -10302.0, -7646.403919, Account.ROTH_IRA),
                sell("2026-06-05", "AMZN", -40, 10302.0, 7649.810648, Account.ROTH_IRA),
                buy("2026-06-05", "AMZN", 40, -10200.0, -7574.069949, Account.ROTH_IRA));
    }

    @Test
    void theReversalAndTheRowItReversesBothDrop() {
        List<CashTransaction> pruned = LedgerCorrections.prune(amzn());
        assertEquals(2, pruned.size());
        assertEquals("2025-10-03", pruned.get(0).transactionDate());
        assertEquals("2026-06-05", pruned.get(1).transactionDate());
        assertEquals(-10200.0, pruned.get(1).amount(), 1e-6, "the corrected price survives");
    }

    @Test
    void fifoKeepsTheOldestLotRatherThanRetiringItForACancellation() {
        // The bug itself, at the layer it bit. Replaying the reversal as a sale made FIFO consume
        // 40 of the 45 shares bought 2025-10-03 at £165.09 and leave the £191.16 lot standing,
        // overstating the surviving cost by £1,042.70 — enough to flip the sign of the gain.
        List<AccountPosition> after =
                new CashLedgerReconstructor().reconstructByAccount(amzn());

        assertEquals(1, after.size());
        AccountPosition p = after.get(0);
        assertEquals(0, p.quantity().compareTo(new BigDecimal("85")), "45 + 40, no sale happened");
        assertEquals(15003.236306, p.costGbp().doubleValue(), 1e-6,
                "7,429.17 + 7,574.07 — the cancelled buy contributes nothing");
    }

    @Test
    void aGenuineSaleIsNotACorrection() {
        // Only an exact opposite native amount pairs. A real sale at a different price must still
        // retire shares, or the rule would quietly stop every disposal from being one.
        List<CashTransaction> rows = List.of(
                buy("2024-01-15", "X", 100, -1000.0, -1000.0, Account.AJBELL),
                sell("2024-06-15", "X", -40, 600.0, 600.0, Account.AJBELL));
        assertEquals(2, LedgerCorrections.prune(rows).size());
    }

    @Test
    void aPartialSaleOfTheSameCashValueIsNotACorrectionEither() {
        // Same native amount both ways but a different share count: selling half a position that
        // has doubled returns exactly what it cost. That is a disposal, not a misbooking.
        List<CashTransaction> rows = List.of(
                buy("2024-01-15", "X", 100, -1000.0, -1000.0, Account.AJBELL),
                sell("2024-06-15", "X", -50, 1000.0, 1000.0, Account.AJBELL));
        assertEquals(2, LedgerCorrections.prune(rows).size());
    }

    @Test
    void aReversalInAnotherAccountIsNotThisOnes() {
        List<CashTransaction> rows = List.of(
                buy("2026-06-02", "AMZN", 40, -10302.0, -7646.40, Account.ROTH_IRA),
                sell("2026-06-05", "AMZN", -40, 10302.0, 7649.81, Account.II));
        assertEquals(2, LedgerCorrections.prune(rows).size());
    }

    @Test
    void eachRowIsClaimedOnce() {
        // Two identical corrections on one name stay two, rather than one reversal cancelling
        // both buys and leaving a phantom sale behind.
        List<CashTransaction> rows = List.of(
                buy("2026-10-08", "TSM", 40, -14182.52, -10500.0, Account.ROTH_IRA),
                buy("2026-10-09", "TSM", 40, -14182.52, -10510.0, Account.ROTH_IRA),
                sell("2026-10-09", "TSM", -40, 14182.52, 10505.0, Account.ROTH_IRA),
                sell("2026-10-16", "TSM", -40, 14182.52, 10520.0, Account.ROTH_IRA));
        assertTrue(LedgerCorrections.prune(rows).isEmpty(), "two pairs, both matched");
    }

    @Test
    void pruningIsIdempotent() {
        // Both an entry point and its caller may apply it without knowing about each other.
        List<CashTransaction> once = LedgerCorrections.prune(amzn());
        assertEquals(once.size(), LedgerCorrections.prune(once).size());
    }

    @Test
    void aLedgerWithNoCorrectionsIsUntouched() {
        List<CashTransaction> rows = List.of(
                buy("2024-01-15", "X", 100, -1000.0, -1000.0, Account.AJBELL),
                buy("2024-02-15", "X", 50, -600.0, -600.0, Account.AJBELL));
        assertEquals(rows, LedgerCorrections.prune(rows));
        assertEquals(List.of(), LedgerCorrections.prune(List.of()));
        assertEquals(List.of(), LedgerCorrections.prune(null));
    }

    /**
     * SPCX exactly as the live ledger held it: bought and sold the same day, with the buy then
     * cancelled and re-entered three days later at the corrected cash. The replacement is dated
     * after the sale it preceded in substance.
     */
    private static List<CashTransaction> spcx() {
        return List.of(
                buy("2026-06-12", "SPCX", 50, -7575.0, -5588.26, Account.ROTH_IRA),
                sell("2026-06-12", "SPCX", -50, 8250.0, 6086.72, Account.ROTH_IRA),
                buy("2026-06-15", "SPCX", 50, -7500.0, -5588.26, Account.ROTH_IRA),
                sell("2026-06-15", "SPCX", -50, 7575.0, 5644.00, Account.ROTH_IRA));
    }

    @Test
    void aPairIsLeftAloneWhenRemovingItWouldSellSharesNotYetBought() {
        // Dropping the -7,575/+7,575 pair leaves a 06-12 sale standing before the 06-15 buy that
        // replaced the cancelled one. The lot engine would swallow that oversell in silence and
        // report the later buy as an open position — fifty shares of a holding that is closed,
        // and which the broker's own export does not list.
        assertEquals(4, LedgerCorrections.prune(spcx()).size(), "the pair stays");
    }

    @Test
    void theClosedPositionStaysClosed() {
        List<AccountPosition> after = new CashLedgerReconstructor().reconstructByAccount(spcx());
        assertTrue(after.isEmpty(),
                "quantities sum to zero, so nothing is held — got " + after);
    }

    @Test
    void theGuardIsPerPositionAndDoesNotBlockOtherCorrections() {
        // One name's incoherent correction must not suppress another's. Both in one ledger: AMZN
        // prunes, SPCX does not.
        List<CashTransaction> both = new java.util.ArrayList<>(amzn());
        both.addAll(spcx());
        List<CashTransaction> pruned = LedgerCorrections.prune(both);
        long amznKept = pruned.stream().filter(t -> "AMZN".equals(t.symbol())).count();
        long spcxKept = pruned.stream().filter(t -> "SPCX".equals(t.symbol())).count();
        assertEquals(2, amznKept, "AMZN's pair is removed");
        assertEquals(4, spcxKept, "SPCX's is not");
    }
}
