package com.portfolio.application;

import com.portfolio.adapter.EdgarFundamentalsFetcher;
import com.portfolio.application.FundamentalsService.FundamentalsReport;
import com.portfolio.application.FundamentalsService.Point;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.PriceHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FundamentalsServiceTest {

    @TempDir
    Path dbDir;

    /**
     * Subclass overrides the network call: returns the cached EPS list verbatim. The rest of
     * the EDGAR adapter (CIK lookup, JSON parsing) is exercised separately.
     */
    private static EdgarFundamentalsFetcher fakeEdgar(List<EdgarFundamentalsFetcher.EpsQuarter> eps) {
        return new EdgarFundamentalsFetcher() {
            @Override
            public List<EpsQuarter> fetchQuarterlyEps(String ticker) { return eps; }
        };
    }

    /** Same shape as edgar-aapl-sample.json yields after parsing — kept inline so this test stays
     *  decoupled from the EDGAR parser (covered separately in EdgarFundamentalsFetcherTest). */
    private List<EdgarFundamentalsFetcher.EpsQuarter> aaplFixture() {
        return List.of(
                q("2021-12-31", "2.10", "2022-01-28"),
                q("2022-03-31", "1.52", "2022-04-29"),
                q("2022-06-30", "1.20", "2022-07-29"),
                q("2022-09-30", "1.29", "2022-10-28"),
                q("2022-12-31", "1.88", "2023-02-03")
        );
    }

    private static EdgarFundamentalsFetcher.EpsQuarter q(String periodEnd, String eps, String filed) {
        LocalDate f = LocalDate.parse(filed);
        return new EdgarFundamentalsFetcher.EpsQuarter(LocalDate.parse(periodEnd), 0, "",
                new BigDecimal(eps), f, f);
    }

    private PriceHistoryRepository priceRepo() {
        return new PriceHistoryRepository(new JdbcConnectionFactory(dbDir));
    }

    private static PriceBar bar(String date, double close) {
        return new PriceBar("AAPL", LocalDate.parse(date), close, close, close, close, close,
                1.0, 1L, "USD");
    }

    @Test
    void returnsEmptyWhenTickerNotSupported() {
        FundamentalsService svc = new FundamentalsService(fakeEdgar(aaplFixture()), priceRepo());
        FundamentalsReport r = svc.report("LGEN.L", 5);
        assertTrue(r.points().isEmpty());
        assertNotNull(r.message());
    }

    @Test
    void flagsMissingPricesWhenPriceHistoryEmpty() {
        FundamentalsService svc = new FundamentalsService(fakeEdgar(aaplFixture()), priceRepo());
        FundamentalsReport r = svc.report("AAPL", 5);
        assertTrue(r.missingPrices(), "no price_history rows → missingPrices=true");
        assertTrue(r.points().isEmpty());
    }

    @Test
    void computesTtmAndPeOnceFourQuartersAreFiled() {
        PriceHistoryRepository repo = priceRepo();
        // AAPL fixture's earliest period ends 2022-03-31 (Q2 2022 — but in fact the fixture's
        // FY2022 Q1 ends 2021-12-31). Filing dates:
        //   2022-Q1 (period 2021-12-31) filed 2022-01-28
        //   2022-Q2 (period 2022-03-31) filed 2022-04-29
        //   2022-Q3 (period 2022-06-30) filed 2022-07-29
        //   2022-Q4 (period 2022-09-30) filed 2022-10-28 (synthesised from 10-K)
        //   2023-Q1 (period 2022-12-31) filed 2023-02-03
        // So TTM should become available 2022-10-28 (after Q4 fills the trailing window).
        List<PriceBar> bars = new ArrayList<>();
        bars.add(bar("2022-09-01", 150.0));   // only 3 quarters filed → no TTM, point skipped
        bars.add(bar("2022-10-27", 145.0));   // still only 3 filed → skipped
        bars.add(bar("2022-10-28", 155.0));   // Q4 filed today → first point with TTM
        bars.add(bar("2022-12-30", 130.0));   // still 4 quarters (2022 Q1..Q4)
        bars.add(bar("2023-02-03", 150.0));   // 2023 Q1 filed → window slides to Q2..Q1
        repo.savePriceBars(bars);

        FundamentalsService svc = new FundamentalsService(fakeEdgar(aaplFixture()), repo);
        FundamentalsReport r = svc.report("AAPL", 10);

        assertFalse(r.missingPrices());
        assertEquals(3, r.points().size(), "Expected 3 points after Q4 was filed");

        // First point: TTM = 2022 Q1 + Q2 + Q3 + Q4 = 2.10 + 1.52 + 1.20 + 1.29 = 6.11
        Point first = r.points().get(0);
        assertEquals("2022-10-28", first.date());
        assertEquals(0, first.ttmEps().compareTo(new BigDecimal("6.11")));
        assertEquals(155.0 / 6.11, first.pe().doubleValue(), 1e-3, "price 155 / ttm 6.11");

        // Third point: TTM window slides — Q1 2022 drops out, 2023 Q1 enters.
        // Q2 2022 + Q3 2022 + Q4 2022 + Q1 2023 = 1.52 + 1.20 + 1.29 + 1.88 = 5.89
        Point third = r.points().get(2);
        assertEquals("2023-02-03", third.date());
        assertEquals(0, third.ttmEps().compareTo(new BigDecimal("5.89")));
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
        List<EdgarFundamentalsFetcher.EpsQuarter> eps = List.of(
                qq("2022-03-31", "5.00", "2022-04-29"),
                qq("2022-06-30", "5.00", "2022-07-29"),
                qq("2022-09-30", "5.00", "2022-10-28"),
                qq("2022-12-31", "5.00", "2023-02-03")
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

        FundamentalsService svc = new FundamentalsService(fakeEdgar(eps), repo);
        FundamentalsReport r = svc.report("AAPL", 5);
        assertFalse(r.points().isEmpty());

        // First point: TTM today-basis = 4 × ($5/20) = $1. P/E = $40 / $1 = 40. Without the
        // rescale we'd get $40 / $20 = 2, an order-of-magnitude error.
        Point first = r.points().get(0);
        assertEquals(0, first.ttmEps().compareTo(new BigDecimal("1")),
                "TTM EPS must be rescaled to today's per-share basis");
        assertEquals(40.0, first.pe().doubleValue(), 1e-6,
                "P/E in today's basis matches the real historical multiple");
    }

    private static EdgarFundamentalsFetcher.EpsQuarter qq(String periodEnd, String eps, String filed) {
        LocalDate f = LocalDate.parse(filed);
        return new EdgarFundamentalsFetcher.EpsQuarter(LocalDate.parse(periodEnd), 0, "",
                new BigDecimal(eps), f, f);
    }

    @Test
    void summaryYieldsConsistentDecomposition() {
        PriceHistoryRepository repo = priceRepo();
        // Same as above but with a wider price spread so we can verify priceMult ≈ epsMult × peMult.
        List<PriceBar> bars = new ArrayList<>();
        bars.add(bar("2022-10-28", 100.0));   // first point, TTM 6.11 → P/E 16.367
        bars.add(bar("2023-02-03", 200.0));   // last point,  TTM 5.89 → P/E 33.956
        repo.savePriceBars(bars);

        FundamentalsService svc = new FundamentalsService(fakeEdgar(aaplFixture()), repo);
        FundamentalsReport r = svc.report("AAPL", 10);
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
