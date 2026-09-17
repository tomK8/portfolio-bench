package com.portfolio.domain;

import com.portfolio.domain.model.Rung;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arithmetic of a share-class switch.
 *
 * <p>The numbers throughout are the live case: EQQQ, a fund's sterling line, moving to EQQU, its
 * dollar line, at 1.3450 USD per GBP. Both markets are set so the two listings are exactly
 * consistent with that rate — which is what two lines of one fund <em>are</em> — so anything the
 * panel reports as a gap is a gap the user chose.
 */
class SwitchesTest {

    /** The app-wide convention: units of foreign currency per 1 GBP. */
    private static final Map<String, BigDecimal> RATES = Map.of("USD", new BigDecimal("1.345"));

    /** £500 on the sterling line; the same unit is $672.50 on the dollar line at 1.345. */
    private static final Switches.LinePrice EQQQ =
            new Switches.LinePrice("EQQQ", new BigDecimal("500.00"), "GBP");
    private static final Switches.LinePrice EQQU =
            new Switches.LinePrice("EQQU", new BigDecimal("672.50"), "USD");

    private static Rung rung(String account, String side, String qty, String price, String ccy,
                             Rung.State state) {
        return new Rung(account + "|" + side + price, null, account, side,
                qty == null ? null : new BigDecimal(qty),
                price == null ? null : new BigDecimal(price), ccy, state, null, null, null,
                null, null);
    }

    // ---- the yardstick ------------------------------------------------------

    /**
     * The headline the panel exists for: £530 and $716 side by side say nothing, and in GBP they
     * say the switch buys back 0.44% above where it sells.
     */
    @Test
    void twoPricesInTwoCurrenciesComeTogetherOnOneYardstick() {
        Switches.Summary s = Switches.summarise(
                List.of(rung("II SIPP", "Sell", "100", "530.00", "GBP", Rung.State.WORKING)),
                List.of(rung("Roth IRA", "Buy", "100", "716.00", "USD", Rung.State.INTENDED)),
                EQQQ, EQQU, RATES, List.of());

        Switches.Comparison c = s.resting();
        assertTrue(c.complete());
        assertEquals(530.00, c.out().avgPriceGbp().doubleValue(), 1e-6, "sterling line is already GBP");
        assertEquals(716.00 / 1.345, c.in().avgPriceGbp().doubleValue(), 1e-6, "dollar line divided by the rate");
        assertEquals(0.0044188, c.costPct().doubleValue(), 1e-6);
        assertTrue(c.costly(), "buying above where it sells costs money");
    }

    /**
     * A GBP figure divided by the rate, not multiplied by it. Getting the convention backwards
     * would read this switch as roughly a third cheaper than it is, and in the right direction to
     * look like a bargain.
     */
    @Test
    void theDollarLegIsDividedByTheRateNotMultiplied() {
        Switches.Summary s = Switches.summarise(
                List.of(rung("II SIPP", "Sell", "10", "500.00", "GBP", Rung.State.WORKING)),
                List.of(rung("Roth IRA", "Buy", "10", "672.50", "USD", Rung.State.WORKING)),
                EQQQ, EQQU, RATES, List.of());
        assertEquals(500.00, s.resting().in().avgPriceGbp().doubleValue(), 1e-6);
        assertEquals(0.0, s.resting().costPct().doubleValue(), 1e-9,
                "the same fund level on both lines is no gap at all");
    }

    /**
     * The split is an identity, not an approximation: where the buy sits against its own market,
     * where the sell sits against its own, and the basis between the two lines multiply to the
     * total. That is what lets the panel say how much of the gap is the exchange rate — namely the
     * third term, and nothing else.
     */
    @Test
    void theThreeTermsMultiplyToTheTotalExactly() {
        Switches.Summary s = Switches.summarise(
                List.of(rung("II SIPP", "Sell", "100", "530.00", "GBP", Rung.State.WORKING)),
                List.of(rung("Roth IRA", "Buy", "100", "716.00", "USD", Rung.State.INTENDED)),
                EQQQ, EQQU, RATES, List.of());

        Switches.Comparison c = s.resting();
        double inTerm = 1 + c.in().vsMarketPct().doubleValue();
        double outTerm = 1 + c.out().vsMarketPct().doubleValue();
        double basisTerm = 1 + s.basis().deviationPct().doubleValue();
        assertEquals(1 + c.costPct().doubleValue(), inTerm / outTerm * basisTerm, 1e-9);
    }

