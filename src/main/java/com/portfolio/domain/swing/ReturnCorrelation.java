package com.portfolio.domain.swing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * §7 — return-correlation regime for the semi cluster. Answers "is this one factor?" and "does
 * correlation spike in sell-offs?".
 *
 * <p>Correlations are Pearson over daily <em>simple</em> returns, computed <b>pairwise-complete</b>
 * (each pair over its own common dates) rather than on a shared grid — essential here because the
 * cluster mixes long histories with recent listings (e.g. SNDK), and an intersection grid would
 * collapse to almost nothing.
 *
 * <ul>
 *   <li>Latest rolling matrices at each configured window (30d/60d) — the pair correlation over
 *       its most recent {@code w} common returns.</li>
 *   <li>Average pairwise correlation as a time series (both windows), sampled weekly.</li>
 *   <li>A down-day matrix over the tail: days where the cluster's mean simple return ≤
 *       −{@code clusterDownDay} — the correlation that actually bites when everything sells off.</li>
 * </ul>
 *
 * <p>Cells with fewer than two common observations render as {@code null}.
 */
public final class ReturnCorrelation {

    private static final int SCALE = 4;
    private static final int SERIES_TARGET_POINTS = 250;   // cap the weekly time series

    private ReturnCorrelation() {
    }

    public static Result compute(List<SwingSeries> clusterSeries, SwingConfig cfg) {
        // Per-symbol date→simple-return maps (finite only), preserving cluster order.
        List<String> symbols = new ArrayList<>();
        List<NavigableMap<LocalDate, Double>> rets = new ArrayList<>();
        for (SwingSeries s : clusterSeries) {
            if (s == null) continue;
            NavigableMap<LocalDate, Double> m = new TreeMap<>();
            for (int i = 1; i < s.size(); i++) {
                if (!Double.isNaN(s.simpleRet[i])) m.put(s.dates[i], s.simpleRet[i]);
            }
            if (m.size() >= 2) {
                symbols.add(s.symbol);
                rets.add(m);
            }
        }
        int[] windows = cfg.corrWindows();
        int w30 = windows.length > 0 ? windows[0] : 30;
        int w60 = windows.length > 1 ? windows[1] : 60;

        List<List<BigDecimal>> matrix30 = matrix(rets, null, w30);
        List<List<BigDecimal>> matrix60 = matrix(rets, null, w60);

        // Down-day set: cluster mean simple return ≤ −threshold.
        TreeSet<LocalDate> allDates = new TreeSet<>();
        for (NavigableMap<LocalDate, Double> m : rets) allDates.addAll(m.keySet());
        TreeSet<LocalDate> downDates = new TreeSet<>();
        for (LocalDate d : allDates) {
            double sum = 0;
            int cnt = 0;
            for (NavigableMap<LocalDate, Double> m : rets) {
                Double r = m.get(d);
                if (r != null) { sum += r; cnt++; }
            }
            if (cnt > 0 && sum / cnt <= -cfg.clusterDownDay()) downDates.add(d);
        }
        List<List<BigDecimal>> downMatrix = matrix(rets, downDates, 0);

        List<CorrPoint> avgPairwise = avgPairwiseSeries(allDates, rets, w30, w60);

        return new Result(symbols, w30, w60, matrix30, matrix60,
                avgPairwise, downMatrix, downDates.size(), cfg.clusterDownDay());
    }

    /** Full symmetric matrix; {@code allowed} null → use all common dates, else restrict; {@code lastW} 0 → no window cap. */
    private static List<List<BigDecimal>> matrix(List<NavigableMap<LocalDate, Double>> rets,
                                                 TreeSet<LocalDate> allowed, int lastW) {
        List<List<BigDecimal>> out = new ArrayList<>();
        for (int a = 0; a < rets.size(); a++) {
            List<BigDecimal> row = new ArrayList<>();
            for (int b = 0; b < rets.size(); b++) {
                if (a == b) {
                    row.add(BigDecimal.ONE.setScale(SCALE, RoundingMode.HALF_UP));
                } else {
                    Double c = pairCorr(rets.get(a), rets.get(b), allowed, lastW);
                    row.add(c == null ? null : BigDecimal.valueOf(c).setScale(SCALE, RoundingMode.HALF_UP));
                }
            }
            out.add(row);
        }
        return out;
    }

