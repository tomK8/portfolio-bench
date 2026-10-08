package com.portfolio.application;

import com.portfolio.adapter.EdgarFundamentalsFetcher;
import com.portfolio.adapter.EdgarFundamentalsFetcher.EpsFact;
import com.portfolio.adapter.EdgarFundamentalsFetcher.EpsHistory;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FundamentalsServiceTest {

    @TempDir
    Path dbDir;

    /**
     * Subclass overrides both network calls: a fixed CIK for anything (so the registry gate is
     * not what is under test here) and the EPS history verbatim. The adapter's own parsing is
     * exercised in {@code EdgarFundamentalsFetcherTest}.
     */
    private static EdgarFundamentalsFetcher fakeEdgar(List<EpsFact> facts) {
        return fakeEdgar(new EpsHistory(facts, facts.isEmpty() ? Status.UNTAGGED : Status.OK));
    }

    private static EdgarFundamentalsFetcher fakeEdgar(EpsHistory history) {
        return new EdgarFundamentalsFetcher(null) {
            @Override
            public Optional<Integer> cik(String ticker) { return Optional.of(320193); }

            @Override
            public EpsHistory fetch(String ticker) { return history; }
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
    private List<EpsFact> aaplFixture() {
        return List.of(
                fact("2021-10-01", "2021-12-31", "2.10", "2022-01-28"),
                fact("2022-01-01", "2022-03-31", "1.52", "2022-04-29"),
                fact("2022-04-01", "2022-06-30", "1.20", "2022-07-29"),
                fact("2021-10-01", "2022-09-30", "6.11", "2022-10-28"),   // the 10-K's year
                fact("2022-10-01", "2022-12-31", "1.88", "2023-02-03")
        );
    }

    private static EpsFact fact(String start, String end, String eps, String filed) {
        LocalDate f = LocalDate.parse(filed);
        return new EpsFact(LocalDate.parse(start), LocalDate.parse(end), new BigDecimal(eps), f, f);
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
        assertTrue(service(fakeEdgar(EpsHistory.of(Status.UNTAGGED)), repo).report("CDNS", 5)
                .message().contains("no diluted EPS"));
        assertTrue(service(fakeEdgar(EpsHistory.of(Status.NOT_DOMESTIC)), repo).report("ARM", 5)
                .message().contains("foreign form"));
        assertTrue(service(fakeEdgar(EpsHistory.of(Status.UNAVAILABLE)), repo).report("AAPL", 5)
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
        List<EpsFact> gappy = List.of(
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
        List<EpsFact> eps = List.of(
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
}