    /**
     * The other reading of the same number, and the one the user asked for in so many words: the
     * two prices imply an exchange rate, the market has one, and the difference is the part of the
     * gap that is <em>not</em> the exchange rate.
     */
    @Test
    void theImpliedCrossAgainstTheMarketCrossIsTheSameFigureAsTheCost() {
        Switches.Summary s = Switches.summarise(
                List.of(rung("II SIPP", "Sell", "100", "530.00", "GBP", Rung.State.WORKING)),
                List.of(rung("Roth IRA", "Buy", "100", "716.00", "USD", Rung.State.INTENDED)),
                EQQQ, EQQU, RATES, List.of());

        Switches.Comparison c = s.resting();
        assertEquals(716.0 / 530.0, c.impliedCross().doubleValue(), 1e-9);
        assertEquals(1.345, c.marketCross().doubleValue(), 1e-9);
        double implied = c.impliedCross().doubleValue() / c.marketCross().doubleValue() - 1;
        assertEquals(c.costPct().doubleValue(), implied, 1e-9);
    }

    /**
     * Two listings of one fund differ by exactly the exchange rate, so once both are in GBP they are
     * the same number. The basis is the only live evidence the panel has that the pair is the pair.
     */
    @Test
    void aGenuinePairHasNoBasisAndOneThatDoesNotTrackIsFlagged() {
        Switches.Summary same = Switches.summarise(List.of(), List.of(), EQQQ, EQQU, RATES, List.of());
        assertEquals(0.0, same.basis().deviationPct().doubleValue(), 1e-9);
        assertFalse(same.basis().wide());

        Switches.LinePrice drifted = new Switches.LinePrice("EQQU", new BigDecimal("700.00"), "USD");
        Switches.Summary off = Switches.summarise(List.of(), List.of(), EQQQ, drifted, RATES, List.of());
        assertTrue(off.basis().wide(), "4% apart is not one fund seen twice");
    }

    /** No price on a line means no yardstick — and the panel says so rather than inventing one. */
    @Test
    void anUnpricedLineLeavesTheComparisonBlankRatherThanGuessed() {
        Switches.Summary s = Switches.summarise(
                List.of(rung("II SIPP", "Sell", "100", "530.00", "GBP", Rung.State.WORKING)),
                List.of(rung("Roth IRA", "Buy", "100", "716.00", "USD", Rung.State.INTENDED)),
                EQQQ, Switches.LinePrice.none("EQQU"), RATES, List.of());
        assertNull(s.basis().deviationPct(), "nothing to compare the lines with");
        assertNull(s.resting().in().vsMarketPct(), "no market to sit against");
        assertNotNull(s.resting().costPct(), "the two legs still convert — only the split is short");
    }

    /** Weighted by quantity, because three legs of 10, 10 and 300 are not three equal opinions. */
    @Test
    void theAverageIsWeightedByQuantityNotByLeg() {
        Switches.Summary s = Switches.summarise(
                List.of(rung("II SIPP", "Sell", "10", "600.00", "GBP", Rung.State.WORKING),
                        rung("II SIPP", "Sell", "90", "500.00", "GBP", Rung.State.WORKING)),
                List.of(), EQQQ, EQQU, RATES, List.of());
        assertEquals(510.0, s.resting().out().avgPrice().doubleValue(), 1e-6);
    }

    // ---- progress -----------------------------------------------------------

