package com.portfolio.application;

import com.portfolio.application.LivePricesService.Row;
import com.portfolio.domain.grouping.HoldingGroupKey;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveGrouperTest {

    private static final Instant T0 = Instant.parse("2026-08-27T09:00:00Z");

    private static Row row(String symbol, String group, String positionGbp, String dayChangeGbp,
                           String monthPct, Instant ts) {
        return new Row(symbol, "GBP", BigDecimal.ONE, new BigDecimal("1"),
                null,
                dayChangeGbp == null ? null : new BigDecimal(dayChangeGbp),
                positionGbp == null ? null : new BigDecimal(positionGbp),
                group,                       // parked in `accounts`, which the test key reads
                ts, "REGULAR", "OPEN",
                null, null,
                monthPct == null ? null : new BigDecimal(monthPct),
                null, null);
    }

    /** Groups on whatever the fixture parked in `accounts`, so the tests stay key-agnostic. */
    private static final HoldingGroupKey<Row> BY_TAG = r ->
            HoldingGroupKey.Group.of(r.accounts(), r.accounts());

    private static LiveGroup byId(List<LiveGroup> groups, String id) {
        return groups.stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    @Test
    void sumsPositionAndDayChange() {
        LiveGroup g = byId(LiveGrouper.group(List.of(
                row("AAA", "A", "1000", "10", null, T0),
                row("BBB", "A", "500", "-30", null, T0)), BY_TAG), "A");

        assertAmount("1500", g.positionGbp());
        assertAmount("-20", g.dayChangeGbp());
        assertEquals(2, g.memberCount());
    }

    /**
     * The previous close is recovered as {@code position − dayChange}, so the group figure is
     * Σδ ÷ Σprev — never the mean of the members' percentages. Here a small name up 50% sits
     * beside a large one down 1%; the average would be +24.5%, the money did −0.75%.
     */
    @Test
    void dayChangePctIsWeightedByThePreviousValueNotAveraged() {
        LiveGroup g = byId(LiveGrouper.group(List.of(
                row("TINY", "A", "150", "50", null, T0),      // prev 100 → +50%
                row("BIG", "A", "9900", "-100", null, T0)),   // prev 10000 → −1%
                BY_TAG), "A");

        assertAmount("10100", g.positionGbp().subtract(g.dayChangeGbp()));   // Σ prev
        // −50 / 10100 = −0.495%
        assertEquals(0, new BigDecimal("-0.004950").compareTo(g.dayChangePct()));
        assertTrue(g.dayChangePct().signum() < 0);
    }

    /**
     * Grouping every row into one group must reproduce the totals row's own Day Δ %, since both
     * are Σδ ÷ Σprev. If they ever diverge, a second valuation path has grown.
     */
    @Test
    void oneGroupOverEverythingReproducesThePortfolioDayChange() {
        List<Row> rows = List.of(
                row("AAA", "ALL", "1000", "10", null, T0),
                row("BBB", "ALL", "500", "-30", null, T0),
                row("CCC", "ALL", "2500", "120", null, T0));

        BigDecimal totalDay = new BigDecimal("100");                 // 10 − 30 + 120
        BigDecimal totalPrev = new BigDecimal("3900");               // 4000 − 100
        LiveGroup g = byId(LiveGrouper.group(rows, BY_TAG), "ALL");

        assertEquals(0, totalDay.divide(totalPrev, 6, RoundingMode.HALF_UP)
                .compareTo(g.dayChangePct()));
    }

    /**
     * A trailing column is weighted by what each position was worth at the window's start,
     * recovered as {@code position ÷ (1 + pct)}. £1,100 up 10% started at £1,000; £900 down 10%
     * started at £1,000 — so the group is flat, where the mean of +10% and −10% is also flat but
     * only by coincidence. The asymmetric case below is the one that separates them.
     */
    @Test
    void trailingWindowIsWeightedByTheValueAtTheStartOfTheWindow() {
        LiveGroup g = byId(LiveGrouper.group(List.of(
                row("UP", "A", "220", "0", "1.20", T0),     // £100 → £220, +120%
                row("FLAT", "A", "9000", "0", "0", T0)),    // £9,000 → £9,000, flat
                BY_TAG), "A");

        // then = 100 + 9000 = 9100; now = 9220 → +1.3187%. The mean would be +60%.
        assertEquals(0, new BigDecimal("0.013187").compareTo(g.monthChangePct()));
    }

    /** A member missing the column must not count as flat — it leaves both sides of the ratio. */
    @Test
    void aMemberWithNoFigureIsExcludedFromThatColumnEntirely() {
        LiveGroup g = byId(LiveGrouper.group(List.of(
                row("HAS", "A", "1100", "0", "0.10", T0),
                row("NONE", "A", "50000", "0", null, T0)), BY_TAG), "A");

        // Only HAS contributes: 1100/1.1 = 1000 → +10%. The huge unreported name cannot flatten it.
        assertEquals(0, new BigDecimal("0.100000").compareTo(g.monthChangePct()));
        assertAmount("51100", g.positionGbp());   // …but it is still in the value
    }

    @Test
    void aColumnNoMemberReportsIsNullRatherThanZero() {
        LiveGroup g = byId(LiveGrouper.group(List.of(
                row("AAA", "A", "1000", "5", null, T0)), BY_TAG), "A");

        assertNull(g.monthChangePct());
        assertNull(g.change5dPct());
        assertNull(g.change6mPct());
    }

    /** A price that fell to zero makes the back-out undefined; skip rather than invert the group. */
    @Test
    void aTotalWipeoutDoesNotInvertTheWindow() {
        LiveGroup g = byId(LiveGrouper.group(List.of(
                row("DEAD", "A", "0", "0", "-1", T0),
                row("OK", "A", "1100", "0", "0.10", T0)), BY_TAG), "A");

        assertEquals(0, new BigDecimal("0.100000").compareTo(g.monthChangePct()));
    }

    /**
     * The header's timestamp is the <b>oldest</b> quote under it, not the newest — a summary line
     * is only as current as its stalest input, and taking the newest would hide the lagging feed
     * that is the whole reason to look at the column.
     */
    @Test
    void quoteTimestampIsTheOldestInTheGroupAndNamesIt() {
        Instant old = T0.minusSeconds(4 * 3600);
        LiveGroup g = byId(LiveGrouper.group(List.of(
                row("FRESH", "A", "1000", "0", null, T0),
                row("LAGGING", "A", "500", "0", null, old),
                row("ALSOFRESH", "A", "700", "0", null, T0.minusSeconds(30))), BY_TAG), "A");

        assertEquals(old, g.priceTs());
        assertEquals("LAGGING", g.stalestSymbol());
    }

    @Test
    void aGroupWhoseMembersHaveNoTimestampReportsNone() {
        LiveGroup g = byId(LiveGrouper.group(List.of(
                row("AAA", "A", "1000", "0", null, null)), BY_TAG), "A");

        assertNull(g.priceTs());
        assertNull(g.stalestSymbol());
    }

    /** Nothing is dropped, so the groups add back to what the ungrouped table totals. */
    @Test
    void groupValuesReconcileToTheUngroupedTotal() {
        List<Row> rows = List.of(
                row("AAA", "A", "1000.55", "10", null, T0),
                row("BBB", "B", "500.10", "-30", null, T0),
                row("CCC", "A", "2500.35", "120", null, T0));

        BigDecimal ungrouped = rows.stream().map(Row::positionGbp)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<LiveGroup> groups = LiveGrouper.group(rows, BY_TAG);

        assertEquals(0, ungrouped.compareTo(groups.stream().map(LiveGroup::positionGbp)
                .reduce(BigDecimal.ZERO, BigDecimal::add)));
        assertEquals(rows.size(), groups.stream().mapToInt(LiveGroup::memberCount).sum());
        assertEquals(List.of("AAA", "CCC"), byId(groups, "A").symbols());
    }

    @Test
    void groupsAreOrderedByValueWithTheResidualPinnedLast() {
        HoldingGroupKey<Row> key = r -> "UNKNOWN".equals(r.symbol())
                ? HoldingGroupKey.Group.residual("UNCLASSIFIED", "Unclassified", null)
                : HoldingGroupKey.Group.of(r.accounts(), r.accounts());

        List<LiveGroup> groups = LiveGrouper.group(List.of(
                row("SMALL", "S", "100", "0", null, T0),
                row("UNKNOWN", "U", "99999", "0", null, T0),
                row("BIG", "B", "5000", "0", null, T0)), key);

        assertEquals(List.of("B", "S", "UNCLASSIFIED"),
                groups.stream().map(LiveGroup::id).toList());
    }

    @Test
    void emptyInputProducesNoGroups() {
        assertTrue(LiveGrouper.group(List.of(), BY_TAG).isEmpty());
    }
}
