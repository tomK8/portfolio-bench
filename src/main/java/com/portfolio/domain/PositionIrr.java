package com.portfolio.domain;

import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-position annualised return, as an IRR over the position's own dated cash flows.
 *
 * <p><b>Why IRR and not CAGR.</b> Positions here are accumulated in chunks over time, so there
 * is no single holding period to annualise over — a CAGR needs one start date and a position
 * built from six purchases across three years has six. IRR needs none: it asks what constant
 * annual rate reconciles every pound with the date it went in. A late top-up is weighted as a
 * late top-up rather than silently re-dating the whole position.
 *
 * <p><b>The flows.</b> Buys are negative (the ledger amount, so dealing fees and FX charges are
 * already inside it), sells positive and net of fees, dividends positive on their pay date, and
 * the current market value is a positive terminal flow dated today — a hypothetical liquidation.
 * A position bought once and never sold still has the two flows it needs.
 *
 * <p><b>It never touches cost basis, which is the point.</b> Brokers sometimes net realised
 * gains through the average cost of the surviving shares, which leaves a nonsensical — even
 * negative — average cost (MRVL and AMAT both do this). Every ratio computed as gain ÷ cost is
 * then garbage, while the IRR is untouched: it divides by nothing, it only discounts. Where the
 * two disagree on such a name, this column is the one to believe.
 *
 * <p><b>Episodes.</b> A series that changes sign more than once can admit several IRRs, and a
 * position sold to zero and later re-entered does exactly that. The flows are therefore split
 * into episodes at each return to zero quantity and only the <em>current</em> episode is
 * reported — the answer to "how is this position doing", not an average over a holding that
 * ceased to exist in between. {@link Result#reentered()} says when there were earlier ones.
 *
 * <p><b>Short holdings are not annualised.</b> Under {@link #MIN_ANNUALIZE_DAYS} days the raw
 * period return is reported instead ({@link Result#annualised()} false). Compounding a
 * two-week move to a year multiplies its noise by 26 and prints the result as though it were a
 * forecast; the same convention (and the same reasoning) governs Attribution and Period diff.
 *
 * <p><b>Currency.</b> The headline is GBP — the reporting currency — so FX is inside the number.
 * Where the ledger's own flows are genuinely denominated in the listing currency, a native-
 * currency IRR is computed too and the gap between them is the currency contribution. That is
 * only sound when every flow settled in that currency: AJ Bell settles USD-listed trades in GBP,
 * so its rows carry no native amount at all, and a "native" IRR built from them would be the
 * GBP one wearing a dollar sign. {@link Result#nativeAnnualisedReturn()} stays null in that case
 * rather than inventing one.
 */
public final class PositionIrr {

    /** Below this many days the period return is shown raw — see the class doc. */
    public static final int MIN_ANNUALIZE_DAYS = 90;

    /**
     * The currency the headline rate is solved in — the ledger's own {@code amountGbp} column.
     * A position listed in it has no currency contribution to isolate, so the native leg is
     * skipped rather than computed to a guaranteed zero: a "Currency effect" column reading
     * +0.00% on every UK holding invites the reader to wonder what it is telling them.
     */
    public static final String REPORTING_CURRENCY = "GBP";

    /** Quantity at or below this counts as flat — absorbs FIFO/split rounding dust. */
    private static final double FLAT_EPSILON = 1e-6;

    private static final int RATE_SCALE = 6;
    private static final int YEARS_SCALE = 2;

    private PositionIrr() {
    }

    /**
     * One position's annualised return and the context needed to read it. Every figure is
     * nullable: a position whose flows admit no rate reports nothing rather than a number
     * nobody solved for.
     */
    public record Result(
            String symbol,
            /** GBP IRR. Null when not annualised (see {@link #annualised}) or unsolvable. */
            BigDecimal annualisedReturn,
            /** Raw period return over the current episode — the figure to show when not annualised. */
            BigDecimal periodReturn,
            /** False when the episode is shorter than {@link #MIN_ANNUALIZE_DAYS}. */
            boolean annualised,
            /** Cost-weighted holding period in years: Σ(cost×days) ÷ Σcost. Context for the rate. */
            BigDecimal holdingYears,
            /** First flow of the current episode. */
            LocalDate episodeStart,
            int episodeDays,
            /** Episodes found in the ledger; >1 means the position was exited and re-entered. */
            int episodeCount,
            int flowCount,
            /** IRR of the same episode in the listing currency; null unless every flow settled in it. */
            BigDecimal nativeAnnualisedReturn,
            String nativeCurrency,
            /** Which solver answered — null when unsolved. */
            Irr.Method method) {

        public static Result unavailable(String symbol) {
            return new Result(symbol, null, null, false, null, null, 0, 0, 0, null, null, null);
        }

        /**
         * GBP IRR minus native IRR — what the currency did, in annualised percentage points.
         * Null unless both legs exist.
         */
        public BigDecimal currencyContribution() {
            if (annualisedReturn == null || nativeAnnualisedReturn == null) return null;
            return annualisedReturn.subtract(nativeAnnualisedReturn);
        }

        /** True when an earlier episode of this position was closed out before the current one. */
        public boolean reentered() {
            return episodeCount > 1;
        }

        /** The figure the column actually shows — the IRR, or the period return under 90 days. */
        public BigDecimal displayReturn() {
            return annualised ? annualisedReturn : periodReturn;
        }

        /**
         * The whole story behind the cell, for its tooltip: which figure it is, what it was
         * solved over, how long the money has actually been deployed, and — where both legs
         * exist — how much of the rate was the exchange rate rather than the asset. Composed
         * here rather than in the template because it is four conditional clauses, and a
         * Thymeleaf expression that long is unreadable and untestable.
         */
        public String explain() {
            if (displayReturn() == null) {
                if (flowCount == 0) return "No purchase in the ledger for this position.";
                return "No rate: these flows have no solution between "
                        + pct(BigDecimal.valueOf(Irr.MIN_RATE)) + " and "
                        + pct(BigDecimal.valueOf(Irr.MAX_RATE)) + " a year.";
            }
            StringBuilder sb = new StringBuilder();
            if (annualised) {
                sb.append("IRR over ").append(flowCount).append(" dated cash flows since ")
                        .append(episodeStart).append(" (").append(episodeDays).append(" days).");
            } else {
                sb.append("Raw period return over ").append(episodeDays)
                        .append(" days — under ").append(MIN_ANNUALIZE_DAYS)
                        .append(" days it is not annualised.");
            }
            if (holdingYears != null) {
                sb.append(" Cost-weighted holding period ").append(holdingYears).append(" yr.");
            }
            if (reentered()) {
                sb.append(" Current episode only — this position was exited and re-entered (")
                        .append(episodeCount).append(" episodes).");
            }
            BigDecimal fx = currencyContribution();
            if (fx != null) {
                sb.append(" In ").append(nativeCurrency).append(": ")
                        .append(pct(nativeAnnualisedReturn)).append(" — currency contributed ")
                        .append(pct(fx)).append('.');
            }
            return sb.toString();
        }

        private static String pct(BigDecimal rate) {
            return String.format("%+.2f%%", rate.doubleValue() * 100);
        }
    }

    /**
     * Computes the current episode's IRR for one symbol.
     *
     * @param rows              every ledger row for this symbol, across accounts, in date order
     * @param terminalValueGbp  current market value in GBP — the hypothetical liquidation
     * @param terminalNative    current market value in the listing currency; null to skip the
     *                          native leg
     * @param listingCurrency   the listing currency, or null to skip the native leg
     * @param today             the terminal flow's date
     */
    public static Result compute(String symbol,
                                 List<CashTransaction> rows,
                                 BigDecimal terminalValueGbp,
                                 BigDecimal terminalNative,
                                 String listingCurrency,
                                 LocalDate today) {
        if (rows == null || rows.isEmpty() || terminalValueGbp == null || today == null) {
            return Result.unavailable(symbol);
        }

        Episodes episodes = findEpisodes(rows);
        if (episodes.startIndex < 0) return Result.unavailable(symbol);

        List<CashTransaction> episodeRows = rows.subList(episodes.startIndex, rows.size());
        LocalDate episodeStart = parseDate(episodeRows.get(0));
        if (episodeStart == null) return Result.unavailable(symbol);

        List<Irr.Flow> gbpFlows = new ArrayList<>();
        double invested = 0.0, returned = 0.0;
        double weightedCostDays = 0.0, totalCost = 0.0;
        boolean nativeUsable = listingCurrency != null && terminalNative != null
                && !REPORTING_CURRENCY.equalsIgnoreCase(listingCurrency);

        for (CashTransaction t : episodeRows) {
            if (!isFlow(t)) continue;
            LocalDate d = parseDate(t);
            if (d == null) continue;
            double gbp = t.amountGbp();
            gbpFlows.add(new Irr.Flow(d, gbp));
            if (gbp < 0) {
                invested -= gbp;
                double days = today.toEpochDay() - d.toEpochDay();
                weightedCostDays += -gbp * days;
                totalCost += -gbp;
            } else {
                returned += gbp;
            }
            // A single row settled in another currency makes the whole native series a
            // mixture — see the class doc. Detected here rather than assumed per broker.
            if (nativeUsable && !listingCurrency.equalsIgnoreCase(t.currency())) {
                nativeUsable = false;
            }
        }

        if (gbpFlows.isEmpty() || invested <= 0) return Result.unavailable(symbol);

        gbpFlows.add(new Irr.Flow(today, terminalValueGbp.doubleValue()));
        returned += terminalValueGbp.doubleValue();

        long episodeDays = today.toEpochDay() - episodeStart.toEpochDay();
        BigDecimal periodReturn = BigDecimal.valueOf((returned - invested) / invested)
                .setScale(RATE_SCALE, RoundingMode.HALF_UP);
        BigDecimal holdingYears = totalCost > 0
                ? BigDecimal.valueOf(weightedCostDays / totalCost / Irr.DAYS_PER_YEAR_XIRR)
                .setScale(YEARS_SCALE, RoundingMode.HALF_UP)
                : null;

        // Under the floor the rate is not computed at all, rather than computed and hidden:
        // there is no circumstance in which a 12-day position's annualised figure is wanted.
        if (episodeDays < MIN_ANNUALIZE_DAYS) {
            return new Result(symbol, null, periodReturn, false, holdingYears, episodeStart,
                    (int) episodeDays, episodes.count, gbpFlows.size(), null, null, null);
        }

        Irr.Solution solved = Irr.solveDetailed(gbpFlows, Irr.DAYS_PER_YEAR_XIRR);
        BigDecimal annualised = solved == null ? null
                : BigDecimal.valueOf(solved.rate()).setScale(RATE_SCALE, RoundingMode.HALF_UP);

        BigDecimal nativeRate = null;
        if (nativeUsable && annualised != null) {
            nativeRate = nativeIrr(episodeRows, terminalNative, today);
        }

        return new Result(symbol, annualised, periodReturn, true, holdingYears, episodeStart,
                (int) episodeDays, episodes.count, gbpFlows.size(),
                nativeRate, nativeRate == null ? null : listingCurrency,
                solved == null ? null : solved.method());
    }

    /** The same episode re-solved on the rows' native {@code amount} column. */
    private static BigDecimal nativeIrr(List<CashTransaction> episodeRows,
                                        BigDecimal terminalNative, LocalDate today) {
        List<Irr.Flow> flows = new ArrayList<>();
        for (CashTransaction t : episodeRows) {
            if (!isFlow(t)) continue;
            LocalDate d = parseDate(t);
            if (d != null) flows.add(new Irr.Flow(d, t.amount()));
        }
        if (flows.isEmpty()) return null;
        flows.add(new Irr.Flow(today, terminalNative.doubleValue()));
        Double r = Irr.solve(flows, Irr.DAYS_PER_YEAR_XIRR);
        return r == null ? null : BigDecimal.valueOf(r).setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * A row that moves cash. Splits are {@code TRANSACTION} rows with a zero amount — they
     * change the share count and nothing else, so they carry no flow (and, correctly, no
     * information about return).
     */
    private static boolean isFlow(CashTransaction t) {
        if (t.type() == TransactionType.DIVIDEND) return t.amountGbp() != 0.0;
        return t.type() == TransactionType.TRANSACTION && t.amount() != 0.0;
    }

    private record Episodes(int startIndex, int count) {
    }

    /**
     * Walks the quantity timeline and returns the index of the current episode's first buy,
     * plus how many episodes the ledger holds. Quantity is summed across accounts — the same
     * NVDA held in two wrappers is one position on screen and one episode here.
     *
     * <p>Uses {@link CashLedgerReconstructor}'s sign model: negative amount buys, positive
     * amount sells, zero amount is a split carrying a signed quantity delta.
     */
    private static Episodes findEpisodes(List<CashTransaction> rows) {
        double qty = 0.0;
        int startIndex = -1;
        int count = 0;
        for (int i = 0; i < rows.size(); i++) {
            CashTransaction t = rows.get(i);
            if (t.type() != TransactionType.TRANSACTION) continue;
            if (t.amount() < 0) {
                if (qty <= FLAT_EPSILON) {          // opening a new episode from flat
                    startIndex = i;
                    count++;
                }
                qty += Math.abs(t.quantity());
            } else if (t.amount() > 0) {
                qty -= Math.abs(t.quantity());
                if (qty <= FLAT_EPSILON) qty = 0.0; // closed out; next buy starts an episode
            } else {
                qty += t.quantity();                // split: signed delta
                if (qty <= FLAT_EPSILON) qty = 0.0;
            }
        }
        return new Episodes(startIndex, count);
    }

    private static LocalDate parseDate(CashTransaction t) {
        try {
            return LocalDate.parse(t.transactionDate());
        } catch (Exception e) {
            return null;
        }
    }
}
