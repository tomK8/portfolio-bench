package com.portfolio.domain;

import com.portfolio.domain.StyleClassifier.Classification;
import com.portfolio.domain.StyleClassifier.Metrics;
import com.portfolio.domain.StyleClassifier.Position;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StyleClassifierTest {

    /** The shipped defaults, so the tests exercise the thresholds the app actually runs with. */
    private static StyleClassifier classifier() {
        Properties p = new Properties();
        p.setProperty("override.PHGP", "NON_EQUITY");
        return new StyleClassifier(p);
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static Metrics.Builder m() {
        return new Metrics.Builder();
    }

    private static Classification classify(Metrics metrics) {
        return classifier().classify(new Position("TEST", d("1000"), metrics, false));
    }

    // ---- the 2x2 ------------------------------------------------------------------------

    @Test
    void cheapAndSlowIsValue() {
        // A European bank: single-digit P/E, big yield, no growth to speak of.
        Classification c = classify(m().pe("9.1", "10.8").divYield("0.0521")
                .evEbitda("7.0").ps("2.3")
                .revGrowth("0.041").epsGrowth("0.02").build());
        assertEquals(StyleBucket.VALUE, c.bucket());
        assertTrue(c.valueScore().compareTo(d("0.5")) >= 0);
        assertTrue(c.growthScore().compareTo(d("0.5")) < 0);
    }

    @Test
    void expensiveAndFastIsGrowth() {
        Classification c = classify(m().pe("66.8", "39.5").divYield(null)
                .evEbitda("40.0").ps("24.4")
                .revGrowth("0.377").epsGrowth("0.357").build());
        assertEquals(StyleBucket.GROWTH, c.bucket());
    }

    @Test
    void cheapAndFastIsValueGrowth() {
        // The cell that a one-dimensional value-to-growth spectrum cannot express at all.
        Classification c = classify(m().pe("20.1", "10.2").divYield("0.048")
                .evEbitda("4.5").ps("0.4")
                .revGrowth("0.482").epsGrowth("1.389").build());
        assertEquals(StyleBucket.VALUE_GROWTH, c.bucket());
    }

    @Test
    void expensiveAndSlowIsNeither() {
        Classification c = classify(m().pe("30.6", "23.7").divYield(null)
                .evEbitda("22.0").ps("10.4")
                .revGrowth("0.125").epsGrowth("0.08").build());
        assertEquals(StyleBucket.NEITHER, c.bucket());
    }

    // ---- the data landmines this class exists to survive --------------------------------

    @Test
    void missingDividendYieldCountsAsZeroNotAsAbsent() {
        // Yahoo omits the field entirely for a non-payer. Reading that as "no data" would shrink
        // the denominator and hand every growth stock a free pass on the value axis.
        Metrics base = m().pe("32.1", "30.0").evEbitda("25.0").ps("9.5")
                .revGrowth("0.164").epsGrowth("0.287").build();
        Classification noYield = classify(base);

        Metrics withZero = m().pe("32.1", "30.0").divYield("0.0").evEbitda("25.0").ps("9.5")
                .revGrowth("0.164").epsGrowth("0.287").build();
        Classification explicitZero = classify(withZero);

        assertEquals(explicitZero.valueScore(), noYield.valueScore(),
                "an omitted yield must score the same as an explicit zero");

        // And it genuinely votes: the same name paying a real dividend must score strictly higher.
        Classification paying = classify(m().pe("32.1", "30.0").divYield("0.04")
                .evEbitda("25.0").ps("9.5").revGrowth("0.164").epsGrowth("0.287").build());
        assertTrue(paying.valueScore().compareTo(noYield.valueScore()) > 0,
                "the yield must be in the denominator, not skipped");
    }

    @Test
    void negativeForwardPeDoesNotManufacturePeCompression() {
        // A loss-making biotech: trailing null, forward negative. Dividing by a negative forward
        // multiple would read as enormous "compression" and label it a growth stock.
        Classification c = classify(m().pe(null, "-13.2").divYield(null)
                .evEbitda("30.0").ps("11.4")
                .revGrowth("0.021").epsGrowth("0.01").build());
        assertFalse(c.growthFlags().contains("P/E compression"));
        assertEquals(StyleBucket.NEITHER, c.bucket());
    }

    @Test
    void aLossMakerWithOnlyOneGrowthInputIsUnknownNotNeither() {
        // Real Moderna: no trailing P/E, negative forward, no earnings-growth figure. Only revenue
        // growth votes, so the growth axis is genuinely unmeasured. Calling that "neither" would
        // dress an absence of data as a finding -- it belongs in the surfaced UNKNOWN list.
        Classification c = classify(m().pe(null, "-13.2").evEbitda("30.0").ps("11.4")
                .revGrowth("0.021").build());
        assertEquals(StyleBucket.UNKNOWN, c.bucket());
        assertTrue(c.note().contains("1 growth"));
    }

    @Test
    void negativeForwardPeIsNotTreatedAsCheap() {
        Classification c = classify(m().pe(null, "-13.2").divYield(null)
                .evEbitda("30.0").ps("11.4")
                .revGrowth("0.021").epsGrowth("0.01").build());
        assertFalse(c.valueFlags().contains("Forward P/E"),
                "a negative multiple is not a low multiple");
    }

    @Test
    void cyclicalWithFlatRevenueIsNotGrowth() {
        // A recovering bank: flat top line, but a huge single-quarter EPS jump AND the P/E
        // compression that always accompanies one. At the original weights (0.5 + 0.7 = 1.2 of a
        // 2.2 denominator) those two soft signals outvoted flat revenue and scored 0.545 -- a
        // growth label for a value stock. They now sum to 0.9 of 1.9 and cannot.
        Classification c = classify(m().pe("15.0", "12.1").divYield("0.02")
                .evEbitda("12.0").ps("3.9")
                .revGrowth("0.05").epsGrowth("3.41").build());
        assertTrue(c.growthScore().compareTo(d("0.5")) < 0,
                "earnings growth alone must not clear the growth threshold");
        assertEquals(StyleBucket.NEITHER, c.bucket());
    }

    @Test
    void tooFewMetricsIsUnknownWithAReason() {
        Classification c = classify(m().pe("20.0", null).build());
        assertEquals(StyleBucket.UNKNOWN, c.bucket());
        assertNull(c.valueScore());
        assertNotNull(c.note());
        assertTrue(c.note().contains("Too few"));
    }

    // ---- resolution order ----------------------------------------------------------------

    @Test
    void bondsAreFixedIncomeWhateverTheMetricsSay() {
        Classification c = classifier().classify(new Position(
                "GILT 4.75% 2038", d("95700"), m().pe("5.0", "5.0").divYield("0.09").build(), false));
        assertEquals(StyleBucket.FIXED_INCOME, c.bucket());
        assertNull(c.valueScore());
    }

    @Test
    void overrideBeatsTheFeed() {
        Classification c = classifier().classify(new Position(
                "PHGP", d("5000"), Metrics.empty(), false));
        assertEquals(StyleBucket.NON_EQUITY, c.bucket());
    }

    @Test
    void bondIsCheckedBeforeOverride() {
        // Ordering guard: a gilt must not need an override entry to be placed.
        Classification c = classifier().classify(new Position(
                "GILT 0.875% 2033", d("1"), Metrics.empty(), false));
        assertEquals(StyleBucket.FIXED_INCOME, c.bucket());
    }

    // ---- funds -----------------------------------------------------------------------------

    @Test
    void fundIsPlacedOnItsAggregatePe() {
        Classification cheap = classifier().classify(new Position(
                "MCTS", d("4606"), m().fund("14.0", "2.8", "1.6").build(), true));
        assertEquals(StyleBucket.FUND_VALUE, cheap.bucket());

        Classification mid = classifier().classify(new Position(
                "SPX5", d("11526"), m().fund("26.9", "5.4", "3.7").build(), true));
        assertEquals(StyleBucket.FUND_BLEND, mid.bucket());

        Classification dear = classifier().classify(new Position(
                "EQQQ", d("217792"), m().fund("29.4", "8.33", "6.11").build(), true));
        assertEquals(StyleBucket.FUND_GROWTH, dear.bucket());
    }

    @Test
    void fundNeverGetsAGrowthScore() {
        // Yahoo returns null for a fund's earnings growth, so there is no growth axis to score.
        // Inferring one from the price multiples would turn missing data into a finding.
        Classification c = classifier().classify(new Position(
                "EQQQ", d("1000"), m().fund("29.4", "8.33", "6.11").build(), true));
        assertNull(c.growthScore());
        assertNull(c.valueScore());
        assertTrue(c.valueFlags().contains("P/E 29.4"));
    }

    @Test
    void fundWithNoPublishedMultipleIsUnknown() {
        Classification c = classifier().classify(new Position(
                "RBTX", d("4908"), Metrics.empty(), true));
        assertEquals(StyleBucket.UNKNOWN, c.bucket());
        assertNotNull(c.note());
    }

    // ---- income overlay ----------------------------------------------------------------------

    @Test
    void incomeCutsAcrossBuckets() {
        Classification value = classify(m().pe("9.1", "10.8").divYield("0.0521")
                .evEbitda("7.0").ps("2.3").revGrowth("0.04").epsGrowth("0.02").build());
        assertEquals(StyleBucket.VALUE, value.bucket());
        assertTrue(value.income());

        // BP: cheap AND growing, and still an income holding. Income is not a synonym for value.
        Classification both = classify(m().pe("20.1", "10.2").divYield("0.048")
                .evEbitda("4.5").ps("0.4").revGrowth("0.482").epsGrowth("1.389").build());
        assertEquals(StyleBucket.VALUE_GROWTH, both.bucket());
        assertTrue(both.income());
    }

    @Test
    void nonPayerIsNotIncome() {
        Classification c = classify(m().pe("66.8", "39.5").evEbitda("40.0").ps("24.4")
                .revGrowth("0.377").epsGrowth("0.357").build());
        assertFalse(c.income());
    }

    @Test
    void scoresAreBoundedToTheUnitInterval() {
        Classification c = classify(m().pe("5.0", "4.0").divYield("0.09")
                .evEbitda("2.0").ps("0.2")
                .revGrowth("0.9").epsGrowth("0.9").fcfGrowth("0.9").build());
        assertEquals(0, c.valueScore().compareTo(BigDecimal.ONE));
        assertTrue(c.growthScore().compareTo(BigDecimal.ONE) <= 0);
    }

}
