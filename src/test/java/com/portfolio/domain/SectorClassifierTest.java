package com.portfolio.domain;

import com.portfolio.domain.SectorClassifier.Classification;
import com.portfolio.domain.SectorClassifier.Constituent;
import com.portfolio.domain.SectorClassifier.Position;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectorClassifierTest {

    private static final Set<String> AI_TECH =
            Set.of("GOOG", "GOOGL", "META", "AMZN", "NFLX", "TSLA", "BABA");

    private static SectorClassifier classifier() {
        return new SectorClassifier(Map.of("PHGP", SectorBucket.COMMODITIES), AI_TECH);
    }

    private static BigDecimal gbp(String v) {
        return new BigDecimal(v);
    }

    private static BigDecimal bucket(Classification c, SectorBucket b) {
        return c.byBucket().getOrDefault(b, BigDecimal.ZERO);
    }

    // ---- resolution order ---------------------------------------------------

    @Test
    void giltsAreFixedIncomeWithoutAnyConfiguration() {
        Classification c = classifier().classify(
                Position.direct("GILT 0.875% 2033", gbp("154840.00"), null));

        assertEquals(0, bucket(c, SectorBucket.FIXED_INCOME).compareTo(gbp("154840.00")));
        assertEquals(0, c.aiTechGbp().signum(), "a gilt is never AI/Tech");
        assertFalse(c.fund());
    }

    @Test
    void overrideBeatsTheYahooSector() {
        // A bullion ETC that Yahoo happens to label — the override is what we mean.
        Classification c = classifier().classify(
                Position.direct("PHGP", gbp("1000.00"), "Financial Services"));

        assertEquals(0, bucket(c, SectorBucket.COMMODITIES).compareTo(gbp("1000.00")));
        assertEquals(0, bucket(c, SectorBucket.FINANCIALS).signum());
    }

    @Test
    void unknownSectorIsUnclassifiedRatherThanGuessed() {
        Classification c = classifier().classify(Position.direct("WAT", gbp("500.00"), null));

        assertEquals(0, bucket(c, SectorBucket.UNCLASSIFIED).compareTo(gbp("500.00")),
                "value must be surfaced, never absorbed into a neighbouring bucket");
    }

    @Test
    void bothYahooSectorSpellingsMapToTheSameBucket() {
        assertEquals(SectorBucket.FINANCIALS, SectorBucket.fromYahooSector("Financial Services"));
        assertEquals(SectorBucket.FINANCIALS, SectorBucket.fromYahooSector("financial_services"));
        assertEquals(SectorBucket.CONSUMER, SectorBucket.fromYahooSector("Consumer Cyclical"));
        assertEquals(SectorBucket.CONSUMER, SectorBucket.fromYahooSector("consumer_defensive"));
        assertEquals(SectorBucket.REAL_ESTATE, SectorBucket.fromYahooSector("realestate"));
        assertEquals(SectorBucket.REAL_ESTATE, SectorBucket.fromYahooSector("Real Estate"));
        assertNull(SectorBucket.fromYahooSector("Nonsense"));
    }

    // ---- the AI/Tech axis ---------------------------------------------------

    @Test
    void technologySectorQualifiesAsAiTechAutomatically() {
        Classification c = classifier().classify(
                Position.direct("NVDA", gbp("25018.00"), "Technology"));

        assertEquals(0, c.aiTechGbp().compareTo(gbp("25018.00")));
    }

    @Test
    void megaCapInternetNamesQualifyDespiteTheirGicsSector() {
        // The whole reason the AI/Tech axis exists: GICS files these outside Technology.
        Classification goog = classifier().classify(
                Position.direct("GOOG", gbp("76415.00"), "Communication Services"));
        Classification amzn = classifier().classify(
                Position.direct("AMZN", gbp("20757.00"), "Consumer Cyclical"));

        assertEquals(0, goog.aiTechGbp().compareTo(gbp("76415.00")));
        assertEquals(0, amzn.aiTechGbp().compareTo(gbp("20757.00")));
        // ...while still sitting in their real sector for the breakdown itself.
        assertEquals(0, bucket(goog, SectorBucket.COMMS_MEDIA).compareTo(gbp("76415.00")));
        assertEquals(0, bucket(amzn, SectorBucket.CONSUMER).compareTo(gbp("20757.00")));
    }

    @Test
    void paymentsAndDataNamesAreDeliberatelyExcluded() {
        for (String symbol : List.of("V", "MA", "LSEG")) {
            Classification c = classifier().classify(
                    Position.direct(symbol, gbp("1000.00"), "Financial Services"));
            assertEquals(0, c.aiTechGbp().signum(), symbol + " must not count as AI/Tech");
        }
    }

    // ---- fund look-through --------------------------------------------------

    @Test
    void fundValueIsSplitAcrossBucketsAndReconcilesExactly() {
        Classification c = classifier().classify(eqqq(gbp("218100.00")));

        assertTrue(c.fund());
        BigDecimal sum = c.byBucket().values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(gbp("218100.00")),
                "look-through must neither create nor destroy value");

        // Normalised by the weights' own total (Yahoo's sum to 1.000099998, not exactly 1),
        // with the rounding remainder absorbed by the largest bucket.
        assertEquals(0, bucket(c, SectorBucket.TECH_AI).compareTo(gbp("126092.82")));
        assertEquals(0, bucket(c, SectorBucket.CONSUMER).compareTo(gbp("38883.34")),
                "the two Consumer sectors merge into one bucket");
        assertEquals(0, bucket(c, SectorBucket.COMMS_MEDIA).compareTo(gbp("29505.98")));
    }

    @Test
    void fundAiTechAddsQualifyingNamesWithoutDoubleCounting() {
        Classification c = classifier().classify(eqqq(gbp("218100.00")));

        // 57.82% technology + AMZN 4.83% + GOOGL 3.43% + GOOG 3.22% + META 2.73%.
        // NVDA/AAPL/MSFT/MU/AMD/AVGO are Technology and already inside the 57.82% — adding
        // them again is the failure this guards against.
        assertEquals(0, c.aiTechGbp().compareTo(gbp("157084.03")));
        assertTrue(c.aiTechGbp().compareTo(bucket(c, SectorBucket.TECH_AI)) > 0,
                "AI/Tech exceeds the raw technology slice precisely because of those names");
    }

    @Test
    void aTechnologyConstituentOnTheListIsNotCountedTwice() {
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        weights.put("technology", new BigDecimal("1.0"));
        // TSLA is on the include list; here it is (contrived) a Technology name, so it is
        // already inside the 100% technology weight.
        Classification c = classifier().classify(new Position("XTEC", gbp("1000.00"), null,
                weights, List.of(new Constituent("TSLA", new BigDecimal("0.5"), "Technology"))));

        assertEquals(0, c.aiTechGbp().compareTo(gbp("1000.00")),
                "must stay at the position value, not 150% of it");
    }

    @Test
    void constituentWithUnknownSectorIsSkippedKeepingTheBoundConservative() {
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        weights.put("technology", new BigDecimal("0.5"));
        weights.put("communication_services", new BigDecimal("0.5"));
        // BABA is on the list but we hold no fundamentals for it, so its sector is unknown:
        // it might already be inside the technology half. Skipping understates rather than
        // risks overstating.
        Classification c = classifier().classify(new Position("XMIX", gbp("1000.00"), null,
                weights, List.of(new Constituent("BABA", new BigDecimal("0.25"), null))));

        assertEquals(0, c.aiTechGbp().compareTo(gbp("500.00")));
    }

    // ---- the bridge between the Technology bucket and the AI/Tech figure -------

    @Test
    void aiTechAlwaysSplitsIntoTechSectorPlusCrossSector() {
        // The identity the UI prints on screen, across all three shapes a position can take.
        for (Classification c : List.of(
                classifier().classify(Position.direct("NVDA", gbp("25018.00"), "Technology")),
                classifier().classify(Position.direct("GOOG", gbp("76415.00"), "Communication Services")),
                classifier().classify(eqqq(gbp("218100.00"))))) {
            assertEquals(0, c.fromTechSectorGbp().add(c.crossSectorGbp()).compareTo(c.aiTechGbp()),
                    "fromTechSector + crossSector must equal the AI/Tech total exactly");
            assertEquals(0, c.fromTechSectorGbp().compareTo(bucket(c, SectorBucket.TECH_AI)),
                    "the tech-sector part is precisely the Technology bucket");
        }
    }

    @Test
    void aTechnologyNameIsEntirelyTechSectorAndATaggedNameEntirelyCrossSector() {
        Classification nvda = classifier().classify(
                Position.direct("NVDA", gbp("25018.00"), "Technology"));
        assertEquals(0, nvda.fromTechSectorGbp().compareTo(gbp("25018.00")));
        assertEquals(0, nvda.crossSectorGbp().signum());

        Classification goog = classifier().classify(
                Position.direct("GOOG", gbp("76415.00"), "Communication Services"));
        assertEquals(0, goog.fromTechSectorGbp().signum(),
                "GOOG contributes nothing to the Technology bucket — that is the whole point");
        assertEquals(0, goog.crossSectorGbp().compareTo(gbp("76415.00")));
    }

    @Test
    void aNameThatIsNotAiTechAtAllContributesToNeitherSide() {
        Classification c = classifier().classify(
                Position.direct("HSBA", gbp("7622.00"), "Financial Services"));

        assertEquals(0, c.aiTechGbp().signum());
        assertEquals(0, c.fromTechSectorGbp().signum());
        assertEquals(0, c.crossSectorGbp().signum());
    }

    @Test
    void fundSplitsBetweenItsTechnologyWeightAndItsNamedAiHoldings() {
        Classification c = classifier().classify(eqqq(gbp("218100.00")));

        assertEquals(0, c.fromTechSectorGbp().compareTo(gbp("126092.82")),
                "the 57.82% technology weight, normalised");
        assertEquals(0, c.crossSectorGbp().compareTo(gbp("30991.21")),
                "AMZN + GOOGL + GOOG + META, all filed outside Technology");
    }

    @Test
    void anAlmostAllTechFundStillMatchesItsTechnologyBucketToThePenny() {
        // SEMI's real shape: 99.88% technology with three slivers elsewhere, and no AI-tagged
        // constituent outside Technology. absorbRounding pushes its remainder onto the Technology
        // bucket, so a naive split leaves the parts a penny under the row they must reconcile to.
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        weights.put("technology", new BigDecimal("0.9988"));
        weights.put("consumer_cyclical", new BigDecimal("0.0001"));
        weights.put("basic_materials", new BigDecimal("0.0001"));
        weights.put("industrials", new BigDecimal("0.0010"));
        Classification c = classifier().classify(new Position("SEMI", gbp("15456.33"), null,
                weights, List.of(new Constituent("MU", new BigDecimal("0.087"), "Technology"))));

        assertEquals(0, c.fromTechSectorGbp().compareTo(bucket(c, SectorBucket.TECH_AI)));
        assertEquals(0, c.fromTechSectorGbp().add(c.crossSectorGbp()).compareTo(c.aiTechGbp()));
        assertTrue(c.crossSectorGbp().signum() >= 0, "cross-sector can never go negative");
    }

    @Test
    void fundReportsHowMuchOfItIsIdentifiedByName() {
        Classification c = classifier().classify(eqqq(gbp("218100.00")));

        assertEquals(0, c.nameCoverage().setScale(4, java.math.RoundingMode.HALF_UP)
                .compareTo(new BigDecimal("0.4630")), "top 10 covers ~46% of the fund");
    }

    @Test
    void aFundWithNoWeightsFallsToUnclassifiedRatherThanStaleNumbers() {
        Classification c = classifier().classify(
                new Position("EQQQ", gbp("218100.00"), null, Map.of(), List.of()));

        assertEquals(0, bucket(c, SectorBucket.UNCLASSIFIED).compareTo(gbp("218100.00")));
        assertFalse(c.fund());
    }

    /**
     * Real EQQQ composition as Yahoo returned it on 2026-08-13 — the largest position in the
     * portfolio, and the one whose misclassification would matter most.
     */
    private static Position eqqq(BigDecimal value) {
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        weights.put("realestate", new BigDecimal("0.0"));
        weights.put("consumer_cyclical", new BigDecimal("0.1118"));
        weights.put("basic_materials", new BigDecimal("0.0101"));
        weights.put("consumer_defensive", new BigDecimal("0.0665"));
        weights.put("technology", new BigDecimal("0.5782"));
        weights.put("communication_services", new BigDecimal("0.1353"));
        weights.put("financial_services", new BigDecimal("0.0023"));
        weights.put("utilities", new BigDecimal("0.0119"));
        weights.put("industrials", new BigDecimal("0.040599998"));
        weights.put("energy", new BigDecimal("0.0054"));
        weights.put("healthcare", new BigDecimal("0.038"));

        List<Constituent> holdings = List.of(
                new Constituent("NVDA", new BigDecimal("0.08037171"), "Technology"),
                new Constituent("AAPL", new BigDecimal("0.0750597"), "Technology"),
                new Constituent("MSFT", new BigDecimal("0.0571113"), "Technology"),
                new Constituent("AMZN", new BigDecimal("0.0483309"), "Consumer Cyclical"),
                new Constituent("MU", new BigDecimal("0.042391"), "Technology"),
                new Constituent("AMD", new BigDecimal("0.0354603"), "Technology"),
                new Constituent("GOOGL", new BigDecimal("0.034313098"), "Communication Services"),
                new Constituent("GOOG", new BigDecimal("0.0321923"), "Communication Services"),
                new Constituent("AVGO", new BigDecimal("0.0305347"), "Technology"),
                new Constituent("META", new BigDecimal("0.027274301"), "Communication Services"));

        return new Position("EQQQ", value, null, weights, holdings);
    }
}
