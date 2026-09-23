package com.portfolio.domain;

import com.portfolio.domain.model.OpenOrder;
import com.portfolio.domain.model.Rung;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The arithmetic of a share-class switch: moving one holding from one listing of a fund to another
 * listing of the same fund.
 *
 * <p>Framework-free and static, like {@link Ladders}, and it takes {@link Rung}s for the same
 * reason — an order resting at a broker and a leg the user has decided on but cannot place yet are
 * the same input to every figure here, and on a switch the unplaceable leg is the <em>normal</em>
 * state rather than the exception.
 *
 * <h2>Why a ladder's arithmetic will not do</h2>
 * A ladder lays its rungs on one price axis because they are one security. The two legs of a switch
 * are not: £530 and $716 are the same thing seen through the exchange rate, and putting them on one
 * axis would draw a 35% gap where there is no gap at all. Every figure here therefore converts to
 * <b>one yardstick</b> — GBP, the book's own currency — before comparing anything, and the numbers
 * that matter are what is left <em>after</em> the exchange rate is divided out.
 *
 * <h2>Two sides, not two lines</h2>
 * A side here is a <b>direction</b> — what is being sold, and what is being bought — and each side
 * may sit on <em>either</em> of the pair's two listings. Limits go out on both lines at once in the
 * ordinary case: sells above on one, buys below on the other, and which line each ends up on is
 * whichever was dearer on the day. Binding the sell side to one listing would have made that
 * ordinary case unrecordable, and it is the case the panel is for.
 *
 * <p>So each leg is measured against <b>its own</b> listing's market price, and a side's market
 * price is the quantity-weighted blend of the listings actually on it. The split below survives
 * that unchanged — the identity holds for any {@code Pout}/{@code Pin} used in both terms — and
 * when every leg of a side is on one listing the blend <em>is</em> that listing's price, so a
 * one-way switch computes exactly what it always did, to the last decimal place.
 *
 * <p>What a mixed side does not have is a <b>native</b> average: £584.80 and $682.35 have no
 * meaningful mean, so {@code avgPrice} and the implied cross rate go null and are printed as
 * absent, never as a blended figure that would look like money and be none.
 *
 * <h2>The split, and why it is exact</h2>
 * With {@code S} the average price on the line being sold, {@code B} on the line being bought,
 * {@code Pout}/{@code Pin} the two lines' current market prices and everything subscripted
 * {@code gbp} converted at today's rate:
 *
 * <pre>
 *   1 + cost = (1 + inVsMarket) / (1 + outVsMarket) x (1 + basis)
 * </pre>
 *
 * where {@code cost = B_gbp/S_gbp - 1}, {@code inVsMarket = B/Pin - 1},
 * {@code outVsMarket = S/Pout - 1} and {@code basis = Pin_gbp/Pout_gbp - 1}. That is an identity,
 * not an approximation: substitute {@code B = (1+inVsMarket)·Pin} and the same for {@code S} and the
 * terms cancel. Its three factors are exactly the three things the user has to tell apart — where
 * the buy limit sits against its own market, where the sell limit sits against its own, and whether
 * the two <em>lines</em> are consistent with the exchange rate. The last one is the FX term, and on
 * a genuine share-class pair it should be ~0: the pair's price ratio <em>is</em> the rate.
 *
 * <p><b>Nothing is summed over a leg it cannot be computed for.</b> A leg with no price, no quantity
 * or no currency is dropped from the figure it would have corrupted and named in
 * {@code excluded} — the app-wide rule.
 */
public final class Switches {

    /** Money-per-share intermediates; ten places is far past any broker's precision. */
    private static final int SCALE = 10;
    private static final BigDecimal ONE = BigDecimal.ONE;

    private Switches() {
    }

    // ---- inputs -------------------------------------------------------------

    /**
     * One listing's live market price, as the feed carries it.
     *
     * @param price    per share, in {@code currency}'s <b>major</b> units; null when the line has
     *                 never priced
     * @param currency the listing's own quote currency
     */
    public record LinePrice(String symbol, BigDecimal price, String currency) {

        /**
         * London pence are folded into pounds here and only here, so no caller can hold a
         * {@code GBp} line price. A raw LSE quote is in pence and the rest of the app prints prices
         * in pounds; a line that stayed in pence would convert correctly and <em>display</em> as
         * £53,860 beside its own £538 limit, which reads as a broken pair rather than a unit
         * mismatch. Normalising in the constructor makes that state unrepresentable.
         */
        public LinePrice {
            if ("GBp".equals(currency) && price != null) {
                price = price.movePointLeft(2);
                currency = "GBP";
            }
        }

        public static LinePrice none(String symbol) {
            return new LinePrice(symbol, null, null);
        }

        public boolean priced() {
            return price != null && currency != null;
        }

        /** The same mapping an order row uses, so a line and its legs never print different money. */
        public String currencySymbol() {
            return OpenOrder.symbolFor(currency);
        }
    }

    /**
     * Cash the ledger says an account holds in one currency.
     *
     * @param asOf the ledger's own latest date for that account — printed beside the figure,
     *             because a cash balance imported a fortnight ago is a claim about a fortnight ago
     *             and a blocker that says "you are short" has to be checkable.
     */
    public record CashAvailability(String account, String currency, BigDecimal available,
                                   String asOf) {
    }

