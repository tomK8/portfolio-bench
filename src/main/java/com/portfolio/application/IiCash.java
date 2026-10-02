package com.portfolio.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The II SIPP cash balances typed into the Holdings form — native amounts, keyed by currency.
 *
 * <p>II's holdings export omits the cash balance entirely, so these boxes are the holdings view's
 * <em>only</em> source of it (the cash-ledger view reads II cash from the ledger instead, and
 * never looks here). The form carries one box per currency: £ and $ from the start, € since the
 * account started holding euro.
 *
 * <p><b>Why a map and not one parameter per currency.</b> The GBP and USD figures used to travel
 * as two {@code BigDecimal} parameters side by side through the controller, the sync, the export
 * and the reconciliation. Adding the third currency that way would have meant touching all four
 * signatures again, and the fourth would mean it once more. Passing them together makes a new
 * currency one constant in {@code IiCashField} and a label in the form — everything in between
 * takes the whole set and never names a currency.
 *
 * <p><b>A balance with no rate to convert it is dropped, not guessed</b> — it would take a
 * Frankfurter outage that outlives the FX cache, and inventing a rate would quietly misstate the
 * portfolio total. It is logged, because the only other symptom is a total short by that amount.
 */
public record IiCash(Map<String, BigDecimal> nativeByCurrency) {

    private static final Logger log = LoggerFactory.getLogger(IiCash.class);

    /** No figures at all — distinct from zero balances, though both value to £0. */
    public static final IiCash NONE = new IiCash(Map.of());

    public IiCash {
        nativeByCurrency = Collections.unmodifiableMap(new LinkedHashMap<>(nativeByCurrency));
    }

    /** Sterling alone, for callers that hold a single figure. */
    public static IiCash ofGbp(BigDecimal gbp) {
        return gbp == null ? NONE : new IiCash(Map.of("GBP", gbp));
    }

    /**
     * One currency's balance converted to GBP, {@code null} when it cannot be converted and
     * {@code null} when no figure was given for it at all. A stated zero converts to zero —
     * absent and zero stay distinguishable here as everywhere else.
     */
    public BigDecimal gbp(String currency, Map<String, BigDecimal> rates) {
        BigDecimal amount = nativeByCurrency.get(currency);
        if (amount == null) return null;
        if ("GBP".equals(currency)) return amount;
        BigDecimal rate = rates.get(currency);
        if (rate == null || rate.signum() == 0) {
            if (amount.signum() != 0) {
                log.warn("No live {} rate — the II SIPP balance of {} {} is left out of the "
                        + "holdings totals", currency, amount.toPlainString(), currency);
            }
            return null;
        }
        // FX convention: rates are foreign units per £1, so native → GBP divides.
        return amount.divide(rate, 10, RoundingMode.HALF_UP);
    }

    /** Every box summed into the single GBP figure the metrics and the Excel sheet take. */
    public BigDecimal totalGbp(Map<String, BigDecimal> rates) {
        BigDecimal total = BigDecimal.ZERO;
        for (String currency : nativeByCurrency.keySet()) {
            BigDecimal gbp = gbp(currency, rates);
            if (gbp != null) total = total.add(gbp);
        }
        return total;
    }
}
