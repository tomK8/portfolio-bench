package com.portfolio.domain;

import com.portfolio.domain.model.AggHolding;
import com.portfolio.domain.model.Holding;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PortfolioAggregatorTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Map<String, BigDecimal> RATES =
            Map.of("GBP", BigDecimal.ONE, "USD", new BigDecimal("1.25"));

    private static Holding usd(String id, String qty, String avgPrice, String marketValue) {
        return Holding.builder(id, new BigDecimal(qty), USD, "II SIPP")
                .avgPricePaid(avgPrice == null ? null : new BigDecimal(avgPrice))
                .currentMarketValue(new BigDecimal(marketValue))
                .build();
    }

    private static AggHolding only(List<AggHolding> rows, String id) {
        return rows.stream().filter(h -> h.securityId().equals(id)).findFirst().orElseThrow();
    }

    private static void eq(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    @Test
    void totalGainCombinesPriceAppreciationAndDividends() {
        // 1500 USD / 1.25 = 1200 GBP value; cost 10*100=1000 USD / 1.25 = 800 GBP; gain 400.
        List<Holding> holdings = List.of(usd("AAPL", "10", "100", "1500"));
        // dividends keyed by upper-cased symbol, in GBP
        Map<String, BigDecimal> dividends = Map.of("AAPL", new BigDecimal("40"));

        AggHolding h = only(new PortfolioAggregator().aggregate(holdings, RATES, dividends), "AAPL");

        eq("1200", h.marketValueGbp());
        eq("400", h.gainGbp());
        eq("0.5", h.gainPct());            // 400 / 800
        eq("40", h.dividendGbp());
        eq("440", h.totalGainGbp());       // 400 + 40
        eq("0.55", h.totalGainPct());      // 440 / 800
    }

    @Test
    void zeroDividendLeavesTotalGainEqualToPriceGain() {
        List<Holding> holdings = List.of(usd("AAPL", "10", "100", "1500"));

        AggHolding h = only(new PortfolioAggregator().aggregate(holdings, RATES, Map.of()), "AAPL");

        eq("0", h.dividendGbp());
        eq("400", h.totalGainGbp());
        eq("0.5", h.totalGainPct());
    }

    @Test
    void dividendShownButTotalGainNullWhenCostUnknown() {
        // No avg price → no cost basis → price gain cannot be computed.
        List<Holding> holdings = List.of(usd("XYZ", "10", null, "1250"));
        Map<String, BigDecimal> dividends = Map.of("XYZ", new BigDecimal("10"));

        AggHolding h = only(new PortfolioAggregator().aggregate(holdings, RATES, dividends), "XYZ");

        assertNull(h.gainGbp());
        eq("10", h.dividendGbp());          // dividend still surfaced
        assertNull(h.totalGainGbp());       // undefined without price appreciation
        assertNull(h.totalGainPct());
    }
}
