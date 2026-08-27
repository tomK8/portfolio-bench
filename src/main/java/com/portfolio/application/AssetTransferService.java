package com.portfolio.application;

import com.portfolio.domain.CashLedgerReconstructor;
import com.portfolio.domain.CashLedgerReconstructor.AccountPosition;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.CashTransactionRepository.LedgerRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-specie transfers: the same shares moving from one account to another, with nothing sold,
 * nothing bought and no cash moving anywhere.
 *
 * <p><b>Why this has to be typed in.</b> No broker export announces it. A cash statement cannot —
 * no cash moves, so no row appears in it — and the holdings files reveal it only after the fact, as
 * a position that has vanished from one export and turned up in another. The cash ledger therefore
 * goes on reporting the position at the losing account indefinitely, which is right about what is
 * held and wrong about where. This screen is the missing input.
 *
 * <p><b>The cost basis moves with the shares, and the tool computes it rather than asking.</b> The
 * amount on each leg is the losing account's own surviving FIFO cost for that position — so the
 * portfolio's total cost, and therefore its gain, is exactly what it was before the transfer.
 * Taking market value instead would rewrite the cost basis and destroy the gain; taking the
 * broker's figure would be almost right and quietly wrong, because AJ Bell pools cost at a Section
 * 104 average while this ledger tracks FIFO lots. On the live book those agree to the penny for
 * every position transferred except Societe Generale, where AJ Bell says £2,085.35 and FIFO says
 * £1,943.34 — a £142 gap that, taken from the statement, would have booked itself as a phantom
 * loss the moment the transfer was recorded.
 *
 * <p><b>The whole position moves.</b> A partial transfer is representable — the rows carry a
 * quantity — but the form does not offer one, because a partial move would need its cost split at
 * some convention and the lots reordered at the destination, and nothing here has ever needed it.
 * The quantity and cost are read off the ledger and shown for confirmation rather than typed.
 *
 * <p><b>Recording a transfer that has not landed yet is the intended use.</b> Enter it the day you
 * initiate it and the ledger says the destination immediately. Until the shares actually arrive,
 * the holdings-vs-ledger reconciliation panel reports the position as present in the ledger and
 * absent from the destination's export — which is precisely the signal that it is still in flight,
 * at the cost of no extra machinery.
 */
public class AssetTransferService {

    private static final Logger log = LoggerFactory.getLogger(AssetTransferService.class);

    /**
     * Placeholder running balance on a transfer leg. {@code cash_balance_gbp} is part of the
     * primary key, and a null there would make two identical transfers distinct to SQLite — the
     * duplicate guard would stop working precisely when it is most wanted. Nothing reads the
     * value: both queries that could are filtered on the type.
     */
    private static final double NO_CASH_BALANCE = 0.0;

    private static final int COST_SCALE = 2;

    private final CashTransactionRepository repo;

    public AssetTransferService(CashTransactionRepository repo) {
        this.repo = repo;
    }

    /** What the screen needs to draw itself: transferable positions, and what has been recorded. */
    public TransferReport report() {
        List<AccountPosition> positions = currentPositions();
        List<Transferable> transferable = new ArrayList<>();
        for (AccountPosition p : positions) {
            transferable.add(new Transferable(
                    p.securityId(), p.account().dbValue(), accountLabel(p.account()),
                    p.quantity().stripTrailingZeros().toPlainString(),
                    p.costGbp().setScale(COST_SCALE, RoundingMode.HALF_UP)));
        }
        transferable.sort(Comparator.comparing(Transferable::accountLabel)
                .thenComparing(Transferable::symbol));

        List<Account> destinations = new ArrayList<>(List.of(Account.values()));
        return new TransferReport(transferable, recorded(),
                destinations.stream()
                        .map(a -> new AccountOption(a.dbValue(), accountLabel(a)))
                        .toList());
    }

