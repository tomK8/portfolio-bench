package com.portfolio.domain.model;

import java.time.LocalDate;

/**
 * One company's scheduled/estimated earnings report on a calendar date — the normalised row behind
 * the Earnings tab. Framework-free and source-agnostic (the Nasdaq calendar is the only feed today),
 * mirroring {@link OpenOrder}: absent fields are {@code null} and render as "—".
 *
 * <p>Amounts are parsed from the feed's display strings into real numbers so the UI can sort/filter:
 * {@code marketCap} is whole dollars (native USD — this is a US-market calendar), {@code epsForecast},
 * {@code actualEps} and {@code lastYearEps} are per-share USD (negatives preserved from parenthesised
 * values).
 *
 * <p><b>{@code actualEps} is what the company reported, and it exists only once the date has
 * passed.</b> The feed publishes a date in one of two shapes and flips the whole roster at once, not
 * row by row: before the date it carries a consensus, a session and last year's figure; after it, the
 * actual and no session at all. So a row with an {@code actualEps} is a settled report and a row
 * without one is a scheduled report — which is the test the calendar cache uses to decide whether a
 * past date is worth asking about again.
 *
 * <p><b>The surprise is deliberately not a field.</b> It is a function of {@code actualEps} and
 * {@code epsForecast}, so storing it would be storing a second copy of something two other fields
 * already say — and the feed's own surprise figure disagrees with its own two numbers often enough
 * that reading it was rejected (see {@code docs/earnings.md}). Callers divide, and only when there is
 * a consensus to divide by.
 *
 * <p>{@code time} is the normalised session marker: {@code "BMO"} (before market open),
 * {@code "AMC"} (after market close) or {@code "TBD"} (unspecified). "Held" is deliberately <b>not</b>
 * a field here — whether a symbol is in the portfolio is a caller concern, tagged client-side from
 * the report's held-symbol set, so this record stays a pure feed row.
 */
public record EarningsEvent(
        LocalDate date,
        String symbol,
        String companyName,
        String time,
        Long marketCap,
        Double epsForecast,
        Integer numEstimates,
        Double lastYearEps,
        Double actualEps) {

    /** Whether this row is a report that has happened, as opposed to one that is scheduled. */
    public boolean reported() {
        return actualEps != null;
    }
}