    private static Double pairCorr(NavigableMap<LocalDate, Double> ma, NavigableMap<LocalDate, Double> mb,
                                   TreeSet<LocalDate> allowed, int lastW) {
        // Iterate the smaller map's keys for the intersection.
        NavigableMap<LocalDate, Double> small = ma.size() <= mb.size() ? ma : mb;
        NavigableMap<LocalDate, Double> big = small == ma ? mb : ma;
        List<Double> as = new ArrayList<>();
        List<Double> bs = new ArrayList<>();
        for (Map.Entry<LocalDate, Double> e : small.entrySet()) {
            if (allowed != null && !allowed.contains(e.getKey())) continue;
            Double other = big.get(e.getKey());
            if (other == null) continue;
            // small may be either a or b — keep a/b assignment stable via identity.
            if (small == ma) { as.add(e.getValue()); bs.add(other); }
            else { as.add(other); bs.add(e.getValue()); }
        }
        int n = as.size();
        if (lastW > 0 && n > lastW) {
            as = as.subList(n - lastW, n);
            bs = bs.subList(n - lastW, n);
        }
        return pearson(as, bs);
    }

    private static List<CorrPoint> avgPairwiseSeries(TreeSet<LocalDate> allDates,
                                                     List<NavigableMap<LocalDate, Double>> rets,
                                                     int w30, int w60) {
        List<LocalDate> dates = new ArrayList<>(allDates);
        List<CorrPoint> out = new ArrayList<>();
        if (dates.isEmpty()) return out;
        int stride = Math.max(1, dates.size() / SERIES_TARGET_POINTS);
        for (int idx = 0; idx < dates.size(); idx += stride) {
            LocalDate t = dates.get(idx);
            Double a30 = avgPairwiseUpTo(rets, t, w30);
            Double a60 = avgPairwiseUpTo(rets, t, w60);
            if (a30 != null || a60 != null) {
                out.add(new CorrPoint(t.toString(), scale(a30), scale(a60)));
            }
        }
        return out;
    }

    private static Double avgPairwiseUpTo(List<NavigableMap<LocalDate, Double>> rets, LocalDate t, int w) {
        double sum = 0;
        int pairs = 0;
        for (int a = 0; a < rets.size(); a++) {
            NavigableMap<LocalDate, Double> headA = rets.get(a).headMap(t, true);
            for (int b = a + 1; b < rets.size(); b++) {
                NavigableMap<LocalDate, Double> mb = rets.get(b);
                List<Double> as = new ArrayList<>();
                List<Double> bs = new ArrayList<>();
                for (Map.Entry<LocalDate, Double> e : headA.entrySet()) {
                    Double other = mb.get(e.getKey());
                    if (other == null || e.getKey().isAfter(t)) continue;
                    as.add(e.getValue());
                    bs.add(other);
                }
                int n = as.size();
                if (n < w) continue;                       // need a full window as of t
                Double c = pearson(as.subList(n - w, n), bs.subList(n - w, n));
                if (c != null) { sum += c; pairs++; }
            }
        }
        return pairs == 0 ? null : sum / pairs;
    }

    private static Double pearson(List<Double> a, List<Double> b) {
        int n = a.size();
        if (n < 2) return null;
        double ma = 0, mb = 0;
        for (int i = 0; i < n; i++) { ma += a.get(i); mb += b.get(i); }
        ma /= n; mb /= n;
        double saa = 0, sbb = 0, sab = 0;
        for (int i = 0; i < n; i++) {
            double da = a.get(i) - ma, db = b.get(i) - mb;
            saa += da * da; sbb += db * db; sab += da * db;
        }
        if (saa <= 0 || sbb <= 0) return null;             // a flat series has no correlation
        return sab / Math.sqrt(saa * sbb);
    }

    private static BigDecimal scale(Double v) {
        return v == null ? null : BigDecimal.valueOf(v).setScale(SCALE, RoundingMode.HALF_UP);
    }

    // ---- DTOs ---------------------------------------------------------------

    /** One point of the average-pairwise-correlation time series (both rolling windows). */
    public record CorrPoint(String date, BigDecimal avg30, BigDecimal avg60) {
    }

    /**
     * §7 result. {@code matrixW} rows/cols follow {@code symbols} order; cells are correlations in
     * [−1,1] or {@code null} when a pair lacks two common observations. {@code downDayMatrix} is the
     * same, restricted to the {@code downDayCount} days where the cluster mean return ≤
     * −{@code downDayThreshold}.
     */
    public record Result(List<String> symbols, int window30, int window60,
                         List<List<BigDecimal>> matrix30, List<List<BigDecimal>> matrix60,
                         List<CorrPoint> avgPairwise,
                         List<List<BigDecimal>> downDayMatrix, int downDayCount, double downDayThreshold) {
    }
}
