package com.portfolio.domain;

import com.portfolio.domain.model.AggHolding;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Pure portfolio-level totals derived from aggregated holdings plus the
 * externally-supplied II SIPP cash balance. No IO, no framework dependencies.
 */
public class PortfolioMetrics {

    public Totals compute(List<AggHolding> aggregated, BigDecimal iiSippCash) {
        BigDecimal totalGbp = aggregated.stream()
                .map(AggHolding::marketValueGbp)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(iiSippCash);

        BigDecimal totalGainGbp = aggregated.stream()
                .filter(h -> h.gainGbp() != null)
                .map(AggHolding::gainGbp)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCashGbp = aggregated.stream()
                .filter(h -> "CASH".equals(h.securityId()))
                .map(AggHolding::marketValueGbp)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(iiSippCash);

        BigDecimal invested = totalGbp.subtract(totalCashGbp);
        BigDecimal returnPct = invested.compareTo(BigDecimal.ZERO) != 0
                ? totalGainGbp.divide(invested, 10, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal totalReturn = totalGbp.compareTo(BigDecimal.ZERO) != 0
                ? totalGainGbp.divide(totalGbp, 10, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new Totals(totalGbp, totalGainGbp, totalCashGbp, returnPct, totalReturn);
    }

    public record Totals(
            BigDecimal totalGbp,
            BigDecimal totalGainGbp,
            BigDecimal totalCashGbp,
            BigDecimal returnPct,
            BigDecimal totalReturn) {
    }
}
