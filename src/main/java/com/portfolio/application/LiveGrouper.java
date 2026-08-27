package com.portfolio.application;

import com.portfolio.application.LivePricesService.Row;
import com.portfolio.domain.grouping.HoldingGroupKey;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Collapses the Live tab's rows into groups, one per key produced by a {@link HoldingGroupKey}.
 * Framework-free and unit-tested, like {@code HoldingGrouper} — which it deliberately does not
 * reuse, because the Live table's columns are not the ledger's (see {@link LiveGroup}).
 *
 * <p>What it shares with the ledger is the discipline: <b>every row lands in exactly one group</b>,
 * so the groups' Position £ sums to the same figure the ungrouped table totals; <b>percentages are
 * weighted, never averaged</b>; and a column with no defensible group-level value is left null
 * rather than filled with something plausible.
 *
 * <h2>Day Δ % — the denominator is recovered, not stored</h2>
 * A row's previous close in GBP is exactly {@code positionGbp − dayChangeGbp}, because that is how
 * {@code LivePricesService} built the change in the first place. So the group figure is
 * {@code Σ dayΔ ÷ Σ(position − dayΔ)} with no extra field on {@link Row} and no second valuation
 * path that could disagree with the numbers printed beside it — the same trick the ledger's
 * grouper uses to recover a cost basis from {@code value − gain}. It also reproduces the totals
 * row's own definition, so grouping the whole table into one group would give the portfolio's Day
 * Δ %.
 *
 * <h2>The trailing windows — weighted by what the position was worth then</h2>
 * A trailing column is a price change, so aggregating it means weighting each name by its size,
 * and the size that matters is the size at the <i>start</i> of the window. That is recoverable
 * from the row itself: {@code then = positionGbp ÷ (1 + pct)}. The group figure is then
 * {@code (Σ now − Σ then) ÷ Σ then} — the value-weighted price change of the group, which is what
 * a reader means by "how did my semis do this month".
 *
 * <p><b>It uses today's quantities throughout, and that is a real caveat rather than a rounding
 * one.</b> A name bought halfway through the window is weighted as though it had been held for all
 * of it, so this is "what today's holdings did over that window", not the return the portfolio
 * actually earned — that needs the dated flows, and the Attribution tab already computes it. The
 * per-row figure has exactly the same property (it is a price change on a line whose quantity may
 * have changed), so the group is the honest generalisation of the column it summarises rather than
 * a different measure wearing its name. The tooltip says so.
 *
 * <p>A row missing the figure in question is left out of that column's numerator <i>and</i>
 * denominator — a name with no 6-month history must not silently count as flat — so different
 * columns of one header row can legitimately rest on different subsets. A column no member has
 * reports null.
 */
public final class LiveGrouper {

    private static final int PCT_SCALE = 6;
    private static final int GBP_SCALE = 2;

    private LiveGrouper() {
    }

    public static List<LiveGroup> group(List<Row> rows, HoldingGroupKey<Row> key) {
        Map<String, Accumulator> byId = new LinkedHashMap<>();
        for (Row r : rows) {
            HoldingGroupKey.Group g = key.keyFor(r);
            byId.computeIfAbsent(g.id(), k -> new Accumulator(g)).add(r);
        }
        List<LiveGroup> out = new ArrayList<>();
        for (Accumulator a : byId.values()) out.add(a.toGroup());
        out.sort(Comparator.comparing(LiveGroup::residual)
                .thenComparing(LiveGroup::positionGbp,
                        Comparator.nullsLast(Comparator.reverseOrder())));
        return List.copyOf(out);
    }

    private static final class Accumulator {

        private final HoldingGroupKey.Group group;
        private final List<String> symbols = new ArrayList<>();

        private BigDecimal position, dayChange, prevPosition;
        private final Window w5 = new Window(), w10 = new Window(), w30 = new Window(),
                w3m = new Window(), w6m = new Window();

        private Instant oldestTs;
        private String stalestSymbol;

        Accumulator(HoldingGroupKey.Group group) {
            this.group = group;
        }

        void add(Row r) {
            symbols.add(r.symbol());
            position = plus(position, r.positionGbp());
            dayChange = plus(dayChange, r.dayChangeGbp());
            if (r.positionGbp() != null && r.dayChangeGbp() != null) {
                prevPosition = plus(prevPosition, r.positionGbp().subtract(r.dayChangeGbp()));
            }
            w5.add(r.positionGbp(), r.change5dPct());
            w10.add(r.positionGbp(), r.change10dPct());
            w30.add(r.positionGbp(), r.monthChangePct());
            w3m.add(r.positionGbp(), r.change3mPct());
            w6m.add(r.positionGbp(), r.change6mPct());
            // Oldest wins: the header is only as fresh as the least fresh thing under it.
            if (r.priceTs() != null && (oldestTs == null || r.priceTs().isBefore(oldestTs))) {
                oldestTs = r.priceTs();
                stalestSymbol = r.symbol();
            }
        }

        LiveGroup toGroup() {
            return new LiveGroup(group.id(), group.label(), group.note(), group.residual(),
                    scale(position, GBP_SCALE), scale(dayChange, GBP_SCALE),
                    ratio(dayChange, prevPosition),
                    w5.pct(), w10.pct(), w30.pct(), w3m.pct(), w6m.pct(),
                    oldestTs, stalestSymbol, List.copyOf(symbols));
        }
    }

    /**
     * One trailing column's running totals: what the group is worth now and what the same holdings
     * were worth at the window's start, both summed only over the members that reported the column.
     */
    private static final class Window {

        private BigDecimal now, then;

        void add(BigDecimal positionGbp, BigDecimal pct) {
            if (positionGbp == null || pct == null) return;
            BigDecimal growth = BigDecimal.ONE.add(pct);
            // A price that fell to zero (or a bad bar reporting worse) makes the back-out
            // undefined or negative. Skip rather than let one row invert the group.
            if (growth.signum() <= 0) return;
            now = plus(now, positionGbp);
            then = plus(then, positionGbp.divide(growth, 10, RoundingMode.HALF_UP));
        }

        BigDecimal pct() {
            return now == null ? null : ratio(now.subtract(then), then);
        }
    }

    private static BigDecimal plus(BigDecimal running, BigDecimal add) {
        if (add == null) return running;
        return running == null ? add : running.add(add);
    }

    /** {@code numerator ÷ denominator}, or null when there is nothing to divide by. */
    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) return null;
        return numerator.divide(denominator, PCT_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale(BigDecimal v, int scale) {
        return v == null ? null : v.setScale(scale, RoundingMode.HALF_UP);
    }

    /** Adapts a symbol/value sector resolver to the Live row type. */
    public static HoldingGroupKey<Row> sectorKey(SectorGroupKey sector) {
        Function<Row, HoldingGroupKey.Group> f =
                r -> sector.groupFor(r.symbol(), r.positionGbp());
        return f::apply;
    }
}
