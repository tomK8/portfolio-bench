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

        BigDecimal rtTotalGbp = aggregated.stream()
                .map(h -> h.rtMarketValueGbp() != null ? h.rtMarketValueGbp() : h.marketValueGbp())
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

        // Cash as a share of the whole portfolio — the same ratio the Portfolio % column
        // carries on each CASH row, so the tile and those rows cannot disagree. Null rather
        // than zero when there is no total: a share of nothing is unknown, not 0% cash.
        BigDecimal cashPct = totalGbp.compareTo(BigDecimal.ZERO) != 0
                ? totalCashGbp.divide(totalGbp, 10, RoundingMode.HALF_UP)
                : null;

        BigDecimal invested = totalGbp.subtract(totalCashGbp);
        BigDecimal returnPct = invested.compareTo(BigDecimal.ZERO) != 0
                ? totalGainGbp.divide(invested, 10, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal totalReturn = totalGbp.compareTo(BigDecimal.ZERO) != 0
                ? totalGainGbp.divide(totalGbp, 10, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new Totals(totalGbp, rtTotalGbp, totalGainGbp, totalCashGbp, cashPct, returnPct, totalReturn);
    }

    public record Totals(
            BigDecimal totalGbp,
            BigDecimal rtTotalGbp,    // same as totalGbp, but uses RT market value when available
            BigDecimal totalGainGbp,
            BigDecimal totalCashGbp,
            BigDecimal cashPct,       // totalCashGbp / totalGbp; null when there is no total
            BigDecimal returnPct,
            BigDecimal totalReturn) {
    }
}
