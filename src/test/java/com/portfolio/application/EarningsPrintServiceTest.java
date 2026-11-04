package com.portfolio.application;

import com.portfolio.adapter.YahooQuoteSummaryFetcher.EarningsDetail;
import com.portfolio.application.EarningsCalendarService.Reported;
import com.portfolio.application.EarningsPrintService.OrderDistance;
import com.portfolio.application.EarningsPrintService.Revenue;
import com.portfolio.application.EarningsPrintService.Revision;
import com.portfolio.application.EarningsPrintService.Surprise;
import com.portfolio.persistence.EarningsPrintRepository.ConsensusCapture;
import com.portfolio.persistence.EarningsPrintRepository.PrintDetail;
import com.portfolio.persistence.OrderHistoryRepository.RestingOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EarningsPrintServiceTest {

    private static final LocalDate PRINT = LocalDate.parse("2026-11-02");

    private static Reported reported(Double actual, Double forecast, Double lastYear) {
        return new Reported("ZZZA", "Zed plc", PRINT, "AMC", forecast, 12, actual, lastYear,
                EarningsCalendarService.surprisePct(actual, forecast), 1);
    }

    private static PrintDetail detail(Double actual, Double consensus, Instant captured) {
        return new PrintDetail("ZZZA", PRINT, "Sep 2026", actual, consensus, "USD", 1.98,
                captured, Instant.parse("2026-11-03T08:00:00Z"), null);
    }

    // ---- the roll guard ----------------------------------------------------------------

    @Test
    void anEstimatesBlockThatHasRolledToTheNextQuarterDescribesNoPrintOfOurs() {
        EarningsDetail rolled = new EarningsDetail("ZZZA", "USD", List.of(),
                LocalDate.parse("2027-01-28"), new BigDecimal("2.90"),
                new BigDecimal("155000000000"), false);
        assertFalse(EarningsPrintService.describesPrint(rolled, PRINT));

        EarningsDetail stillOnIt = new EarningsDetail("ZZZA", "USD", List.of(), PRINT,
                new BigDecimal("1.98"), new BigDecimal("112500000000"), false);
        assertTrue(EarningsPrintService.describesPrint(stillOnIt, PRINT));
    }

    @Test
    void aFailedFetchDescribesNothing() {
        assertFalse(EarningsPrintService.describesPrint(EarningsDetail.empty("ZZZA"), PRINT));
        assertFalse(EarningsPrintService.describesPrint(null, PRINT));
    }

    // ---- revenue -----------------------------------------------------------------------

    @Test
    void revenueWithNoCapturedConsensusSaysSoRatherThanReadingAsAMiss() {
        Revenue r = EarningsPrintService.revenue(reported(2.05, 1.98, 1.85),
                detail(114_477_000_000.0, null, null));
        assertEquals("NO_CONSENSUS", r.state());
        assertNull(r.surprisePct());
        assertEquals(114_477_000_000.0, r.actual(), 1e-6);
    }

    @Test
    void revenueNotYetPublishedIsAwaitingRatherThanAbsent() {
        Revenue r = EarningsPrintService.revenue(reported(2.05, 1.98, 1.85),
                detail(null, 112_500_000_000.0, Instant.parse("2026-10-30T08:00:00Z")));
        assertEquals("AWAITING", r.state());
        assertNull(r.actual());
    }

    @Test
    void aPrintWeHaveNotFetchedDetailForIsNotTheSameAsOneWithNoRevenue() {
        assertEquals("NOT_FETCHED",
                EarningsPrintService.revenue(reported(2.05, 1.98, 1.85), null).state());
        assertEquals("AWAITING",
                EarningsPrintService.revenue(reported(null, 1.98, 1.85), null).state());
    }

    @Test
    void theRevenueSurpriseDividesByTheMagnitudeAndIsInPercentagePoints() {
        Revenue r = EarningsPrintService.revenue(reported(2.05, 1.98, 1.85),
                detail(16_209_000_000.0, 16_564_703_399.0,
                        Instant.parse("2026-10-29T08:00:00Z")));
        assertEquals("FIGURES", r.state());
        assertEquals(-2.147, r.surprisePct(), 1e-3);
        assertTrue(r.prePrintConsensus());
    }

    @Test
    void aConsensusReadOnTheDayOfThePrintIsNotClaimedAsAPrePrintReading() {
        Revenue r = EarningsPrintService.revenue(reported(2.05, 1.98, 1.85),
                detail(16_209_000_000.0, 16_564_703_399.0,
                        Instant.parse("2026-11-02T22:00:00Z")));
        assertEquals("FIGURES", r.state());
        assertFalse(r.prePrintConsensus());
    }

    // ---- context -----------------------------------------------------------------------

    @Test
    void epsGrowthIsWithheldWhereTheYearAgoQuarterWasALoss() {
        assertNull(EarningsPrintService.epsGrowth(reported(0.10, 0.05, -0.40)));
        assertNull(EarningsPrintService.epsGrowth(reported(0.10, 0.05, 0.0)));
        assertNull(EarningsPrintService.epsGrowth(reported(0.10, 0.05, null)));
        assertEquals(10.81, EarningsPrintService.epsGrowth(reported(2.05, 1.98, 1.85)), 1e-2);
    }

    @Test
    void theMedianOfTheOwnHistoryIsAMedianNotAMean() {
        List<Surprise> h = List.of(
                surprise("2026-07-30", 1.6), surprise("2026-04-30", 4.69),
                surprise("2026-01-29", -51.38));
        assertEquals(1.6, EarningsPrintService.medianOf(h), 1e-9);
        assertNull(EarningsPrintService.medianOf(List.of()));
    }

    // ---- revisions ---------------------------------------------------------------------

    @Test
    void theChangeIsAgainstTheLastReadingBeforeThePrintAndNamesIt() {
        List<ConsensusCapture> caps = List.of(
                capture("2026-10-20", "Q", "Dec 2026", 2.70),
                capture("2026-10-30", "Q", "Dec 2026", 2.80),
                capture("2026-11-03", "Q", "Dec 2026", 2.94));
        Revision r = EarningsPrintService.revisions(caps, PRINT).get(0);
        assertEquals("Dec 2026", r.fiscalEnd());
        assertEquals(2.94, r.consensus(), 1e-9);
        assertEquals(2.80, r.before(), 1e-9);
        assertEquals(LocalDate.parse("2026-10-30"), r.beforeOn());
        assertEquals(5.0, r.changePct(), 1e-9);
    }

    @Test
    void withNoReadingBeforeThePrintThereIsNoChangeButTheCountsStillStand() {
        List<ConsensusCapture> caps = List.of(
                new ConsensusCapture("ZZZA", LocalDate.parse("2026-11-03"), "Q", "Dec 2026",
                        2.94, 6, 3, 1));
        Revision r = EarningsPrintService.revisions(caps, PRINT).get(0);
        assertNull(r.changePct());
        assertNull(r.beforeOn());
        assertEquals(3, r.up());
        assertEquals(1, r.down());
    }

    @Test
    void theNearestPeriodOfEachKindIsReportedNotEveryPeriodHeld() {
        List<ConsensusCapture> caps = List.of(
                capture("2026-11-03", "Q", "Mar 2027", 2.19),
                capture("2026-11-03", "Q", "Dec 2026", 2.94),
                capture("2026-11-03", "Y", "Sep 2027", 9.56));
        List<Revision> rows = EarningsPrintService.revisions(caps, PRINT);
        assertEquals(2, rows.size());
        assertEquals("Dec 2026", rows.get(0).fiscalEnd());
        assertEquals("Q", rows.get(0).periodKind());
        assertEquals("Y", rows.get(1).periodKind());
    }

    // ---- orders and the market -----------------------------------------------------------

    @Test
    void anOrderDistanceIsTheSignedMoveStillNeededToReachTheLimit() {
        EarningsPrintService.Reaction reaction = new EarningsPrintService.Reaction(
                "ZZZA", "MEASURED", "USD", LocalDate.parse("2026-11-02"), 100.0,
                LocalDate.parse("2026-11-03"), 110.0, 10.0, 1.0, 9.0, "SPY",
                2.0, 4, 0, 5.0);
        List<OrderDistance> d = EarningsPrintService.orderDistances(List.of(
                new RestingOrder("k", "Roth IRA", "ZZZA", "Sell", "Limit", 10.0, 121.0, "USD")),
                reaction);
        assertEquals(21.0, d.get(0).beforePct(), 1e-9);   // 21% further up before the print
        assertEquals(10.0, d.get(0).afterPct(), 1e-9);    // only 10% after it
    }

    @Test
    void anOrderWithNoLimitIsNotGivenADistance() {
        EarningsPrintService.Reaction reaction = new EarningsPrintService.Reaction(
                "ZZZA", "MEASURED", "USD", LocalDate.parse("2026-11-02"), 100.0,
                LocalDate.parse("2026-11-03"), 110.0, 10.0, null, null, null,
                null, 0, 0, null);
        assertTrue(EarningsPrintService.orderDistances(List.of(
                new RestingOrder("k", "AJ Bell", "ZZZA", "Buy", "Market", 10.0, null, "GBP")),
                reaction).isEmpty());
    }

    @Test
    void theMarketIsNamedPerListingAndWithheldWhereThisAppHasNotNamedOne() {
        assertEquals("SPY", EarningsPrintService.marketIndexFor("AAPL"));
        assertEquals("ISF.L", EarningsPrintService.marketIndexFor("AZN.L"));
        assertNull(EarningsPrintService.marketIndexFor("BNP.PA"));
        assertNull(EarningsPrintService.marketIndexFor(null));
    }

    @Test
    void thePositionEffectIsTheGbpValueTimesThePriceMove() {
        EarningsPrintService.Reaction reaction = new EarningsPrintService.Reaction(
                "ZZZA", "MEASURED", "GBp", LocalDate.parse("2026-10-29"), 130.52,
                LocalDate.parse("2026-10-30"), 139.60, 6.9568, null, null, null,
                1.72, 3, 0, 4.04);
        assertEquals(new BigDecimal("1391.36"),
                EarningsPrintService.positionEffect(new BigDecimal("20000"), reaction));
        assertNull(EarningsPrintService.positionEffect(null, reaction));
    }

    private static Surprise surprise(String date, double pct) {
        return new Surprise(LocalDate.parse(date), "Q", 1.0, 1.0, pct);
    }

    private static ConsensusCapture capture(String on, String kind, String period, double v) {
        return new ConsensusCapture("ZZZA", LocalDate.parse(on), kind, period, v, 6, 1, 1);
    }
}