    // ---- outputs ------------------------------------------------------------

    /**
     * One side of the switch — everything being sold, or everything being bought — averaged over
     * whichever legs were asked for.
     *
     * @param symbol       the listings this side actually sits on, joined for display. Usually one;
     *                     both when limits are out on each line at once. A side with no legs falls
     *                     back to the line it would ordinarily be on, so an absent side still says
     *                     which one is missing.
     * @param listings     the same, unjoined, for a caller that needs them apart. Empty on a side
     *                     with no legs, which is what {@link #mixed()} is careful to ask about.
     * @param currency     the one currency every leg of this side quotes in, or <b>null</b> when
     *                     the side spans both lines and therefore both currencies.
     * @param avgPrice     quantity-weighted, in that currency — what the broker screen shows. Null
     *                     on a mixed side: pounds and dollars have no mean, and inventing one is
     *                     the error this whole class exists to prevent.
     * @param avgPriceGbp  the same on the yardstick, so the two sides can be read against each
     *                     other. Always available when anything on the side could be converted,
     *                     which is why it and not {@code avgPrice} is what {@link #present()} asks
     *                     about.
     * @param marketGbp    this side's own market price in GBP: each leg's listing weighted by that
     *                     leg's quantity, over exactly the legs in {@code avgPriceGbp}. Null when
     *                     any of them sits on a listing that has never priced — the blend would
     *                     otherwise be over a different set of legs than the average, and the split
     *                     below would stop being an identity.
     * @param vsMarketPct  where that average sits against {@code marketGbp}, as a signed fraction.
     *                     Currency-free by construction (both sides converted), which is what lets
     *                     it stand as the non-FX half of the split.
     */
    public record LegSide(String symbol, List<String> listings, String currency,
                          BigDecimal quantity, BigDecimal avgPrice, BigDecimal avgPriceGbp,
                          BigDecimal marketGbp, BigDecimal vsMarketPct, int legCount) {

        /** Something to compare: legs, and a price for them on the yardstick. */
        public boolean present() {
            return legCount > 0 && avgPriceGbp != null;
        }

        /** This side has legs on both listings, so it has no single quote currency. */
        public boolean mixed() {
            return listings != null && listings.size() > 1;
        }

        public String currencySymbol() {
            return OpenOrder.symbolFor(currency);
        }
    }

    /**
     * The two sides on one yardstick.
     *
     * @param perUnitGbp    what one unit costs to move: buy price less sell price, both in GBP.
     *                      Positive means buying above where you are selling.
     * @param costPct       the same as a fraction of the sell price — the headline.
     * @param impliedCross  the exchange rate the user's <em>own two prices</em> imply, in units of
     *                      the buy currency per unit of the sell currency.
     * @param marketCross   what that rate actually is. {@code impliedCross / marketCross - 1} is
     *                      {@code costPct} exactly, which is the point: it says how much of the gap
     *                      between £530 and $716 is <em>not</em> the exchange rate.
     * @param totalGbp      {@code perUnitGbp} over the matched quantity — the smaller of the two
     *                      sides, since the excess on the longer one has no counterpart to be
     *                      compared against.
     * @param splitBasisPct the FX term of the split: the buy side's own market price against the
     *                      sell side's, both in GBP. When each side sits on one listing this is the
     *                      two <em>lines</em> against the exchange rate and is identical to
     *                      {@link Basis#deviationPct()} — the un-editable evidence that the pair is
     *                      the pair. When a side spans both lines it is the same figure blended
     *                      across them, which on a genuine pair moves it by nothing, since the two
     *                      lines in GBP are the same number. It is carried separately from
     *                      {@code Basis} so the three printed terms multiply to {@code costPct}
     *                      exactly in both cases.
     */
    public record Comparison(LegSide sellSide, LegSide buySide, BigDecimal perUnitGbp,
                             BigDecimal costPct, BigDecimal matchedQuantity, BigDecimal totalGbp,
                             BigDecimal impliedCross, BigDecimal marketCross,
                             BigDecimal splitBasisPct) {

        /** Both sides present and priced — otherwise there is no comparison, and no number is shown. */
        public boolean complete() {
            return sellSide.present() && buySide.present() && costPct != null;
        }

        /** True when the switch is buying back above where it sells — the gap costs money. */
        public boolean costly() {
            return costPct != null && costPct.signum() > 0;
        }
    }

    /**
     * The two lines against each other at this instant — the live evidence that they are one fund.
     *
     * <p>On a share-class pair {@code deviationPct} should sit at zero: the ratio between the two
     * listings' prices <em>is</em> the exchange rate, so once both are in GBP they are the same
     * number. A reading that is not ~0 says one of three things and the panel says so rather than
     * interpreting it: a quote is stale, the pair is not the pair the user thinks it is, or the two
     * lines are not one unit for one unit — which the progress figures below assume.
     */
    public record Basis(LinePrice outLine, LinePrice inLine,
                        BigDecimal outPriceGbp, BigDecimal inPriceGbp, BigDecimal deviationPct,
                        BigDecimal impliedCross, BigDecimal marketCross) {

        public boolean known() {
            return deviationPct != null;
        }

        /** Beyond half a percent the two lines are not tracking, and everything unit-for-unit is suspect. */
        public boolean wide() {
            return deviationPct != null && deviationPct.abs().compareTo(new BigDecimal("0.005")) > 0;
        }
    }