    /**
     * Three states, not two. Units sold and not yet bought back are in neither line: they are cash,
     * they own nothing, and that is the state a half-finished switch gets lost in.
     */
    @Test
    void unitsSoldButNotBoughtBackAreOutOfTheMarket() {
        Switches.Summary s = Switches.summarise(
                List.of(rung("II SIPP", "Sell", "40", "530.00", "GBP", Rung.State.FILLED),
                        rung("II SIPP", "Sell", "60", "545.00", "GBP", Rung.State.WORKING)),
                List.of(rung("Roth IRA", "Buy", "25", "716.00", "USD", Rung.State.FILLED)),
                EQQQ, EQQU, RATES, List.of());

        Switches.Progress p = s.progress();
        assertEquals(100, p.declared().intValue(), "the switch covers what its sell legs add up to");
        assertEquals(60, p.inOldLine().intValue());
        assertEquals(15, p.inFlight().intValue(), "40 sold, 25 bought back");
        assertEquals(25, p.inNewLine().intValue());
        assertTrue(p.outOfMarket());
        assertFalse(p.complete());
        assertEquals(25.0, p.movedPct().multiply(new BigDecimal("100")).doubleValue(), 1e-6);
    }

    /**
     * Complete means the holding has arrived, not that the order screen is empty. A sell that filled
     * and a buy that never did leaves nothing resting and is the state the panel most exists for.
     */
    @Test
    void nothingRestingIsNotTheSameAsComplete() {
        Switches.Summary stranded = Switches.summarise(
                List.of(rung("II SIPP", "Sell", "100", "530.00", "GBP", Rung.State.FILLED)),
                List.of(), EQQQ, EQQU, RATES, List.of());
        assertFalse(stranded.progress().complete());
        assertEquals(100, stranded.progress().inFlight().intValue());

        Switches.Summary done = Switches.summarise(
                List.of(rung("II SIPP", "Sell", "100", "530.00", "GBP", Rung.State.FILLED)),
                List.of(rung("Roth IRA", "Buy", "100", "716.00", "USD", Rung.State.FILLED)),
                EQQQ, EQQU, RATES, List.of());
        assertTrue(done.progress().complete());
    }

    /** A cancelled leg is part of how the switch played out, and counted in nothing. */
    @Test
    void aCancelledLegDoesNotCountTowardsWhatTheSwitchCovers() {
        Switches.Summary s = Switches.summarise(
                List.of(rung("II SIPP", "Sell", "100", "530.00", "GBP", Rung.State.FILLED),
                        rung("II SIPP", "Sell", "40", "560.00", "GBP", Rung.State.CANCELLED)),
                List.of(rung("Roth IRA", "Buy", "100", "716.00", "USD", Rung.State.FILLED)),
                EQQQ, EQQU, RATES, List.of());
        assertEquals(100, s.progress().declared().intValue());
        assertTrue(s.progress().complete());
    }

    /** A partial sum wearing a full one's clothes would read as a finished switch. */
    @Test
    void aLegWithNoQuantityLeavesTheProgressUnknownRatherThanPartial() {
        Switches.Summary s = Switches.summarise(
                List.of(rung("II SIPP", "Sell", null, "530.00", "GBP", Rung.State.WORKING),
                        rung("II SIPP", "Sell", "60", "545.00", "GBP", Rung.State.WORKING)),
                List.of(), EQQQ, EQQU, RATES, List.of());
        assertFalse(s.progress().known());
        assertNull(s.progress().declared());
        assertTrue(s.excluded().stream().anyMatch(x -> x.contains("no quantity")));
    }

    // ---- cash and sequencing ------------------------------------------------

    /**
     * The precise error the panel exists to stop: £53,000 raised in one wrapper and $53,700 needed
     * in another is not cash-neutral. Two lines, never netted.
     */
    @Test
    void cashIsPerAccountAndCurrencyAndIsNeverNetted() {
        Switches.Summary s = Switches.summarise(
                List.of(rung("II SIPP", "Sell", "100", "530.00", "GBP", Rung.State.WORKING)),
                List.of(rung("Roth IRA", "Buy", "75", "716.00", "USD", Rung.State.INTENDED)),
                EQQQ, EQQU, RATES, List.of());

        assertEquals(2, s.cash().size());
        Ladders.CashLine sell = s.cash().stream()
                .filter(c -> c.account().equals("II SIPP")).findFirst().orElseThrow();
        Ladders.CashLine buy = s.cash().stream()
                .filter(c -> c.account().equals("Roth IRA")).findFirst().orElseThrow();
        assertEquals(53000.0, sell.raises().doubleValue(), 1e-6);
        assertEquals("GBP", sell.currency());
        assertEquals(53700.0, buy.needs().doubleValue(), 1e-6);
        assertEquals("USD", buy.currency());
    }

