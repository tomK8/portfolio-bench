package com.portfolio.domain;

import com.portfolio.domain.VolForecast.Config;
import com.portfolio.domain.VolForecast.Inputs;
import com.portfolio.domain.VolForecast.Source;
import com.portfolio.domain.VolForecast.Touch;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The horizon-matched volatility forecast and the touch probability built on it. The formula is
 * unchanged from the fixed-horizon version — what is under test is the σ that goes into it, and
 * the properties the composed probability has to keep.
 */
class VolForecastTest {

    private static final Config CFG = Config.DEFAULT;   // half-life 20 sessions, GTC 90

    private static Inputs inputs(String rv10, String rv20, String rv30, String rv250) {
        return new Inputs(dec(rv10), dec(rv20), dec(rv30), dec(rv250));
    }

    private static BigDecimal dec(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    // ---- decay weight ------------------------------------------------------

    @Test
    void decayGoesToOneAsTheHorizonGoesToZero() {
        assertEquals(1.0, VolForecast.decayWeight(20, 0.0), 1e-12);
        assertEquals(1.0, VolForecast.decayWeight(20, 1e-9), 1e-9);
        assertEquals(1.0, VolForecast.decayWeight(20, 1e-4), 1e-5);
        // A one-session horizon is still overwhelmingly current state.
        assertTrue(VolForecast.decayWeight(20, 1) > 0.98);
    }

    @Test
    void decayGoesToZeroAsTheHorizonGrows() {
        assertTrue(VolForecast.decayWeight(20, 250) < 0.12);
        assertTrue(VolForecast.decayWeight(20, 2500) < 0.02);
        assertEquals(0.0, VolForecast.decayWeight(20, 1e9), 1e-6);
    }

    @Test
    void decayFallsMonotonicallyWithTheHorizonAndRisesWithTheHalfLife() {
        double prev = Double.MAX_VALUE;
        for (int n = 1; n <= 500; n += 7) {
            double d = VolForecast.decayWeight(20, n);
            assertTrue(d < prev, "decay must fall as the horizon lengthens (N=" + n + ")");
            assertTrue(d >= 0 && d <= 1, "decay is a weight");
            prev = d;
        }
        // A longer half-life keeps more weight on the current window at the same horizon.
        assertTrue(VolForecast.decayWeight(60, 90) > VolForecast.decayWeight(20, 90));
    }

    // ---- the blend ---------------------------------------------------------

    @Test
    void varNEqualsVarNowWhenCurrentStateEqualsTheAnchorAtEveryHorizon() {
        // The blend is a weighted average of the two variances, so when they agree the weight is
        // irrelevant — at any horizon, and at any half-life.
        Inputs flat = inputs("0.02", "0.025", "0.03", "0.025");
        for (int n : new int[]{1, 5, 20, 90, 250, 1250}) {
            var f = VolForecast.resolve(flat, n, CFG);
            assertEquals(Source.BLEND, f.source());
            assertEquals(0.025, f.sigmaEff().doubleValue(), 1e-9,
                    "sigma_eff must be var_now itself when var_now == var_LR (N=" + n + ")");
        }
    }

    @Test
    void aCalmNameRevertsUpwardAndAViolentOneRevertsDownAsTheHorizonLengthens() {
        Inputs violent = inputs("0.05", "0.05", "0.045", "0.02");   // just had a rough fortnight
        double shortH = VolForecast.resolve(violent, 5, CFG).sigmaEff().doubleValue();
        double longH = VolForecast.resolve(violent, 250, CFG).sigmaEff().doubleValue();
        assertTrue(shortH > longH, "a spike should decay toward the anchor over a long horizon");
        assertTrue(longH > 0.02 && longH < 0.05, "and land between the two, not outside them");

        Inputs asleep = inputs("0.005", "0.005", "0.006", "0.02");
        assertTrue(VolForecast.resolve(asleep, 5, CFG).sigmaEff().doubleValue()
                < VolForecast.resolve(asleep, 250, CFG).sigmaEff().doubleValue(),
                "a quiet spell should revert upward toward the anchor");
    }

    @Test
    void degradesToWhicheverWindowExistsRatherThanFailing() {
        var noAnchor = VolForecast.resolve(inputs("0.02", "0.02", "0.02", null), 90, CFG);
        assertEquals(Source.CURRENT_ONLY, noAnchor.source());
        assertEquals(0.02, noAnchor.sigmaEff().doubleValue(), 1e-9);
        assertEquals(1.0, noAnchor.decay().doubleValue(), 1e-9);

        var noCurrent = VolForecast.resolve(inputs(null, null, null, "0.03"), 90, CFG);
        assertEquals(Source.ANCHOR_ONLY, noCurrent.source());
        assertEquals(0.03, noCurrent.sigmaEff().doubleValue(), 1e-9);
        assertEquals(0.0, noCurrent.decay().doubleValue(), 1e-9);

        assertEquals(Source.NONE, VolForecast.resolve(Inputs.NONE, 90, CFG).source());
        assertNull(VolForecast.resolve(Inputs.NONE, 90, CFG).sigmaEff());
    }

    @Test
    void theStillReservedFlagsAreNoOpsAndChangeNothingYet() {
        // useEventVar / useImpliedVol are declared seams, not behaviour. (useDrift is wired — see
        // the drift tests below.)
        Inputs in = inputs("0.03", "0.028", "0.026", "0.02");
        var plain = VolForecast.touch(in, dec("100"), dec("110"), 60, Config.DEFAULT);
        var flagged = VolForecast.touch(in, dec("100"), dec("110"), 60,
                new Config(20, 90, false, true, true));
        assertEquals(plain.sigmaEff(), flagged.sigmaEff());
        assertEquals(plain.probability(), flagged.probability());
        assertEquals(plain.source(), flagged.source());
    }

    // ---- the drift branch --------------------------------------------------

    private static final Config DRIFT_ON = new Config(20, 90, true, false, false);

    @Test
    void driftIsOffByDefaultSoNothingIsDriftedUnlessAskedFor() {
        assertFalse(Config.DEFAULT.useDrift());
        assertFalse(Config.of(null, null).useDrift());
        assertTrue(Config.of(null, null, true).useDrift());

        Inputs in = inputs("0.02", "0.02", "0.02", "0.02");
        Touch equityOff = VolForecast.touch(in, dec("100"), dec("90"), 250, Config.DEFAULT, AssetClass.EQUITY);
        assertEquals(0, equityOff.drift().signum());
        assertFalse(equityOff.drifted());
        assertEquals(PriceStats.touchProbability(PriceStats.sigmaDistance(
                        dec("100"), dec("90"), equityOff.sigmaEff(), 250)),
                equityOff.probability(),
                "flag off: the driftless formula, whatever the asset class");
    }

    @Test
    void withTheFlagOnAZeroDriftAssetIsBitForBitTheDriftlessResult() {
        // THE wiring test. A gilt and a bullion ETC carry μ = 0, so with drift *switched on* the
        // branch must still produce the driftless number — identical, not merely close. Anything
        // else means the wiring changed the maths rather than routing it.
        Inputs in = inputs("0.012", "0.010", "0.011", "0.009");
        for (AssetClass zero : new AssetClass[]{AssetClass.GILT, AssetClass.COMMODITY, AssetClass.UNKNOWN}) {
            for (String limit : new String[]{"92", "100", "108"}) {
                for (int n : new int[]{1, 5, 90, 250}) {
                    Touch off = VolForecast.touch(in, dec("100"), dec(limit), n, Config.DEFAULT, zero);
                    Touch on = VolForecast.touch(in, dec("100"), dec(limit), n, DRIFT_ON, zero);
                    assertEquals(off.probability(), on.probability(),
                            zero + " has no drift, so the flag must change nothing (limit " + limit
                                    + ", N=" + n + ")");
                    assertEquals(off.probabilityLow(), on.probabilityLow());
                    assertEquals(off.probabilityHigh(), on.probabilityHigh());
                    assertEquals(off.sigmaEff(), on.sigmaEff(), "σ_eff is not a function of drift");
                    assertFalse(on.drifted());
                }
            }
        }
    }

    @Test
    void withTheFlagOnAnEquityDriftsAwayFromABuyLimitAndTowardASellLimit() {
        Inputs in = inputs("0.015", "0.015", "0.015", "0.015");
        Touch buyOff = VolForecast.touch(in, dec("100"), dec("90"), 250, Config.DEFAULT, AssetClass.EQUITY);
        Touch buyOn = VolForecast.touch(in, dec("100"), dec("90"), 250, DRIFT_ON, AssetClass.EQUITY);
        assertTrue(buyOn.drifted());
        assertEquals(AssetClass.EQUITY.dailyDrift(), buyOn.drift());
        assertTrue(buyOn.probability().compareTo(buyOff.probability()) < 0,
                "6%/yr of drift walks an equity away from a limit below it");

        Touch sellOn = VolForecast.touch(in, dec("100"), dec("110"), 250, DRIFT_ON, AssetClass.EQUITY);
        Touch sellOff = VolForecast.touch(in, dec("100"), dec("110"), 250, Config.DEFAULT, AssetClass.EQUITY);
        assertTrue(sellOn.probability().compareTo(sellOff.probability()) > 0, "and toward one above it");

        // An ETF carries the equity drift by construction — the two are the same number today.
        assertEquals(AssetClass.EQUITY.dailyDrift(), AssetClass.ETF.dailyDrift());
        assertEquals(buyOn.probability(),
                VolForecast.touch(in, dec("100"), dec("90"), 250, DRIFT_ON, AssetClass.ETF).probability());

        // The range takes the same branch as the headline: a drifted headline inside driftless
        // bounds would be a range around a different question.
        Touch spread = VolForecast.touch(inputs("0.010", "0.015", "0.020", "0.015"),
                dec("100"), dec("90"), 250, DRIFT_ON, AssetClass.EQUITY);
        assertTrue(spread.probabilityLow().compareTo(
                        PriceStats.touchProbability(PriceStats.sigmaDistance(dec("100"), dec("90"), dec("0.010"), 250))) < 0,
                "the low end must be drifted too");
    }

    @Test
    void theDriftIsItoCorrectedAgainstTheHorizonsOwnSigma() {
        // μ is an expected *arithmetic* return; the first passage consumes a log drift. The gap —
        // σ²/2, worth 1.26%/yr of a 6% figure at a 1%/day σ — is applied where the two meet, so the
        // probability must match the drifted formula fed ν, not μ.
        Inputs in = inputs("0.015", "0.015", "0.015", "0.015");
        Touch t = VolForecast.touch(in, dec("100"), dec("90"), 250, DRIFT_ON, AssetClass.EQUITY);

        BigDecimal mu = AssetClass.EQUITY.dailyDrift();
        BigDecimal sigma = t.sigmaEff();
        BigDecimal expectedNu = BigDecimal.valueOf(
                mu.doubleValue() - sigma.doubleValue() * sigma.doubleValue() / 2.0);
        assertEquals(expectedNu.doubleValue(), t.logDrift().doubleValue(), 1e-12);
        assertEquals(mu, t.drift(), "μ is reported as well as ν — they are different claims");
        assertEquals(PriceStats.touchProbabilityDrifted(dec("100"), dec("90"), sigma, 250, t.logDrift()),
                t.probability());

        // The correction bites in the right direction: less upward pull than raw μ, so a limit
        // below the market is left slightly *more* reachable than the uncorrected model says.
        assertTrue(t.logDrift().compareTo(mu) < 0);
        assertTrue(t.probability().compareTo(
                        PriceStats.touchProbabilityDrifted(dec("100"), dec("90"), sigma, 250, mu)) > 0,
                "Itô-corrected drift must not overstate the walk away from a buy limit");

        // ν is horizon-dependent because σ_eff is: a spike decaying toward the anchor changes the
        // drag along with it, so the same class yields a different log drift at 5 sessions and 250.
        Inputs violent = inputs("0.05", "0.05", "0.045", "0.02");
        BigDecimal nuNear = VolForecast.touch(violent, dec("100"), dec("90"), 5, DRIFT_ON, AssetClass.EQUITY).logDrift();
        BigDecimal nuFar = VolForecast.touch(violent, dec("100"), dec("90"), 250, DRIFT_ON, AssetClass.EQUITY).logDrift();
        assertTrue(nuNear.compareTo(nuFar) < 0, "a wilder near-term σ means a heavier drag: "
                + nuNear + " vs " + nuFar);
    }

    @Test
    void aZeroDriftClassIsLeftAloneRatherThanDraggedBelowZero() {
        // The branch tests μ, not ν. A gilt with no expected return is modelled as having no drift
        // at all — handing it ν = −σ²/2 would invent a downward pull nobody asked for, and would
        // break the equivalence the whole flag rests on.
        Inputs in = inputs("0.015", "0.015", "0.015", "0.015");
        for (AssetClass zero : new AssetClass[]{AssetClass.GILT, AssetClass.COMMODITY, AssetClass.UNKNOWN}) {
            Touch on = VolForecast.touch(in, dec("100"), dec("90"), 250, DRIFT_ON, zero);
            assertEquals(0, on.logDrift().signum(), zero + " must not acquire a variance drag");
            assertEquals(VolForecast.touch(in, dec("100"), dec("90"), 250, Config.DEFAULT, zero).probability(),
                    on.probability());
        }
    }

    // ---- event variance ----------------------------------------------------

    private static final Config EVENTS_ON = new Config(20, 90, false, true, false);

    private static Inputs withEvents(String rv, VolForecast.Event... events) {
        return new Inputs(dec(rv), dec(rv), dec(rv), dec(rv), java.util.List.of(events));
    }

    @Test
    void aScheduledPrintAddsVarianceRatherThanVolatility() {
        // σ_eff = √((var_N·N + Σmove²)/N). A 6% print inside a 63-session horizon at 1%/day:
        // total var = 0.0001·63 + 0.0036 = 0.0099, σ_eff = √(0.0099/63) = 1.2536%/day.
        Inputs in = withEvents("0.01", new VolForecast.Event(20, dec("0.06")));
        Touch t = VolForecast.touch(in, dec("100"), dec("90"), 63, EVENTS_ON, AssetClass.EQUITY);

        assertEquals(0.012536, t.sigmaEff().doubleValue(), 1e-6);
        assertEquals(0.01, t.sigmaEffDiffusive().doubleValue(), 1e-9, "the diffusive figure is kept");
        assertEquals(1, t.eventCount());
        assertEquals(0.0036, t.eventVariance().doubleValue(), 1e-9);
        assertTrue(t.eventAdjusted());

        // The whole point: a quiet name with a print ahead is not priced as a quiet name.
        Touch without = VolForecast.touch(in, dec("100"), dec("90"), 63, Config.DEFAULT, AssetClass.EQUITY);
        assertTrue(t.probability().compareTo(without.probability()) > 0);
        assertEquals(0, without.eventCount(), "flag off: nothing added");
        assertEquals(without.sigmaEff(), without.sigmaEffDiffusive());
    }

    @Test
    void everyPrintInsideTheHorizonCountsAndNoneOutsideIt() {
        // A 90-session window routinely holds two quarterly prints; a 63-session one holds the
        // first only. The same input set has to give different answers at the two horizons.
        Inputs in = withEvents("0.01",
                new VolForecast.Event(20, dec("0.06")),
                new VolForecast.Event(83, dec("0.06")),
                new VolForecast.Event(146, dec("0.06")));

        assertEquals(0, VolForecast.touch(in, dec("100"), dec("90"), 5, EVENTS_ON, AssetClass.EQUITY).eventCount());
        assertEquals(1, VolForecast.touch(in, dec("100"), dec("90"), 63, EVENTS_ON, AssetClass.EQUITY).eventCount());
        assertEquals(2, VolForecast.touch(in, dec("100"), dec("90"), 90, EVENTS_ON, AssetClass.EQUITY).eventCount());
        assertEquals(3, VolForecast.touch(in, dec("100"), dec("90"), 250, EVENTS_ON, AssetClass.EQUITY).eventCount());

        Touch one = VolForecast.touch(in, dec("100"), dec("90"), 63, EVENTS_ON, AssetClass.EQUITY);
        Touch two = VolForecast.touch(in, dec("100"), dec("90"), 90, EVENTS_ON, AssetClass.EQUITY);
        assertEquals(0.0036, one.eventVariance().doubleValue(), 1e-9);
        assertEquals(0.0072, two.eventVariance().doubleValue(), 1e-9, "variance sums, vol does not");

        // A print today is still ahead of a resting order; one already past is not an input at all.
        assertEquals(1, VolForecast.touch(withEvents("0.01", new VolForecast.Event(0, dec("0.06"))),
                dec("100"), dec("90"), 5, EVENTS_ON, AssetClass.EQUITY).eventCount());
        assertEquals(0, VolForecast.touch(withEvents("0.01", new VolForecast.Event(-3, dec("0.06"))),
                dec("100"), dec("90"), 90, EVENTS_ON, AssetClass.EQUITY).eventCount());
    }

    @Test
    void anUnknownDateOrAZeroMoveLeavesTheFigureVisiblyUnadjusted() {
        // The instruction that matters most here: no date, no adjustment — never a guessed one.
        // An unadjusted probability has to be indistinguishable from the diffusive model, so the
        // panel can say "unadjusted" rather than quietly showing a number that looks adjusted.
        Inputs noDates = new Inputs(dec("0.01"), dec("0.01"), dec("0.01"), dec("0.01"));
        Touch t = VolForecast.touch(noDates, dec("100"), dec("90"), 63, EVENTS_ON, AssetClass.EQUITY);
        assertEquals(0, t.eventCount());
        assertFalse(t.eventAdjusted());
        assertEquals(t.sigmaEffDiffusive(), t.sigmaEff());
        assertEquals(VolForecast.touch(noDates, dec("100"), dec("90"), 63, Config.DEFAULT, AssetClass.EQUITY)
                .probability(), t.probability());

        // A fund or gilt has a zero move even when a date somehow lands on it — no event, no jump.
        assertEquals(0, VolForecast.touch(withEvents("0.01", new VolForecast.Event(10, BigDecimal.ZERO)),
                dec("100"), dec("90"), 63, EVENTS_ON, AssetClass.GILT).eventCount());
    }

    @Test
    void eventVarianceRidesOnTheDegradedForecastsToo() {
        // A short-history name with a print next week is exactly where the calendar carries most of
        // the information, so the CURRENT_ONLY path must fold events in as well.
        Inputs shortHistory = new Inputs(dec("0.01"), dec("0.01"), dec("0.01"), null,
                java.util.List.of(new VolForecast.Event(5, dec("0.06"))));
        Touch t = VolForecast.touch(shortHistory, dec("100"), dec("90"), 20, EVENTS_ON, AssetClass.EQUITY);
        assertEquals(Source.CURRENT_ONLY, t.source());
        assertEquals(1, t.eventCount());
        assertTrue(t.sigmaEff().compareTo(t.sigmaEffDiffusive()) > 0);
    }

    @Test
    void driftAndEventsCompose() {
        // Both flags on: the event inflates σ_eff, and ν is then computed against that inflated σ —
        // the drag belongs to the volatility actually being used, not to the diffusive one.
        Inputs in = withEvents("0.01", new VolForecast.Event(20, dec("0.06")));
        Touch t = VolForecast.touch(in, dec("100"), dec("90"), 63,
                new Config(20, 90, true, true, false), AssetClass.EQUITY);
        assertTrue(t.eventAdjusted());
        assertTrue(t.drifted());
        double sigma = t.sigmaEff().doubleValue();
        assertEquals(AssetClass.EQUITY.dailyDrift().doubleValue() - sigma * sigma / 2.0,
                t.logDrift().doubleValue(), 1e-12);
    }

    @Test
    void aStatedAssetClassIsRequiredBeforeAnyDriftCanBeApplied() {
        // The 5-argument form says nothing about what it is pricing, so it can never inject drift
        // — an unclassified instrument defaults to the neutral model, not to an equity's.
        Inputs in = inputs("0.02", "0.02", "0.02", "0.02");
        assertEquals(VolForecast.touch(in, dec("100"), dec("90"), 250, Config.DEFAULT).probability(),
                VolForecast.touch(in, dec("100"), dec("90"), 250, DRIFT_ON).probability());
        assertEquals(0, VolForecast.touch(in, dec("100"), dec("90"), 250, DRIFT_ON, null)
                .drift().signum());
    }

    // ---- the probability ---------------------------------------------------

    @Test
    void probabilityRisesWithTheHorizonEvenWhileTheBlendPullsSigmaDown() {
        // The subtle one: sigma_eff *falls* here as N grows (current state is above the anchor),
        // but total variance N·var_N still rises, so P must not go backwards.
        Inputs violent = inputs("0.05", "0.05", "0.045", "0.02");
        BigDecimal prev = BigDecimal.ZERO;
        BigDecimal prevSigma = null;
        for (int n : new int[]{1, 2, 5, 10, 20, 40, 60, 90, 130, 250, 500, 1250}) {
            Touch t = VolForecast.touch(violent, dec("100"), dec("115"), n, CFG);
            assertTrue(t.probability().compareTo(prev) >= 0,
                    "P must be non-decreasing in N (N=" + n + ": " + t.probability() + " < " + prev + ")");
            if (prevSigma != null) {
                assertTrue(t.sigmaEff().compareTo(prevSigma) <= 0, "sigma_eff falls as N grows here");
            }
            prev = t.probability();
            prevSigma = t.sigmaEff();
        }
        // ln(1.15) / (0.02·√1250) ≈ 0.20σ ⇒ 2·Φ(−0.20) ≈ 0.84: a 15% move over five
        // years at a 2%/day anchor is likely, not certain — the model says so and should.
        assertTrue(prev.doubleValue() > 0.8, "a 15% move over five years should be likely");
    }

    @Test
    void probabilityRisesWithVolatilityAndFallsWithDistance() {
        BigDecimal prev = BigDecimal.ZERO;
        for (String rv : new String[]{"0.005", "0.010", "0.020", "0.040", "0.080"}) {
            Touch t = VolForecast.touch(inputs(rv, rv, rv, rv), dec("100"), dec("110"), 60, CFG);
            assertTrue(t.probability().compareTo(prev) > 0, "P must rise with σ (σ=" + rv + ")");
            prev = t.probability();
        }

        Inputs in = inputs("0.02", "0.02", "0.02", "0.02");
        BigDecimal nearer = VolForecast.touch(in, dec("100"), dec("105"), 60, CFG).probability();
        BigDecimal further = VolForecast.touch(in, dec("100"), dec("130"), 60, CFG).probability();
        assertTrue(nearer.compareTo(further) > 0, "P must fall as |b| grows");
        // Symmetric in the sign of b: first passage does not care which way the barrier lies.
        assertEquals(VolForecast.touch(in, dec("100"), dec("110"), 60, CFG).probability(),
                VolForecast.touch(in, dec("110"), dec("100"), 60, CFG).probability());
    }

    @Test
    void probabilityStaysInsideZeroToOneAtTheExtremes() {
        Inputs in = inputs("0.02", "0.02", "0.02", "0.02");

        // b → 0: the market is already at the limit, so a touch is certain.
        Touch atBarrier = VolForecast.touch(in, dec("100"), dec("100"), 1, CFG);
        assertEquals(0, atBarrier.probability().compareTo(BigDecimal.ONE));

        // σ → 0: nothing moves, so a barrier away from the market is never reached.
        Touch frozen = VolForecast.touch(inputs("0.0000001", "0.0000001", "0.0000001", "0.0000001"),
                dec("100"), dec("200"), 1, CFG);
        assertEquals(0, frozen.probability().compareTo(BigDecimal.ZERO));

        // N = 1, and a wild σ against a near barrier: still a probability.
        for (String limit : new String[]{"100.0001", "101", "150", "1000", "0.01"}) {
            for (String rv : new String[]{"0.0001", "0.02", "0.5", "3.0"}) {
                Touch t = VolForecast.touch(inputs(rv, rv, rv, rv), dec("100"), dec(limit), 1, CFG);
                double p = t.probability().doubleValue();
                assertTrue(p >= 0.0 && p <= 1.0, "P out of range: " + p + " (limit " + limit + ", σ " + rv + ")");
            }
        }
    }

    @Test
    void theRangeBracketsTheHeadlineWhenTheBlendSitsBetweenRv10AndRv30() {
        // RV10 5%, RV30 3%, blend somewhere between: the reported range has to contain the
        // headline, which is the only thing that makes "low end clears the threshold" a usable rule.
        Touch t = VolForecast.touch(inputs("0.05", "0.04", "0.03", "0.035"),
                dec("100"), dec("112"), 60, CFG);
        assertTrue(t.hasRange());
        assertTrue(t.probabilityLow().compareTo(t.probabilityHigh()) <= 0, "low ≤ high");
        assertTrue(t.probabilityLow().compareTo(t.probability()) <= 0
                        && t.probability().compareTo(t.probabilityHigh()) <= 0,
                "the headline must sit inside its own range: " + t.probabilityLow()
                        + " ≤ " + t.probability() + " ≤ " + t.probabilityHigh());
    }

    @Test
    void aMissingRangeWindowDoesNotSinkTheHeadline() {
        Touch t = VolForecast.touch(inputs(null, "0.02", null, "0.02"), dec("100"), dec("110"), 60, CFG);
        assertNotNull(t.probability());
        assertFalse(t.hasRange());
    }

    @Test
    void gtcConfigDefaultsAreTheDocumentedOnes() {
        assertEquals(20.0, Config.DEFAULT.halfLifeDays());
        assertEquals(90, Config.DEFAULT.gtcHorizonSessions());
        assertFalse(Config.DEFAULT.useDrift() || Config.DEFAULT.useEventVar() || Config.DEFAULT.useImpliedVol());
        // Nonsense knobs fall back rather than producing a nonsense forecast.
        assertEquals(20.0, new Config(-1, 90, false, false, false).halfLifeDays());
        assertEquals(90, new Config(20, 0, false, false, false).gtcHorizonSessions());
        assertEquals(20.0, Config.of(null, null).halfLifeDays());
        assertEquals(45, Config.of(null, 45).gtcHorizonSessions());
    }
}