    /**
     * Records one transfer as a pair of {@code TRANSFER} rows written in a single database
     * transaction.
     *
     * <p>The source position is re-read here rather than trusted from the form: the page may have
     * been open a while, and the quantity and cost that get written must be the ones the ledger
     * holds at the moment of writing, not the ones it held when the page was rendered.
     *
     * @throws IllegalArgumentException if the accounts are the same, or the source does not hold it
     */
    public Result transfer(LocalDate date, Account from, Account to, String symbol) {
        if (from == to) {
            throw new IllegalArgumentException("Choose two different accounts");
        }
        String wanted = symbol == null ? "" : symbol.trim().toUpperCase();
        if (wanted.isEmpty()) throw new IllegalArgumentException("Choose a position to transfer");

        AccountPosition source = currentPositions().stream()
                .filter(p -> p.account() == from && p.securityId().equalsIgnoreCase(wanted))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        accountLabel(from) + " does not hold " + wanted
                                + " — nothing to transfer. If it was already moved, it is at "
                                + heldAtDescription(wanted) + "."));

        BigDecimal qty = source.quantity();
        BigDecimal costGbp = source.costGbp().setScale(COST_SCALE, RoundingMode.HALF_UP);
        String day = date.toString();

        // Positive amount = shares (and their cost) leaving; negative = arriving. The TRANSACTION
        // convention, so one FIFO lot engine serves both types — see TransactionType.TRANSFER.
        CashTransaction out = leg(day, from, source.securityId(), qty, costGbp,
                "In-specie transfer out to " + accountLabel(to));
        CashTransaction in = leg(day, to, source.securityId(), qty, costGbp.negate(),
                "In-specie transfer in from " + accountLabel(from));

        repo.saveTransfer(out, in);
        log.info("Recorded in-specie transfer: {} {} {} -> {} on {}, cost basis {}",
                qty.toPlainString(), source.securityId(), from.dbValue(), to.dbValue(), day, costGbp);
        return new Result(source.securityId(), qty, costGbp, accountLabel(from), accountLabel(to), day);
    }

    /** Undo a recorded transfer — both legs, or neither. */
    public boolean delete(long outRowid, long inRowid) {
        return repo.deleteTransfer(outRowid, inRowid);
    }

    /**
     * The recorded transfers, newest first, each pairing its two legs back up.
     *
     * <p>Legs are matched on {@code (date, symbol, |amountGbp|)} — everything the pair shares —
     * with the positive-amount leg the source and the negative-amount leg the destination. A leg
     * with no partner is still listed, flagged, rather than hidden: a lone leg would mean a
     * position moved out of an account and into nothing, and that has to be visible to be fixable.
     */
    private List<RecordedTransfer> recorded() {
        Map<String, List<LedgerRow>> pairs = new LinkedHashMap<>();
        for (LedgerRow r : repo.loadTransferRows()) {
            String key = r.tx().transactionDate() + "|" + r.tx().symbol() + "|"
                    + BigDecimal.valueOf(Math.abs(r.tx().amountGbp()))
                            .setScale(COST_SCALE, RoundingMode.HALF_UP).toPlainString();
            pairs.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        List<RecordedTransfer> out = new ArrayList<>();
        for (List<LedgerRow> legs : pairs.values()) {
            LedgerRow outLeg = legs.stream().filter(r -> r.tx().amountGbp() > 0).findFirst().orElse(null);
            LedgerRow inLeg = legs.stream().filter(r -> r.tx().amountGbp() < 0).findFirst().orElse(null);
            LedgerRow any = outLeg != null ? outLeg : inLeg;
            if (any == null) continue;
            out.add(new RecordedTransfer(
                    any.tx().transactionDate(),
                    any.tx().symbol(),
                    BigDecimal.valueOf(Math.abs(any.tx().quantity())).stripTrailingZeros().toPlainString(),
                    BigDecimal.valueOf(Math.abs(any.tx().amountGbp())).setScale(COST_SCALE, RoundingMode.HALF_UP),
                    outLeg == null ? null : accountLabel(outLeg.tx().account()),
                    inLeg == null ? null : accountLabel(inLeg.tx().account()),
                    outLeg == null ? -1 : outLeg.rowid(),
                    inLeg == null ? -1 : inLeg.rowid(),
                    outLeg != null && inLeg != null));
        }
        return out;
    }

    private List<AccountPosition> currentPositions() {
        return new CashLedgerReconstructor().reconstructByAccount(repo.loadDividendTransactions());
    }

    /** Where a symbol currently lives, for the "nothing to transfer" message. */
    private String heldAtDescription(String symbol) {
        List<String> at = currentPositions().stream()
                .filter(p -> p.securityId().equalsIgnoreCase(symbol))
                .map(p -> accountLabel(p.account()))
                .distinct().toList();
        return at.isEmpty() ? "no account" : String.join(", ", at);
    }

    private static CashTransaction leg(String date, Account account, String symbol,
                                       BigDecimal qty, BigDecimal amountGbp, String description) {
        return new CashTransaction(date, account, TransactionType.TRANSFER, symbol,
                qty.doubleValue(), amountGbp.doubleValue(), "GBP", 1.0, amountGbp.doubleValue(),
                NO_CASH_BALANCE, NO_CASH_BALANCE, description);
    }

    /**
     * Legs are denominated in GBP whatever the accounts settle in, because the figure they carry is
     * a cost basis and {@link CashLedgerReconstructor} tracks cost in GBP only — historical FX at
     * trade time. A USD leg would have to invent a rate for a conversion that never happened.
     */
    private static String accountLabel(Account a) {
        return switch (a) {
            case AJBELL -> "AJ Bell SIPP";
            case ROTH_IRA -> "Roth IRA";
            case II -> "II SIPP";
            case EQUATE -> "Equate";
        };
    }

    /** A position that could be transferred, with the quantity and cost that would move with it. */
    public record Transferable(String symbol, String accountDbValue, String accountLabel,
                               String quantity, BigDecimal costGbp) {
    }

    public record AccountOption(String dbValue, String label) {
    }

    /** @param paired false when only one leg is stored — a broken transfer, shown so it can be fixed */
    public record RecordedTransfer(String date, String symbol, String quantity, BigDecimal costGbp,
                                   String fromLabel, String toLabel,
                                   long outRowid, long inRowid, boolean paired) {
    }

    public record TransferReport(List<Transferable> transferable,
                                 List<RecordedTransfer> recorded,
                                 List<AccountOption> accounts) {
    }

    public record Result(String symbol, BigDecimal quantity, BigDecimal costGbp,
                         String fromLabel, String toLabel, String date) {
    }
}