    /**
     * How far through the switch the holding is, in units.
     *
     * <p>Three states, not two, and the third is the one worth having: units sold but not yet
     * bought back are <b>out of the market</b> — not in the old line, not in the new one, sitting
     * as cash while the index does whatever it does. That is the exposure a half-finished switch
     * actually carries, and it is invisible in any view that counts positions.
     *
     * @param declared what this switch covers: the sell legs it has, filled and unfilled together.
     *                 Derived from the legs rather than typed in at creation, so it cannot go stale
     *                 — extend the switch by recording another leg and it extends. A leg the user
     *                 has decided on but not placed counts, which is the whole reason intents exist.
     */
    public record Progress(BigDecimal declared, BigDecimal sold, BigDecimal bought,
                           BigDecimal inOldLine, BigDecimal inFlight, BigDecimal inNewLine,
                           BigDecimal movedPct, boolean complete, boolean started) {

        public boolean known() {
            return declared != null && declared.signum() > 0;
        }

        /** Units sold and not yet bought back — the part of the holding that owns nothing right now. */
        public boolean outOfMarket() {
            return inFlight != null && inFlight.signum() > 0;
        }

        /**
         * Units bought back <em>beyond</em> what was sold, which a switch worked from both ends can
         * reach and a one-way one cannot.
         *
         * <p>It is not a switch state at all: more units came back than went out, so the holding
         * grew rather than moved, and the three shares stop summing to the whole. Named instead of
         * drawn — clamping it would paint a bar that says the move is finished, and dropping it
         * would lose the one fact that makes the other numbers read oddly.
         */
        public BigDecimal overBought() {
            return inFlight != null && inFlight.signum() < 0 ? inFlight.negate() : null;
        }

        /** Whether the three shares are a whole that can be drawn as one bar. */
        public boolean representable() {
            return known() && overBought() == null;
        }

        /** Share of the switch still in the old line, as a percentage — for the progress bar. */
        public BigDecimal pctInOldLine() {
            return share(inOldLine);
        }

        /** Share sold but not yet bought back: out of the market. */
        public BigDecimal pctInFlight() {
            return share(inFlight);
        }

        /** Share that has arrived in the new line. */
        public BigDecimal pctInNewLine() {
            return share(inNewLine);
        }

        private BigDecimal share(BigDecimal part) {
            if (!known() || part == null) return null;
            return part.multiply(new BigDecimal("100"))
                    .divide(declared, 2, RoundingMode.HALF_UP);
        }
    }

    /** Where one step of the sequence stands. */
    public enum StepState {
        /** Nothing left to do here. */
        DONE("done"),
        /** This step is what the next one is waiting on. */
        WAITING("waiting"),
        /** The cash to do it is not there — the amount is in the detail. */
        SHORT("short"),
        /** Not part of this switch: same account, or same currency. */
        NOT_NEEDED("not-needed"),
        /** Reported, not judged — the ledger's own figure, which this tool did not compute. */
        INFO("info"),
        /** Nothing on file says either way. */
        UNKNOWN("unknown");

        private final String cssClass;

        StepState(String cssClass) {
            this.cssClass = cssClass;
        }

        public String cssClass() {
            return cssClass;
        }

        /** Whether this step stops the one after it. */
        public boolean blocks() {
            return this == WAITING || this == SHORT || this == UNKNOWN;
        }
    }

    /** One step of the chain from "sell the old line" to "buy the new one". */
    public record Step(String title, StepState state, String detail) {
    }

    /** Everything the panel prints for one switch. */
    public record Summary(Progress progress, Comparison resting, Comparison filled, Basis basis,
                          List<Ladders.CashLine> cash, List<Step> sequence, Step blockedBy,
                          List<String> excluded) {
    }

    // ---- the assembly -------------------------------------------------------

    /**
     * Everything a switch comes to.
     *
     * @param sells    every leg being sold, on <em>either</em> listing
     * @param buys     every leg being bought, on either listing
     * @param outLine  the declared line being left, and its live price
     * @param inLine   the declared line being entered, and its live price. The pair is still
     *                 declared with a direction — it names which listing the holding is counted
     *                 under everywhere exposure is measured — but the direction no longer decides
     *                 which side a leg is on. These two are the pair, and a leg is measured against
     *                 whichever of them it trades.
     * @param rates    the FX snapshot, units of foreign currency per 1 GBP (the app-wide convention)
     * @param cash     what the ledger says each account holds, for the sequencing step that asks
     *                 whether the buy can actually be funded
     */
    public static Summary summarise(List<Rung> sells, List<Rung> buys, LinePrice outLine,
                                    LinePrice inLine, Map<String, BigDecimal> rates,
                                    List<CashAvailability> cash) {
        List<String> excluded = new ArrayList<>();

        BigDecimal outLineGbp = toGbp(outLine.price(), outLine.currency(), rates);
        BigDecimal inLineGbp = toGbp(inLine.price(), inLine.currency(), rates);
        Basis basis = basis(outLine, inLine, outLineGbp, inLineGbp, rates);

        Comparison resting = compare(sells, buys, Rung::remaining, outLine, inLine,
                rates, excluded, "resting or intended");
        Comparison filled = compare(sells, buys, Rung::filled, outLine, inLine,
                rates, excluded, "filled");

        Progress progress = progress(sells, buys, excluded);
        List<Ladders.CashLine> cashLines = cashLines(sells, buys, excluded);
        List<Step> steps = sequence(sells, buys, outLine, inLine, progress, cashLines, cash, rates);
        Step blocker = null;
        for (Step s : steps) {
            if (s.state().blocks()) {
                blocker = s;
                break;
            }
        }
        return new Summary(progress, resting, filled, basis, cashLines, steps, blocker, excluded);
    }

