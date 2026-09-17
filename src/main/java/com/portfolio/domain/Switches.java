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
     * One side of the switch, averaged over whichever legs were asked for.
     *
     * @param avgPrice     quantity-weighted, in the listing's own currency — what the broker screen
     *                     shows
     * @param avgPriceGbp  the same on the yardstick, so the two sides can be read against each other
     * @param vsMarketPct  where that average sits against this line's own market price, as a signed
     *                     fraction. Currency-free by construction (both sides converted), which is
     *                     what lets it stand as the non-FX half of the split.
     */
    public record LegSide(String symbol, String currency, BigDecimal quantity,
                          BigDecimal avgPrice, BigDecimal avgPriceGbp, BigDecimal vsMarketPct,
                          int legCount) {

        public boolean present() {
            return legCount > 0 && avgPrice != null;
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
     */
    public record Comparison(LegSide out, LegSide in, BigDecimal perUnitGbp, BigDecimal costPct,
                             BigDecimal matchedQuantity, BigDecimal totalGbp,
                             BigDecimal impliedCross, BigDecimal marketCross) {

        /** Both sides present and priced — otherwise there is no comparison, and no number is shown. */
        public boolean complete() {
            return out.present() && in.present() && costPct != null;
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
     * @param out      legs on the line being left — sells of the old listing
     * @param in       legs on the line being entered — buys of the new listing
     * @param outLine  the old listing's live price
     * @param inLine   the new listing's live price
     * @param rates    the FX snapshot, units of foreign currency per 1 GBP (the app-wide convention)
     * @param cash     what the ledger says each account holds, for the sequencing step that asks
     *                 whether the buy can actually be funded
     */
    public static Summary summarise(List<Rung> out, List<Rung> in, LinePrice outLine,
                                    LinePrice inLine, Map<String, BigDecimal> rates,
                                    List<CashAvailability> cash) {
        List<String> excluded = new ArrayList<>();

        BigDecimal outLineGbp = toGbp(outLine.price(), outLine.currency(), rates);
        BigDecimal inLineGbp = toGbp(inLine.price(), inLine.currency(), rates);
        Basis basis = basis(outLine, inLine, outLineGbp, inLineGbp, rates);

        Comparison resting = compare(out, in, Rung::remaining, outLine, inLine,
                outLineGbp, inLineGbp, rates, excluded, "resting or intended");
        Comparison filled = compare(out, in, Rung::filled, outLine, inLine,
                outLineGbp, inLineGbp, rates, excluded, "filled");

        Progress progress = progress(out, in, excluded);
        List<Ladders.CashLine> cashLines = cashLines(out, in, excluded);
        List<Step> steps = sequence(out, in, outLine, inLine, progress, cashLines, cash, rates);
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

    private static Comparison compare(List<Rung> out, List<Rung> in, Predicate<Rung> which,
                                      LinePrice outLine, LinePrice inLine, BigDecimal outLineGbp,
                                      BigDecimal inLineGbp, Map<String, BigDecimal> rates,
                                      List<String> excluded, String what) {
        LegSide sell = side(out, which, outLine, outLineGbp, rates, excluded, what);
        LegSide buy = side(in, which, inLine, inLineGbp, rates, excluded, what);

        BigDecimal perUnit = null;
        BigDecimal costPct = null;
        BigDecimal matched = null;
        BigDecimal total = null;
        if (sell.present() && buy.present() && sell.avgPriceGbp() != null
                && buy.avgPriceGbp() != null && sell.avgPriceGbp().signum() != 0) {
            perUnit = buy.avgPriceGbp().subtract(sell.avgPriceGbp());
            costPct = ratioLessOne(buy.avgPriceGbp(), sell.avgPriceGbp());
            matched = sell.quantity().min(buy.quantity());
            total = matched.multiply(perUnit);
        }
        BigDecimal implied = sell.present() && buy.present() && sell.avgPrice().signum() != 0
                ? buy.avgPrice().divide(sell.avgPrice(), SCALE, RoundingMode.HALF_UP) : null;
        BigDecimal market = cross(sell.currency(), buy.currency(), rates);
        return new Comparison(sell, buy, perUnit, costPct, matched, total, implied, market);
    }

    /**
     * One side's quantity-weighted average, in its own currency and on the yardstick.
     *
     * <p>Weighted by quantity rather than averaged across legs: three rungs of 10, 10 and 300 units
     * are not three equal opinions about the price, and a plain mean of them would be a number about
     * no trade anyone is making.
     */
    private static LegSide side(List<Rung> rungs, Predicate<Rung> which, LinePrice line,
                                BigDecimal lineGbp, Map<String, BigDecimal> rates,
                                List<String> excluded, String what) {
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal value = BigDecimal.ZERO;
        String currency = null;
        int count = 0;
        for (Rung r : rungs) {
            if (!which.test(r)) continue;
            count++;
            if (r.quantity() == null || r.price() == null) {
                excluded.add(r.describe() + " — no " + (r.price() == null ? "price" : "quantity")
                        + ", so the " + what + " average leaves it out");
                continue;
            }
            if (currency == null) currency = r.currency();
            qty = qty.add(r.quantity());
            value = value.add(r.quantity().multiply(r.price()));
        }
        if (currency == null) currency = line.currency();
        if (count == 0 || qty.signum() <= 0 || currency == null) {
            return new LegSide(line.symbol(), currency, qty.signum() > 0 ? qty : null, null, null,
                    null, count);
        }
        BigDecimal avg = value.divide(qty, SCALE, RoundingMode.HALF_UP);
        BigDecimal avgGbp = toGbp(avg, currency, rates);
        return new LegSide(line.symbol(), currency, qty, avg, avgGbp,
                ratioLessOne(avgGbp, lineGbp), count);
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
    private static List<Step> sequence(List<Rung> out, List<Rung> in, LinePrice outLine,
                                       LinePrice inLine, Progress progress,
                                       List<Ladders.CashLine> cashLines,
                                       List<CashAvailability> cash, Map<String, BigDecimal> rates) {
        List<Step> steps = new ArrayList<>();
        Set<String> outAccounts = accounts(out);
        Set<String> inAccounts = accounts(in);
        String outCcy = currencyOf(out, outLine);
        String inCcy = currencyOf(in, inLine);

        // 1. Sell.
        if (out.isEmpty()) {
            steps.add(new Step("Sell " + outLine.symbol(), StepState.UNKNOWN,
                    "No sell leg recorded yet. Assign the order, or record the one you mean to place."));
        } else if (!progress.known()) {
            steps.add(new Step("Sell " + outLine.symbol(), StepState.UNKNOWN,
                    "A sell leg has no quantity, so how much this switch covers is unknown."));
        } else if (progress.inOldLine().signum() > 0) {
            steps.add(new Step("Sell " + outLine.symbol(), StepState.WAITING,
                    plain(progress.sold()) + " of " + plain(progress.declared()) + " units sold — "
                            + plain(progress.inOldLine()) + " still in the old line."));
        } else {
            steps.add(new Step("Sell " + outLine.symbol(), StepState.DONE,
                    "All " + plain(progress.declared()) + " units sold."));
        }

        // 2. Settlement, reported from the ledger rather than asserted.
        CashAvailability raised = find(cash, outAccounts, outCcy);
        steps.add(new Step("Proceeds settle as cash" + (outAccounts.isEmpty() ? ""
                : " in " + String.join(" / ", outAccounts)),
                StepState.INFO,
                raised == null
                        ? "No imported cash balance for that account and currency, so this tool "
                        + "cannot say what has landed."
                        : "Ledger shows " + money(raised.available()) + " " + raised.currency()
                        + (raised.asOf() == null ? "" : " as at " + raised.asOf())
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

        // 4. Convert.
        BigDecimal needed = needed(cashLines, inAccounts, inCcy);
        if (outCcy == null || inCcy == null) {
            steps.add(new Step("Convert the currency", StepState.UNKNOWN,
                    "One of the two lines has never priced, so its currency is unknown."));
        } else if (outCcy.equals(inCcy)) {
            steps.add(new Step("Convert the currency", StepState.NOT_NEEDED,
                    "Both lines trade in " + inCcy + "."));
        } else {
            BigDecimal rate = cross(outCcy, inCcy, rates);
            steps.add(new Step("Convert " + outCcy + " to " + inCcy, StepState.WAITING,
                    (needed == null ? "" : money(needed) + " " + inCcy + " needed — ")
                            + (rate == null ? "no rate on file."
                            : "about " + rate.setScale(4, RoundingMode.HALF_UP).toPlainString()
                            + " " + inCcy + " per " + outCcy + " today.")));
        }

        // 5. Buy.
        CashAvailability funds = find(cash, inAccounts, inCcy);
        boolean anyPlaced = in.stream().anyMatch(r -> r.state() == Rung.State.WORKING);
        boolean anyIntended = in.stream().anyMatch(Rung::intended);
        BigDecimal shortfall = needed != null && funds != null
                ? needed.subtract(funds.available()) : null;
        if (in.isEmpty()) {
            steps.add(new Step("Buy " + inLine.symbol(), StepState.UNKNOWN,
                    "No buy leg recorded yet. Record it as an intended leg — that is what it is "
                            + "until the cash is there."));
        } else if (shortfall != null && shortfall.signum() > 0) {
            steps.add(new Step("Buy " + inLine.symbol(), StepState.SHORT,
                    "Needs " + money(needed) + " " + inCcy + "; " + String.join(" / ", inAccounts)
                            + " holds " + money(funds.available()) + " " + inCcy
                            + (funds.asOf() == null ? "" : " as at " + funds.asOf())
                            + ". Short " + money(shortfall) + " " + inCcy + "."));
        } else if (anyIntended && !anyPlaced) {
            steps.add(new Step("Buy " + inLine.symbol(), StepState.WAITING,
                    "Recorded but not placed at a broker."));
        } else if (progress.known() && progress.inFlight() != null
                && progress.inFlight().signum() > 0) {
            steps.add(new Step("Buy " + inLine.symbol(), StepState.WAITING,
                    plain(progress.bought()) + " of " + plain(progress.sold())
                            + " sold units bought back."));
        } else {
            steps.add(new Step("Buy " + inLine.symbol(), StepState.DONE,
                    progress.known() ? plain(progress.bought()) + " units bought." : "Placed."));
        }
        return steps;
    }

    private static Set<String> accounts(List<Rung> rungs) {
        Set<String> out = new LinkedHashSet<>();
        for (Rung r : rungs) {
            if (!r.gone() && r.account() != null) out.add(r.account());
        }
        return out;
    }

    private static String currencyOf(List<Rung> rungs, LinePrice line) {
        for (Rung r : rungs) {
            if (r.currency() != null) return r.currency();
        }
        return line.currency();
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
