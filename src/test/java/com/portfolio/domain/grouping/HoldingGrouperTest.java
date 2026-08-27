package com.portfolio.domain.grouping;

import com.portfolio.domain.model.AggHolding;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoldingGrouperTest {

    private static final Currency GBP = Currency.getInstance("GBP");

    /**
     * The ledger view builds {@code gain = marketValue − cost}, so a fixture states value and
     * gain and the cost basis is whatever that implies — the same identity {@link HoldingGrouper}
     * inverts.
     */
    private static AggHolding holding(String id, String marketValueGbp, String gainGbp,
                                      String dividendGbp, String sources) {
        BigDecimal gain = gainGbp == null ? null : new BigDecimal(gainGbp);
        BigDecimal div = new BigDecimal(dividendGbp);
        BigDecimal value = new BigDecimal(marketValueGbp);
        BigDecimal cost = gain == null ? null : value.subtract(gain);
        return new AggHolding(id, BigDecimal.ONE, null, value,
                gain,
                gain == null || cost.signum() == 0 ? null : gain.divide(cost, 10, java.math.RoundingMode.HALF_UP),
                div,
                gain == null ? null : gain.add(div),
                null, GBP, sources, null, null, null, null);
    }

    /** Files each holding under a key read off its {@code sources}, for key-agnostic assertions. */
    private static final HoldingGroupKey<AggHolding> BY_SOURCE = h ->
            HoldingGroupKey.Group.of(h.sources(), h.sources());

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    private static HoldingGroup byId(List<HoldingGroup> groups, String id) {
        return groups.stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void sumsValueGainDividendAndTotalGainPerGroup() {
        List<HoldingGroup> groups = HoldingGrouper.group(List.of(
                holding("AAA", "1000", "200", "10", "A"),
                holding("BBB", "500", "-50", "5", "A"),
                holding("CCC", "300", "30", "0", "B")), BY_SOURCE);

        HoldingGroup a = byId(groups, "A");
        assertAmount("1500", a.marketValueGbp());
        assertAmount("150", a.gainGbp());
        assertAmount("15", a.dividendGbp());
        assertAmount("165", a.totalGainGbp());
        assertEquals(2, a.memberCount());

        HoldingGroup b = byId(groups, "B");
        assertAmount("300", b.marketValueGbp());
        assertAmount("30", b.gainGbp());
    }

    /**
     * The whole reason the percentages are not averaged. A tiny position up 300% next to a large
     * one down 2% averages to +149%; what the money in the group did is −2%.
     */
    @Test
    void gainPctIsCostWeightedNotTheMeanOfTheMembers() {
        // £5 now, cost £1.25 → +300%.   £49,000 now, cost £50,000 → −2%.
        List<HoldingGroup> groups = HoldingGrouper.group(List.of(
                holding("TINY", "5", "3.75", "0", "A"),
                holding("BIG", "49000", "-1000", "0", "A")), BY_SOURCE);

        HoldingGroup a = byId(groups, "A");
        assertAmount("50001.25", a.costGbp());
        assertAmount("-996.25", a.gainGbp());
        // Σgain ÷ Σcost = −996.25 / 50001.25 ≈ −1.99%. The mean of the members would be +149%.
        assertEquals(0, new BigDecimal("-0.0199245")
                .compareTo(a.gainPct().setScale(7, java.math.RoundingMode.HALF_UP)));
        assertTrue(a.gainPct().signum() < 0, "weighted return must follow the money, not the count");
    }

    @Test
    void totalGainPctDividesByTheSameCostBasis() {
        List<HoldingGroup> groups = HoldingGrouper.group(List.of(
                holding("AAA", "1200", "200", "100", "A")), BY_SOURCE);

        HoldingGroup a = byId(groups, "A");
        assertAmount("1000", a.costGbp());
        assertAmount("0.2", a.gainPct());        // 200 / 1000
        assertAmount("0.3", a.totalGainPct());   // (200 + 100) / 1000
    }

    /** A one-member group must reproduce that member's own row, or the header contradicts it. */
    @Test
    void singleMemberGroupReproducesTheRow() {
        AggHolding only = holding("AAA", "1234.56", "234.56", "12.34", "A");
        HoldingGroup g = byId(HoldingGrouper.group(List.of(only), BY_SOURCE), "A");

        assertAmount(only.marketValueGbp().toPlainString(), g.marketValueGbp());
        assertEquals(0, only.gainPct().compareTo(g.gainPct()));
    }

    /**
     * The property that makes a collapsed table trustworthy: nothing is dropped, so the groups
     * add back to what the ungrouped table totals.
     */
    @Test
    void groupValuesReconcileToTheUngroupedTotal() {
        List<AggHolding> holdings = List.of(
                holding("AAA", "1000", "200", "10", "A"),
                holding("BBB", "500", "-50", "5", "B"),
                holding("CASH", "742.19", null, "0", "A"),
                holding("CCC", "300", "30", "0", "A, B"));

        BigDecimal ungrouped = holdings.stream().map(AggHolding::marketValueGbp)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<HoldingGroup> groups = HoldingGrouper.group(holdings, BY_SOURCE);

        BigDecimal grouped = groups.stream().map(HoldingGroup::marketValueGbp)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, ungrouped.compareTo(grouped));
        assertEquals(holdings.size(),
                groups.stream().mapToInt(HoldingGroup::memberCount).sum());
    }

    /** Even a resolver that can place nothing must keep every holding and every penny. */
    @Test
    void aResolverThatPlacesNothingStillKeepsEveryHolding() {
        List<AggHolding> holdings = List.of(
                holding("AAA", "1000", "200", "0", "A"),
                holding("BBB", "500", "-50", "0", "B"));
        HoldingGroupKey<AggHolding> placesNothing = h ->
                HoldingGroupKey.Group.residual("UNCLASSIFIED", "Unclassified", "no idea");

        List<HoldingGroup> groups = HoldingGrouper.group(holdings, placesNothing);

        assertEquals(1, groups.size());
        assertAmount("1500", groups.get(0).marketValueGbp());
        assertTrue(groups.get(0).residual());
    }

    /**
     * Cash has a value and no cost basis. Reporting 0% for it would read as "broke even"; the
     * blank reads as "there is no cost basis here", which is the truth.
     */
    @Test
    void aGroupWithNoCostBasisReportsNoPercentageRatherThanZero() {
        List<HoldingGroup> groups = HoldingGrouper.group(List.of(
                holding("CASH", "742.19", null, "0", "A")), BY_SOURCE);

        HoldingGroup a = byId(groups, "A");
        assertAmount("742.19", a.marketValueGbp());
        assertNull(a.costGbp());
        assertNull(a.gainGbp());
        assertNull(a.gainPct());
        assertNull(a.totalGainPct());
    }

    /** Cash rides along in the value without diluting the group's return. */
    @Test
    void cashAddsValueButNotCostToAMixedGroup() {
        HoldingGroup a = byId(HoldingGrouper.group(List.of(
                holding("AAA", "1200", "200", "0", "A"),
                holding("CASH", "800", null, "0", "A")), BY_SOURCE), "A");

        assertAmount("2000", a.marketValueGbp());
        assertAmount("1000", a.costGbp());
        assertAmount("0.2", a.gainPct());   // not 200/1800 — cash brought no cost basis with it
    }

    @Test
    void groupsAreOrderedByValueWithTheResidualPinnedLast() {
        HoldingGroupKey<AggHolding> key = h -> switch (h.securityId()) {
            case "SMALL" -> HoldingGroupKey.Group.of("SMALL", "Small");
            case "BIG" -> HoldingGroupKey.Group.of("BIG", "Big");
            default -> HoldingGroupKey.Group.residual("UNCLASSIFIED", "Unclassified", null);
        };

        List<HoldingGroup> groups = HoldingGrouper.group(List.of(
                holding("SMALL", "100", "1", "0", "A"),
                holding("UNKNOWN", "99999", "1", "0", "A"),   // huge, but unplaced
                holding("BIG", "5000", "1", "0", "A")), key);

        assertEquals(List.of("BIG", "SMALL", "UNCLASSIFIED"),
                groups.stream().map(HoldingGroup::id).toList());
    }

    @Test
    void memberOrderWithinAGroupFollowsTheInputOrder() {
        List<HoldingGroup> groups = HoldingGrouper.group(List.of(
                holding("AAA", "1", "0", "0", "A"),
                holding("BBB", "2", "0", "0", "A"),
                holding("CCC", "3", "0", "0", "A")), BY_SOURCE);

        assertEquals(List.of("AAA", "BBB", "CCC"),
                byId(groups, "A").members().stream().map(AggHolding::securityId).toList());
    }

    @Test
    void emptyInputProducesNoGroups() {
        assertTrue(HoldingGrouper.group(List.of(), BY_SOURCE).isEmpty());
    }
}