    // ---- the yardstick ------------------------------------------------------

    private static Basis basis(LinePrice outLine, LinePrice inLine, BigDecimal outGbp,
                               BigDecimal inGbp, Map<String, BigDecimal> rates) {
        BigDecimal deviation = ratioLessOne(inGbp, outGbp);
        BigDecimal implied = outLine.priced() && inLine.priced() && outLine.price().signum() != 0
                ? inLine.price().divide(outLine.price(), SCALE, RoundingMode.HALF_UP) : null;
        BigDecimal market = cross(outLine.currency(), inLine.currency(), rates);
        return new Basis(outLine, inLine, outGbp, inGbp, deviation, implied, market);
    }

    private static Comparison compare(List<Rung> sells, List<Rung> buys, Predicate<Rung> which,
                                      LinePrice outLine, LinePrice inLine,
                                      Map<String, BigDecimal> rates,
                                      List<String> excluded, String what) {
        LegSide sell = side(sells, which, outLine, inLine, outLine.symbol(), rates, excluded, what);
        LegSide buy = side(buys, which, outLine, inLine, inLine.symbol(), rates, excluded, what);

        BigDecimal perUnit = null;
        BigDecimal costPct = null;
        BigDecimal matched = null;
        BigDecimal total = null;
        if (sell.present() && buy.present() && sell.avgPriceGbp().signum() != 0) {
            perUnit = buy.avgPriceGbp().subtract(sell.avgPriceGbp());
            costPct = ratioLessOne(buy.avgPriceGbp(), sell.avgPriceGbp());
            matched = sell.quantity().min(buy.quantity());
            total = matched.multiply(perUnit);
        }
        // Native prices only: a side spanning both listings has no single currency, so it has no
        // implied cross either. Absent, never blended — the whole point of the pair of columns.
        BigDecimal implied = sell.avgPrice() != null && buy.avgPrice() != null
                && sell.avgPrice().signum() != 0
                ? buy.avgPrice().divide(sell.avgPrice(), SCALE, RoundingMode.HALF_UP) : null;
        BigDecimal market = cross(sell.currency(), buy.currency(), rates);
        // The split's own FX term, over the two sides' market prices rather than the two declared
        // lines'. Identical to the Basis block whenever each side sits on one listing, which is
        // what keeps a one-way switch computing exactly what it always did.
        BigDecimal splitBasis = ratioLessOne(buy.marketGbp(), sell.marketGbp());
        return new Comparison(sell, buy, perUnit, costPct, matched, total, implied, market,
                splitBasis);
    }

