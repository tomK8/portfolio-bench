package com.portfolio.domain.momentum;

import com.portfolio.domain.swing.Stats;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Momentum-crash diagnostics, reported separately because a single crash rebound can erase years
 * of edge that a long-window mean hides.
 *
 * <p><b>Worst months:</b> the strategy's three worst calendar months with the regime index's
 * (QQQ's) same-month return alongside — "what did the market do while we bled".
 *
 * <p><b>Crash setups:</b> every month-end where the index closes ≥ {@code crashDrawdownPct}%
 * below its running high (split-adjusted close, running high from all history up to that bar —
 * no look-ahead) contributes the strategy's compounded return over the following
 * {@code crashWindowMonths} months. This is the classic momentum-crash configuration: the sharp
 * rebound off a bear-market low, when the portfolio is loaded with whatever held up. Windows that
 * run past the section's last month are censored, counted, never dropped or truncated. Contiguous
 * qualifying month-ends are one <em>episode</em> — overlapping windows from a single bear market
 * are not independent evidence.
 */
public final class CrashDiagnostics {

    public record WorstMonth(String month, double strategyReturn, Double indexReturn) {
    }

    public record Result(List<WorstMonth> worstMonths, int setupMonths, int episodes,
                         int windows, int censored, Stats.Dist windowReturns) {
    }

    private CrashDiagnostics() {
    }

    /**
     * @param months        the variant's monthly returns over the section (key "YYYY-MM")
     * @param indexMonths   the regime index's monthly returns (same keys)
     * @param s             the panel, for the index close series and month-end clock
     */
    public static Result compute(MomentumSeries s, MomentumConfig cfg,
                                 Map<String, Double> months, Map<String, Double> indexMonths) {
        List<WorstMonth> worst = months.entrySet().stream()
                .sorted(Comparator.comparingDouble(Map.Entry::getValue))
                .limit(3)
                .map(e -> new WorstMonth(e.getKey(), e.getValue(), indexMonths.get(e.getKey())))
                .toList();

        int ri = s.indexOf(cfg.regimeSymbol());
        List<Double> windowRets = new ArrayList<>();
        int setups = 0, episodes = 0, censored = 0;
        boolean inEpisode = false;
        if (ri >= 0) {
            double runningHigh = Double.NaN;
            double threshold = 1 - cfg.crashDrawdownPct() / 100.0;
            for (int i : s.monthEndIdx()) {
                double px = s.close(ri, i);
                if (Double.isNaN(px)) continue;
                if (Double.isNaN(runningHigh) || px > runningHigh) runningHigh = px;
                String monthKey = YearMonth.from(s.dateAt(i)).toString();
                if (!months.containsKey(monthKey)) continue;   // outside this section
                boolean setup = px <= runningHigh * threshold;
                if (!setup) {
                    inEpisode = false;
                    continue;
                }
                setups++;
                if (!inEpisode) episodes++;
                inEpisode = true;

                // Strategy return over the following crashWindowMonths months.
                YearMonth from = YearMonth.from(s.dateAt(i)).plusMonths(1);
                double factor = 1;
                boolean complete = true;
                for (int k = 0; k < cfg.crashWindowMonths(); k++) {
                    Double r = months.get(from.plusMonths(k).toString());
                    if (r == null) {
                        complete = false;
                        break;
                    }
                    factor *= 1 + r;
                }
                if (complete) windowRets.add(factor - 1);
                else censored++;
            }
        }
        double[] arr = windowRets.stream().mapToDouble(Double::doubleValue).toArray();
        return new Result(worst, setups, episodes, arr.length, censored, Stats.dist(arr));
    }
}
