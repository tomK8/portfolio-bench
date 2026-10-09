package com.portfolio.application;

import com.portfolio.adapter.EdgarFundamentalsFetcher;
import com.portfolio.adapter.EdgarFundamentalsFetcher.FiledFact;
import com.portfolio.adapter.EdgarFundamentalsFetcher.FiledHistory;
import com.portfolio.adapter.EdgarXbrlFetcher.Status;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.application.FundamentalsService.FundamentalsReport;
import com.portfolio.application.FundamentalsService.Point;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.FundamentalsRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.PriceHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FundamentalsServiceTest {

    @TempDir
    Path dbDir;

    /**
     * Subclass overrides both network calls: a fixed CIK for anything (so the registry gate is
     * not what is under test here) and one history per us-gaap concept. The adapter's own
     * parsing is exercised in {@code EdgarFundamentalsFetcherTest}.
     *
     * <p>A concept with no fixture answers {@code UNTAGGED} — which is the honest stand-in for
     * the filers that tag no operating-income subtotal, and what keeps a test about the EPS
     * decomposition from needing an operating one.
     */
    private static EdgarFundamentalsFetcher fakeEdgar(List<FiledFact> facts) {
        return fakeEdgar(new FiledHistory(facts, facts.isEmpty() ? Status.UNTAGGED : Status.OK));
    }

    private static EdgarFundamentalsFetcher fakeEdgar(FiledHistory history) {
        return fakeEdgar(Map.of(EdgarFundamentalsFetcher.EPS_DILUTED, history));
    }

    private static EdgarFundamentalsFetcher fakeEdgar(Map<String, FiledHistory> byConcept) {
        return new EdgarFundamentalsFetcher(null) {
            @Override
            public Optional<Integer> cik(String ticker) { return Optional.of(320193); }

            @Override
            public FiledHistory fetch(String ticker, String concept, String unit) {
                return byConcept.getOrDefault(concept, FiledHistory.of(Status.UNTAGGED));
            }
        };
    }

    private FundamentalsService service(EdgarFundamentalsFetcher edgar,
                                        PriceHistoryRepository prices) {
        return new FundamentalsService(edgar, prices,
                new FundamentalsRepository(new JdbcConnectionFactory(dbDir)), new YahooTickerMap());
    }

    /**
     * Apple's real filing pattern, which is also the one this chart used to get wrong: the
     * fiscal Q4 (Jul–Sep) is never tagged as a quarter. The 10-K carries the year, and the
     * quarter has to be recovered from it by subtraction.
     */
    private static List<FiledFact> aaplFixture() {
        return List.of(
                fact("2021-10-01", "2021-12-31", "2.10", "2022-01-28"),
                fact("2022-01-01", "2022-03-31", "1.52", "2022-04-29"),
                fact("2022-04-01", "2022-06-30", "1.20", "2022-07-29"),
                fact("2021-10-01", "2022-09-30", "6.11", "2022-10-28"),   // the 10-K's year
                fact("2022-10-01", "2022-12-31", "1.88", "2023-02-03")
        );
    }

    private static FiledFact fact(String start, String end, String eps, String filed) {
        LocalDate f = LocalDate.parse(filed);
        return new FiledFact(LocalDate.parse(start), LocalDate.parse(end), new BigDecimal(eps), f, f);
    }

    private PriceHistoryRepository priceRepo() {
        return new PriceHistoryRepository(new JdbcConnectionFactory(dbDir));
    }

    private static PriceBar bar(String date, double close) {
        return new PriceBar("AAPL", LocalDate.parse(date), close, close, close, close, close,
                1.0, 1L, "USD");
    }

    @Test
    void aNonUsListingIsRefusedBeforeAnyRequest() {
        // LGEN maps to LGEN.L, so the bare ticker SEC would resolve is a different company.
        FundamentalsReport r = service(fakeEdgar(aaplFixture()), priceRepo()).report("LGEN", 5);
        assertTrue(r.points().isEmpty());
        assertNotNull(r.message());
    }

    @Test
    void eachSilenceIsReportedAsItsOwnFact() {
        PriceHistoryRepository repo = priceRepo();
        assertTrue(service(fakeEdgar(FiledHistory.of(Status.UNTAGGED)), repo).report("CDNS", 5)
                .message().contains("no diluted EPS"));
        assertTrue(service(fakeEdgar(FiledHistory.of(Status.NOT_DOMESTIC)), repo).report("ARM", 5)
                .message().contains("foreign form"));
        assertTrue(service(fakeEdgar(FiledHistory.of(Status.UNAVAILABLE)), repo).report("AAPL", 5)
                .message().contains("EDGAR did not answer"));
    }

    @Test
    void flagsMissingPricesWhenPriceHistoryEmpty() {
        FundamentalsReport r = service(fakeEdgar(aaplFixture()), priceRepo()).report("AAPL", 5);
        assertTrue(r.missingPrices(), "no price_history rows → missingPrices=true");
        assertTrue(r.points().isEmpty());
    }

    @Test
    void recoversTheQuarterTheTenKLeavesImplicitRatherThanSkippingIt() {
        PriceHistoryRepository repo = priceRepo();
        // Filing dates: Q1 2022-01-28, Q2 2022-04-29, Q3 2022-07-29, the 10-K 2022-10-28,
        // next Q1 2023-02-03. Apple never tags Jul–Sep as a quarter, so the TTM can only be
        // completed once the 10-K lands and 6.11 − (2.10 + 1.52 + 1.20) = 1.29 fills it in.
        List<PriceBar> bars = new ArrayList<>();
        bars.add(bar("2022-09-01", 150.0));   // three quarters filed, no year → no point
        bars.add(bar("2022-10-27", 145.0));   // still nothing to tile a year with
        bars.add(bar("2022-10-28", 155.0));   // 10-K filed today → first complete window
        bars.add(bar("2022-12-30", 130.0));
        bars.add(bar("2023-02-03", 150.0));   // next Q1 filed → window slides a quarter
        repo.savePriceBars(bars);

        FundamentalsReport r = service(fakeEdgar(aaplFixture()), repo).report("AAPL", 10);

        assertFalse(r.missingPrices());
        assertEquals(3, r.points().size(), "a point needs four quarters that tile a year");

        // First point: the fiscal year itself — 2.10 + 1.52 + 1.20 + 1.29 = 6.11.
        Point first = r.points().get(0);
        assertEquals("2022-10-28", first.date());
        assertEquals(0, first.ttmEps().compareTo(new BigDecimal("6.11")));
        assertEquals(155.0 / 6.11, first.pe().doubleValue(), 1e-3, "price 155 / ttm 6.11");

        // Third point: the window slides — Q1 2022 drops out, Q1 2023 enters.
        // 1.52 + 1.20 + 1.29 + 1.88 = 5.89.
        Point third = r.points().get(2);
        assertEquals("2023-02-03", third.date());
        assertEquals(0, third.ttmEps().compareTo(new BigDecimal("5.89")));

        assertEquals("2022-12-31", r.epsThrough(), "the window's own end, not today");
        assertTrue(r.finalQuarterDerived(),
                "the Jul-Sep quarter in that window was subtracted out of the 10-K, and says so");
    }

    /**
     * The defect this replaced: with the fourth quarter missing and no year to recover it from,
     * summing "the four most recent records" silently spans five quarters — Q3 of the prior year
     * through Q3 of this one — and reports it as twelve months. Nothing is published instead.
     */
    @Test
    void refusesAWindowThatDoesNotTileAYear() {
        PriceHistoryRepository repo = priceRepo();
        repo.savePriceBars(List.of(bar("2023-05-01", 100.0)));
        List<FiledFact> gappy = List.of(
                fact("2021-10-01", "2021-12-31", "2.10", "2022-01-28"),
                fact("2022-01-01", "2022-03-31", "1.52", "2022-04-29"),
                fact("2022-04-01", "2022-06-30", "1.20", "2022-07-29"),
                // no 10-K, so Jul-Sep 2022 is simply absent
                fact("2022-10-01", "2022-12-31", "1.88", "2023-02-03"),
                fact("2023-01-01", "2023-04-01", "1.99", "2023-05-05"));

        FundamentalsReport r = service(fakeEdgar(gappy), repo).report("AAPL", 10);
        assertTrue(r.points().isEmpty(), "four quarters with a hole in the middle are not a year");
        assertNotNull(r.message());
    }

    @Test
    void rescalesPreSplitEpsToTodayBasisSoPeIsConsistent() {
        // GOOGL-style fixture: a 20:1 split happens AFTER the last EPS quarter but BEFORE
        // today, so the pre-split bars carry splitFactor = 20. The quarterly EPS in EDGAR is
        // on pre-split basis. Without rescaling, P/E = today-basis-price ÷ pre-split-EPS comes
        // out 20× too low. After rescaling, the historical P/E we report matches what the
        // market saw at the time.
        PriceHistoryRepository repo = priceRepo();
        // 4 quarters @ pre-split basis: each $5 of EPS → TTM $20 raw, ÷ 20 = $1 today-basis.
        List<FiledFact> eps = List.of(
                fact("2022-01-01", "2022-03-31", "5.00", "2022-04-29"),
                fact("2022-04-01", "2022-06-30", "5.00", "2022-07-29"),
                fact("2022-07-01", "2022-09-30", "5.00", "2022-10-28"),
                fact("2022-10-01", "2022-12-31", "5.00", "2023-02-03")
        );
        // Pre-split bar at quarter end carries splitFactor=20; today-basis close $40.
        List<PriceBar> bars = new ArrayList<>();
        for (String d : new String[]{"2022-03-31", "2022-06-30", "2022-09-30", "2022-12-31"}) {
            bars.add(new PriceBar("AAPL", LocalDate.parse(d),
                    40.0, 40.0, 40.0, 40.0, 40.0, 20.0, 1L, "USD"));
        }
        bars.add(new PriceBar("AAPL", LocalDate.parse("2023-02-15"),
                40.0, 40.0, 40.0, 40.0, 40.0, 20.0, 1L, "USD"));
        repo.savePriceBars(bars);

        FundamentalsReport r = service(fakeEdgar(eps), repo).report("AAPL", 5);
        assertFalse(r.points().isEmpty());

        // First point: TTM today-basis = 4 × ($5/20) = $1. P/E = $40 / $1 = 40. Without the
        // rescale we'd get $40 / $20 = 2, an order-of-magnitude error.
        Point first = r.points().get(0);
        assertEquals(0, first.ttmEps().compareTo(new BigDecimal("1")),
                "TTM EPS must be rescaled to today's per-share basis");
        assertEquals(40.0, first.pe().doubleValue(), 1e-6,
                "P/E in today's basis matches the real historical multiple");
        assertFalse(r.finalQuarterDerived(), "every quarter here was filed as one");
    }

    @Test
    void summaryYieldsConsistentDecomposition() {
        PriceHistoryRepository repo = priceRepo();
        // Same fixture, wider price spread, so priceMult ≈ epsMult × peMult can be checked.
        List<PriceBar> bars = new ArrayList<>();
        bars.add(bar("2022-10-28", 100.0));   // first point, TTM 6.11 → P/E 16.367
        bars.add(bar("2023-02-03", 200.0));   // last point,  TTM 5.89 → P/E 33.956
        repo.savePriceBars(bars);

        FundamentalsReport r = service(fakeEdgar(aaplFixture()), repo).report("AAPL", 10);
        assertNotNull(r.summary());

        double priceMult = r.summary().priceMult().doubleValue();
        double epsMult = r.summary().epsMult().doubleValue();
        double peMult = r.summary().peMult().doubleValue();
        // Identity: price = EPS × P/E.
        assertEquals(priceMult, epsMult * peMult, 1e-4,
                "Decomposition identity must hold to round-off tolerance");
        assertEquals(2.0, priceMult, 1e-4);                 // 200 / 100
        assertEquals(5.89 / 6.11, epsMult, 1e-4);
    }

    /**
     * The same aggregate fixture shaped like GOOG's distortion: operating income grows steadily
     * while net income is lifted by something below the operating line, so GAAP EPS runs away
     * from the operating business and the multiple absorbs the difference.
     */
    private static Map<String, FiledHistory> googShaped() {
        List<FiledFact> operating = List.of(
                fact("2021-10-01", "2021-12-31", "100", "2022-01-28"),
                fact("2022-01-01", "2022-03-31", "100", "2022-04-29"),
                fact("2022-04-01", "2022-06-30", "100", "2022-07-29"),
                fact("2021-10-01", "2022-09-30", "400", "2022-10-28"),
                fact("2022-10-01", "2022-12-31", "120", "2023-02-03")
        );
        // Net income matches operating income exactly in the base year (ratio 1.00) and then
        // takes a 180 mark in the latest quarter, lifting the trailing ratio to 600/420.
        List<FiledFact> net = List.of(
                fact("2021-10-01", "2021-12-31", "100", "2022-01-28"),
                fact("2022-01-01", "2022-03-31", "100", "2022-04-29"),
                fact("2022-04-01", "2022-06-30", "100", "2022-07-29"),
                fact("2021-10-01", "2022-09-30", "400", "2022-10-28"),
                fact("2022-10-01", "2022-12-31", "300", "2023-02-03")
        );
        return Map.of(
                EdgarFundamentalsFetcher.EPS_DILUTED, new FiledHistory(aaplFixture(), Status.OK),
                EdgarFundamentalsFetcher.OPERATING_INCOME, new FiledHistory(operating, Status.OK),
                EdgarFundamentalsFetcher.NET_INCOME, new FiledHistory(net, Status.OK));
    }

    @Test
    void splitsTheEpsTermIntoOperatingAndWhatArrivedBelowTheLine() {
        PriceHistoryRepository repo = priceRepo();
        repo.savePriceBars(List.of(bar("2022-10-28", 100.0), bar("2023-02-03", 200.0)));

        FundamentalsReport r = service(fakeEdgar(googShaped()), repo).report("AAPL", 10);
        assertNull(r.operatingSplitWithheld());
        assertNotNull(r.operatingSplit());

        double start = r.operatingSplit().netToOperatingStart().doubleValue();
        double end = r.operatingSplit().netToOperatingEnd().doubleValue();
        assertEquals(1.0, start, 1e-6, "base year: net income is exactly the operating income");
        // Trailing year at the end: both TTMs assembled the same way the EPS beside them is, so
        // assert the ratio the two actually form rather than restating the arithmetic.
        assertEquals(600.0 / 420.0, end, 1e-6);

        double epsMult = r.summary().epsMult().doubleValue();
        double opEpsMult = r.operatingSplit().opEpsMult().doubleValue();
        double mixMult = r.operatingSplit().mixMult().doubleValue();
        // The second identity closes on the epsMult the cards already print.
        assertEquals(epsMult, opEpsMult * mixMult, 1e-4);
        assertTrue(opEpsMult < epsMult,
                "taking the mark out leaves less earnings growth than GAAP shows");
        // And the multiple on the operating business is the one that did not de-rate as far.
        double peMult = r.summary().peMult().doubleValue();
        double opPeMult = r.operatingSplit().opMultipleMult().doubleValue();
        assertTrue(opPeMult > peMult, "the apparent de-rating shrinks on operating earnings");
        assertEquals(r.summary().priceMult().doubleValue(), opEpsMult * mixMult * peMult, 1e-4);
    }

    /**
     * MSFT's FY2016 shape: the base window's four quarters are the pre-ASC-606 ones the filer
     * never re-tagged, while its latest annual for the identical window is 22% higher. The
     * decomposition's base endpoint is the one that can be superseded like this, and a two-point
     * comparison cannot disclose it from anything else on screen.
     */
    @Test
    void disclosesABaseWindowTheFilerHasSinceRestated() {
        PriceHistoryRepository repo = priceRepo();
        repo.savePriceBars(List.of(bar("2022-10-28", 100.0), bar("2023-02-03", 200.0)));

        List<FiledFact> withRestatement = new ArrayList<>(aaplFixture());
        // The same year the four quarters tile, restated upward long afterwards.
        withRestatement.add(fact("2021-10-01", "2022-09-30", "7.50", "2024-10-28"));

        FundamentalsReport r = service(fakeEdgar(withRestatement), repo).report("AAPL", 10);
        assertNotNull(r.baseRestated());
        assertEquals("2022-09-30", r.baseRestated().periodEnd());
        assertEquals(0, r.baseRestated().assembled().compareTo(new BigDecimal("6.11")),
                "the cards and the chart keep what the quarters assemble to");
        assertEquals(0, r.baseRestated().filedAnnual().compareTo(new BigDecimal("7.50")));

        // The restated base is larger, so less of the move is earnings and more is the multiple.
        assertTrue(r.baseRestated().epsMult().doubleValue() < r.summary().epsMult().doubleValue());
        assertTrue(r.baseRestated().peMult().doubleValue() > r.summary().peMult().doubleValue());
        // And it is still an identity.
        assertEquals(r.summary().priceMult().doubleValue(),
                r.baseRestated().epsMult().doubleValue() * r.baseRestated().peMult().doubleValue(),
                1e-4);
    }

    @Test
    void saysNothingWhereTheBaseWindowStands() {
        PriceHistoryRepository repo = priceRepo();
        repo.savePriceBars(List.of(bar("2022-10-28", 100.0), bar("2023-02-03", 200.0)));
        // aaplFixture's own 10-K annual (6.11) is exactly what its four quarters assemble to.
        FundamentalsReport r = service(fakeEdgar(aaplFixture()), repo).report("AAPL", 10);
        assertNotNull(r.summary());
        assertNull(r.baseRestated());
    }

    @Test
    void aFilerWithNoOperatingSubtotalIsToldAboutRatherThanLeftBlank() {
        PriceHistoryRepository repo = priceRepo();
        repo.savePriceBars(List.of(bar("2022-10-28", 100.0), bar("2023-02-03", 200.0)));

        // Only EPS on file — a bank, which has no operating-income line to tag.
        FundamentalsReport r = service(fakeEdgar(aaplFixture()), repo).report("AAPL", 10);
        assertNotNull(r.summary(), "the GAAP decomposition is unaffected");
        assertNull(r.operatingSplit());
        assertNotNull(r.operatingSplitWithheld());
        assertTrue(r.operatingSplitWithheld().contains("OperatingIncomeLoss"),
                "the reason names the concept that is missing");
    }

    @Test
    void refusesAMixReadOffADifferentTwelveMonthsThanTheEps() {
        PriceHistoryRepository repo = priceRepo();
        repo.savePriceBars(List.of(bar("2022-10-28", 100.0), bar("2023-02-03", 200.0)));

        // Operating income is a quarter behind: its latest window ends 2022-09-30 while the EPS
        // window ends 2022-12-31. A ratio across the two is the mix of neither.
        List<FiledFact> lagging = List.of(
                fact("2021-01-01", "2021-03-31", "100", "2021-04-29"),
                fact("2021-04-01", "2021-06-30", "100", "2021-07-29"),
                fact("2021-07-01", "2021-09-30", "100", "2021-10-28"),
                fact("2021-10-01", "2021-12-31", "100", "2022-01-28"),
                fact("2022-01-01", "2022-03-31", "100", "2022-04-29"),
                fact("2022-04-01", "2022-06-30", "100", "2022-07-29"),
                fact("2022-07-01", "2022-09-30", "100", "2022-10-28")
        );
        Map<String, FiledHistory> m = Map.of(
                EdgarFundamentalsFetcher.EPS_DILUTED, new FiledHistory(aaplFixture(), Status.OK),
                EdgarFundamentalsFetcher.OPERATING_INCOME, new FiledHistory(lagging, Status.OK),
                EdgarFundamentalsFetcher.NET_INCOME, new FiledHistory(lagging, Status.OK));

        FundamentalsReport r = service(fakeEdgar(m), repo).report("AAPL", 10);
        assertNull(r.operatingSplit());
        assertNotNull(r.operatingSplitWithheld());
        assertTrue(r.operatingSplitWithheld().contains("twelve months"));
    }
}
