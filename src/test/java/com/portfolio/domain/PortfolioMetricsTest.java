package com.portfolio.domain;

import com.portfolio.domain.model.AggHolding;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PortfolioMetricsTest {

    private static final Currency USD = Currency.getInstance("USD");

    private static AggHolding agg(String id, String marketValueGbp, String gainGbp) {
        BigDecimal gain = gainGbp == null ? null : new BigDecimal(gainGbp);
        return new AggHolding(
                id,
                BigDecimal.ONE,                 // quantity — unused by metrics
                null,                           // avgPricePaid — unused
                new BigDecimal(marketValueGbp),
                gain,
                null,                           // gainPct — unused
                BigDecimal.ZERO,                // dividendGbp — unused by metrics
                gain,                           // totalGainGbp — unused
                null,                           // totalGainPct — unused
                USD,
                "test",
                null,                           // latestPrice — unused
                null,                           // rtMarketValue — unused
                null);                          // rtMarketValueGbp — unused
    }

    private static void assertBigDecimalEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    @Test
    void computesTotalsGainsCashAndReturns() {
        List<AggHolding> holdings = List.of(
                agg("AAPL", "1000", "200"),   // equity with gain
                agg("CASH", "500", null));    // cash row, no gain
        BigDecimal iiSippCash = new BigDecimal("300");

        PortfolioMetrics.Totals t = new PortfolioMetrics().compute(holdings, iiSippCash);

        assertBigDecimalEquals("1800", t.totalGbp());        // 1000 + 500 + 300
        assertBigDecimalEquals("200", t.totalGainGbp());     // null gain filtered out
        assertBigDecimalEquals("800", t.totalCashGbp());     // CASH 500 + ii cash 300
        assertBigDecimalEquals("0.2", t.returnPct());        // 200 / (1800 - 800)
        assertBigDecimalEquals("0.1111111111", t.totalReturn()); // 200 / 1800, 10dp
    }

    @Test
    void emptyPortfolioYieldsZeroReturnsWithoutDivideByZero() {
        PortfolioMetrics.Totals t = new PortfolioMetrics().compute(List.of(), BigDecimal.ZERO);

        assertBigDecimalEquals("0", t.totalGbp());
        assertBigDecimalEquals("0", t.totalGainGbp());
        assertBigDecimalEquals("0", t.totalCashGbp());
        assertBigDecimalEquals("0", t.returnPct());
        assertBigDecimalEquals("0", t.totalReturn());
    }
}
