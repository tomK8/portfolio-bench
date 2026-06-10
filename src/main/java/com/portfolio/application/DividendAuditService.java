package com.portfolio.application;

import com.portfolio.domain.CashLedgerReconstructor;
import com.portfolio.domain.CashLedgerReconstructor.Position;
import com.portfolio.domain.DividendAttributor;
import com.portfolio.domain.DividendAttributor.Attribution;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;
import com.portfolio.persistence.CashTransactionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-checks the dividend attribution against two other accounting paths so the user can
 * see exactly where each pound of dividend income went:
 * <ul>
 *   <li><b>Raw total</b> = SUM(amountGbp) of every DIVIDEND row in {@code cash_transactions}
 *       — what hit the cash side, no attribution model applied.</li>
 *   <li><b>Attributed</b> = {@link DividendAttributor}'s FIFO output — dividends earned by
 *       shares <i>still held</i>.</li>
 *   <li><b>Sold-share leak</b> = raw − attributed — dividends that accrued to shares the user
 *       has since trimmed or fully sold. Material for symbols where the position has shrunk.</li>
 * </ul>
 *
 * <p>Plus a self-consistency check: the share count {@code DividendAttributor} reconstructs
 * should equal the share count {@link CashLedgerReconstructor} comes up with. They use the
 * same cash ledger, so they should agree to within fractional-share rounding; if they don't,
 * one of the two has a bug. The {@code sharesMismatch} flag surfaces this for review.
 */
public class DividendAuditService {

    private static final BigDecimal SHARE_TOLERANCE = new BigDecimal("0.01");
    private static final int GBP_SCALE = 2;
    private static final int SHARES_SCALE = 4;

    private final CashTransactionRepository cashRepo;

    public DividendAuditService(CashTransactionRepository cashRepo) {
        this.cashRepo = cashRepo;
    }

    public AuditReport report() {
        List<CashTransaction> rows = cashRepo.loadDividendTransactions();

        Map<String, BigDecimal> rawBySymbol = new LinkedHashMap<>();
        for (CashTransaction t : rows) {
            if (t.type() != TransactionType.DIVIDEND) continue;
            if (t.symbol() == null || t.symbol().isBlank()) continue;
            rawBySymbol.merge(t.symbol().toUpperCase(),
                    BigDecimal.valueOf(t.amountGbp()), BigDecimal::add);
        }

        Map<String, Attribution> attributed = new DividendAttributor().attributeBySymbol(rows);
        List<Position> positions = new CashLedgerReconstructor().reconstruct(rows);
        Map<String, BigDecimal> sharesFromLedger = new HashMap<>();
        for (Position p : positions) {
            sharesFromLedger.merge(p.securityId().toUpperCase(),
                    p.quantity(), BigDecimal::add);
        }

        // Union of every symbol that ever had a dividend OR currently exists as a position.
        java.util.LinkedHashSet<String> allSymbols = new java.util.LinkedHashSet<>(rawBySymbol.keySet());
        allSymbols.addAll(attributed.keySet());
        allSymbols.addAll(sharesFromLedger.keySet());

        List<Row> reportRows = new ArrayList<>();
        BigDecimal rawTotal = BigDecimal.ZERO;
        BigDecimal attribTotal = BigDecimal.ZERO;
        BigDecimal leakTotal = BigDecimal.ZERO;
        int mismatchCount = 0;
        for (String sym : allSymbols) {
            BigDecimal raw = rawBySymbol.getOrDefault(sym, BigDecimal.ZERO);
            Attribution a = attributed.get(sym);
            BigDecimal attribGbp = a != null ? a.dividendGbp() : BigDecimal.ZERO;
            BigDecimal attribShares = a != null ? a.shares() : BigDecimal.ZERO;
            BigDecimal ledgerShares = sharesFromLedger.getOrDefault(sym, BigDecimal.ZERO);
            BigDecimal leak = raw.subtract(attribGbp);

            boolean mismatch = attribShares.subtract(ledgerShares).abs()
                    .compareTo(SHARE_TOLERANCE) > 0;
            if (mismatch) mismatchCount++;

            rawTotal = rawTotal.add(raw);
            attribTotal = attribTotal.add(attribGbp);
            leakTotal = leakTotal.add(leak);

            reportRows.add(new Row(sym,
                    money(raw), money(attribGbp), money(leak),
                    shares(attribShares), shares(ledgerShares),
                    mismatch));
        }
        reportRows.sort(Comparator.comparing(Row::leakGbp).reversed());

        return new AuditReport(reportRows.size(), mismatchCount,
                money(rawTotal), money(attribTotal), money(leakTotal), reportRows);
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? null : v.setScale(GBP_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal shares(BigDecimal v) {
        return v == null ? null : v.setScale(SHARES_SCALE, RoundingMode.HALF_UP);
    }

    // ---- DTOs --------------------------------------------------------------

    /**
     * One symbol's audit row. {@code leakGbp} = raw total − attributed total = dividend
     * income earned by shares no longer held. {@code sharesMismatch} = the two FIFO engines
     * disagree on current share count (bug signal — investigate before trusting the row).
     */
    public record Row(String symbol,
                      BigDecimal rawGbp,
                      BigDecimal attributedGbp,
                      BigDecimal leakGbp,
                      BigDecimal attributorShares,
                      BigDecimal ledgerShares,
                      boolean sharesMismatch) {
    }

    public record AuditReport(int symbols,
                              int sharesMismatchCount,
                              BigDecimal totalRawGbp,
                              BigDecimal totalAttributedGbp,
                              BigDecimal totalLeakGbp,
                              List<Row> rows) {
    }
}
