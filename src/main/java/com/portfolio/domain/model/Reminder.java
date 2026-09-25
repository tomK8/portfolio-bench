package com.portfolio.domain.model;

import java.time.LocalDate;

/**
 * One hand-entered calendar reminder: on and after {@code dueDate}, say this again every day until
 * it is switched off or deleted.
 *
 * <p><b>{@code rule} is never evaluated.</b> It is the note you wrote to your future self about why
 * the date matters ("check whether the 5% floor held"), and {@code action} is what you decided to do
 * about it ("trim to 3% or let it run"). Nothing in the app parses either. That is the whole
 * distinction from the watchlist, whose triggers <em>are</em> evaluated against live prices and fire
 * when the market says so: this one fires when the <em>calendar</em> says so, and the judgement stays
 * yours. A reminder that quietly re-interpreted its own free text would be neither.
 *
 * <p>{@code symbol} is optional — a reminder can be about the portfolio rather than a position — and
 * is stored uppercase when present so it lines up with every other symbol in the app.
 */
public record Reminder(long id,
                       String symbol,
                       LocalDate dueDate,
                       String rule,
                       String action,
                       boolean enabled,
                       String createdAt,
                       String updatedAt) {
}
