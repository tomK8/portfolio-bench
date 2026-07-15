package com.portfolio.domain.model;

import java.time.LocalDate;

/**
 * One company's scheduled/estimated earnings report on a calendar date — the normalised row behind
 * the Earnings tab. Framework-free and source-agnostic (the Nasdaq calendar is the only feed today),
 * mirroring {@link OpenOrder}: absent fields are {@code null} and render as "—".
 *
 * <p>Amounts are parsed from the feed's display strings into real numbers so the UI can sort/filter:
 * {@code marketCap} is whole dollars (native USD — this is a US-market calendar), {@code epsForecast}
 * and {@code lastYearEps} are per-share USD (negatives preserved from parenthesised values).
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
        Double lastYearEps) {
}