    /**
     * One side's quantity-weighted average, in its own currency where it has one and on the
     * yardstick always, together with the market price that average is judged against.
     *
     * <p>Weighted by quantity rather than averaged across legs: three rungs of 10, 10 and 300 units
     * are not three equal opinions about the price, and a plain mean of them would be a number about
     * no trade anyone is making.
     *
     * <p><b>Each leg converts on its own currency, then the GBP figures are averaged</b> — not the
     * other way round, which would need a currency the side may not have. The market blend runs over
     * <em>exactly</em> the legs the average ran over, with the same weights; that is what makes
     * {@code avg / market} decomposable into the identity above rather than approximately so, and it
     * is why a leg that cannot be converted leaves both figures rather than only one.
     */
    private static LegSide side(List<Rung> rungs, Predicate<Rung> which, LinePrice outLine,
                                LinePrice inLine, String ifEmpty, Map<String, BigDecimal> rates,
                                List<String> excluded, String what) {
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal valueNative = BigDecimal.ZERO;
        BigDecimal valueGbp = BigDecimal.ZERO;
        BigDecimal marketValueGbp = BigDecimal.ZERO;
        Set<String> listings = new LinkedHashSet<>();
        Set<String> currencies = new LinkedHashSet<>();
        boolean everyLinePriced = true;
        int count = 0;
        for (Rung r : rungs) {
            if (!which.test(r)) continue;
            count++;
            if (r.quantity() == null || r.price() == null) {
                excluded.add(r.describe() + " — no " + (r.price() == null ? "price" : "quantity")
                        + ", so the " + what + " average leaves it out");
                continue;
            }
            LinePrice line = lineFor(r.symbol(), outLine, inLine);
            String ccy = r.currency() != null ? r.currency() : line.currency();
            BigDecimal gbp = toGbp(r.price(), ccy, rates);
            if (gbp == null) {
                excluded.add(r.describe() + " — no currency yet (nothing has priced this listing), "
                        + "so the " + what + " average leaves it out");
                continue;
            }
            if (line.symbol() != null) listings.add(line.symbol());
            currencies.add(ccy);
            qty = qty.add(r.quantity());
            valueNative = valueNative.add(r.quantity().multiply(r.price()));
            valueGbp = valueGbp.add(r.quantity().multiply(gbp));
            BigDecimal lineGbp = toGbp(line.price(), line.currency(), rates);
            if (lineGbp == null) everyLinePriced = false;
            else marketValueGbp = marketValueGbp.add(r.quantity().multiply(lineGbp));
        }
        String currency = currencies.size() == 1 ? currencies.iterator().next() : null;
        List<String> lines = List.copyOf(listings);
        // A side with nothing on it still names the line it would ordinarily be on, so the row reads
        // "Buy EQQU  —  —" rather than "Buy —": which line is absent is the point of printing it.
        String label = listings.isEmpty() ? ifEmpty : String.join(" / ", listings);
        if (count == 0 || qty.signum() <= 0) {
            return new LegSide(label, lines, currency, qty.signum() > 0 ? qty : null, null, null,
                    null, null, count);
        }
        // Native only when the whole side quotes in one currency; pounds and dollars have no mean.
        BigDecimal avg = currency == null ? null
                : valueNative.divide(qty, SCALE, RoundingMode.HALF_UP);
        BigDecimal avgGbp = valueGbp.divide(qty, SCALE, RoundingMode.HALF_UP);
        BigDecimal marketGbp = everyLinePriced
                ? marketValueGbp.divide(qty, SCALE, RoundingMode.HALF_UP) : null;
        return new LegSide(label, lines, currency, qty, avg, avgGbp, marketGbp,
                ratioLessOne(avgGbp, marketGbp), count);
    }

    /**
     * A leg's limit on the yardstick — the only axis two listings' prices can be ordered on.
     *
     * <p>Public because the ordering of the leg tables belongs to the panel and the conversion
     * belongs here: £584.80 above $682.35 is a statement about the exchange rate unless both are in
     * GBP first, and a table sorted on the raw numbers would put every sterling row above every
     * dollar one whatever the prices said.
     */
    public static BigDecimal gbpPrice(Rung r, LinePrice outLine, LinePrice inLine,
                                      Map<String, BigDecimal> rates) {
        if (r == null || r.price() == null) return null;
        LinePrice line = lineFor(r.symbol(), outLine, inLine);
        return toGbp(r.price(), r.currency() != null ? r.currency() : line.currency(), rates);
    }

    /**
     * The listing a leg trades, out of the pair the switch declares.
     *
     * <p>A leg whose symbol matches neither is never placed on a switch in the first place, so this
     * only has to settle which of the two it is. A leg with no symbol at all falls back to the line
     * being left, which is where a pre-existing sell leg was already being measured.
     */
    private static LinePrice lineFor(String symbol, LinePrice outLine, LinePrice inLine) {
        if (symbol != null && inLine.symbol() != null
                && symbol.equalsIgnoreCase(inLine.symbol())) {
            return inLine;
        }
        return outLine;
    }

    // ---- how far through it is ---------------------------------------------

    private static Progress progress(List<Rung> out, List<Rung> in, List<String> excluded) {
        BigDecimal declared = BigDecimal.ZERO;
        BigDecimal sold = BigDecimal.ZERO;
        BigDecimal bought = BigDecimal.ZERO;
        boolean known = true;
        boolean remaining = false;

        for (Rung r : out) {
            if (r.gone()) continue;
            if (r.quantity() == null) {
                known = false;
                excluded.add(r.describe() + " — no quantity, so the units this switch covers "
                        + "cannot be counted");
                continue;
            }
            declared = declared.add(r.quantity());
            if (r.filled()) sold = sold.add(r.quantity());
            else remaining = true;
        }
        for (Rung r : in) {
            if (r.gone()) continue;
            if (r.quantity() == null) {
                known = false;
                continue;
            }
            if (r.filled()) bought = bought.add(r.quantity());
            else remaining = true;
        }
        if (!known) {
            return new Progress(null, null, null, null, null, null, null, false,
                    sold.signum() > 0 || bought.signum() > 0);
        }
        BigDecimal inOld = declared.subtract(sold);
        BigDecimal inFlight = sold.subtract(bought);
        BigDecimal pct = declared.signum() > 0
                ? bought.divide(declared, 6, RoundingMode.HALF_UP) : null;
        // Complete means the holding has arrived, not merely that the orders are gone: everything
        // declared has been sold and the same number of units has been bought back. A switch whose
        // sell filled and whose buy never did is the state this panel exists to keep visible, and
        // calling it complete because nothing is resting would hide exactly that.
        boolean complete = declared.signum() > 0 && !remaining
                && inOld.signum() == 0 && inFlight.signum() == 0;
        return new Progress(declared, sold, bought, inOld, inFlight, bought, pct, complete,
                sold.signum() > 0 || bought.signum() > 0);
    }

