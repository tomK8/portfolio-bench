package com.portfolio.application;

import java.math.BigDecimal;
import java.util.List;

/**
 * JSON DTO for the investment-style block at the top of Allocation → Breakdown, above the sector
 * block.
 *
 * <p><b>All percentages are fractions</b> (0.2296 = 22.96%), formatted by the client. Two bases
 * ship for every row for the same reason the sector block ships them: {@code pctOfTotal} includes
 * cash, {@code pctOfInvested} excludes it, and neither is obviously primary. Both are computed
 * server-side so the basis toggle needs no refetch.
 *
 * @param rows     one per non-empty bucket, largest first; {@code gbp} sums to {@code totalGbp}
 * @param income   the cross-cutting income figure — like the sector block's AI/Tech tile, this is
 *                 an overlay on the buckets, <b>not</b> a row in them
 * @param unknown  positions with too little data to score, listed by name rather than absorbed
 * @param quadrant the 2×2 the scored equity buckets form, for the visual grid
 */
public record StyleBreakdown(
        String asOf,
        BigDecimal totalGbp,
        BigDecimal investedGbp,
        BigDecimal cashGbp,
        List<Row> rows,
        Income income,
        Quadrant quadrant,
        List<Contributor> unknown,
        List<String> warnings) {

    /**
     * @param scored whether this bucket came out of the two-axis score, as opposed to being
     *               structural (cash, gilts, funds). The UI dims the structural ones so the
     *               growth-vs-value comparison is read off comparable rows only.
     */
    public record Row(String bucket, String label, BigDecimal gbp,
                      BigDecimal pctOfTotal, BigDecimal pctOfInvested,
                      boolean scored,
                      List<Constituent> constituents) {
    }

    /**
     * One position feeding a bucket, for the click-through popup.
     *
     * @param valueScore  0–1 on the cheapness axis; null for structural buckets and funds
     * @param growthScore 0–1 on the growth axis; null likewise
     * @param flags       which tests it passed, so a placement can be argued with rather than
     *                    merely accepted — for a fund, its published multiples instead
     * @param note        why it could not be scored, for UNKNOWN rows
     */
    public record Constituent(String label,
                              BigDecimal gbp,
                              BigDecimal pctOfBucket,
                              BigDecimal valueScore,
                              BigDecimal growthScore,
                              BigDecimal dividendYield,
                              List<String> flags,
                              String note) {
    }

    /**
     * The cross-cutting income figure. <b>Not a row in {@link #rows()}</b>: income names already
     * sit in whichever style bucket they scored into (mostly Value, but a cheap grower like BP is
     * in Value+Growth), so adding this to the table would double-count them.
     *
     * <p><b>Two yields, because one is misreadable.</b> A single "portfolio yield" is either a
     * strong-looking number over a base most of the portfolio is not in, or a weak-looking one that
     * understates what the income holdings actually pay — and nothing on screen says which. So both
     * ship, each with its denominator named beside it:
     * <ul>
     *   <li>{@code yieldOnIncomeHoldings} = {@code incomeAnnualGbp ÷ gbp} — a true yield on the
     *       basket the tile is about, and exactly what {@link #fixedIncome} and {@link #equity}
     *       sum to.</li>
     *   <li>{@code yieldOnTotal} = {@code annualIncomeGbp ÷ totalGbp} — everything the portfolio
     *       pays over everything it holds, cash and funds included.</li>
     * </ul>
     * The two numerators differ, so {@code otherAnnualIncomeGbp} carries the bridge: what the
     * holdings <i>below</i> the income bar pay. {@code incomeAnnualGbp + otherAnnualIncomeGbp =
     * annualIncomeGbp}, printed in the drill-down so all three figures reconcile on screen rather
     * than looking like an inconsistency.
     *
     * @param gbp            value of every income holding — the yielding equities <b>plus all
     *                       fixed income</b>, which is an income holding structurally rather than
     *                       by clearing the yield bar
     * @param fundsExcluded  whether any fund was left out of the income — Yahoo publishes no
     *                       distribution yield for the funds held here, so both figures are a
     *                       <b>lower bound</b> on the portfolio's real income and say so
     */
    public record Income(BigDecimal gbp,
                         BigDecimal pctOfTotal,
                         BigDecimal pctOfInvested,
                         BigDecimal yieldOnIncomeHoldings,
                         BigDecimal yieldOnTotal,
                         BigDecimal incomeAnnualGbp,
                         BigDecimal annualIncomeGbp,
                         BigDecimal otherAnnualIncomeGbp,
                         boolean fundsExcluded,
                         Section fixedIncome,
                         Section equity) {
    }

    /**
     * One half of the income holdings, for the drill-down. Kept as two sections rather than one
     * flat list because they answer different questions and are not comparable: a gilt's running
     * yield is contractual and its capital is pulling to par, while an equity's is a policy the
     * company can cut tomorrow.
     */
    public record Section(String label, BigDecimal gbp, BigDecimal annualIncomeGbp,
                          BigDecimal yield, List<IncomeContributor> contributors) {
    }

    /**
     * @param note why this holding contributes no income despite counting as an income holding —
     *             a bond fund with no published distribution yield, say. Null when it does.
     */
    public record IncomeContributor(String symbol, BigDecimal gbp, BigDecimal dividendYield,
                                    BigDecimal annualIncomeGbp, String bucket, String note,
                                    String yieldSource) {
    }

    /**
     * The four scored cells, for the 2×2 grid. Structural buckets are deliberately absent — a
     * grid that also had to hold cash and gilts would stop being a 2×2.
     */
    public record Quadrant(BigDecimal valueGbp, BigDecimal growthGbp,
                           BigDecimal valueGrowthGbp, BigDecimal neitherGbp,
                           BigDecimal scoredTotalGbp) {
    }

    /**
     * A position the two-axis score could not place. {@code state} carries the same three causes
     * {@link SectorBreakdown.Contributor} documents — {@code AWAITING_DATA} and
     * {@code FETCH_FAILED} are both "no metrics have arrived yet" and self-heal, while
     * {@code NOT_SCOREABLE} means the metrics are present but too few of them to score an axis,
     * which is what {@code note} explains.
     */
    public record Contributor(String symbol, BigDecimal gbp, String note, String state) {
    }
}
