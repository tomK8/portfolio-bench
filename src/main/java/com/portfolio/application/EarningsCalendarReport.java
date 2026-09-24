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
     * One held position in the holdings section, ranked by size. {@code state} separates four
     * things a blank row would conflate, and names <b>which source</b> knows the date:
     *
     * <ul>
     *   <li>{@code REPORTING} — the market calendar has it inside the window, with a session and
     *       a consensus EPS estimate. {@code date}/{@code time}/{@code epsForecast} are that
     *       event.</li>
     *   <li>{@code EXPECTED} — the calendar has nothing in the window, but the fundamentals
     *       snapshot carries a date still ahead ({@code expectedDate}). A weaker answer, and
     *       deliberately a separate state rather than a second kind of {@code REPORTING}: it is
     *       usually beyond the window, and it comes with no session and no estimate.</li>
     *   <li>{@code NOT_COVERED} — neither source has a forward date <em>and</em> the listing is
     *       one the US-only calendar never carries, so its silence says nothing. Without this a
     *       large UK/EU position with an unknown date would look identical to one with genuinely
     *       nothing due.</li>
     *   <li>{@code NONE_SCHEDULED} — the calendar covers it, nothing is due in the window, and the
     *       snapshot has no forward date either.</li>
     * </ul>
     *
     * <p>{@code expectedDate} is the fundamentals snapshot's date whenever the calendar had no
     * event for the row — <b>including one that has already passed</b>, which is what Yahoo
     * publishes for a company whose next date it has not learnt yet. A passed date does not make
     * the row {@code EXPECTED} (it is not a forward date), but it still rides along, because "last
     * reported in July" is a different and more useful silence than "we have never had a date".
     * {@code expectedDateEstimate} is Yahoo's own flag for a day it inferred from the past
     * reporting rhythm rather than one the company announced; null means it said nothing either way.
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
            String state,
            LocalDate expectedDate,
            Boolean expectedDateEstimate) {
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
