package com.portfolio.domain.swing;

import java.time.LocalDate;

/**
 * A per-{@code (symbol, date)} exclusion used by the §D ex-earnings variants. Keyed by symbol as well
 * as date because pooling is by observation: in a pooled-cluster run one member's earnings window must
 * not blank that calendar date for the other members (their earnings are staggered). A date-only
 * filter would over-exclude ~an order of magnitude too much when the cluster's earnings dates are
 * unioned.
 */
@FunctionalInterface
public interface EventFilter {

    /** {@code true} if the event for {@code symbol} on {@code date} should be excluded. */
    boolean excludes(String symbol, LocalDate date);
}
