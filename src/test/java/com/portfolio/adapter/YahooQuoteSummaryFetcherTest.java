package com.portfolio.adapter;

import com.portfolio.adapter.YahooQuoteSummaryFetcher.QuoteSummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class YahooQuoteSummaryFetcherTest {

    private String sample() throws Exception {
        return Files.readString(Path.of(
                getClass().getResource("/yahoo-quotesummary-aapl-sample.json").toURI()));
    }

    @Test
    void extractsAllCoreColumns() throws Exception {
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("AAPL", sample());

        assertEquals("AAPL", q.symbol());
        assertEquals("USD", q.currency());
        assertFalse(q.missing());

        assertEquals(0, q.price().compareTo(new BigDecimal("291.13")));
        assertEquals(0, q.marketCap().compareTo(new BigDecimal("4471234560000")));
        assertEquals(0, q.trailingPe().compareTo(new BigDecimal("36.12")));
        assertEquals(0, q.forwardPe().compareTo(new BigDecimal("32.10")));
        assertEquals(0, q.pegRatio().compareTo(new BigDecimal("2.85")));
        assertEquals(0, q.beta().compareTo(new BigDecimal("1.21")));
        assertEquals(0, q.week52High().compareTo(new BigDecimal("305.50")));
        assertEquals(0, q.week52Low().compareTo(new BigDecimal("198.20")));
        assertEquals(0, q.targetMeanPrice().compareTo(new BigDecimal("305.00")));
    }

    @Test
    void extractsSecondaryFieldsIntoExtraMap() throws Exception {
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("AAPL", sample());

        assertEquals(0, q.extra().get("dividendYield").compareTo(new BigDecimal("0.0042")));
        assertEquals(0, q.extra().get("payoutRatio").compareTo(new BigDecimal("0.15")));
        assertEquals(0, q.extra().get("priceToBook").compareTo(new BigDecimal("55.10")));
        assertEquals(0, q.extra().get("returnOnEquity").compareTo(new BigDecimal("1.52")));
        assertEquals(0, q.extra().get("52WeekChange").compareTo(new BigDecimal("0.245")));
        assertEquals(0, q.extra().get("SandP52WeekChange").compareTo(new BigDecimal("0.183")));
        assertEquals(0, q.extra().get("fiftyDayAverage").compareTo(new BigDecimal("280.40")));
    }

    @Test
    void labelsCarryStringMetadata() throws Exception {
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("AAPL", sample());
        assertEquals("Technology", q.labels().get("sector"));
        assertEquals("Consumer Electronics", q.labels().get("industry"));
        assertEquals("buy", q.labels().get("recommendationKey"));
    }

    @Test
    void missingFieldsBecomeNullsNotErrors() throws Exception {
        // Minimal response: only the price block. Everything else should be null but the
        // record itself must construct cleanly so partial responses are usable.
        String minimal = """
                {"quoteSummary":{"result":[{
                  "price":{"currency":"GBp","regularMarketPrice":{"raw":3500,"fmt":"35.00p"}}
                }],"error":null}}""";
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("EQQQ.L", minimal);
        assertEquals("EQQQ.L", q.symbol());
        assertEquals("GBp", q.currency());
        assertEquals(0, q.price().compareTo(new BigDecimal("3500")));
        assertNull(q.trailingPe(), "trailingPE missing → null, not crash");
        assertNull(q.beta());
        assertNull(q.marketCap());
        assertTrue(q.extra().isEmpty());
        assertFalse(q.missing(), "missing=true is only for full fetch failure, not partial data");
    }

    @Test
    void computesCashFlowMetrics() throws Exception {
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("AAPL", sample());

        // Capex = OCF (118B) - FCF (108B) = 10B
        assertEquals(0, q.extra().get("operatingCashflow").compareTo(new BigDecimal("118000000000")));
        assertEquals(0, q.extra().get("capexTtm").compareTo(new BigDecimal("10000000000")));
        assertEquals(0, q.extra().get("freeCashflow").compareTo(new BigDecimal("108000000000")));
        // FCF Margin = 108B / 400B = 0.27
        assertEquals(0, q.extra().get("fcfMarginTtm").compareTo(new BigDecimal("0.270000")));
        // Net Debt = 109B - 65B = 44B
        assertEquals(0, q.extra().get("netDebt").compareTo(new BigDecimal("44000000000")));
        // Net Debt / EBITDA = 44B / 130B ≈ 0.338462
        assertNotNull(q.extra().get("netDebtToEbitda"));
    }

    @Test
    void computesFcfGrowthAndRoic() throws Exception {
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("AAPL", sample());

        // TTM FCF = 30+28+26+24 = 108B, prior = 22+20+18+16 = 76B, growth = (108-76)/76
        BigDecimal fcfGrowth = q.extra().get("fcfGrowthYoy");
        assertNotNull(fcfGrowth);
        // (108-76)/76 ≈ 0.421053
        assertTrue(fcfGrowth.compareTo(new BigDecimal("0.42")) > 0);
        assertTrue(fcfGrowth.compareTo(new BigDecimal("0.43")) < 0);

        // ROIC: TTM EBIT = 35+33+31+29=128B, TTM Tax = 21B, TTM Pretax = 132B
        // taxRate = 21/132 ≈ 0.159, NOPAT = 128 * 0.841 ≈ 107.6B
        // Invested Capital = 62 + 85 + 10 - 20 = 137B
        // ROIC ≈ 107.6/137 ≈ 0.785
        BigDecimal roic = q.extra().get("roic");
        assertNotNull(roic);
        assertTrue(roic.compareTo(new BigDecimal("0.7")) > 0);
        assertTrue(roic.compareTo(new BigDecimal("0.9")) < 0);
    }

    @Test
    void storesForwardEstimatesFromEarningsTrend() throws Exception {
        // Intel's real earningsTrend shape: positive EPS base, so both estimates are usable.
        String json = """
                {"quoteSummary":{"result":[{"earningsTrend":{"trend":[
                  {"period":"0q","growth":{"raw":0.6907}},
                  {"period":"0y","growth":{"raw":2.6021},
                   "earningsEstimate":{"avg":{"raw":1.5129}},
                   "revenueEstimate":{"growth":{"raw":0.1919}}},
                  {"period":"+1y","growth":{"raw":0.34849998},
                   "earningsEstimate":{"avg":{"raw":2.04013}},
                   "revenueEstimate":{"growth":{"raw":0.1306}}}
                ]}}],"error":null}}""";
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("INTC", json);
        // Next fiscal year only -- 0y largely restates the trailing figure it votes alongside.
        assertEquals(0, q.extra().get("forwardRevenueGrowth").compareTo(new BigDecimal("0.1306")));
        assertEquals(0, q.extra().get("forwardEarningsGrowth").compareTo(new BigDecimal("0.34849998")));
    }

    @Test
    void withholdsForwardEarningsGrowthOffANegativeBase() throws Exception {
        // Moderna's real shape. Yahoo reports +42.5% growth while estimated EPS runs -8.58 ->
        // -4.91: the loss is shrinking, earnings are not growing. Publishing that number would
        // score a loss-maker as a grower off a ratio of two negatives. Revenue growth has a
        // positive base by definition and is still stored.
        String json = """
                {"quoteSummary":{"result":[{"earningsTrend":{"trend":[
                  {"period":"0y","growth":{"raw":-0.1724},
                   "earningsEstimate":{"avg":{"raw":-8.58108}},
                   "revenueEstimate":{"growth":{"raw":0.083500005}}},
                  {"period":"+1y","growth":{"raw":0.42490003},
                   "earningsEstimate":{"avg":{"raw":-4.90677}},
                   "revenueEstimate":{"growth":{"raw":0.1529}}}
                ]}}],"error":null}}""";
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("MRNA", json);
        assertEquals(0, q.extra().get("forwardRevenueGrowth").compareTo(new BigDecimal("0.1529")));
        assertNull(q.extra().get("forwardEarningsGrowth"),
                "growth measured off a negative EPS base must not be published");
    }

    @Test
    void missingEarningsTrendYieldsNoForwardKeys() throws Exception {
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("AAPL", sample());
        // The committed fixture predates the module; absent estimates must abstain, not crash.
        assertNull(q.extra().get("forwardRevenueGrowth"));
        assertNull(q.extra().get("forwardEarningsGrowth"));
    }

    @Test
    void returnsEmptyOnUnknownTickerResponse() throws Exception {
        // Yahoo returns {"result":null,"error":{...}} for unknown tickers.
        String unknown = "{\"quoteSummary\":{\"result\":null,\"error\":{\"code\":\"Not Found\"}}}";
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("XYZNOPE", unknown);
        assertTrue(q.missing());
        assertNull(q.price());
    }
}
