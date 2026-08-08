package com.portfolio.domain.swing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StatsTest {

    @Test
    void meanAndStdevIgnoreNaN() {
        double[] a = {Double.NaN, 2, 4, Double.NaN};
        assertEquals(3.0, Stats.mean(a), 1e-12);
        assertEquals(2, Stats.count(a));
        // sample stdev of {2,4}: sqrt(((2-3)^2+(4-3)^2)/(2-1)) = sqrt(2)
        assertEquals(Math.sqrt(2.0), Stats.stdevSample(a), 1e-12);
    }

    @Test
    void medianOddAndEven() {
        assertEquals(2.0, Stats.median(new double[]{3, 1, 2}), 1e-12);
        assertEquals(2.5, Stats.median(new double[]{1, 2, 3, 4}), 1e-12);
    }

    @Test
    void fractionPositiveAndAtMost() {
        assertEquals(0.5, Stats.fractionPositive(new double[]{-1, 0, 2, 3}), 1e-12);
        // ≤ 0 over {-1,0,1} = 2/3
        assertEquals(2.0 / 3.0, Stats.fractionAtMost(new double[]{-1, 0, 1}, 0.0), 1e-12);
    }

    @Test
    void distMatchesLinearInterpolationQuartiles() {
        Stats.Dist d = Stats.dist(new double[]{1, 2, 3, 4});
        assertEquals(4, d.n());
        assertEquals(1.0, d.min(), 1e-12);
        assertEquals(4.0, d.max(), 1e-12);
        assertEquals(2.5, d.median(), 1e-12);
        assertEquals(2.5, d.mean(), 1e-12);
        assertEquals(1.75, d.q1(), 1e-12);   // 0.25*(n-1)=0.75 → 1*0.25 + 2*0.75
        assertEquals(3.25, d.q3(), 1e-12);   // 0.75*(n-1)=2.25 → 3*0.75 + 4*0.25
    }

    @Test
    void emptySamplesAreNaN() {
        assertTrue(Double.isNaN(Stats.mean(new double[]{Double.NaN})));
        assertTrue(Double.isNaN(Stats.median(new double[]{})));
        assertEquals(0, Stats.dist(new double[]{}).n());
    }
}
