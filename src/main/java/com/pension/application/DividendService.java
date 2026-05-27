package com.pension.application;

import com.pension.PortfolioDatabase;
import com.pension.domain.DividendAttributor;
import com.pension.domain.DividendAttributor.Attribution;
import com.pension.domain.model.Holding;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Produces the per-symbol dividends applied to holdings, attributing each dividend to the
 * shares still held (via {@link DividendAttributor}) rather than summing a symbol's whole
 * history. As a sanity check it reconciles the share count the cash ledger reconstructs to
 * against the holdings file and warns on any disagreement — a mismatch means the cash history
 * is incomplete and the attributed dividend may be off.
 */
public class DividendService {

    /**
     * Shares can be fractional (RothIRA); tolerate tiny rounding noise before warning.
     */
    private static final BigDecimal SHARE_TOLERANCE = new BigDecimal("0.01");

    private final PortfolioDatabase db;
    private final DividendAttributor attributor = new DividendAttributor();

    public DividendService(PortfolioDatabase db) {
        this.db = db;
    }

    public Map<String, BigDecimal> dividendsBySymbol(List<Holding> holdings) {
        Map<String, Attribution> attributed = attributor.attributeBySymbol(db.loadDividendTransactions());
        reconcile(attributed, holdings);

        Map<String, BigDecimal> dividends = new HashMap<>();
        attributed.forEach((symbol, a) -> dividends.put(symbol, a.dividendGbp()));
        return dividends;
    }

    private void reconcile(Map<String, Attribution> attributed, List<Holding> holdings) {
        Map<String, BigDecimal> heldBySymbol = new HashMap<>();
        for (Holding h : holdings)
            heldBySymbol.merge(h.getSecurityId().toUpperCase(), h.getQuantity(), BigDecimal::add);

        attributed.forEach((symbol, a) -> {
            BigDecimal held = heldBySymbol.getOrDefault(symbol, BigDecimal.ZERO);
            if (a.shares().subtract(held).abs().compareTo(SHARE_TOLERANCE) > 0) {
                System.err.printf(
                        "[dividends] %s: cash history reconstructs to %s shares but holdings show %s " +
                                "— dividend attribution may be inaccurate (incomplete transaction history?)%n",
                        symbol, a.shares().stripTrailingZeros().toPlainString(),
                        held.stripTrailingZeros().toPlainString());
            }
        });
    }
}
