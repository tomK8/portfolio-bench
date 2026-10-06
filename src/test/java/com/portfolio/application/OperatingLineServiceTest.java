package com.portfolio.application;

import com.portfolio.adapter.EdgarXbrlFetcher;
import com.portfolio.adapter.EdgarXbrlFetcher.Series;
import com.portfolio.adapter.EdgarXbrlFetcher.Status;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.FiledPeriods.Fact;
import com.portfolio.domain.model.OperatingLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class OperatingLineServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    /** Four contiguous quarters ending 2026-06-30, summing to {@code total}. */
    private static List<Fact> fourQuarters(String total) {
        BigDecimal each = new BigDecimal(total).divide(new BigDecimal("4"));
        return List.of(
                new Fact(LocalDate.parse("2025-07-01"), LocalDate.parse("2025-09-30"), each),
                new Fact(LocalDate.parse("2025-10-01"), LocalDate.parse("2025-12-31"), each),
                new Fact(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31"), each),
                new Fact(LocalDate.parse("2026-04-01"), LocalDate.parse("2026-06-30"), each));
    }

    /** A fetcher answering from a canned {@code concept -> series} map. */
    private static EdgarXbrlFetcher fetcherOf(Map<String, Series> byConcept) {
        return new EdgarXbrlFetcher(null) {
            @Override
            public Optional<Integer> cik(String symbol) {
                return Optional.of(1);
            }

            @Override
            public Series series(String symbol, String concept) {
                return byConcept.getOrDefault(concept,
                        new Series(List.of(), null, Status.UNTAGGED));
            }
        };
    }

    private static Series ok(List<Fact> facts) {
        return new Series(facts, "USD", Status.OK);
    }

    private static OperatingLineService serviceOf(Map<String, Series> byConcept) {
        return new OperatingLineService(fetcherOf(byConcept), new YahooTickerMap(), null, null);
    }

    @Test
    void publishesTheMarginWhenRevenueReconciles() {
        OperatingLine line = serviceOf(Map.of(
                "OperatingIncomeLoss", ok(fourQuarters("400")),
                "Revenues", ok(fourQuarters("1000")))
        ).build("TEST", new BigDecimal("1000"), NOW);

        assertNotNull(line);
        assertFalse(line.withheld());
        assertEquals(0, line.operatingIncomeTtm().compareTo(new BigDecimal("400")));
        assertEquals(0, line.operatingMarginTtm().compareTo(new BigDecimal("0.400000")));
        assertEquals(LocalDate.parse("2026-06-30"), line.periodEnd());
        assertEquals("Revenues", line.revenueConcept());
        assertEquals("USD", line.unit());
        assertEquals(4, line.quarters().size());
        // Each quarter carries both sides, so it has a margin of its own — the direction is
        // most of what the series is for.
        assertEquals(0, line.quarters().get(0).revenue().compareTo(new BigDecimal("250")));
        assertEquals(0, line.quarters().get(0).margin().compareTo(new BigDecimal("0.400000")));
    }

    /**
     * The two series are paired on period end, never on position: a filer can tag quarterly
     * revenue in a 10-K while leaving operating income to be derived, so the lists need not have
     * been built the same way even where both tile the same year. Here revenue covers one
     * quarter the income series does not, and the pairing still lines the rest up.
     */
    @Test
    void pairsQuartersOnPeriodEndRatherThanPosition() {
        List<Fact> revenueQuarters = List.of(
                new Fact(LocalDate.parse("2025-07-01"), LocalDate.parse("2025-09-30"),
                        new BigDecimal("100")),
                new Fact(LocalDate.parse("2025-10-01"), LocalDate.parse("2025-12-31"),
                        new BigDecimal("200")),
                new Fact(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31"),
                        new BigDecimal("300")),
                new Fact(LocalDate.parse("2026-04-01"), LocalDate.parse("2026-06-30"),
                        new BigDecimal("400")));
        OperatingLine line = serviceOf(Map.of(
                "OperatingIncomeLoss", ok(fourQuarters("400")),
                "Revenues", ok(revenueQuarters))
        ).build("TEST", new BigDecimal("1000"), NOW);

        assertNotNull(line);
        assertEquals(0, line.quarters().get(0).revenue().compareTo(new BigDecimal("100")),
                "the quarter ending 2025-09-30, not the first revenue fact in the list");
        assertEquals(0, line.quarters().get(3).revenue().compareTo(new BigDecimal("400")));
        assertEquals(0, line.quarters().get(3).margin().compareTo(new BigDecimal("0.250000")),
                "100 of operating income on 400 of revenue");
    }

    /**
     * The WMT case. Its contract-revenue tag excludes membership income and came out 1.0% below
     * the revenue on the row; {@code Revenues} reproduces it exactly. The denominator is chosen
     * by which tag reconciles, so the margin and the Revenue column can never be two different
     * definitions — and that selection is what removes the need to rank the tags by hand.
     */
    @Test
    void choosesTheRevenueTagThatReproducesTheRowsOwnRevenue() {
        OperatingLine line = serviceOf(Map.of(
                "OperatingIncomeLoss", ok(fourQuarters("40")),
                "Revenues", ok(fourQuarters("1000")),
                "RevenueFromContractWithCustomerExcludingAssessedTax", ok(fourQuarters("900")))
        ).build("TEST", new BigDecimal("1000"), NOW);

        assertNotNull(line);
        assertEquals("Revenues", line.revenueConcept());
        assertEquals(0, line.revenueTtm().compareTo(new BigDecimal("1000")));
    }

    /**
     * KLAC's latest tagged window was 2015 and BLND's CIK belongs to a different company
     * altogether; both land here, because neither can reproduce the revenue on the row. The
     * reason names the window so the row can say what it was looking at.
     */
    @Test
    void withholdsWhenNoRevenueTagReconciles() {
        OperatingLine line = serviceOf(Map.of(
                "OperatingIncomeLoss", ok(fourQuarters("400")),
                "Revenues", ok(fourQuarters("130")))
        ).build("TEST", new BigDecimal("1000"), NOW);

        assertNotNull(line);
        assertTrue(line.withheld());
        assertNull(line.operatingMarginTtm(), "no margin, not a margin on the wrong denominator");
        assertNull(line.operatingIncomeTtm());
        assertTrue(line.withheldReason().contains("2026-06-30"), line.withheldReason());
    }

    /** Within tolerance is still a reconciliation: rounding in the feed is not a disagreement. */
    @Test
    void acceptsRevenueWithinTheTolerance() {
        OperatingLine line = serviceOf(Map.of(
                "OperatingIncomeLoss", ok(fourQuarters("400")),
                "Revenues", ok(fourQuarters("1002")))
        ).build("TEST", new BigDecimal("1000"), NOW);

        assertNotNull(line);
        assertFalse(line.withheld(), "0.2% apart — the same figure, rounded");
    }

    @Test
    void namesTheFormWhenTheFilerIsForeign() {
        OperatingLine line = serviceOf(Map.of(
                "OperatingIncomeLoss", new Series(List.of(), "EUR", Status.NOT_DOMESTIC))
        ).build("TEST", new BigDecimal("1000"), NOW);

        assertNotNull(line);
        assertTrue(line.withheld());
        assertTrue(line.withheldReason().contains("20-F"), line.withheldReason());
    }

    @Test
    void namesTheMissingSubtotalWhenTheConceptIsUntagged() {
        OperatingLine line = serviceOf(Map.of()).build("TEST", new BigDecimal("1000"), NOW);

        assertNotNull(line);
        assertTrue(line.withheld());
        assertTrue(line.withheldReason().contains("operating-income subtotal"),
                line.withheldReason());
    }

    /**
     * An outage is not a verdict about the company. Storing one would leave a refusal on the row
     * reading like a finding about the filer until somebody noticed it was really a failed read.
     */
    @Test
    void storesNothingWhenTheSecCouldNotBeRead() {
        assertNull(serviceOf(Map.of(
                "OperatingIncomeLoss", new Series(List.of(), null, Status.UNAVAILABLE))
        ).build("TEST", new BigDecimal("1000"), NOW));
    }

    @Test
    void withholdsWhenTheQuartersDoNotTileAYear() {
        OperatingLine line = serviceOf(Map.of(
                "OperatingIncomeLoss", ok(List.of(
                        new Fact(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31"),
                                new BigDecimal("30")),
                        new Fact(LocalDate.parse("2026-04-01"), LocalDate.parse("2026-06-30"),
                                new BigDecimal("40")))))
        ).build("TEST", new BigDecimal("1000"), NOW);

        assertNotNull(line);
        assertTrue(line.withheld());
        assertTrue(line.withheldReason().contains("tile a year"), line.withheldReason());
    }

    /**
     * SEC keys its registry on bare tickers and so do we, so they collide: BLND is British Land
     * here and Blend Labs there. The test is whether the symbol reaches its own listing without
     * a Yahoo mapping — if it needs one, the filings behind that CIK are a different company's.
     */
    @Test
    void onlyAttemptsSymbolsThatAreThemselvesUsTickers() {
        OperatingLineService service = serviceOf(Map.of());

        assertTrue(service.isPlainUsTicker("AAPL"));
        assertTrue(service.isPlainUsTicker("NVDA"));
        assertFalse(service.isPlainUsTicker("BLND"), "British Land, not Blend Labs");
        assertFalse(service.isPlainUsTicker("AZN"), "AZN.L — the London line");
        assertFalse(service.isPlainUsTicker("BA."), "BA.L — BAE, not Boeing");
    }
}
