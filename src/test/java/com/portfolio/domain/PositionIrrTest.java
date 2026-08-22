package com.portfolio.domain;

import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PositionIrrTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-01-01");

    /** An AJ Bell purchase: GBP-settled, amount negative, fees already inside the amount. */
    private static CashTransaction buy(String date, String symbol, double qty, double costGbp) {
        return new CashTransaction(date, Account.AJBELL, TransactionType.TRANSACTION, symbol,
                qty, -costGbp, "GBP", 1.0, -costGbp, null, null, "buy");
    }

    private static CashTransaction sell(String date, String symbol, double qty, double proceedsGbp) {
        return new CashTransaction(date, Account.AJBELL, TransactionType.TRANSACTION, symbol,
                qty, proceedsGbp, "GBP", 1.0, proceedsGbp, null, null, "sell");
    }

    private static CashTransaction dividend(String date, String symbol, double gbp) {
        return new CashTransaction(date, Account.AJBELL, TransactionType.DIVIDEND, symbol,
                0, gbp, "GBP", 1.0, gbp, null, null, "dividend");
    }

    /** A split: TRANSACTION with a zero amount carrying a signed quantity delta. */
    private static CashTransaction split(String date, String symbol, double qtyDelta) {
        return new CashTransaction(date, Account.AJBELL, TransactionType.TRANSACTION, symbol,
                qtyDelta, 0.0, "GBP", 1.0, 0.0, null, null, "split");
    }

    private static PositionIrr.Result compute(List<CashTransaction> rows, double terminalGbp) {
        return PositionIrr.compute("TEST", rows, BigDecimal.valueOf(terminalGbp), null, null, TODAY);
    }

    private static void assertRate(double expected, BigDecimal actual) {
        assertNotNull(actual, "expected a rate");
        assertEquals(expected, actual.doubleValue(), 1e-5);
    }

    @Test
    void boughtOnceAndNeverSoldStillWorks() {
        // Two flows exist — the purchase and the terminal value — so a rate exists, and with a
        // single purchase it must be the plain CAGR.
        var r = compute(List.of(buy("2021-01-01", "TEST", 10, 1000)), 2000);

        assertTrue(r.annualised());
        double days = LocalDate.parse("2021-01-01").until(TODAY).toTotalMonths(); // sanity only
        assertTrue(days > 0);
        double cagr = Math.pow(2.0, 365.0 / (TODAY.toEpochDay()
                - LocalDate.parse("2021-01-01").toEpochDay())) - 1;
        assertRate(cagr, r.annualisedReturn());
        assertEquals(2, r.flowCount());
        assertEquals(1, r.episodeCount());
        assertFalse(r.reentered());
    }

    @Test
    void dividendsCountOnTheirPayDate() {
        var r = compute(List.of(
                buy("2022-01-01", "TEST", 50, 5000),
                dividend("2023-01-01", "TEST", 150),
                dividend("2024-01-01", "TEST", 150),
                dividend("2025-01-01", "TEST", 150)), 6000);

        assertRate(0.06833305, r.annualisedReturn());
        assertEquals(5, r.flowCount());
    }

    @Test
    void reEntryAfterAFullExitReportsOnlyTheCurrentEpisode() {
        // Sold to zero in 2021 and re-entered in 2024. The whole series changes sign three
        // times and can admit several roots; the current episode admits exactly one, and it is
        // also the only one that answers "how is this position doing".
        var r = compute(List.of(
                buy("2020-01-01", "TEST", 10, 1000),
                sell("2021-01-01", "TEST", 10, 1500),
                buy("2024-01-01", "TEST", 10, 2000)), 3000);

        assertEquals(2, r.episodeCount());
        assertTrue(r.reentered());
        assertEquals(LocalDate.parse("2024-01-01"), r.episodeStart());
        assertEquals(2, r.flowCount(), "only the 2024 buy and the terminal value");
        assertRate(0.22440525, r.annualisedReturn());
    }

    @Test
    void aPartialSaleDoesNotStartANewEpisode() {
        // Trimming is not exiting: the position never reached zero, so the 2020 purchase is
        // still part of the current episode and still dates the rate.
        var r = compute(List.of(
                buy("2020-01-01", "TEST", 100, 10000),
                sell("2024-01-01", "TEST", 90, 14000)), 1600);

        assertEquals(1, r.episodeCount());
        assertEquals(LocalDate.parse("2020-01-01"), r.episodeStart());
        assertRate(0.11197724, r.annualisedReturn());
    }

    @Test
    void nonsensicalAverageCostCannotDistortTheRate() {
        // The broker has netted the realised gain through the surviving shares' cost basis, so
        // the reported average cost is negative and every gain-over-cost ratio is garbage. IRR
        // divides by nothing, so it is unaffected — the reason to trust it over the Gain %
        // column on names like MRVL and AMAT. Same flows as the partial-sale case, and the
        // same answer, because the cost basis was never an input.
        var withNonsenseCost = compute(List.of(
                buy("2020-01-01", "TEST", 100, 10000),
                sell("2024-01-01", "TEST", 90, 14000)), 1600);

        assertRate(0.11197724, withNonsenseCost.annualisedReturn());
        assertTrue(withNonsenseCost.annualised());
    }

    @Test
    void underNinetyDaysReportsTheRawPeriodReturnInstead() {
        // Annualising a seven-week move multiplies its noise by eight and prints it as signal.
        var r = compute(List.of(buy("2025-11-10", "TEST", 10, 1000)), 1100);

        assertFalse(r.annualised());
        assertNull(r.annualisedReturn());
        assertRate(0.10, r.periodReturn());
        assertRate(0.10, r.displayReturn());
        assertEquals(52, r.episodeDays());
    }

    @Test
    void exactlyNinetyDaysIsAnnualised() {
        String start = TODAY.minusDays(90).toString();
        var r = compute(List.of(buy(start, "TEST", 10, 1000)), 1100);

        assertTrue(r.annualised());
        assertNotNull(r.annualisedReturn());
        assertEquals(90, r.episodeDays());
    }

    @Test
    void holdingPeriodIsCostWeighted() {
        // £1,000 held 730 days and £3,000 held 365: (1000×730 + 3000×365) ÷ 4000 = 456.25 days
        // = 1.25 years. The £3,000 dominates, which is the point — a small early purchase must
        // not date the whole position.
        var r = compute(List.of(
                buy("2024-01-02", "TEST", 10, 1000),
                buy("2025-01-01", "TEST", 30, 3000)), 5000);

        assertNotNull(r.holdingYears());
        assertEquals(1.25, r.holdingYears().doubleValue(), 1e-9);
    }

    @Test
    void splitsCarryNoFlow() {
        // A split changes the share count and no cash, so it must not enter the series — and
        // must not be mistaken for a purchase that re-dates the episode.
        var withSplit = compute(List.of(
                buy("2021-01-01", "TEST", 10, 1000),
                split("2022-06-01", "TEST", 30)), 2000);
        var withoutSplit = compute(List.of(buy("2021-01-01", "TEST", 10, 1000)), 2000);

        assertEquals(2, withSplit.flowCount());
        assertEquals(withoutSplit.annualisedReturn(), withSplit.annualisedReturn());
        assertEquals(1, withSplit.episodeCount());
    }

    @Test
    void nativeCurrencyLegIsolatesTheCurrencyContribution() {
        // A Roth USD position: every flow settled in USD, so a native rate is well defined and
        // the gap to the GBP rate is what the exchange rate did. USD 8,000 → 12,000 is 10.67%/yr;
        // the pound strengthened over the same window, so the GBP rate is lower.
        var buyUsd = new CashTransaction("2022-01-03", Account.ROTH_IRA, TransactionType.TRANSACTION,
                "TEST", 100, -8000.0, "USD", 1.20, -8000.0 / 1.20, null, null, "buy");
        var r = PositionIrr.compute("TEST", List.of(buyUsd),
                BigDecimal.valueOf(12000.0 / 1.35), BigDecimal.valueOf(12000.0), "USD",
                LocalDate.parse("2026-01-02"));

        assertRate(0.07456993, r.annualisedReturn());
        assertRate(0.10668192, r.nativeAnnualisedReturn());
        assertEquals("USD", r.nativeCurrency());
        assertRate(0.07456993 - 0.10668192, r.currencyContribution());
    }

    @Test
    void aGbpListingGetsNoNativeLegBecauseThereIsNoCurrencyToContribute() {
        // The headline is already in GBP. Solving it a second time under the same currency would
        // print "currency contributed +0.00%" on every UK holding — a column of zeroes that reads
        // as a finding rather than as the definition it is.
        var r = PositionIrr.compute("TEST", List.of(buy("2021-01-01", "TEST", 10, 1000)),
                BigDecimal.valueOf(2000), BigDecimal.valueOf(2000), "GBP", TODAY);

        assertNotNull(r.annualisedReturn());
        assertNull(r.nativeAnnualisedReturn());
        assertNull(r.currencyContribution());
        assertFalse(r.explain().contains("currency contributed"));
    }

    @Test
    void aMixedCurrencyLedgerYieldsNoNativeRate() {
        // The same name bought in a USD wrapper and a GBP-settling one. AJ Bell's row carries no
        // native amount at all, so a "native" series built from both would be two currencies
        // added together. Reported as absent rather than as a number.
        var buyUsd = new CashTransaction("2022-01-03", Account.ROTH_IRA, TransactionType.TRANSACTION,
                "TEST", 100, -8000.0, "USD", 1.20, -8000.0 / 1.20, null, null, "buy");
        var buyGbp = buy("2023-01-03", "TEST", 50, 4000);

        var r = PositionIrr.compute("TEST", List.of(buyUsd, buyGbp),
                BigDecimal.valueOf(14000), BigDecimal.valueOf(18000), "USD", TODAY);

        assertNotNull(r.annualisedReturn(), "the GBP leg is still sound");
        assertNull(r.nativeAnnualisedReturn());
        assertNull(r.nativeCurrency());
        assertNull(r.currencyContribution());
    }

    @Test
    void aPositionWithNoPurchasesInTheCurrentEpisodeIsUnavailable() {
        var r = compute(List.of(dividend("2024-01-01", "TEST", 50)), 1000);
        assertNull(r.annualisedReturn());
        assertNull(r.periodReturn());
        assertNull(r.displayReturn());
    }

    @Test
    void missingTerminalValueYieldsNothingRatherThanZero() {
        var r = PositionIrr.compute("TEST", List.of(buy("2021-01-01", "TEST", 10, 1000)),
                null, null, null, TODAY);
        assertNull(r.annualisedReturn());
        assertNull(r.periodReturn());
    }

    @Test
    void aTotalLossReportsThePeriodReturnAndNoRate() {
        // Worth nothing after four years: the IRR root is below the -99% bracket, so the rate is
        // absent while the period return (-100%) still states plainly what happened.
        var r = compute(List.of(buy("2022-01-01", "TEST", 10, 1000)), 0.0);
        assertNull(r.annualisedReturn());
        assertRate(-1.0, r.periodReturn());
        assertTrue(r.annualised(), "the window was long enough; the solver simply has no root");
    }
}