    // ---- what the cash has to do -------------------------------------------

    /**
     * What the unfilled legs do to cash, per account and currency, and never netted across either.
     *
     * <p>Netting is the precise error this panel exists to stop on a switch: a sell raising £10,000
     * in the SIPP and a buy needing $13,450 in the Roth is not cash-neutral, it is two brokers and a
     * currency conversion apart, which is the reason the buy leg cannot simply be placed.
     */
    private static List<Ladders.CashLine> cashLines(List<Rung> out, List<Rung> in,
                                                    List<String> excluded) {
        Map<String, BigDecimal[]> acc = new LinkedHashMap<>();
        Map<String, String[]> keys = new LinkedHashMap<>();
        for (List<Rung> side : List.of(out, in)) {
            for (Rung r : side) {
                if (!r.remaining()) continue;
                BigDecimal value = r.cash();
                if (value == null || r.currency() == null) {
                    excluded.add(r.describe() + " — "
                            + (r.price() == null ? "no price"
                            : r.currency() == null ? "no currency yet (nothing has priced this listing)"
                            : "no quantity")
                            + ", so it is left out of the cash lines");
                    continue;
                }
                String key = r.account() + "|" + r.currency();
                keys.putIfAbsent(key, new String[]{r.account(), r.currency()});
                BigDecimal[] pair = acc.computeIfAbsent(key,
                        k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                if (r.sell()) pair[0] = pair[0].add(value);
                else if (r.buy()) pair[1] = pair[1].add(value);
            }
        }
        List<Ladders.CashLine> outLines = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> e : acc.entrySet()) {
            String[] k = keys.get(e.getKey());
            outLines.add(new Ladders.CashLine(k[0], k[1], e.getValue()[0], e.getValue()[1]));
        }
        return outLines;
    }

    // ---- the sequencing -----------------------------------------------------

