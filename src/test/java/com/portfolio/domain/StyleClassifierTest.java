package com.portfolio.domain;

import com.portfolio.domain.StyleClassifier.Classification;
import com.portfolio.domain.StyleClassifier.Metrics;
import com.portfolio.domain.StyleClassifier.Position;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StyleClassifierTest {

    /** The shipped defaults, so the tests exercise the thresholds the app actually runs with. */
    private static StyleClassifier classifier() {
        return new StyleClassifier(props());
    }

    private static Properties props() {
        Properties p = new Properties();
        p.setProperty("override.PHGP", "NON_EQUITY");
        return p;
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
    void lossMakerWithGrowingRevenueIsScoredNotUnknown() {
        // Intel's real shape (2026-08): no trailing P/E and no earningsGrowth, because trailing
        // earnings are negative. That also kills P/E compression, leaving trailing revenue growth
        // alone -- one metric, below min.metrics, so it fell out as UNKNOWN. The two forward
        // estimates restore a scorable axis.
        Classification c = classify(m().pe(null, "47.02")
                .evEbitda("33.16").ps("8.89")
                .revGrowth("0.254").fwdRevGrowth("0.1306").fwdEpsGrowth("0.3485").build());
        assertEquals(StyleBucket.GROWTH, c.bucket());
        // Revenue 1.0 + fwd earnings 0.25 achieved, of 1.0 + 0.5 + 0.25 available.
        assertEquals(0, c.growthScore().compareTo(d("0.7143")), "got " + c.growthScore());
        assertEquals(0, c.valueScore().compareTo(d("0.0")), "expensive on every value test");
    }

    @Test
    void shrinkingLossIsNotForwardGrowth() {
        // Moderna's real shape: flat revenue (+2.1%) and a forward revenue estimate that scrapes
        // over the bar (0.1529 vs 0.15). Yahoo also publishes +42.5% "earnings growth" for it,
        // but that is EPS -8.58 -> -4.91: a shrinking loss off a negative base. The fetcher
        // withholds it (see YahooQuoteSummaryFetcherTest), so it must not vote here -- and
        // without it the name cannot reach the growth threshold on one marginal estimate.
        Classification c = classify(m().pe(null, "-13.05")
                .evEbitda("-9.97").ps("11.45")
                .revGrowth("0.021").fwdRevGrowth("0.1529").build());
        assertEquals(StyleBucket.NEITHER, c.bucket());
        // 0.5 of 1.5 -- the forward estimate alone cannot outvote flat trailing revenue.
        assertEquals(0, c.growthScore().compareTo(d("0.3333")), "got " + c.growthScore());
    }

    @Test
    void forwardEarningsGrowthCannotCarryTheAxisAlone() {
        // The weighting that makes the above hold: even with BOTH forward estimates passing, a
        // name with flat trailing revenue stays under the threshold (0.75 of 2.25). Real top-line
        // growth remains close to necessary, which is the same property
        // cyclicalWithFlatRevenueIsNotGrowth protects on the trailing side.
        Classification c = classify(m().pe("20.0", "18.0").divYield("0.01")
                .evEbitda("14.0").ps("3.0")
                .revGrowth("0.02").fwdRevGrowth("0.30").fwdEpsGrowth("0.40").build());
        assertTrue(c.growthScore().compareTo(d("0.5")) < 0, "got " + c.growthScore());
        assertEquals(StyleBucket.NEITHER, c.bucket());
    }

    @Test
    void forwardEstimatesDoNotUnseatReportedGrowth() {
        // Apple's real shape (2026-08): reported revenue +16.4% and EPS +28.7%, but FY+1 estimates
        // of +9.7% / +8.1% -- analyst estimates for mature large-caps cluster under a bar set for
        // trailing figures. At the first weights tried (0.8/0.4) the two failing estimates
        // outvoted both reported metrics and dropped AAPL and AMZN out of Growth entirely. The
        // estimates must only decide a position whose reported metrics are missing.
        Classification c = classify(m().pe("35.64", "32.65").divYield("0.004")
                .evEbitda("25.0").ps("9.0")
                .revGrowth("0.164").epsGrowth("0.287")
                .fwdRevGrowth("0.0973").fwdEpsGrowth("0.0807").build());
        assertEquals(StyleBucket.GROWTH, c.bucket(),
                "reported double-digit growth must survive two undershooting estimates");
        assertTrue(c.growthScore().compareTo(d("0.5")) >= 0, "got " + c.growthScore());
    }

    @Test
    void absentForwardEstimatesChangeNothing() {
        // Regression guard for the whole book: a name Yahoo covers normally must score exactly
        // as it did before earningsTrend was wired in, because absent metrics abstain rather
        // than diluting the denominator.
        Classification c = classify(m().pe("17.13", "23.17").divYield("0.0026")
                .evEbitda("18.0").ps("6.0")
                .revGrowth("0.16").epsGrowth("0.22").build());
        assertEquals(0, c.growthScore().compareTo(d("0.7368")), "got " + c.growthScore());
        assertEquals(StyleBucket.GROWTH, c.bucket());
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
    void giltIncomeIsNominalTimesCouponAndItsYieldIsRunning() {
        // GILT 4.75% 2038: £100,000 nominal, clean price 95.24 -> £95,240 of value. The coupon
        // pays £4,750/yr on the nominal, so the running yield is 4750/95240 = 4.987%, i.e.
        // 4.75 / 0.9524 -- the coupon over the price the position was valued at.
        Classification c = classifier().classify(new Position(
                "GILT 4.75% 2038", d("95240"), Metrics.empty(), false, d("100000")));
        assertEquals(StyleBucket.FIXED_INCOME, c.bucket());
        assertTrue(c.income());
        assertEquals(0, d("0.049874").compareTo(c.dividendYield().setScale(6, RoundingMode.HALF_UP)));
        // The caller recovers the income by multiplying back, so that round-trip must be exact
        // enough to print: value x yield has to come back to nominal x coupon.
        assertEquals(0, d("4750.00").compareTo(
                d("95240").multiply(c.dividendYield()).setScale(2, RoundingMode.HALF_UP)));
    }

    @Test
    void aLowCouponGiltIsStillAnIncomeHoldingAtItsRealYield() {
        // GILT 0.875% 2033 at 77.13: bought for pull-to-par, not income. It counts in the income
        // holdings by value -- fixed income IS the income allocation -- but at the 1.13% it truly
        // yields, so the basket yield tells the truth rather than being flattered by exclusion.
        Classification c = classifier().classify(new Position(
                "GILT 0.875% 2033", d("154260"), Metrics.empty(), false, d("200000")));
        assertTrue(c.income());
        assertEquals(0, d("0.011344").compareTo(c.dividendYield().setScale(6, RoundingMode.HALF_UP)));
    }

    @Test
    void fixedIncomeWithNoDerivableYieldStillCountsButReportsNoIncome() {
        // A bond fund overridden to FIXED_INCOME has no coupon in its symbol. Its value is real,
        // so it belongs in the income holdings; its income is unknown, and a null is what makes
        // the caller say so instead of booking a silent zero.
        Properties p = props();
        p.setProperty("override.VGOV", "FIXED_INCOME");
        Classification c = new StyleClassifier(p).classify(new Position(
                "VGOV", d("10000"), Metrics.empty(), false, d("500")));
        assertEquals(StyleBucket.FIXED_INCOME, c.bucket());
        assertTrue(c.income());
        assertNull(c.dividendYield());
        assertNotNull(c.note());
    }

    @Test
    void aBondFundFallsBackToItsPublishedDistributionYield() {
        Properties p = props();
        p.setProperty("override.VGOV", "FIXED_INCOME");
        Classification c = new StyleClassifier(p).classify(new Position(
                "VGOV", d("10000"), m().divYield("0.041").build(), false, d("500")));
        assertTrue(c.income());
        assertEquals(0, d("0.041").compareTo(c.dividendYield()));
    }

    @Test
    void giltWithNoQuantityHasNoYieldToDerive() {
        // Guards the caller contract: the nominal comes from the ledger replay, and without it
        // there is no income figure -- a coupon alone cannot say how much is held.
        Classification c = classifier().classify(new Position(
                "GILT 4.75% 2038", d("95240"), Metrics.empty(), false));
        assertTrue(c.income());
        assertNull(c.dividendYield());
    }

    @Test
    void everyBullionEtcIsNonEquityWithNoOverride() {
        // classifier() configures PHGP only. SGLN, the gold actually held, was in Instruments'
        // bullion list but not in style.properties, so it was reported as unscoreable — a data
        // gap to go and fix rather than the deliberate holding it is (2026-08-25).
        for (String symbol : Instruments.bullionEtcs()) {
            Classification c = classifier().classify(new Position(
                    symbol, d("6643"), Metrics.empty(), false));
            assertEquals(StyleBucket.NON_EQUITY, c.bucket(), symbol);
            assertFalse(c.income(), symbol + " pays nothing and must not be an income holding");
        }
    }

    @Test
    void everyBondFundIsFixedIncomeWithNoOverride() {
        for (String symbol : Instruments.bondFunds()) {
            Classification c = classifier().classify(new Position(
                    symbol, d("10000"), Metrics.empty(), false));
            assertEquals(StyleBucket.FIXED_INCOME, c.bucket(), symbol);
            assertTrue(c.income(), symbol + " is fixed income and so an income holding");
        }
    }

    @Test
    void aBullionEtcIsNotScoredAsAFund() {
        // Ordering guard: the asset-class step precedes the fund branch, so a metal ETC with an
        // aggregate P/E published against it is still bullion rather than a value fund.
        Classification c = classifier().classify(new Position(
                "SGLN", d("6643"), m().fund("12", "1.2", "1.1").build(), true));
        assertEquals(StyleBucket.NON_EQUITY, c.bucket());
    }

    @Test
    void bullionIsStructuralButNotIncome() {
        // Only fixed income is an income holding structurally. A bullion ETC pays nothing, and
        // routing every structural bucket through the same "counts as income" rule would put
        // gold in a tile about what the portfolio pays.
        Classification c = classifier().classify(new Position(
                "PHGP", d("6643"), Metrics.empty(), false));
        assertEquals(StyleBucket.NON_EQUITY, c.bucket());
        assertFalse(c.income());
    }

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
