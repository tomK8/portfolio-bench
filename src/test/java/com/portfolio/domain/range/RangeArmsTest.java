package com.portfolio.domain.range;

import com.portfolio.domain.range.RangeBacktester.ExitType;
import com.portfolio.domain.range.RangeBacktester.Trade;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RangeArmsTest {

    /**
     * Stamp duty on a synthetic UK round trip: £10,000 at 0.5% stamp + 10 bps commission each
     * way, +10% TP. Stamp = £50 (purchase only); commission = £10 in + £11 out on the £11,000
     * proceeds; final wealth = 10,000 − 10,060 + 10,989 = £10,929.
     */
    @Test
    void stampDutyOnSyntheticUkRoundTrip() {
        RangeSeries s = RangeSeriesTest.series(100, 110);
        LocalDate d0 = s.dateAt(0);
        LocalDate d1 = s.dateAt(1);
        Trade trade = new Trade(0, d0, 100.0, 100.0, Double.NaN, ExitType.TP, 1, d1,
                110.0, 0.10, 0.10, 0.0, 0, 1, 1);
        RangeArms.NameInput in = new RangeArms.NameInput(
                s, List.of(trade), 0.005, RangeArms.FxLookup.IDENTITY);

        RangeArms.ArmOutcome out = RangeArms.strategyArm(in, 10_000, 0.001, 0.0, Double.NaN);

        assertEquals(50.0, out.stampPaid(), 1e-9);
        assertEquals(21.0, out.commissionPaid(), 1e-9);   // 10 in + 11 out
        assertEquals(10_929.0, out.finalWealth(), 1e-9);
        assertEquals(929.0, out.tradePnl(), 1e-9);
        assertEquals(0.0, out.rfEarned(), 1e-9);
        assertEquals(1, out.purchases());
        assertEquals(0, out.openPositions());
    }

    /** Buy-and-hold pays purchase costs once and marks the end without an exit cost. */
    @Test
    void buyHoldArmMarksEndWithoutExitCost() {
        RangeSeries s = RangeSeriesTest.series(100, 110);
        Trade trade = new Trade(0, s.dateAt(0), 100.0, 100.0, Double.NaN, ExitType.TP, 1,
                s.dateAt(1), 110.0, 0.10, 0.10, 0.0, 0, 1, 1);
        RangeArms.NameInput in = new RangeArms.NameInput(
                s, List.of(trade), 0.005, RangeArms.FxLookup.IDENTITY);

        RangeArms.ArmOutcome out = RangeArms.buyHoldArm(in, 10_000, 0.001, 0.0);

        assertEquals(50.0, out.stampPaid(), 1e-9);
        assertEquals(10.0, out.commissionPaid(), 1e-9);   // purchase side only
        assertEquals(11_000.0 - 60.0, out.finalWealth(), 1e-9);
    }
}
