package com.portfolio.application;

import com.portfolio.adapter.YahooQuoteSummaryFetcher.QuoteSummary;
import com.portfolio.domain.SectorBucket;
import com.portfolio.domain.SectorClassifier;
import com.portfolio.domain.grouping.HoldingGroupKey;
import com.portfolio.domain.model.AggHolding;
import com.portfolio.persistence.FundamentalsRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectorGroupKeyTest {

    private static AggHolding held(String symbol) {
        return new AggHolding(symbol, BigDecimal.ONE, null, new BigDecimal("10000"),
                new BigDecimal("1000"), null, BigDecimal.ZERO, new BigDecimal("1000"), null,
                Currency.getInstance("GBP"), "AJ Bell SIPP", null, null, null, null);
    }

    private static QuoteSummary stock(String symbol, String sector) {
        return new QuoteSummary(symbol, "USD", null, null, null, null, null, null, null, null,
                null, Map.of(), Map.of("sector", sector), false);
    }

    private static QuoteSummary fund(String symbol, Map<String, BigDecimal> weights) {
        return new QuoteSummary(symbol, "GBP", null, null, null, null, null, null, null, null,
                null, Map.of(), Map.of(), weights, List.of(), false);
    }

    private static Map<String, FundamentalsRepository.Cached> cache(QuoteSummary... quotes) {
        Map<String, FundamentalsRepository.Cached> out = new LinkedHashMap<>();
        for (QuoteSummary q : quotes) {
            out.put(q.symbol(), new FundamentalsRepository.Cached(q, Instant.EPOCH));
        }
        return out;
    }

    private static SectorGroupKey keyWith(QuoteSummary... quotes) {
        return new SectorGroupKey(new SectorClassifier(), cache(quotes));
    }

    @Test
    void aDirectHoldingIsFiledUnderItsYahooSector() {
        HoldingGroupKey.Group g = keyWith(stock("NVDA", "Technology")).keyFor(held("NVDA"));
        assertEquals(SectorBucket.TECH_AI.name(), g.id());
        assertEquals("Technology", g.label());
        assertFalse(g.residual());
    }

    /** Bonds resolve from the symbol itself, so a gilt needs no fundamentals row at all. */
    @Test
    void aGiltIsFixedIncomeWithNoCacheEntry() {
        assertEquals(SectorBucket.FIXED_INCOME.name(),
                keyWith().keyFor(held("GILT 4.75% 2038")).id());
    }

    /**
     * Cash is placed here rather than left to the classifier: it has no symbol Yahoo knows, so it
     * would otherwise land in the unclassified group and make the largest "data gap" in the table
     * a balance that is not a data gap at all.
     */
    @Test
    void cashIsItsOwnGroupRatherThanUnclassified() {
        HoldingGroupKey.Group g = keyWith().keyFor(held("CASH"));
        assertEquals(SectorBucket.CASH.name(), g.id());
        assertFalse(g.residual());
    }

    /**
     * A fund's classification is a split, and a table row cannot be split without the group
     * totals ceasing to add up. Filing a broad tracker under its largest sector would report a
     * concentration that is not what is held, so it groups as a fund and the note points at the
     * panel that does look it through.
     */
    @Test
    void aFundSpanningSectorsGroupsAsAFundRatherThanUnderItsLargestSector() {
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        weights.put("technology", new BigDecimal("0.578"));
        weights.put("consumer_cyclical", new BigDecimal("0.190"));
        weights.put("communication_services", new BigDecimal("0.232"));

        HoldingGroupKey.Group g = keyWith(fund("EQQQ", weights)).keyFor(held("EQQQ"));

        assertFalse(SectorBucket.TECH_AI.name().equals(g.id()),
                "57.8% technology is not a technology holding");
        assertEquals("Funds — multi-sector", g.label());
        assertNotNull(g.note());
        assertFalse(g.residual());
    }

    /** A single-sector ETF is placed like any other holding — the fund group is for the split ones. */
    @Test
    void aFundWhoseLookThroughLandsInOneBucketIsFiledThere() {
        HoldingGroupKey.Group g = keyWith(fund("XWHS", Map.of("healthcare", BigDecimal.ONE)))
                .keyFor(held("XWHS"));
        assertEquals(SectorBucket.HEALTHCARE.name(), g.id());
    }

    /** Never dropped: a symbol nothing knows about is placed explicitly and flagged residual. */
    @Test
    void anUnplaceableHoldingLandsInAnExplicitResidualGroup() {
        HoldingGroupKey.Group g = keyWith().keyFor(held("WHOKNOWS"));
        assertEquals(SectorBucket.UNCLASSIFIED.name(), g.id());
        assertEquals("Unclassified", g.label());
        assertTrue(g.residual());
        assertNotNull(g.note(), "the note has to distinguish 'not fetched yet' from 'no sector'");
    }

    /** The wrapper sets live in Instruments, so a bullion ETC needs no properties entry. */
    @Test
    void aBullionEtcIsCommoditiesWithoutAnySectorData() {
        assertEquals(SectorBucket.COMMODITIES.name(), keyWith().keyFor(held("SGLN")).id());
    }
}
