package com.portfolio.domain.grouping;

import com.portfolio.domain.model.AggHolding;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collapses the Cash-ledger view's holdings into groups, one per key produced by a
 * {@link HoldingGroupKey}. Pure: no Spring, no JDBC, no network — the resolver arrives already
 * holding whatever data it needs, so the aggregation is unit-testable on its own.
 *
 * <p>The class knows nothing about sectors, accounts or any other way of grouping, and must stay
 * that way — see {@link HoldingGroupKey} for why. The only key-shaped thing it acts on is
 * {@link HoldingGroupKey.Group#residual()}, and that is a property the resolver declares about
 * its own group rather than something inferred here.
 *
 * <h2>The two invariants</h2>
 * <ol>
 *   <li><b>Every holding lands in exactly one group.</b> Nothing is dropped, so the groups'
 *       market values sum to the ungrouped total to the penny — the property that lets a reader
 *       trust a collapsed table at all. {@code HoldingGrouperTest} pins it, including for a
 *       resolver that cannot place anything.</li>
 *   <li><b>Percentages are weighted, never averaged.</b> {@code Σgain ÷ Σcost}, so a group's
 *       Gain % is the return the money in it actually earned.</li>
 * </ol>
 *
 * <h2>What a null contributes</h2>
 * A holding priced in a currency with no FX rate has a null market value and a null gain; a CASH
 * row has a real value and no gain at all, by construction. So the sums are taken over the members
 * that have the figure in question, and cost is accumulated only alongside a known gain — the two
 * must come from the same members or the ratio is between mismatched sets. A group in which
 * nothing has a gain reports null rather than zero: "no cost basis here" and "broke even" are
 * different statements, and cash is the first.
 */
public final class HoldingGrouper {

    /** Same scale the per-row Gain % is computed at, so a one-member group reproduces its row. */
    private static final int PCT_SCALE = 10;

    private HoldingGrouper() {
    }

    public static List<HoldingGroup> group(List<AggHolding> holdings,
                                          HoldingGroupKey<AggHolding> key) {
        Map<String, Accumulator> byId = new LinkedHashMap<>();
        for (AggHolding h : holdings) {
            HoldingGroupKey.Group g = key.keyFor(h);
            byId.computeIfAbsent(g.id(), k -> new Accumulator(g)).add(h);
        }
        List<HoldingGroup> out = new ArrayList<>();
        for (Accumulator a : byId.values()) out.add(a.toGroup());
        // Largest first, so the table opens on what matters; the residual group is pinned last
        // whatever it is worth, because ranking the unplaced among the placed implies a
        // classification that did not happen.
        out.sort(Comparator.comparing(HoldingGroup::residual)
                .thenComparing(HoldingGroup::marketValueGbp,
                        Comparator.nullsLast(Comparator.reverseOrder())));
        return List.copyOf(out);
    }

    private static final class Accumulator {

        private final HoldingGroupKey.Group group;
        private final List<AggHolding> members = new ArrayList<>();

        private BigDecimal value, cost, gain, dividend, totalGain;

        Accumulator(HoldingGroupKey.Group group) {
            this.group = group;
        }

        void add(AggHolding h) {
            members.add(h);
            value = plus(value, h.marketValueGbp());
            dividend = plus(dividend, h.dividendGbp());
            totalGain = plus(totalGain, h.totalGainGbp());
            // Cost is derived from the pair, so it can only be accumulated where both exist —
            // the ledger view builds gain as (market value − cost), which makes the inverse exact.
            if (h.gainGbp() != null && h.marketValueGbp() != null) {
                gain = plus(gain, h.gainGbp());
                cost = plus(cost, h.marketValueGbp().subtract(h.gainGbp()));
            }
        }

        HoldingGroup toGroup() {
            return new HoldingGroup(group.id(), group.label(), group.note(), group.residual(),
                    value, cost, gain, ratio(gain, cost), dividend, totalGain, ratio(totalGain, cost),
                    List.copyOf(members));
        }

        private static BigDecimal plus(BigDecimal running, BigDecimal add) {
            if (add == null) return running;
            return running == null ? add : running.add(add);
        }

        /**
         * {@code numerator ÷ cost}, or null when there is no cost to divide by. A zero cost basis
         * is real — a position whose realised gains the broker netted through the surviving
         * shares' average cost can reach it — and a percentage of nothing is not a large return,
         * it is an undefined one.
         */
        private static BigDecimal ratio(BigDecimal numerator, BigDecimal cost) {
            if (numerator == null || cost == null || cost.signum() == 0) return null;
            return numerator.divide(cost, PCT_SCALE, RoundingMode.HALF_UP);
        }
    }
}
