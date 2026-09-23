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

    /**
     * A leg on whichever line quotes in that currency — EQQQ is the sterling line and EQQU the
     * dollar one, so the currency names the listing here and the helper stays a one-liner. Tests
     * that put both lines on one side name the listing themselves with {@link #on}.
     */
    private static Rung rung(String account, String side, String qty, String price, String ccy,
                             Rung.State state) {
        return on("GBP".equals(ccy) || "GBp".equals(ccy) ? "EQQQ" : "EQQU",
                account, side, qty, price, ccy, state);
    }

    private static Rung on(String symbol, String account, String side, String qty, String price,
                           String ccy, Rung.State state) {
        return new Rung(account + "|" + symbol + side + price, null, account, symbol, side,
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
        assertEquals(530.00, c.sellSide().avgPriceGbp().doubleValue(), 1e-6, "sterling line is already GBP");
        assertEquals(716.00 / 1.345, c.buySide().avgPriceGbp().doubleValue(), 1e-6, "dollar line divided by the rate");
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
        assertEquals(500.00, s.resting().buySide().avgPriceGbp().doubleValue(), 1e-6);
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
        double inTerm = 1 + c.buySide().vsMarketPct().doubleValue();
        double outTerm = 1 + c.sellSide().vsMarketPct().doubleValue();
        double basisTerm = 1 + s.basis().deviationPct().doubleValue();
        assertEquals(1 + c.costPct().doubleValue(), inTerm / outTerm * basisTerm, 1e-9);
    }

    // ---- worked from both ends ---------------------------------------------

    /**
     * The identity survives a side that spans both listings, which is the whole argument for
     * blending the market price rather than pinning each side to one line.
     *
     * <p>{@code (1 + in vs market) / (1 + out vs market) x (1 + FX term)} is {@code 1 + cost} for
     * <em>any</em> pair of market prices used in both terms, so defining each side's market as the
     * quantity-weighted blend of the listings actually on it keeps the three printed factors exact
     * rather than nearly right. An approximation here would be the panel quietly attributing part
     * of a gap the user chose to the exchange rate, which is the one thing it exists to separate.
     */
    @Test
    void theThreeTermsStillMultiplyExactlyWhenASideSpansBothListings() {
        Switches.Summary s = Switches.summarise(
                List.of(on("EQQQ", "II SIPP", "Sell", "60", "545.00", "GBP", Rung.State.WORKING),
                        on("EQQU", "Roth IRA", "Sell", "40", "730.00", "USD", Rung.State.WORKING)),
                List.of(on("EQQU", "Roth IRA", "Buy", "70", "660.00", "USD", Rung.State.INTENDED),
                        on("EQQQ", "II SIPP", "Buy", "30", "492.00", "GBP", Rung.State.WORKING)),
                EQQQ, EQQU, RATES, List.of());

        Switches.Comparison c = s.resting();
        double inTerm = 1 + c.buySide().vsMarketPct().doubleValue();
        double outTerm = 1 + c.sellSide().vsMarketPct().doubleValue();
        double fxTerm = 1 + c.splitBasisPct().doubleValue();
        assertEquals(1 + c.costPct().doubleValue(), inTerm / outTerm * fxTerm, 1e-9);
    }

    /**
     * A one-way switch computes exactly what it always did. The blend of a single listing is that
     * listing, so the split's FX term is the two lines against the exchange rate — the same number
     * the basis block prints, and the one an edit must not be able to move.
     */
    @Test
    void aOneWaySwitchSplitsOnTheLinesExactlyAsItAlwaysDid() {
        Switches.Summary s = Switches.summarise(
                List.of(rung("II SIPP", "Sell", "100", "530.00", "GBP", Rung.State.WORKING)),
                List.of(rung("Roth IRA", "Buy", "100", "716.00", "USD", Rung.State.INTENDED)),
                EQQQ, EQQU, RATES, List.of());

        assertEquals(s.basis().deviationPct().doubleValue(),
                s.resting().splitBasisPct().doubleValue(), 1e-12,
                "one listing a side, so the blend is the line itself");
    }

    /**
     * Pounds and dollars have no mean, so a side on both listings has no native average and no
     * implied cross rate. Absent and said so — a blended figure would print as money and be none,
     * which is the error the GBP column exists to make unnecessary.
     */
    @Test
    void aSideOnBothListingsHasNoNativeAverageAndNoImpliedCross() {
        Switches.Summary s = Switches.summarise(
                List.of(on("EQQQ", "II SIPP", "Sell", "50", "530.00", "GBP", Rung.State.WORKING),
                        on("EQQU", "Roth IRA", "Sell", "50", "716.00", "USD", Rung.State.WORKING)),
                List.of(on("EQQU", "Roth IRA", "Buy", "100", "672.50", "USD", Rung.State.INTENDED)),
                EQQQ, EQQU, RATES, List.of());

        Switches.LegSide sell = s.resting().sellSide();
        assertTrue(sell.mixed());
        assertNull(sell.currency(), "two currencies, so the side has none");
        assertNull(sell.avgPrice(), "and therefore no native average");
        assertNull(s.resting().impliedCross(), "an implied rate needs one currency each side");
        assertEquals("EQQQ / EQQU", sell.symbol(), "both lines named, so the reader knows why");

        // The yardstick still answers, which is the point: (50x530 + 50x716/1.345) / 100.
        assertEquals((50 * 530.00 + 50 * 716.00 / 1.345) / 100,
                sell.avgPriceGbp().doubleValue(), 1e-6);
        assertNotNull(s.resting().costPct(), "the comparison is unaffected — it was always GBP");
    }

    /**
     * A mixed side is judged against a blend of its own listings, weighted the same way its average
     * was. Both lines sit at the same GBP level on a genuine pair, so the blend lands there too and
     * the limit reads against the market exactly as a single-line side would.
     */
    @Test
    void aMixedSideIsJudgedAgainstTheBlendOfItsOwnListings() {
        Switches.Summary s = Switches.summarise(
                List.of(on("EQQQ", "II SIPP", "Sell", "75", "500.00", "GBP", Rung.State.WORKING),
                        on("EQQU", "Roth IRA", "Sell", "25", "672.50", "USD", Rung.State.WORKING)),
                List.of(), EQQQ, EQQU, RATES, List.of());

        Switches.LegSide sell = s.resting().sellSide();
        assertEquals(500.00, sell.marketGbp().doubleValue(), 1e-6,
                "both lines are £500 in GBP, so any weighting of them is £500");
        assertEquals(0.0, sell.vsMarketPct().doubleValue(), 1e-9,
                "every leg is at its own market, so the side is too");
    }

    /**
     * Buying back more than went out is not a switch state, and the panel says so rather than
     * clamping a bar that would read as finished. A one-way switch cannot reach it; one worked from
     * both ends can, and then the holding has grown rather than moved.
     */
    @Test
    void buyingBackMoreThanWasSoldIsNamedRatherThanDrawn() {
        Switches.Summary s = Switches.summarise(
                List.of(on("EQQQ", "II SIPP", "Sell", "40", "530.00", "GBP", Rung.State.FILLED)),
                List.of(on("EQQU", "Roth IRA", "Buy", "70", "716.00", "USD", Rung.State.FILLED)),
                EQQQ, EQQU, RATES, List.of());

        Switches.Progress p = s.progress();
        assertTrue(p.known());
        assertFalse(p.representable(), "the three shares no longer make a whole");
        assertEquals(0, new BigDecimal("30").compareTo(p.overBought()));
        assertFalse(p.complete(), "more came back than went out — that is not a completed move");
    }

    /**
     * A leg on a listing that has never priced takes the whole side's market blend with it, rather
     * than leaving a blend over a different set of legs than the average it is divided into. The
     * average still prints; only the comparison to market goes, which is the app-wide rule.
     */
    @Test
    void oneUnpricedListingLeavesTheWholeSideWithNoMarketToSitAgainst() {
        Switches.Summary s = Switches.summarise(
                List.of(on("EQQQ", "II SIPP", "Sell", "50", "530.00", "GBP", Rung.State.WORKING),
                        on("EQQU", "Roth IRA", "Sell", "50", "716.00", "USD", Rung.State.WORKING)),
                List.of(), EQQQ, Switches.LinePrice.none("EQQU"), RATES, List.of());

        Switches.LegSide sell = s.resting().sellSide();
        assertNotNull(sell.avgPriceGbp(), "both legs still convert");
        assertNull(sell.marketGbp(), "one of the two lines has no price");
        assertNull(sell.vsMarketPct(), "so there is nothing to sit against");
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
        assertNull(s.resting().buySide().vsMarketPct(), "no market to sit against");
        assertNotNull(s.resting().costPct(), "the two legs still convert — only the split is short");
    }

    /** Weighted by quantity, because three legs of 10, 10 and 300 are not three equal opinions. */
    @Test
    void theAverageIsWeightedByQuantityNotByLeg() {
        Switches.Summary s = Switches.summarise(
                List.of(rung("II SIPP", "Sell", "10", "600.00", "GBP", Rung.State.WORKING),
                        rung("II SIPP", "Sell", "90", "500.00", "GBP", Rung.State.WORKING)),
                List.of(), EQQQ, EQQU, RATES, List.of());
        assertEquals(510.0, s.resting().sellSide().avgPrice().doubleValue(), 1e-6);
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
        // Both lines quote in GBP here, so the currency cannot name the listing — each leg says.
        Switches.Summary resting = Switches.summarise(
                List.of(on("EQQQ", "II SIPP", "Sell", "100", "500.00", "GBP", Rung.State.FILLED)),
                List.of(on("EQGB", "II SIPP", "Buy", "100", "500.00", "GBP", Rung.State.WORKING)),
                EQQQ, sterlingTwo, RATES, List.of());

        assertEquals(Switches.StepState.NOT_NEEDED, resting.sequence().get(2).state());
        assertEquals(Switches.StepState.NOT_NEEDED, resting.sequence().get(3).state());
        // Placed but not filled is still waiting — on the market, not on the plumbing.
        assertEquals("Buy EQGB", resting.blockedBy().title());
        assertEquals(Switches.StepState.WAITING, resting.blockedBy().state());

        Switches.Summary done = Switches.summarise(
                List.of(on("EQQQ", "II SIPP", "Sell", "100", "500.00", "GBP", Rung.State.FILLED)),
                List.of(on("EQGB", "II SIPP", "Buy", "100", "500.00", "GBP", Rung.State.FILLED)),
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
        assertEquals(520.0, s.filled().sellSide().avgPrice().doubleValue(), 1e-6);
        assertEquals(545.0, s.resting().sellSide().avgPrice().doubleValue(), 1e-6);
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
        assertEquals(0.0, s.resting().sellSide().vsMarketPct().doubleValue(), 1e-9,
                "the limit is the market, and would be a hundredth of it unconverted");
    }
}
