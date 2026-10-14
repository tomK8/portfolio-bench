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
        // Recomputed from price / bookValue (291.13 / 5.00), deliberately not the 55.10 Yahoo
        // publishes in the same payload — see recomputesPriceToBookRatherThanTrustingYahoo.
        assertEquals(0, q.extra().get("priceToBook").compareTo(new BigDecimal("58.226000")));
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
    void carriesTheListingsOwnName() throws Exception {
        // longName is the clean form across markets; shortName is the exchange's and is only a
        // fallback, because a row dated from this snapshot alone has no other source for a name.
        String json = """
                {"quoteSummary":{"result":[{"price":{
                  "longName":"Barclays PLC","shortName":"BARCLAYS PLC ORD 25P"}}],"error":null}}""";
        assertEquals("Barclays PLC",
                new YahooQuoteSummaryFetcher().parse("BARC.L", json).labels().get("companyName"));

        String noLong = """
                {"quoteSummary":{"result":[{"price":{
                  "shortName":"BARCLAYS PLC ORD 25P"}}],"error":null}}""";
        assertEquals("BARCLAYS PLC ORD 25P",
                new YahooQuoteSummaryFetcher().parse("BARC.L", noLong).labels().get("companyName"));
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

        assertEquals(0, q.extra().get("operatingCashflow").compareTo(new BigDecimal("118000000000")));
        assertEquals(0, q.extra().get("freeCashflow").compareTo(new BigDecimal("108000000000")));
        // Levered FCF margin = 108B / 400B = 0.27
        assertEquals(0, q.extra().get("fcfMarginTtm").compareTo(new BigDecimal("0.270000")));
        // No capex is derived from the two — see omitsCapexDerivedFromLeveredFcf.
        assertNull(q.extra().get("capexTtm"));
        // Net Debt = 109B - 65B = 44B
        assertEquals(0, q.extra().get("netDebt").compareTo(new BigDecimal("44000000000")));
        // Net Debt / EBITDA = 44B / 130B ≈ 0.338462
        assertNotNull(q.extra().get("netDebtToEbitda"));
    }

    /**
     * Capex was derived as {@code operatingCashflow - freeCashflow} and must not come back.
     * Yahoo's {@code freeCashflow} is its own modelled <em>levered</em> free cash flow, not the
     * statement's OCF minus purchases of property, plant and equipment, so the residual is a
     * quantity with no name — and on a live 68-symbol fetch it was negative for 5 of the 52
     * rows carrying both sides, because Yahoo's FCF exceeded its own OCF there. This fixture is
     * that shape: FCF (125B) above OCF (118B), which would have published a capital expenditure
     * of -7B. The margin still computes, on the numerator Yahoo actually supplies.
     * See docs/fundamentals.md.
     */
    @Test
    void omitsCapexDerivedFromLeveredFcf() throws Exception {
        String leveredAboveOcf = sample()
                .replace("\"freeCashflow\": { \"raw\": 108000000000, \"fmt\": \"108.00B\" }",
                         "\"freeCashflow\": { \"raw\": 125000000000, \"fmt\": \"125.00B\" }");
        assertNotEquals(sample(), leveredAboveOcf, "fixture shape changed; patch no longer applies");

        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("AAPL", leveredAboveOcf);

        assertEquals(0, q.extra().get("freeCashflow").compareTo(new BigDecimal("125000000000")));
        assertEquals(0, q.extra().get("operatingCashflow").compareTo(new BigDecimal("118000000000")));
        assertNull(q.extra().get("capexTtm"), "a negative capex is not a measurement");
        // 125B / 400B = 0.3125 — unaffected by the absent capex.
        assertEquals(0, q.extra().get("fcfMarginTtm").compareTo(new BigDecimal("0.312500")));
    }

    /**
     * The three fields that were dropped for lack of a stateable period, asserted absent
     * against a fixture that offers all three. The fixture still carries full quarterly
     * statements and an {@code operatingMargins}, because Yahoo's live payload still carries
     * the shapes too — hollow statements and an MRQ margin. Surfacing any of them again is
     * the regression this guards: an MRQ operating margin among TTM figures compares against
     * nothing, and a ROIC derived from statements whose line items arrive as a literal 0
     * publishes a manufactured zero as a measured return. See docs/fundamentals.md.
     */
    @Test
    void omitsFieldsWithNoStateablePeriod() throws Exception {
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("AAPL", sample());

        // Present in the fixture's financialData, deliberately not surfaced: Yahoo's
        // operatingMargins is the latest quarter while profitMargins/grossMargins are TTM.
        assertNull(q.extra().get("operatingMargins"));
        // Derived from the *HistoryQuarterly modules, which are no longer requested.
        assertNull(q.extra().get("roic"));
        assertNull(q.extra().get("fcfGrowthYoy"));

        // The TTM figures from the same module are unaffected.
        assertNotNull(q.extra().get("profitMargins"));
        assertNotNull(q.extra().get("grossMargins"));
        assertNotNull(q.extra().get("returnOnEquity"));
        assertNotNull(q.extra().get("fcfMarginTtm"));
    }

    /**
     * Yahoo's own {@code priceToBook} is price / bookValue with no unit handling, so it is
     * ignored in favour of a ratio we scale ourselves. The fixture publishes 55.10 against a
     * bookValue of 5.00 on a price of 291.13, so trusting Yahoo and recomputing give visibly
     * different answers and this test can only pass one way.
     */
    @Test
    void recomputesPriceToBookRatherThanTrustingYahoo() throws Exception {
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("AAPL", sample());
        assertEquals(0, q.extra().get("priceToBook").compareTo(new BigDecimal("58.226000")),
                "291.13 / 5.00, not Yahoo's published 55.10");

        // The denominator is kept too, so the ratio can be checked on the row: 291.13 / 5.00.
        assertEquals(0, q.extra().get("bookValue").compareTo(new BigDecimal("5.00")));
    }

    /**
     * A London line quotes in pence and files in pounds, so its price has to be scaled before
     * it meets a book value. Yahoo does not do this, which is what put REL at 3676 instead of
     * 36.8 and British Land at 66.6 instead of 0.67 — on the metric that matters most for the
     * banks and REITs it broke.
     */
    @Test
    void scalesPenceQuotesBeforeDividingByAPoundBookValue() throws Exception {
        String json = """
                {"quoteSummary":{"result":[{
                  "price":{"currency":"GBp","regularMarketPrice":{"raw":1877.5}},
                  "defaultKeyStatistics":{"bookValue":{"raw":4.135}},
                  "financialData":{"financialCurrency":"GBP"}}]}}""";
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("BA.L", json);

        // 1877.5p = GBP 18.775, over a book value of GBP 4.135 -> 4.54, not 454.
        BigDecimal pb = q.extra().get("priceToBook");
        assertNotNull(pb);
        assertTrue(pb.compareTo(new BigDecimal("4.5")) > 0 && pb.compareTo(new BigDecimal("4.6")) < 0,
                "expected ~4.54, got " + pb);
        // GBp against GBP is a scale difference, not a currency one: nothing is withheld.
        assertNull(q.labels().get("excluded:priceToBook"));
    }

    /**
     * When the statements are filed in a different currency from the quote, a ratio spanning the
     * two divides pounds by dollars. Withheld with a reason rather than published or silently
     * dropped, so a reader can tell "could not be trusted" from "the feed never sent it".
     *
     * <p>The two <i>amounts</i> built the same way go with them. Enterprise value is a sum
     * rather than a quotient — Yahoo adds net debt in the filing currency to a market cap in the
     * quote currency without converting, exactly reproducible on NVO (263.68bn = 168.54bn USD
     * market cap + 95.14bn DKK net debt, to 0.002%) — and book value per share is the
     * denominator of a P/B that is no longer there to be audited.
     */
    @Test
    void withholdsRatiosThatWouldDivideTwoCurrencies() throws Exception {
        String json = """
                {"quoteSummary":{"result":[{
                  "price":{"currency":"GBp","regularMarketPrice":{"raw":12074.0}},
                  "summaryDetail":{"priceToSalesTrailing12Months":{"raw":3.05}},
                  "defaultKeyStatistics":{"bookValue":{"raw":24.28},
                                          "enterpriseValue":{"raw":211963953152},
                                          "enterpriseToEbitda":{"raw":10.55}},
                  "financialData":{"financialCurrency":"USD","ebitda":{"raw":19904000000}}}]}}""";
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("AZN.L", json);

        for (String key : new String[]{"priceToBook", "priceToSalesTrailing12Months",
                                       "enterpriseToEbitda", "enterpriseValue", "bookValue"}) {
            assertNull(q.extra().get(key), key + " spans two currencies and must not be published");
            String why = q.labels().get("excluded:" + key);
            assertNotNull(why, key + " must say why it was withheld");
            assertTrue(why.contains("USD") && why.contains("GBp"), why);
        }
        assertEquals("USD", q.labels().get("financialCurrency"));
    }

    /**
     * The gate is the currency mismatch and nothing else: a row whose statements are in its own
     * quote currency keeps both amounts. Without this the previous test passes just as well for
     * a fetcher that had simply stopped publishing enterprise value and book value.
     */
    @Test
    void keepsEnterpriseValueAndBookValueWhenTheCurrenciesAgree() throws Exception {
        String json = """
                {"quoteSummary":{"result":[{
                  "price":{"currency":"USD","regularMarketPrice":{"raw":183.0950}},
                  "defaultKeyStatistics":{"bookValue":{"raw":4.858},
                                          "enterpriseValue":{"raw":241653907456}},
                  "financialData":{"financialCurrency":"USD"}}]}}""";
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("KLAC", json);

        assertEquals(0, q.extra().get("enterpriseValue")
                .compareTo(new BigDecimal("241653907456")));
        assertEquals(0, q.extra().get("bookValue").compareTo(new BigDecimal("4.858")));
        assertNull(q.labels().get("excluded:enterpriseValue"));
        assertNull(q.labels().get("excluded:bookValue"));
    }

    /**
     * Net Debt / EBITDA takes both sides from financialData, so it shares a currency whatever
     * the line is quoted in and must survive the gate that stops the others.
     */
    @Test
    void keepsRatiosWhoseBothSidesComeFromTheStatements() throws Exception {
        String json = """
                {"quoteSummary":{"result":[{
                  "price":{"currency":"GBp","regularMarketPrice":{"raw":12074.0}},
                  "financialData":{"financialCurrency":"USD","ebitda":{"raw":20000000000},
                                   "totalDebt":{"raw":32000000000},
                                   "totalCash":{"raw":4000000000}}}]}}""";
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("AZN.L", json);
        // (32B - 4B) / 20B, both USD.
        assertEquals(0, q.extra().get("netDebtToEbitda").compareTo(new BigDecimal("1.400000")));
        assertNull(q.labels().get("excluded:netDebtToEbitda"));
    }

    /**
     * A negative EV/EBITDA is not merely meaningless, it sorts to the top of a cheapest-first
     * scan — which is how SKHY's -0.45 and MRNA's -35.21 read as the two best values on the
     * screen. Withheld on a non-positive EBITDA regardless of currency.
     */
    @Test
    void withholdsEvEbitdaWhenEbitdaIsNotPositive() throws Exception {
        String json = """
                {"quoteSummary":{"result":[{
                  "price":{"currency":"USD","regularMarketPrice":{"raw":25.0}},
                  "defaultKeyStatistics":{"enterpriseToEbitda":{"raw":-35.21}},
                  "financialData":{"financialCurrency":"USD","ebitda":{"raw":-2195000064}}}]}}""";
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("MRNA", json);
        assertNull(q.extra().get("enterpriseToEbitda"));
        assertTrue(q.labels().get("excluded:enterpriseToEbitda").contains("not positive"));
    }

    /**
     * The three provenance fields ride a call we already make. Without them the panel can only
     * claim a period in a column heading, for every symbol at once.
     */
    @Test
    void capturesStatementCurrencyAndPeriodDates() throws Exception {
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("AAPL", sample());
        assertEquals("USD", q.labels().get("financialCurrency"));
        assertEquals("2026-07-31", q.labels().get("mostRecentQuarter"));
        assertEquals("2025-11-02", q.labels().get("lastFiscalYearEnd"));
    }

    /**
     * A fund files no statements, so {@code financialCurrency} is absent. That must not be read
     * as a mismatch — it would report an exclusion on every ETF for a ratio the feed never sent
     * them anyway.
     */
    @Test
    void absentStatementCurrencyIsNotTreatedAsAMismatch() throws Exception {
        String json = """
                {"quoteSummary":{"result":[{
                  "price":{"currency":"GBp","regularMarketPrice":{"raw":500.0}},
                  "summaryDetail":{"priceToSalesTrailing12Months":{"raw":2.5}},
                  "defaultKeyStatistics":{"bookValue":{"raw":2.0}}}]}}""";
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("EQQQ.L", json);
        assertEquals(0, q.extra().get("priceToSalesTrailing12Months")
                .compareTo(new BigDecimal("2.5")));
        assertNull(q.labels().get("excluded:priceToSalesTrailing12Months"));
    }

    /**
     * Net income is what makes the trailing P/E auditable, so it is stored rather than left to
     * be inferred. It is also the figure the forward P/E is *not* built on.
     */
    @Test
    void capturesGaapNetIncome() throws Exception {
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("AAPL", sample());
        assertEquals(0, q.extra().get("netIncomeToCommon")
                .compareTo(new BigDecimal("96500000000")));
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

    /**
     * KLAC's real shape, which is the whole reason this exists: forwardEps reproduces the
     * <b>+1y</b> consensus to the digit, and that year ends June 2028 while the company's last
     * reported one ended June 2026. "Next year" would have been the wrong label by a year.
     */
    @Test
    void namesTheFiscalYearTheForwardMultipleIsPricedOn() throws Exception {
        String json = """
                {"quoteSummary":{"result":[{
                  "defaultKeyStatistics":{"forwardEps":{"raw":6.74039}},
                  "earningsTrend":{"trend":[
                   {"period":"0q","endDate":"2026-09-30"},
                   {"period":"0y","endDate":"2027-06-30",
                    "earningsEstimate":{"avg":{"raw":5.43753}}},
                   {"period":"+1y","endDate":"2028-06-30",
                    "earningsEstimate":{"avg":{"raw":6.74039}}}
                ]}}],"error":null}}""";
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("KLAC", json);
        assertEquals("2028-06-30", q.labels().get("forwardEpsPeriodEnd"));
    }

    /**
     * The two modules refresh on their own clocks, so the match is near rather than exact —
     * META's forward EPS ran 1.35% above its own +1y estimate on a live fetch while the two
     * candidate years were 9.8% apart. The year is still unambiguous and is still named.
     */
    @Test
    void anEstimateThatHasMovedSinceStillNamesItsYear() throws Exception {
        String json = """
                {"quoteSummary":{"result":[{
                  "defaultKeyStatistics":{"forwardEps":{"raw":34.54533}},
                  "earningsTrend":{"trend":[
                   {"period":"0y","endDate":"2026-12-31",
                    "earningsEstimate":{"avg":{"raw":31.04905}}},
                   {"period":"+1y","endDate":"2027-12-31",
                    "earningsEstimate":{"avg":{"raw":34.08393}}}
                ]}}],"error":null}}""";
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("META", json);
        assertEquals("2027-12-31", q.labels().get("forwardEpsPeriodEnd"));
    }

    /** A forward EPS that sits between the two years is attributable to neither, so it names none. */
    @Test
    void aForwardEpsBetweenTwoYearsNamesNoYear() throws Exception {
        String json = """
                {"quoteSummary":{"result":[{
                  "defaultKeyStatistics":{"forwardEps":{"raw":6.00}},
                  "earningsTrend":{"trend":[
                   {"period":"0y","endDate":"2027-06-30",
                    "earningsEstimate":{"avg":{"raw":5.43753}}},
                   {"period":"+1y","endDate":"2028-06-30",
                    "earningsEstimate":{"avg":{"raw":6.74039}}}
                ]}}],"error":null}}""";
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("KLAC", json);
        assertNull(q.labels().get("forwardEpsPeriodEnd"),
                "a figure equidistant from both candidates must not be attributed to either");
    }

    /** The year in progress is a real answer too, when that is the one the feed reproduces. */
    @Test
    void namesTheCurrentYearWhenThatIsTheOneReproduced() throws Exception {
        String json = """
                {"quoteSummary":{"result":[{
                  "defaultKeyStatistics":{"forwardEps":{"raw":5.43753}},
                  "earningsTrend":{"trend":[
                   {"period":"0y","endDate":"2027-06-30",
                    "earningsEstimate":{"avg":{"raw":5.43753}}},
                   {"period":"+1y","endDate":"2028-06-30",
                    "earningsEstimate":{"avg":{"raw":6.74039}}}
                ]}}],"error":null}}""";
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("KLAC", json);
        assertEquals("2027-06-30", q.labels().get("forwardEpsPeriodEnd"));
    }

    @Test
    void missingEarningsTrendYieldsNoForwardKeys() throws Exception {
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("AAPL", sample());
        // The committed fixture predates the module; absent estimates must abstain, not crash.
        assertNull(q.extra().get("forwardRevenueGrowth"));
        assertNull(q.extra().get("forwardEarningsGrowth"));
    }

    @Test
    void storesTheNextEarningsDateFromCalendarEvents() throws Exception {
        // MRVL's real shape: a single announced day, flagged as Yahoo's own estimate. The raw
        // epoch is 20:00 UTC (an after-the-close US report), so only the fmt label names the
        // trading day the report belongs to.
        String json = """
                {"quoteSummary":{"result":[{"calendarEvents":{"earnings":{
                  "earningsDate":[{"raw":1796155200,"fmt":"2026-12-01"}],
                  "isEarningsDateEstimate":true}}}],"error":null}}""";
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("MRVL", json);
        assertEquals("2026-12-01", q.labels().get("earningsDate"));
        assertEquals("true", q.labels().get("earningsDateEstimate"));
        assertNull(q.labels().get("earningsDateEnd"), "one announced day is not a window");
    }

    @Test
    void keepsBothEndsOfAnUnconfirmedEarningsWindow() throws Exception {
        // Two entries mean Yahoo knows only a window. A window is a different fact from a day,
        // so both ends are stored rather than the start standing in for the whole thing.
        String json = """
                {"quoteSummary":{"result":[{"calendarEvents":{"earnings":{
                  "earningsDate":[{"raw":1772064000,"fmt":"2026-02-24"},
                                  {"raw":1772496000,"fmt":"2026-03-01"}],
                  "isEarningsDateEstimate":false}}}],"error":null}}""";
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("XYZ", json);
        assertEquals("2026-02-24", q.labels().get("earningsDate"));
        assertEquals("2026-03-01", q.labels().get("earningsDateEnd"));
        assertEquals("false", q.labels().get("earningsDateEstimate"));
    }

    @Test
    void fallsBackToTheRawEpochWhenYahooSendsNoDayLabel() throws Exception {
        String json = """
                {"quoteSummary":{"result":[{"calendarEvents":{"earnings":{
                  "earningsDate":[{"raw":1791981000}]}}}],"error":null}}""";
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("ASML", json);
        assertEquals("2026-10-14", q.labels().get("earningsDate"));
        assertNull(q.labels().get("earningsDateEstimate"), "no flag from Yahoo ⇒ no claim either way");
    }

    @Test
    void noEarningsDateIsAbsentRatherThanBlank() throws Exception {
        // Every ETF tried answers with an empty earnings block. Absent is not a zero date.
        String json = """
                {"quoteSummary":{"result":[{"calendarEvents":{"earnings":{
                  "earningsDate":[],"earningsAverage":{}}}}],"error":null}}""";
        QuoteSummary q = new YahooQuoteSummaryFetcher().parse("EQQQ.L", json);
        assertFalse(q.labels().containsKey("earningsDate"));

        // The committed fixture predates the module entirely — the same silence, one level up.
        assertFalse(new YahooQuoteSummaryFetcher().parse("AAPL", sample())
                .labels().containsKey("earningsDate"));
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