    /**
     * What is actually blocking the buy leg, and it is nearly never a decision: the money is in the
     * wrong account in the wrong currency, and the ledger says how much of it is there.
     */
    @Test
    void theBuyLegIsBlockedByTheCashThatIsNotThereYet() {
        Switches.Summary s = Switches.summarise(
                List.of(rung("II SIPP", "Sell", "40", "530.00", "GBP", Rung.State.FILLED),
                        rung("II SIPP", "Sell", "60", "545.00", "GBP", Rung.State.WORKING)),
                List.of(rung("Roth IRA", "Buy", "75", "716.00", "USD", Rung.State.INTENDED)),
                EQQQ, EQQU, RATES,
                List.of(new Switches.CashAvailability("Roth IRA", "USD",
                        new BigDecimal("412.00"), "2026-09-12")));

        List<Switches.Step> steps = s.sequence();
        assertEquals(5, steps.size());
        assertEquals(Switches.StepState.WAITING, steps.get(0).state(), "60 units still in the old line");
        assertEquals(Switches.StepState.WAITING, steps.get(2).state(), "II SIPP is not Roth IRA");
        assertEquals(Switches.StepState.WAITING, steps.get(3).state(), "GBP is not USD");
        assertEquals(Switches.StepState.SHORT, steps.get(4).state());
        assertTrue(steps.get(4).detail().contains("Short 53,288.00 USD"), steps.get(4).detail());
        assertTrue(steps.get(4).detail().contains("2026-09-12"),
                "a shortfall claimed off the ledger has to say as at when");
        assertEquals("Sell EQQQ", s.blockedBy().title(), "the first step that is not done");
    }

    /**
     * A move inside one account and one currency has no plumbing to wait on, and the panel says
     * "not needed" rather than dropping the step — that the two legs share a wrapper is the fact
     * worth reading.
     */
    @Test
    void aSwitchInsideOneAccountAndCurrencyHasNoTransferOrConversionToWaitFor() {
        Switches.LinePrice sterlingTwo =
                new Switches.LinePrice("EQGB", new BigDecimal("500.00"), "GBP");
        Switches.Summary resting = Switches.summarise(
                List.of(rung("II SIPP", "Sell", "100", "500.00", "GBP", Rung.State.FILLED)),
                List.of(rung("II SIPP", "Buy", "100", "500.00", "GBP", Rung.State.WORKING)),
                EQQQ, sterlingTwo, RATES, List.of());

        assertEquals(Switches.StepState.NOT_NEEDED, resting.sequence().get(2).state());
        assertEquals(Switches.StepState.NOT_NEEDED, resting.sequence().get(3).state());
        // Placed but not filled is still waiting — on the market, not on the plumbing.
        assertEquals("Buy EQGB", resting.blockedBy().title());
        assertEquals(Switches.StepState.WAITING, resting.blockedBy().state());

        Switches.Summary done = Switches.summarise(
                List.of(rung("II SIPP", "Sell", "100", "500.00", "GBP", Rung.State.FILLED)),
                List.of(rung("II SIPP", "Buy", "100", "500.00", "GBP", Rung.State.FILLED)),
                EQQQ, sterlingTwo, RATES, List.of());
        assertNull(done.blockedBy(), "nothing outstanding once both legs have traded");
    }

    /**
     * Settlement is reported, never asserted. This tool reads order exports and a cash ledger and
     * neither says "that trade settled", so the step prints the ledger's own figure and its date and
     * leaves the conclusion to the reader.
     */
    @Test
    void theSettlementStepReportsTheLedgerRatherThanJudgingIt() {
        Switches.Summary s = Switches.summarise(
                List.of(rung("II SIPP", "Sell", "100", "530.00", "GBP", Rung.State.FILLED)),
                List.of(rung("Roth IRA", "Buy", "100", "716.00", "USD", Rung.State.INTENDED)),
                EQQQ, EQQU, RATES,
                List.of(new Switches.CashAvailability("II SIPP", "GBP",
                        new BigDecimal("53000.00"), "2026-09-12")));
        Switches.Step settle = s.sequence().get(1);
        assertEquals(Switches.StepState.INFO, settle.state());
        assertFalse(settle.state().blocks(), "a reported figure cannot block anything");
        assertTrue(settle.detail().contains("53,000.00"));
        assertTrue(settle.detail().contains("not in any export"));
    }

