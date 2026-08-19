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
        return change(history, ticker, today, LOOKBACK_DAYS);
    }

    /**
     * Trailing price change over {@code lookbackDays} calendar days (base = last close
     * on-or-before {@code today − lookbackDays}), calendar-anchored the same way the watchlist
     * 5d/30d momentum is. Returns a decimal fraction, or {@code null} when either close is
     * missing.
     */
    public static BigDecimal change(PriceHistoryRepository history, String ticker,
                                    LocalDate today, int lookbackDays) {
        if (ticker == null) return null;
        PriceBar current = history.getPriceOn(ticker, today);
        return current == null ? null : changeFromLive(current.close(), current.currency(),
                history.getPriceOn(ticker, today.minusDays(lookbackDays)));
    }

    /**
     * The same fraction against a <em>live</em> price rather than a stored close — for the Live
     * tab, whose base bars are bulk-loaded (see {@link PriceHistoryRepository#loadPricesAsOf})
     * but whose current price is the latest intraday quote.
     *
     * <p>Anchoring the numerator on the newest stored daily close instead would silently drop
     * today's move: the daily job doesn't write today's bar until 22:00, so every trailing
     * window would end at yesterday's close while the Day Δ column beside it was already live.
     * On 2026-08-19 MRNA was +144% intraday and its 5d column read −0.6% — the two columns
     * disagreeing about what "now" means, on the one day it mattered most.
     *
     * <p>Both prices come from the same Yahoo feed and are stored verbatim, so units normally
     * match (a {@code .L} listing is {@code GBp} on both sides and the pence cancel). A
     * currency mismatch is treated as unpriceable — {@code null} rather than a ratio across
     * two different units.
     */
    public static BigDecimal changeFromLive(Double current, String currentCurrency, PriceBar base) {
        if (current == null || base == null) return null;
        if (currentCurrency != null && base.currency() != null
                && !currentCurrency.equals(base.currency())) return null;
        double b = base.close();
        if (b == 0.0) return null;
        return BigDecimal.valueOf((current - b) / b).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
