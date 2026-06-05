package com.portfolio.domain;

import com.portfolio.domain.model.AggHolding;
import com.portfolio.domain.model.Holding;
import com.portfolio.domain.model.IntradayPrice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PortfolioAggregatorTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency GBP = Currency.getInstance("GBP");
    private static final Map<String, BigDecimal> RATES =
            Map.of("GBP", BigDecimal.ONE, "USD", new BigDecimal("1.25"));
    private static final Instant T0 = Instant.parse("2025-01-02T14:30:00Z");

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
    void rtFieldsPopulatedFromLatestPrice() {
        List<Holding> holdings = List.of(usd("AAPL", "10", "100", "1500"));
        Map<String, IntradayPrice> latest = Map.of("AAPL", new IntradayPrice(T0, 160.00, "USD"));

        AggHolding h = only(new PortfolioAggregator().aggregate(holdings, RATES, Map.of(), latest), "AAPL");

        eq("160", h.latestPrice());
        eq("1600", h.rtMarketValue());     // 160 * 10 USD
        eq("1280", h.rtMarketValueGbp());  // 1600 / 1.25
    }

    @Test
    void gbpHoldingWithGbpQuoteDividesByHundred() {
        Holding gbpEquity = Holding.builder("EQQQ", new BigDecimal("100"), GBP, "AJ Bell SIPP")
                .avgPricePaid(new BigDecimal("400"))
                .currentMarketValue(new BigDecimal("45000"))
                .build();
        // Yahoo's .L quotes report "GBp" (pence) — 451.23p ≡ £4.5123
        Map<String, IntradayPrice> latest = Map.of("EQQQ", new IntradayPrice(T0, 451.23, "GBp"));

        AggHolding h = only(new PortfolioAggregator().aggregate(List.of(gbpEquity), RATES, Map.of(), latest), "EQQQ");

        eq("4.5123", h.latestPrice());
        eq("451.23", h.rtMarketValue());      // 4.5123 * 100 shares
        eq("451.23", h.rtMarketValueGbp());   // GBP holding passes through
    }

    @Test
    void rtFieldsNullWhenNoLatestPrice() {
        AggHolding h = only(new PortfolioAggregator().aggregate(
                List.of(usd("AAPL", "10", "100", "1500")), RATES, Map.of(), Map.of()), "AAPL");

        assertNull(h.latestPrice());
        assertNull(h.rtMarketValue());
        assertNull(h.rtMarketValueGbp());
    }

    @Test
    void giltUsesCleanPriceDividedByHundred() {
        // £100,000 nominal of TG44 at clean price 75.01 → market value £75,010.
        // latestPrice keeps the per-£100 quote form a UK investor expects to see.
        Holding gilt = Holding.builder("GILT 3.25% 2044", new BigDecimal("100000"), GBP, "AJ Bell SIPP")
                .avgPricePaid(new BigDecimal("80"))
                .currentMarketValue(new BigDecimal("80000"))
                .build();
        Map<String, IntradayPrice> latest = Map.of(
                "GILT 3.25% 2044", new IntradayPrice(T0, 75.01, "GBP"));

        AggHolding h = only(new PortfolioAggregator().aggregate(List.of(gilt), RATES, Map.of(), latest),
                "GILT 3.25% 2044");

        eq("75.01", h.latestPrice());
        eq("75010", h.rtMarketValue());       // 100000 * 75.01 / 100
        eq("75010", h.rtMarketValueGbp());    // already GBP
    }

    @Test
    void giltRtFieldsNullWhenNoCachedPrice() {
        Holding gilt = Holding.builder("GILT 0.25% 2026", new BigDecimal("1000"), GBP, "AJ Bell SIPP")
                .avgPricePaid(new BigDecimal("0.95"))
                .currentMarketValue(new BigDecimal("950"))
                .build();

        AggHolding h = only(new PortfolioAggregator().aggregate(List.of(gilt), RATES, Map.of(), Map.of()),
                "GILT 0.25% 2026");

        assertNull(h.latestPrice());
        assertNull(h.rtMarketValue());
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
