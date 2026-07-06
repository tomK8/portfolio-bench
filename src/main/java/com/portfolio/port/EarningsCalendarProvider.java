package com.portfolio.port;

import java.time.LocalDate;
import java.util.List;

/**
 * Supplies a symbol's historical earnings-adjacent dates (§D earnings hygiene). The concrete adapter
 * proxies earnings with SEC EDGAR 10-Q/10-K filing dates, which land within a few days of the actual
 * release — good enough for a ±few-session hygiene flag over a multi-year window, and the only
 * free multi-year source available here.
 *
 * <p>Coverage is US-filers-only: a symbol without a known CIK (e.g. ASML, which files 20-F/6-K, or a
 * UK listing like SEMI.L) is <b>not verifiable</b>. Such symbols are never silently treated as clean —
 * {@link #isVerifiable} lets callers mark their events {@code unverified}.
 */
public interface EarningsCalendarProvider {

    /** Earnings-proxy dates for {@code symbol}, ascending and distinct; empty on failure or no source. */
    List<LocalDate> earningsDates(String symbol);

    /** {@code true} when the symbol can be sourced (a US filer with a known CIK). */
    boolean isVerifiable(String symbol);

    /** Short human label for the source, shown in the coverage summary. */
    String sourceName();
}
