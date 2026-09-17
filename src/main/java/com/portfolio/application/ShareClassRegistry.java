package com.portfolio.application;

import com.portfolio.domain.ShareClasses;

/**
 * Which listings the user has declared to be the same fund.
 *
 * <p>An interface rather than a direct dependency so the exposure views — allocation, concentration,
 * currency — depend on the <em>question</em> ("are these two tickers one holding?") and not on the
 * Open orders tab that happens to answer it. They have no business knowing that share-class switches
 * exist; they have every business knowing that two of their rows are one position.
 *
 * <p>Read per request rather than cached. The declaration is a handful of rows and it changes the
 * moment the user creates a switch, and a stale answer here shows up as a chart that disagrees with
 * the panel the user just used.
 */
@FunctionalInterface
public interface ShareClassRegistry {

    /** Nothing declared — every listing is its own holding. */
    ShareClassRegistry NONE = () -> ShareClasses.NONE;

    ShareClasses declared();
}
