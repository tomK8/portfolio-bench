package com.portfolio.domain.trend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The cost side of the whole trade-off, made explicit. For each completed round trip (exit →
 * re-entry) compare being out against having stayed in over the same window, in total-return
 * terms:
 *
 * <pre>tripFactor = [(1 + cash earned while out) × (1 − c)²] / (re-entryAdj / exitAdj)</pre>
 *
 * A trip with {@code tripFactor < 1} lost money versus staying put. The spec's whipsaw count is
 * price-based — trips whose re-entry fill exceeded the exit fill ("bought back higher") — and
 * {@code whipsawDrag} compounds only those trips' factors, so the two headline numbers answer
 * "how often did the filter buy back higher, and what did those trips cost in total?".
 * {@code netTimingEffect} compounds <em>every</em> completed trip — positive means the exits as
 * a whole added value (the 2008-style saves outweighing the whipsaws).
 *
 * <p>Computed on full-switch runs (the headline grid); a half-switch whipsaw is the same story
 * scaled by the moved fraction. An open exit at the data edge is excluded (censored, like §6's
 * Open bucket in the swing module — never counted as an outcome).
 */
public final class Whipsaw {

    /**
     * @param roundTrips       completed exit→re-entry pairs
     * @param boughtBackHigher trips whose re-entry TR fill exceeded the exit TR fill
     * @param whipsawDrag      compounded return effect of only those trips (usually negative)
     * @param netTimingEffect  compounded effect of all completed trips vs staying invested
     * @param perDecade        the same accounting grouped by exit-date decade
     */
    public record Result(int roundTrips, int boughtBackHigher, double whipsawDrag,
                         double netTimingEffect, List<DecadeDrag> perDecade) {
    }

    public record DecadeDrag(String decade, int trips, int boughtBackHigher, double whipsawDrag,
                             double netTimingEffect) {
    }

    private Whipsaw() {
    }

    public static Result compute(TrendSeries s, Backtester.Result r, double costPerSide) {
        record Acc(int[] trips, int[] higher, double[] whipsaw, double[] net) {
            Acc() { this(new int[]{0}, new int[]{0}, new double[]{1}, new double[]{1}); }
        }
        Acc total = new Acc();
        Map<String, Acc> decades = new LinkedHashMap<>();

        Backtester.Trade exit = null;
        for (Backtester.Trade t : r.trades()) {
            if (!t.entering()) {
                exit = t;
                continue;
            }
            if (exit == null) continue;   // leading entry: the run simply started out of the market
            double stayedIn = t.fillAdj() / exit.fillAdj();
            double cash = 1;   // historical cash leg — whipsaw is reported for the headline run
            for (int j = exit.fillIndex() + 1; j <= t.fillIndex(); j++) cash *= 1 + s.cashDaily[j];
            double tripFactor = cash * (1 - costPerSide) * (1 - costPerSide) / stayedIn;
            boolean higher = t.fillAdj() > exit.fillAdj();

            String decade = (s.dates[exit.fillIndex()].getYear() / 10 * 10) + "s";
            for (Acc a : List.of(total, decades.computeIfAbsent(decade, d -> new Acc()))) {
                a.trips()[0]++;
                a.net()[0] *= tripFactor;
                if (higher) {
                    a.higher()[0]++;
                    a.whipsaw()[0] *= tripFactor;
                }
            }
            exit = null;
        }

        List<DecadeDrag> perDecade = new ArrayList<>();
        decades.forEach((d, a) -> perDecade.add(new DecadeDrag(
                d, a.trips()[0], a.higher()[0], a.whipsaw()[0] - 1, a.net()[0] - 1)));
        return new Result(total.trips()[0], total.higher()[0],
                total.whipsaw()[0] - 1, total.net()[0] - 1, perDecade);
    }
}