    /**
     * The chain the switch has to walk, and where it currently stands.
     *
     * <p>This is the part no broker will do: the proceeds arrive in one account in one currency and
     * the purchase needs a different account in a different one, nothing chains the two orders, and
     * moving money between brokers takes days. The buy leg is therefore usually not blocked on a
     * decision at all — it is blocked on plumbing, and the panel's job is to say which piece.
     *
     * <p>The settlement step is reported rather than judged. This tool reads order exports and a
     * cash ledger; neither says "that trade settled". What it can do is print the ledger's own
     * balance for the account the proceeds land in, with the date that ledger was last imported, and
     * let the reader draw the obvious conclusion from a figure they can check.
     */
    private static List<Step> sequence(List<Rung> sells, List<Rung> buys, LinePrice outLine,
                                       LinePrice inLine, Progress progress,
                                       List<Ladders.CashLine> cashLines,
                                       List<CashAvailability> cash, Map<String, BigDecimal> rates) {
        List<Step> steps = new ArrayList<>();
        Set<String> outAccounts = accounts(sells);
        Set<String> inAccounts = accounts(buys);
        // The listings each side is actually on, which is what the step titles have to name now
        // that a side is a direction rather than a line.
        String sellLines = listingLabel(sells, outLine, inLine, outLine.symbol());
        String buyLines = listingLabel(buys, outLine, inLine, inLine.symbol());
        Set<String> outCcy = currencies(sells, outLine, inLine);
        Set<String> inCcy = currencies(buys, outLine, inLine);

        // 1. Sell.
        if (sells.isEmpty()) {
            steps.add(new Step("Sell " + sellLines, StepState.UNKNOWN,
                    "No sell leg recorded yet. Assign the order, or record the one you mean to place."));
        } else if (!progress.known()) {
            steps.add(new Step("Sell " + sellLines, StepState.UNKNOWN,
                    "A sell leg has no quantity, so how much this switch covers is unknown."));
        } else if (progress.inOldLine().signum() > 0) {
            steps.add(new Step("Sell " + sellLines, StepState.WAITING,
                    plain(progress.sold()) + " of " + plain(progress.declared()) + " units sold — "
                            + plain(progress.inOldLine()) + " still to sell."));
        } else {
            steps.add(new Step("Sell " + sellLines, StepState.DONE,
                    "All " + plain(progress.declared()) + " units sold."));
        }

        // 2. Settlement, reported from the ledger rather than asserted. One line per currency the
        // sell side raises: two lines raising pounds and dollars are two separate balances, and
        // netting them is the error this whole block exists to avoid.
        List<String> raised = new ArrayList<>();
        for (String ccy : outCcy) {
            CashAvailability c = find(cash, outAccounts, ccy);
            if (c != null) {
                raised.add("Ledger shows " + money(c.available()) + " " + c.currency()
                        + (c.asOf() == null ? "" : " as at " + c.asOf()));
            }
        }
        steps.add(new Step("Proceeds settle as cash" + (outAccounts.isEmpty() ? ""
                : " in " + String.join(" / ", outAccounts)),
                StepState.INFO,
                raised.isEmpty()
                        ? "No imported cash balance for that account and currency, so this tool "
                        + "cannot say what has landed."
                        : String.join("; ", raised)
                        + ". Settlement itself is not in any export this tool reads."));

        // 3. Move between accounts — only when they really are different.
        boolean sameAccount = !outAccounts.isEmpty() && outAccounts.equals(inAccounts);
        if (inAccounts.isEmpty() || outAccounts.isEmpty()) {
            steps.add(new Step("Move the cash", StepState.UNKNOWN,
                    "Both legs need an account before the transfer can be described."));
        } else if (sameAccount) {
            steps.add(new Step("Move the cash", StepState.NOT_NEEDED,
                    "Both legs are in " + String.join(" / ", inAccounts) + "."));
        } else {
            steps.add(new Step("Move the cash to " + String.join(" / ", inAccounts),
                    StepState.WAITING,
                    "From " + String.join(" / ", outAccounts) + ". No broker chains this; it is a "
                            + "transfer you make, and it is the slow part."));
        }

        // 4. Convert — one entry per currency the buy side needs that the sell side does not
        // itself raise. A switch running both ways can need none of them, or both.
        steps.add(convertStep(outCcy, inCcy, inAccounts, cashLines, rates));

        // 5. Buy, and whether the cash for it is there — asked per currency, since a buy side on
        // both lines needs both, and being flush in one says nothing about the other.
        List<String> shorts = new ArrayList<>();
        List<String> needs = new ArrayList<>();
        for (String ccy : inCcy) {
            BigDecimal need = needed(cashLines, inAccounts, ccy);
            if (need == null) continue;
            needs.add(money(need) + " " + ccy);
            CashAvailability funds = find(cash, inAccounts, ccy);
            if (funds == null) continue;
            BigDecimal shortfall = need.subtract(funds.available());
            if (shortfall.signum() > 0) {
                shorts.add("Needs " + money(need) + " " + ccy + "; "
                        + String.join(" / ", inAccounts) + " holds " + money(funds.available())
                        + " " + ccy + (funds.asOf() == null ? "" : " as at " + funds.asOf())
                        + ". Short " + money(shortfall) + " " + ccy + ".");
            }
        }
        boolean anyPlaced = buys.stream().anyMatch(r -> r.state() == Rung.State.WORKING);
        boolean anyIntended = buys.stream().anyMatch(Rung::intended);
        if (buys.isEmpty()) {
            steps.add(new Step("Buy " + buyLines, StepState.UNKNOWN,
                    "No buy leg recorded yet. Record it as an intended leg — that is what it is "
                            + "until the cash is there."));
        } else if (!shorts.isEmpty()) {
            // One pair of sentences per currency. A buy side on both lines needs both, and being
            // flush in one says nothing whatever about the other.
            steps.add(new Step("Buy " + buyLines, StepState.SHORT, String.join(" ", shorts)));
        } else if (anyIntended && !anyPlaced) {
            steps.add(new Step("Buy " + buyLines, StepState.WAITING,
                    "Recorded but not placed at a broker."
                            + (needs.isEmpty() ? "" : " Needs " + String.join(" and ", needs) + ".")));
        } else if (progress.known() && progress.inFlight() != null
                && progress.inFlight().signum() > 0) {
            steps.add(new Step("Buy " + buyLines, StepState.WAITING,
                    plain(progress.bought()) + " of " + plain(progress.sold())
                            + " sold units bought back."));
        } else {
            steps.add(new Step("Buy " + buyLines, StepState.DONE,
                    progress.known() ? plain(progress.bought()) + " units bought." : "Placed."));
        }
        return steps;
    }

    /**
     * The conversion step, which on a two-way switch is no longer one conversion.
     *
     * <p>A currency the buy side needs and the sell side already raises needs no conversion at all,
     * and saying so is worth as much as naming the ones that do — it is the difference between
     * "waiting on the FX desk" and "the money is already in the right denomination".
     */
    private static Step convertStep(Set<String> outCcy, Set<String> inCcy, Set<String> inAccounts,
                                    List<Ladders.CashLine> cashLines,
                                    Map<String, BigDecimal> rates) {
        if (outCcy.isEmpty() || inCcy.isEmpty()) {
            return new Step("Convert the currency", StepState.UNKNOWN,
                    "One of the two lines has never priced, so its currency is unknown.");
        }
        List<String> toConvert = new ArrayList<>();
        for (String ccy : inCcy) {
            if (!outCcy.contains(ccy)) toConvert.add(ccy);
        }
        if (toConvert.isEmpty()) {
            return new Step("Convert the currency", StepState.NOT_NEEDED,
                    inCcy.size() == 1 && outCcy.size() == 1
                            ? "Both lines trade in " + inCcy.iterator().next() + "."
                            : "Every currency the buy side needs is one the sell side already "
                            + "raises (" + String.join(", ", inCcy) + ").");
        }
        List<String> detail = new ArrayList<>();
        for (String ccy : toConvert) {
            BigDecimal need = needed(cashLines, inAccounts, ccy);
            StringBuilder line = new StringBuilder();
            if (need != null) line.append(money(need)).append(' ').append(ccy).append(" needed");
            for (String from : outCcy) {
                BigDecimal rate = cross(from, ccy, rates);
                if (rate == null) continue;
                if (line.length() > 0) line.append(" — ");
                line.append("about ").append(rate.setScale(4, RoundingMode.HALF_UP).toPlainString())
                        .append(' ').append(ccy).append(" per ").append(from).append(" today");
            }
            if (line.length() > 0) detail.add(line.toString());
        }
        return new Step("Convert " + String.join(" / ", outCcy) + " to "
                + String.join(" / ", toConvert), StepState.WAITING,
                detail.isEmpty() ? "No rate on file." : String.join("; ", detail) + ".");
    }

