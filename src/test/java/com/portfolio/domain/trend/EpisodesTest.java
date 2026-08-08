package com.portfolio.domain.trend;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EpisodesTest {

    private static TrendSeries series(double... closes) {
        return TrendSeries.of(
                TrendConfig.builder().maLengths(new int[]{2}).monthlyMaLengths(new int[]{2})
                        .flatCashRatePct(0).build(),
                TrendSeriesTest.bars("QQQ", LocalDate.parse("2026-01-05"), 1.0, 1.0, closes),
                null, null);
    }

    @Test
    void coveredEpisodeMeasuresFromThePriorCloseWithSelfContainedDrawdown() {
        // Bars: Jan 5..9 (Mon–Fri). Episode Jan 7–8: base = Jan 6 (prior close), last = Jan 8.
        TrendSeries s = series(100, 110, 99, 121, 130);
        boolean[] target = {true, true, false, true, true};   // exit fills at close Jan 7, re-enter Jan 8
        Backtester.Result strat = Backtester.run(s, target, 0,
                new Backtester.Params(Backtester.Execution.CLOSE, 0, 0, s.cashDaily));
        Backtester.Result bh = Backtester.run(s, Backtester.buyAndHold(s.size()), 0,
                new Backtester.Params(Backtester.Execution.CLOSE, 0, 0, s.cashDaily));

        List<Episodes.Row> rows = Episodes.rows(s, strat, bh, List.of(new Episodes.Episode(
                "test", LocalDate.parse("2026-01-07"), LocalDate.parse("2026-01-08"))));

        Episodes.Row row = rows.get(0);
        assertTrue(row.covered());
        // Strategy: rides 110→99 (in), then out for the +22% day → 99/110 − 1.
        assertEquals(99.0 / 110 - 1, row.strategyReturn(), 1e-12);
        assertEquals(121.0 / 110 - 1, row.bhReturn(), 1e-12);
        // Drawdown restarts at the base bar: both see the 110→99 leg.
        assertEquals(99.0 / 110 - 1, row.strategyMaxDd(), 1e-12);
        assertEquals(99.0 / 110 - 1, row.bhMaxDd(), 1e-12);
        assertEquals(2, row.switches(), "exit and re-entry both fill inside the window");
        assertEquals(1, row.daysOut());
    }

    @Test
    void episodeStartingBeforeTheDataIsFlaggedNotCoveredButUsesTheOverlap() {
        TrendSeries s = series(100, 110, 99, 121, 130);
        Backtester.Result bh = Backtester.run(s, Backtester.buyAndHold(s.size()), 0,
                new Backtester.Params(Backtester.Execution.CLOSE, 0, 0, s.cashDaily));

        List<Episodes.Row> rows = Episodes.rows(s, bh, bh, List.of(new Episodes.Episode(
                "starts too early", LocalDate.parse("2025-06-01"), LocalDate.parse("2026-01-07"))));

        Episodes.Row row = rows.get(0);
        assertFalse(row.covered());
        assertEquals(99.0 / 100 - 1, row.bhReturn(), 1e-12, "measured over the available overlap");
    }

    @Test
    void episodeWithNoOverlapKeepsNullStats() {
        TrendSeries s = series(100, 110, 99, 121, 130);
        Backtester.Result bh = Backtester.run(s, Backtester.buyAndHold(s.size()), 0,
                new Backtester.Params(Backtester.Execution.CLOSE, 0, 0, s.cashDaily));
        List<Episodes.Row> rows = Episodes.rows(s, bh, bh, List.of(new Episodes.Episode(
                "future", LocalDate.parse("2030-01-01"), LocalDate.parse("2030-06-01"))));
        Episodes.Row row = rows.get(0);
        assertFalse(row.covered());
        assertNull(row.strategyReturn());
        assertNull(row.switches());
    }

    @Test
    void namedEpisodesOutsideASyntheticSeriesAreNotCovered() {
        TrendSeries s = series(100, 110, 99, 121, 130);
        Backtester.Result bh = Backtester.run(s, Backtester.buyAndHold(s.size()), 0,
                new Backtester.Params(Backtester.Execution.CLOSE, 0, 0, s.cashDaily));
        List<Episodes.Row> rows = Episodes.rows(s, bh, bh);
        assertEquals(Episodes.NAMED.size(), rows.size(), "every named episode appears, covered or not");
        assertTrue(rows.stream().noneMatch(r -> r.covered() && r.name().equals("GFC")));
    }
}
