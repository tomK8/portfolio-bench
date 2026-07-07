package com.portfolio.domain.trend;

import java.util.ArrayList;
import java.util.List;

/**
 * Calendar-year strategy-vs-buy-and-hold table. Year boundaries are each year's last trading bar;
 * the first year runs from the analysis start and the last to the data edge — {@code partial}
 * flags both so a stub year is never read as a full one. {@code timeInvested} is the mean
 * exposure over the year's bars; {@code switches} counts fills dated in the year.
 */
public final class TrendAnnual {

    public record YearRow(int year, boolean partial, double strategyReturn, double bhReturn,
                          double timeInvested, int switches) {
    }

    private TrendAnnual() {
    }

    public static List<YearRow> rows(TrendSeries s, Backtester.Result strategy, Backtester.Result bh) {
        List<YearRow> out = new ArrayList<>();
        int start = strategy.start(), end = s.size() - 1;
        int yearStartBase = start;   // bar the year's return measures from
        for (int i = start; i <= end; i++) {
            boolean lastOfYear = i == end || s.dates[i + 1].getYear() != s.dates[i].getYear();
            if (!lastOfYear) continue;

            int year = s.dates[i].getYear();
            double timeIn = 0;
            int bars = 0, switches = 0;
            for (int j = yearStartBase + 1; j <= i; j++) {
                timeIn += strategy.weight()[j];
                bars++;
            }
            for (Backtester.Trade t : strategy.trades()) {
                if (s.dates[t.fillIndex()].getYear() == year) switches++;
            }
            boolean partial = (year == s.dates[start].getYear() && s.dates[start].getMonthValue() > 1)
                    || (i == end && s.dates[end].getMonthValue() < 12);
            out.add(new YearRow(year, partial,
                    strategy.equity()[i] / strategy.equity()[yearStartBase] - 1,
                    bh.equity()[i] / bh.equity()[yearStartBase] - 1,
                    bars == 0 ? Double.NaN : timeIn / bars, switches));
            yearStartBase = i;
        }
        return out;
    }
}
