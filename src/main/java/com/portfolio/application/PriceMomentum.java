package com.portfolio.application;

import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.PriceHistoryRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Trailing ~1-month price change for a single Yahoo ticker, from daily {@code price_history}
 * closes. Used to colour the Price column in the holdings/live views by recent price
 * direction — deliberately distinct from gain/loss, which is measured against cost basis, so
 * a name up over the month can still show a red (below-cost) gain and vice versa.
 *
 * <p>Both the base (~30 calendar days back) and the current close come from the same daily
 * series via {@link PriceHistoryRepository#getPriceOn} (on-or-before, so weekends/holidays
 * walk back to the last trading day), so the two prices are always in the same units — no
 * GBp/GBP or live-vs-stored mismatch to reconcile. Raw {@code close} (not {@code adj_close})
 * is intentional: this is "what the price did", not a total-return figure. Returns a decimal
 * fraction (0.05 = +5%), or {@code null} when either close is missing (e.g. a position with
 * under a month of history, or a symbol with no price feed).
 */
public final class PriceMomentum {

    private PriceMomentum() {
    }

    private static final int SCALE = 6;
    private static final int LOOKBACK_DAYS = 30;

    public static BigDecimal monthChange(PriceHistoryRepository history, String ticker, LocalDate today) {
        if (ticker == null) return null;
        PriceBar current = history.getPriceOn(ticker, today);
        PriceBar base = history.getPriceOn(ticker, today.minusDays(LOOKBACK_DAYS));
        if (current == null || base == null) return null;
        double c = current.close(), b = base.close();
        if (b == 0.0) return null;
        return BigDecimal.valueOf((c - b) / b).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
