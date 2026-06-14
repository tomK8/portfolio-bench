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
    void returnsEmptyOnUnknownTickerResponse() throws Exception {
        // Yahoo returns {"result":null,"error":{...}} for unknown tickers.
        String unknown = "{\"quoteSummary\":{\"result\":null,\"error\":{\"code\":\"Not Found\"}}}";
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("XYZNOPE", unknown);
        assertTrue(q.missing());
        assertNull(q.price());
    }
}
