package com.portfolio.application;

import com.portfolio.domain.model.EarningsEvent;

import java.time.LocalDate;
import java.util.List;

/**
 * JSON DTO for the Earnings tab: the reporting calendar over a {@code [today−back, today+forward]}
 * window, one {@link Day} per date (weekends/empty days included so the calendar reads continuously),
 * plus the held-symbol set so the client can tag/highlight the user's own holdings without a second
 * request. {@code myHoldings} pre-collects the held events across the window (ignoring the market-cap
 * filter — you want your positions regardless of size) for the summary strip.
 */
public record EarningsCalendarReport(
        LocalDate today,
        int backDays,
        int forwardDays,
        Long minMarketCap,
        List<Day> days,
        List<String> heldSymbols,
        List<EarningsEvent> myHoldings,
        String source,
        List<String> warnings) {

    /**
     * One calendar date. {@code label} is the relative marker ("Today", "Yesterday", "Tomorrow",
     * "In 2 days", "2 days ago"); {@code weekday} is the short day name; {@code count} is the number
     * of {@code events} after the market-cap filter.
     */
    public record Day(
            LocalDate date,
            String weekday,
            String label,
            int count,
            List<EarningsEvent> events) {
    }
}
