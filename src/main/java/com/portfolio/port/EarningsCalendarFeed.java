package com.portfolio.port;

import com.portfolio.domain.model.EarningsEvent;

import java.time.LocalDate;
import java.util.List;

/**
 * A market-wide earnings <b>calendar</b> keyed by date — "who reports on day X" — as opposed to
 * {@link EarningsCalendarProvider}, which answers "which past dates did symbol X report" for the
 * Swing §D hygiene flag. Different question, different shape, deliberately a separate port.
 *
 * <p>The concrete adapter is a forward-looking source with consensus EPS estimates
 * ({@code adapter/NasdaqEarningsFetcher}); EDGAR can't serve this half because it only records
 * filings after they land.
 */
public interface EarningsCalendarFeed {

    /**
     * Companies reporting on {@code date}.
     *
     * <p>The three-valued return distinguishes a genuinely empty day from a fetch failure, which the
     * service needs to decide whether to cache the result: a non-null (possibly empty) list is a
     * successful read — {@code []} means "no reports scheduled" (e.g. a weekend); {@code null} means
     * the fetch failed, so the caller should fall back to any cached rows rather than caching an
     * empty roster over good data.
     */
    List<EarningsEvent> forDate(LocalDate date);

    /** Short human label for the source, shown in the report footer. */
    String sourceName();
}
