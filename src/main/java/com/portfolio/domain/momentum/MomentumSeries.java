package com.portfolio.domain.momentum;

import com.portfolio.domain.model.PriceBar;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The aligned multi-symbol daily panel every momentum engine reads. Built once per report.
 *
 * <p><b>Calendar.</b> The master calendar is the union of every symbol's trading dates
 * (universe + benchmarks). Each symbol's {@code close} (split-adjusted price — regime MA and
 * drawdown detection) and {@code adj} (total-return adj_close — all P&amp;L) are aligned to it.
 *
 * <p><b>Forward-fill discipline.</b> A missing day is filled from the last real bar for at most
 * {@code maxFfillSessions}; beyond that the cell is {@code NaN} (ineligible) and the gap is
 * recorded in {@link #gaps()} — a symbol is never silently forward-filled across a long gap.
 * Cells before a symbol's first bar and after its last are NaN.
 *
 * <p>{@code monthEndIdx} lists the last trading day of each <em>completed</em> calendar month
 * (the final, possibly partial month is excluded — same convention as {@code TrendSeries}), which
 * is the rebalance clock.
 */
public final class MomentumSeries {

    /** A forward-fill gap longer than the allowed window: the symbol was ineligible over it. */
    public record Gap(String symbol, LocalDate from, LocalDate to, int sessions) {
    }

    private final String[] names;          // universe first, then benchmarks
    private final int universeCount;
    private final LocalDate[] dates;
    private final double[][] adj;          // [name][day], ffilled ≤ maxFfill, else NaN
    private final double[][] close;        // same discipline, split-adjusted price
    private final int[] monthEndIdx;
    private final List<Gap> gaps;
    private final Map<String, Integer> indexByName = new HashMap<>();

    private MomentumSeries(String[] names, int universeCount, LocalDate[] dates,
                           double[][] adj, double[][] close, int[] monthEndIdx, List<Gap> gaps) {
        this.names = names;
        this.universeCount = universeCount;
        this.dates = dates;
        this.adj = adj;
        this.close = close;
        this.monthEndIdx = monthEndIdx;
        this.gaps = gaps;
        for (int s = 0; s < names.length; s++) indexByName.put(names[s], s);
    }

    /**
     * Build from per-symbol ascending daily bars. {@code universeBars} preserves universe order;
     * {@code benchmarkBars} adds non-ranked series (benchmarks / regime index). A symbol present
     * in both is stored once, as a universe member. Symbols with no bars at all are skipped here —
     * the caller reports them as missing.
     */
    public static MomentumSeries of(LinkedHashMap<String, List<PriceBar>> universeBars,
                                    LinkedHashMap<String, List<PriceBar>> benchmarkBars,
                                    int maxFfillSessions) {
        LinkedHashMap<String, List<PriceBar>> all = new LinkedHashMap<>();
        universeBars.forEach((k, v) -> {
            if (v != null && !v.isEmpty()) all.put(k, v);
        });
        int universeCount = all.size();
        benchmarkBars.forEach((k, v) -> {
            if (v != null && !v.isEmpty()) all.putIfAbsent(k, v);
        });
        if (all.isEmpty()) return null;

        TreeSet<LocalDate> calendar = new TreeSet<>();
        for (List<PriceBar> bars : all.values()) for (PriceBar b : bars) calendar.add(b.date());
        LocalDate[] dates = calendar.toArray(new LocalDate[0]);
        Map<LocalDate, Integer> dayIdx = new HashMap<>();
        for (int i = 0; i < dates.length; i++) dayIdx.put(dates[i], i);

        int m = all.size(), n = dates.length;
        String[] names = all.keySet().toArray(new String[0]);
        double[][] adj = new double[m][n];
        double[][] close = new double[m][n];
        List<Gap> gaps = new ArrayList<>();

        int s = 0;
        for (Map.Entry<String, List<PriceBar>> e : all.entrySet()) {
            double[] a = adj[s], c = close[s];
            java.util.Arrays.fill(a, Double.NaN);
            java.util.Arrays.fill(c, Double.NaN);
            for (PriceBar b : e.getValue()) {
                int i = dayIdx.get(b.date());
                a[i] = b.adjClose();
                c[i] = b.close();
            }
            fillAndLogGaps(e.getKey(), dates, a, c, maxFfillSessions, gaps);
            s++;
        }

        List<Integer> monthEnds = new ArrayList<>();
        for (int i = 0; i + 1 < n; i++) {
            if (!YearMonth.from(dates[i]).equals(YearMonth.from(dates[i + 1]))) monthEnds.add(i);
        }
        int[] monthEndIdx = monthEnds.stream().mapToInt(Integer::intValue).toArray();

        return new MomentumSeries(names, universeCount, dates, adj, close, monthEndIdx, gaps);
    }

    /**
     * Forward-fill missing cells between a symbol's first and last real bar for up to
     * {@code maxFfill} sessions; leave longer stretches NaN and log them as gaps.
     */
    private static void fillAndLogGaps(String symbol, LocalDate[] dates, double[] a, double[] c,
                                       int maxFfill, List<Gap> gaps) {
        int first = -1, last = -1;
        for (int i = 0; i < a.length; i++) {
            if (!Double.isNaN(a[i])) {
                if (first < 0) first = i;
                last = i;
            }
        }
        if (first < 0) return;
        int lastReal = first;
        int gapStart = -1;
        for (int i = first + 1; i <= last; i++) {
            if (!Double.isNaN(a[i])) {
                if (gapStart >= 0 && i - lastReal - 1 > maxFfill) {
                    gaps.add(new Gap(symbol, dates[gapStart], dates[i - 1], i - lastReal - 1));
                }
                lastReal = i;
                gapStart = -1;
                continue;
            }
            if (gapStart < 0) gapStart = i;
            if (i - lastReal <= maxFfill) {   // within the allowed staleness → carry forward
                a[i] = a[lastReal];
                c[i] = c[lastReal];
            }
        }
    }

    public int size() {
        return dates.length;
    }

    public LocalDate dateAt(int i) {
        return dates[i];
    }

    public LocalDate firstDate() {
        return dates[0];
    }

    public LocalDate lastDate() {
        return dates[dates.length - 1];
    }

    /** Number of ranked (universe) symbols; indices {@code 0..universeCount-1} are rankable. */
    public int universeCount() {
        return universeCount;
    }

    public String nameOf(int s) {
        return names[s];
    }

    /** Index for a symbol (universe or benchmark), or -1 when it has no data. */
    public int indexOf(String symbol) {
        Integer i = indexByName.get(symbol);
        return i == null ? -1 : i;
    }

    /** Total-return price of symbol {@code s} on day {@code i}; NaN when unavailable/ineligible. */
    public double adj(int s, int i) {
        return adj[s][i];
    }

    /** Split-adjusted price (regime MA / drawdown detection); NaN when unavailable. */
    public double close(int s, int i) {
        return close[s][i];
    }

    /** Indices of each completed calendar month's last trading day — the rebalance clock. */
    public int[] monthEndIdx() {
        return monthEndIdx;
    }

    /** Gaps longer than the forward-fill window, for the report's missing-data log. */
    public List<Gap> gaps() {
        return gaps;
    }

    /** First day index with a real (or validly filled) price for symbol {@code s}; -1 if none. */
    public int firstValid(int s) {
        for (int i = 0; i < dates.length; i++) if (!Double.isNaN(adj[s][i])) return i;
        return -1;
    }
}