    private static Set<String> accounts(List<Rung> rungs) {
        Set<String> out = new LinkedHashSet<>();
        for (Rung r : rungs) {
            if (!r.gone() && r.account() != null) out.add(r.account());
        }
        return out;
    }

    /** The listings a side is actually on, joined — one on a one-way switch, both on a two-way one. */
    private static String listingLabel(List<Rung> rungs, LinePrice outLine, LinePrice inLine,
                                       String fallback) {
        Set<String> out = new LinkedHashSet<>();
        for (Rung r : rungs) {
            if (r.gone()) continue;
            String symbol = lineFor(r.symbol(), outLine, inLine).symbol();
            if (symbol != null) out.add(symbol);
        }
        return out.isEmpty() ? dash(fallback) : String.join(" / ", out);
    }

    /** Every currency a side quotes in — one per listing it sits on. */
    private static Set<String> currencies(List<Rung> rungs, LinePrice outLine, LinePrice inLine) {
        Set<String> out = new LinkedHashSet<>();
        for (Rung r : rungs) {
            if (r.gone()) continue;
            String ccy = r.currency() != null ? r.currency()
                    : lineFor(r.symbol(), outLine, inLine).currency();
            if (ccy != null) out.add("GBp".equals(ccy) ? "GBP" : ccy);
        }
        return out;
    }

    private static String dash(String s) {
        return s == null ? "—" : s;
    }


    private static CashAvailability find(List<CashAvailability> cash, Set<String> accounts,
                                         String currency) {
        if (cash == null || currency == null) return null;
        for (CashAvailability c : cash) {
            if (accounts.contains(c.account()) && currency.equalsIgnoreCase(c.currency())) return c;
        }
        return null;
    }

    private static BigDecimal needed(List<Ladders.CashLine> lines, Set<String> accounts,
                                     String currency) {
        if (currency == null) return null;
        BigDecimal total = BigDecimal.ZERO;
        boolean any = false;
        for (Ladders.CashLine l : lines) {
            if (!accounts.contains(l.account()) || !currency.equalsIgnoreCase(l.currency())) continue;
            if (l.needs().signum() <= 0) continue;
            total = total.add(l.needs());
            any = true;
        }
        return any ? total : null;
    }

    // ---- conversions --------------------------------------------------------

    /**
     * Native to GBP. Rates are units of foreign currency per 1 GBP, so this divides — the app-wide
     * convention, and getting it backwards is how a switch would read as 80% cheaper than it is.
     */
    static BigDecimal toGbp(BigDecimal amount, String currency, Map<String, BigDecimal> rates) {
        if (amount == null || currency == null) return null;
        String ccy = "GBp".equals(currency) ? "GBP" : currency;
        BigDecimal value = "GBp".equals(currency) ? amount.movePointLeft(2) : amount;
        if ("GBP".equals(ccy)) return value;
        BigDecimal rate = rates == null ? null : rates.get(ccy);
        if (rate == null || rate.signum() == 0) return null;
        return value.divide(rate, SCALE, RoundingMode.HALF_UP);
    }

    /** Units of {@code to} per unit of {@code from}, out of the GBP-based snapshot. */
    static BigDecimal cross(String from, String to, Map<String, BigDecimal> rates) {
        if (from == null || to == null) return null;
        BigDecimal f = rateOf(from, rates);
        BigDecimal t = rateOf(to, rates);
        if (f == null || t == null || f.signum() == 0) return null;
        return t.divide(f, SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal rateOf(String currency, Map<String, BigDecimal> rates) {
        String ccy = "GBp".equals(currency) ? "GBP" : currency;
        if ("GBP".equals(ccy)) return ONE;
        return rates == null ? null : rates.get(ccy);
    }

    /** {@code a/b - 1}, or null when either side is missing or the denominator is zero. */
    private static BigDecimal ratioLessOne(BigDecimal a, BigDecimal b) {
        if (a == null || b == null || b.signum() == 0) return null;
        return a.divide(b, SCALE, RoundingMode.HALF_UP).subtract(ONE);
    }

    /** Units: trailing zeros stripped, because 100 units is 100 and not 100.00. */
    private static String plain(BigDecimal v) {
        return v == null ? "?" : v.stripTrailingZeros().toPlainString();
    }

    /**
     * Money: always two places and grouped, because a shortfall of "412" dollars reads as a unit
     * count and "71600.00" has to be counted on the screen. Grouped in the UK locale, which is the
     * one every other figure in this app is formatted in.
     */
    private static String money(BigDecimal v) {
        return v == null ? "?"
                : String.format(Locale.UK, "%,.2f", v.setScale(2, RoundingMode.HALF_UP));
    }
}
