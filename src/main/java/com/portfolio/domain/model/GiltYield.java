package com.portfolio.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * The market's quoted yield for one whole-year gilt tenor — "the 10-year" — as of the source's
 * last update.
 *
 * <p>It is the redemption yield of whichever gilt the source currently designates as that
 * tenor's <em>benchmark</em>, not a constant-maturity point fitted to the curve. That is why the
 * benchmark rides along: it is the honest answer to "how long is the 2-year?", which on
 * 2026-11-25 was a gilt maturing in May 2029 — two and a half years out, not two.
 *
 * <p>Yields are in percent: {@code 4.8037} is 4.8037%. Everything but the tenor and the yield is
 * nullable — absent is absent, never a zero.
 *
 * @param previousClosePct the tenor's previous close, or null when the source gave none
 * @param asOf             when the source last updated the yield, or null
 * @param benchmark        the benchmark gilt under this app's own id ({@code GILT 4% 2029}), or null
 * @param maturity         the benchmark's maturity date, or null
 * @param sourceUrl        a page at the source charting this tenor, or null
 */
public record GiltYield(int tenorYears, BigDecimal yieldPct, BigDecimal previousClosePct,
                        Instant asOf, String benchmark, LocalDate maturity, String sourceUrl) {

    public GiltYield {
        Objects.requireNonNull(yieldPct, "a tenor with no yield is not a row");
    }

    /**
     * Move since the previous close, in basis points — the unit a gilt's day is read in. Null
     * when the previous close is unknown: a yield with nothing to compare against is not a flat
     * day.
     */
    public BigDecimal changeBp() {
        if (previousClosePct == null) return null;
        return yieldPct.subtract(previousClosePct).movePointRight(2);
    }
}
