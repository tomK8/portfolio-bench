package com.portfolio.domain.trend;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TrendAnnualTest {

    @Test
    void yearsSplitAtTheLastTradingBarWithPartialFlags() {
        // Weekday bars from Mon 2025-12-29: three 2025 bars, then 2026 (the synthetic calendar
        // has no holidays, so 2026-01-01 trades).
        TrendSeries s = TrendSeries.of(
                TrendConfig.builder().maLengths(new int[]{2}).monthlyMaLengths(new int[]{2})
                        .flatCashRatePct(0).build(),
                TrendSeriesTest.bars("QQQ", LocalDate.parse("2025-12-29"), 1.0, 1.0,
                        100, 102, 104, 104, 130, 143),
                null, null);
        boolean[] target = {true, true, true, false, false, true};
        Backtester.Params p = new Backtester.Params(Backtester.Execution.CLOSE, 0, 0, s.cashDaily);
        Backtester.Result strat = Backtester.run(s, target, 0, p);
        Backtester.Result bh = Backtester.run(s, Backtester.buyAndHold(s.size()), 0, p);

        List<TrendAnnual.YearRow> rows = TrendAnnual.rows(s, strat, bh);

        assertEquals(2, rows.size());
        TrendAnnual.YearRow y2025 = rows.get(0);
        assertEquals(2025, y2025.year());
        assertTrue(y2025.partial(), "starts in December");
        assertEquals(0.04, y2025.strategyReturn(), 1e-12);
        assertEquals(0.04, y2025.bhReturn(), 1e-12);
        assertEquals(1.0, y2025.timeInvested(), 1e-12);

        TrendAnnual.YearRow y2026 = rows.get(1);
        assertTrue(y2026.partial(), "ends in January");
        // Strategy: in for the flat day (104→104)... exited at close of it? target[3]=false fills
        // at close of bar 3 (return into bar 3 still at weight 1), misses +25% and +10%.
        assertEquals(0.0, y2026.strategyReturn(), 1e-12);
        assertEquals(143.0 / 104 - 1, y2026.bhReturn(), 1e-12);
        assertEquals(2, y2026.switches(), "exit at bar 3 close and re-entry at bar 5 close");
        // Exposure over 2026's three bars: day 3 (into flat) w=1, day 4 w=0, day 5 w=0 → 1/3.
        assertEquals(1.0 / 3, y2026.timeInvested(), 1e-12);
    }
}
