package com.portfolio.domain.grouping;

import com.portfolio.domain.model.AggHolding;

import java.math.BigDecimal;
import java.util.List;

/**
 * One group of holdings and its aggregate, shaped to sit in the Cash-ledger table as a header row
 * above its members.
 *
 * <p><b>Only the columns that have a defensible group-level value carry one.</b> The rest are null
 * and render blank, which is a statement rather than an omission:
 * <ul>
 *   <li><b>Quantity, Price, Ccy, Sources, Security</b> — no meaning across instruments. Summing
 *       200,000 of nominal gilt with 40 shares of Alphabet produces a number that is not about
 *       anything.</li>
 *   <li><b>Gain % and Total Gain %</b> — {@code Σgain ÷ Σcost}, never the mean of the members'
 *       percentages. A £5 position up 300% and a £50,000 position down 2% average to +149%; the
 *       weighted figure is the −2% the money actually did. See {@link #costGbp}.</li>
 *   <li><b>Annualised return</b> — deliberately absent from this record. See below.</li>
 * </ul>
 *
 * <p><b>There is no group-level annualised return, and an approximation would be worse than the
 * blank.</b> The column is an IRR over each position's own dated flows. Members of a group have
 * different holding periods and different flow histories, so no weighting of the per-holding rates
 * is defensible — not by value (a rate is not a quantity of money), not by cost, not by holding
 * period. The correct figure is a single money-weighted IRR solved over the union of the group's
 * dated cash flows, which is a different computation over data this record does not carry: it
 * needs the ledger rows, not the aggregated rows. That is a separate piece of work, tracked as a
 * backlog item. Until it exists the cell stays empty with a tooltip saying why, because a
 * plausible-looking wrong rate in a column of right ones is the one outcome worth avoiding.
 *
 * @param costGbp the group's cost basis, {@code Σ(marketValue − gain)} over members whose gain is
 *                known. Derived rather than carried because {@link AggHolding} has no cost field
 *                and the identity is exact — the ledger view builds {@code gain = value − cost}.
 *                It is the denominator of both percentages and is kept on the record so the two
 *                can be checked against it rather than taken on trust.
 */
public record HoldingGroup(
        String id,
        String label,
        String note,
        boolean residual,
        BigDecimal marketValueGbp,
        BigDecimal costGbp,
        BigDecimal gainGbp,
        BigDecimal gainPct,
        BigDecimal dividendGbp,
        BigDecimal totalGainGbp,
        BigDecimal totalGainPct,
        List<AggHolding> members) {

    public int memberCount() {
        return members.size();
    }
}
