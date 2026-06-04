package com.portfolio.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Supplies historical GBP FX rates by date, as units of foreign currency per 1 GBP
 * (e.g. {"2025-10-03": 1.3447}). Kept separate from {@link FxRateProvider} so the
 * existing "latest rates" fakes don't have to grow a method they never use.
 */
public interface HistoricalFxRateProvider {

    /**
     * The rate for {@code date}, or the most recent earlier one; null if none at or before.
     */
    static BigDecimal rateOnOrBefore(Map<LocalDate, BigDecimal> series, LocalDate date) {
        BigDecimal exact = series.get(date);
        if (exact != null) return exact;
        return series.entrySet().stream()
                .filter(e -> !e.getKey().isAfter(date))
                .max(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    /**
     * Daily rates for {@code currency} over the inclusive {@code [start, end]} range.
     * Only the source's business days are present; callers should fall back to the
     * nearest earlier date for weekends/holidays via {@link #rateOnOrBefore}.
     */
    Map<LocalDate, BigDecimal> fetchRateSeries(String currency, LocalDate start, LocalDate end) throws Exception;
}
