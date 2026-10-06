package com.portfolio.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The operating half of an income statement, assembled from a US filer's own 10-K/10-Q XBRL
 * and therefore carrying the window it covers. Yahoo supplies no TTM operating income at all
 * (its {@code operatingMargins} is a single quarter, which is why that column was deleted —
 * see docs/fundamentals.md), so this is the only operating figure the panel has.
 *
 * <p>Two states, and the second is as important as the first. A line that passed the
 * reconciliation against Yahoo's own TTM revenue carries figures and no {@code withheldReason};
 * one that failed carries the reason and no figures, so the panel can say why a row is blank
 * instead of leaving a bare dash that cannot distinguish "not reported" from "not trusted".
 *
 * @param revenueConcept which us-gaap revenue tag the denominator came from — filers do not
 *                       agree on one, and the tag that reconciles is the tag we use
 * @param derivedQuarterEnd the quarter recovered by subtracting nine months from the year
 *                          (a 10-K rarely tags its own Q4), or null when all four were filed
 */
public record OperatingLine(String symbol,
                            BigDecimal operatingIncomeTtm,
                            BigDecimal revenueTtm,
                            BigDecimal operatingMarginTtm,
                            LocalDate periodEnd,
                            String unit,
                            String revenueConcept,
                            LocalDate derivedQuarterEnd,
                            List<Quarter> quarters,
                            String withheldReason,
                            Instant fetchedAt) {

    /**
     * One filed quarter, with the window the filer tagged it with. Both sides are carried so
     * the quarter has its own margin: a point estimate cannot show a direction, and the
     * direction is most of what these figures are for. {@code revenue} is null where the
     * revenue tag covers a different set of quarters than operating income does — the two are
     * paired by period end, never by position.
     *
     * @param derived this quarter's operating income was recovered from the year rather than
     *                filed as a quarter
     */
    public record Quarter(LocalDate start, LocalDate end, BigDecimal operatingIncome,
                          BigDecimal revenue, boolean derived) {

        /** Operating income ÷ revenue for this quarter alone, or null without both sides. */
        public BigDecimal margin() {
            if (operatingIncome == null || revenue == null || revenue.signum() == 0) return null;
            return operatingIncome.divide(revenue, 6, java.math.RoundingMode.HALF_UP);
        }
    }

    public boolean withheld() {
        return withheldReason != null && !withheldReason.isBlank();
    }

    public static OperatingLine refused(String symbol, String reason, Instant fetchedAt) {
        return new OperatingLine(symbol, null, null, null, null, null, null, null,
                List.of(), reason, fetchedAt);
    }
}
