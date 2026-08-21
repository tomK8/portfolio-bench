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
    void theReservedFlagsAreNoOpsAndChangeNothingYet() {
        Inputs in = inputs("0.03", "0.028", "0.026", "0.02");
        var plain = VolForecast.touch(in, dec("100"), dec("110"), 60, Config.DEFAULT);
        var flagged = VolForecast.touch(in, dec("100"), dec("110"), 60,
                new Config(20, 90, true, true, true));
        assertEquals(plain.sigmaEff(), flagged.sigmaEff());
        assertEquals(plain.probability(), flagged.probability());
        assertEquals(plain.source(), flagged.source());
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