    /** A leg with no price is dropped from the figure it would corrupt, and named. */
    @Test
    void aLegWithNoPriceIsLeftOutOfTheCashLinesAndSaidSo() {
        Switches.Summary s = Switches.summarise(
                List.of(rung("II SIPP", "Sell", "100", null, "GBP", Rung.State.WORKING)),
                List.of(), EQQQ, EQQU, RATES, List.of());
        assertTrue(s.cash().isEmpty());
        assertTrue(s.excluded().stream().anyMatch(x -> x.contains("no price")));
    }

    /** Filled and unfilled legs are averaged separately: mixing them would be an average of nothing. */
    @Test
    void whatHasTradedAndWhatIsStillRestingAreTwoComparisonsNotOne() {
        Switches.Summary s = Switches.summarise(
                List.of(rung("II SIPP", "Sell", "40", "520.00", "GBP", Rung.State.FILLED),
                        rung("II SIPP", "Sell", "60", "545.00", "GBP", Rung.State.WORKING)),
                List.of(rung("Roth IRA", "Buy", "40", "700.00", "USD", Rung.State.FILLED),
                        rung("Roth IRA", "Buy", "60", "716.00", "USD", Rung.State.INTENDED)),
                EQQQ, EQQU, RATES, List.of());
        assertEquals(520.0, s.filled().out().avgPrice().doubleValue(), 1e-6);
        assertEquals(545.0, s.resting().out().avgPrice().doubleValue(), 1e-6);
        assertEquals(40, s.filled().matchedQuantity().intValue());
        assertEquals(60, s.resting().matchedQuantity().intValue());
    }

    /** One side alone is not a comparison, and no number is better than a number about one leg. */
    @Test
    void oneSideAloneYieldsNoComparison() {
        Switches.Summary s = Switches.summarise(
                List.of(rung("II SIPP", "Sell", "100", "530.00", "GBP", Rung.State.WORKING)),
                List.of(), EQQQ, EQQU, RATES, List.of());
        assertFalse(s.resting().complete());
        assertNull(s.resting().costPct());
        assertNull(s.resting().perUnitGbp());
    }

    /** The progress bar's three shares add to the whole, so the bar cannot lie about what is left. */
    @Test
    void theThreeProgressSharesAddToOneHundred() {
        Switches.Summary s = Switches.summarise(
                List.of(rung("II SIPP", "Sell", "40", "530.00", "GBP", Rung.State.FILLED),
                        rung("II SIPP", "Sell", "60", "545.00", "GBP", Rung.State.WORKING)),
                List.of(rung("Roth IRA", "Buy", "25", "716.00", "USD", Rung.State.FILLED)),
                EQQQ, EQQU, RATES, List.of());
        Switches.Progress p = s.progress();
        BigDecimal total = p.pctInOldLine().add(p.pctInFlight()).add(p.pctInNewLine());
        assertEquals(0, total.setScale(2, RoundingMode.HALF_UP).compareTo(new BigDecimal("100.00")));
    }

    /**
     * A London quote arrives in pence and the rest of the app prints pounds. Folded once, in the
     * constructor: a line left in pence would convert correctly and still display as £53,860 beside
     * its own £538 limit, which reads as a broken pair rather than a unit mismatch.
     */
    @Test
    void aLondonQuoteInPenceBecomesPoundsOnce() {
        Switches.LinePrice pence =
                new Switches.LinePrice("EQQQ", new BigDecimal("53860"), "GBp");
        assertEquals("GBP", pence.currency());
        assertEquals(538.60, pence.price().doubleValue(), 1e-9);

        Switches.Summary s = Switches.summarise(
                List.of(rung("II SIPP", "Sell", "100", "538.60", "GBP", Rung.State.WORKING)),
                List.of(), pence, EQQU, RATES, List.of());
        assertEquals(0.0, s.resting().out().vsMarketPct().doubleValue(), 1e-9,
                "the limit is the market, and would be a hundredth of it unconverted");
    }
}
