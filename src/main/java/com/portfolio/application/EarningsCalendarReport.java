package com.portfolio.application;

import com.portfolio.domain.model.EarningsEvent;

import java.math.BigDecimal;
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
        int holdingsForwardDays,
        Long minMarketCap,
        List<Day> days,
        List<String> heldSymbols,
        List<EarningsEvent> myHoldings,
        List<HoldingRow> holdings,
        BigDecimal portfolioTotalGbp,
        String source,
        List<String> warnings) {

    /**
     * One held position in the holdings section, ranked by size. {@code state} separates three
     * things a blank row would conflate:
     *
     * <ul>
     *   <li>{@code REPORTING} — a date was found on the calendar</li>
     *   <li>{@code NOT_COVERED} — a non-US listing the US feed cannot see at all, so its silence
     *       says nothing. Without this the #2 and #3 single-name positions here (both UK/EU) would
     *       look identical to a name with genuinely nothing due.</li>
     *   <li>{@code NONE_SCHEDULED} — covered by the feed, nothing due in the window</li>
     * </ul>
     *
     * Funds and bonds never appear at all: they have no earnings, so listing them as "not
     * reporting" would be noise rather than information.
     *
     * <p>{@code gbpValue}/{@code pctOfPortfolio} are null when the position snapshot was
     * unavailable — the section still renders, just unranked.
     */
    public record HoldingRow(
            String symbol,
            String companyName,
            LocalDate date,
            String time,
            Double epsForecast,
            Integer numEstimates,
            Double lastYearEps,
            BigDecimal gbpValue,
            BigDecimal pctOfPortfolio,
            String state) {
    }

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
