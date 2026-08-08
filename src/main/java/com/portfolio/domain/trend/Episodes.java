package com.portfolio.domain.trend;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The named-episode table — where the decision actually gets made. The case for the filter lives
 * in 2000–02 / 2008 / 2022; the case against lives in 2015–16 and COVID; both must be equally
 * visible. Episodes that start before the analysis window are flagged {@code covered = false}
 * (their stats run over the available overlap when one exists, or stay null) — never silently
 * dropped or silently truncated.
 *
 * <p>Returns are measured from the last bar <em>before</em> the episode window (entry base at
 * the prior close) to the last bar inside it; drawdowns restart their peak at the base bar, so
 * an episode's max drawdown is self-contained rather than inherited from earlier highs.
 */
public final class Episodes {

    public record Episode(String name, LocalDate from, LocalDate to) {   // to == null → data end
    }

    public static final List<Episode> NAMED = List.of(
            new Episode("Dot-com bust", LocalDate.parse("2000-03-10"), LocalDate.parse("2002-10-09")),
            new Episode("GFC", LocalDate.parse("2007-10-31"), LocalDate.parse("2009-03-09")),
            new Episode("2011 correction", LocalDate.parse("2011-04-29"), LocalDate.parse("2011-10-03")),
            new Episode("2015–16 chop", LocalDate.parse("2015-06-01"), LocalDate.parse("2016-06-30")),
            new Episode("Q4 2018", LocalDate.parse("2018-09-30"), LocalDate.parse("2018-12-24")),
            new Episode("COVID", LocalDate.parse("2020-02-19"), LocalDate.parse("2020-08-31")),
            new Episode("2022 bear", LocalDate.parse("2022-01-03"), LocalDate.parse("2022-12-28")),
            new Episode("2023–26 melt-up", LocalDate.parse("2023-01-01"), null));

    /**
     * {@code covered} is true only when the analysis window spans the whole episode. {@code
     * daysOut} counts trading days at reduced exposure. Nullable stats mean "no overlap at all".
     */
    public record Row(String name, String from, String to, boolean covered,
                      Double strategyReturn, Double bhReturn,
                      Double strategyMaxDd, Double bhMaxDd,
                      Integer switches, Integer daysOut) {
    }

    private Episodes() {
    }

    public static List<Row> rows(TrendSeries s, Backtester.Result strategy, Backtester.Result bh) {
        return rows(s, strategy, bh, NAMED);
    }

    static List<Row> rows(TrendSeries s, Backtester.Result strategy, Backtester.Result bh,
                          List<Episode> episodes) {   // package-private for tests
        List<Row> out = new ArrayList<>();
        int start = strategy.start(), end = s.size() - 1;
        for (Episode ep : episodes) {
            LocalDate to = ep.to() != null ? ep.to() : s.dates[end];
            boolean covered = !s.dates[start].isAfter(ep.from()) && !s.dates[end].isBefore(to);

            // Base = last bar strictly before the window (floored at the analysis start).
            int base = start, last = -1;
            for (int i = start; i <= end; i++) {
                if (s.dates[i].isBefore(ep.from())) base = i;
                if (!s.dates[i].isAfter(to)) last = i;
                else break;
            }
            if (last <= base) {   // no bars inside the window
                out.add(new Row(ep.name(), ep.from().toString(), to.toString(), false,
                        null, null, null, null, null, null));
                continue;
            }

            int switches = 0, daysOut = 0;
            for (Backtester.Trade t : strategy.trades()) {
                if (t.fillIndex() > base && t.fillIndex() <= last) switches++;
            }
            for (int i = base + 1; i <= last; i++) {
                if (strategy.weight()[i] < 1.0) daysOut++;
            }
            out.add(new Row(ep.name(), ep.from().toString(), to.toString(), covered,
                    ret(strategy.equity(), base, last), ret(bh.equity(), base, last),
                    maxDd(strategy.equity(), base, last), maxDd(bh.equity(), base, last),
                    switches, daysOut));
        }
        return out;
    }

    private static double ret(double[] equity, int base, int last) {
        return equity[last] / equity[base] - 1;
    }

    private static double maxDd(double[] equity, int base, int last) {
        double peak = equity[base], dd = 0;
        for (int i = base + 1; i <= last; i++) {
            if (equity[i] >= peak) peak = equity[i];
            else dd = Math.min(dd, equity[i] / peak - 1);
        }
        return dd;
    }
}
