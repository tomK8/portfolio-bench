package com.portfolio.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One group of Live-tab positions and its aggregate, shaped to sit in the Live table as a header
 * row above its members.
 *
 * <p>It is a separate record from the Cash ledger's {@code HoldingGroup} because the two tables
 * report different things and a header row must only say what its own columns can support. The
 * ledger has a cost basis and weights Gain % by it; the Live table has no cost basis anywhere in
 * it and instead carries a day move and five trailing price windows, each of which needs its own
 * weighting. Sharing one record would have meant a majority of null fields on both screens and no
 * way to tell "this column has no group-level value" from "this table does not have this column".
 *
 * <p><b>Every percentage is weighted, never averaged</b> — see {@link LiveGrouper} for the two
 * weightings and why the trailing windows can be weighted at all without a stored history.
 *
 * @param priceTs       the <b>oldest</b> quote in the group, not the newest. A summary line is
 *                      only as current as its stalest input: a group whose largest name last
 *                      printed four hours ago is a four-hour-old number however fresh the rest of
 *                      it is, and taking the newest would hide exactly the case worth seeing.
 *                      This is the opposite of {@code Snapshot.latestPriceAt}, which answers a
 *                      different question ("has anything at all come in recently").
 * @param stalestSymbol which member that timestamp came from, so the header can name it rather
 *                      than leaving a reader to hunt for the lagging feed
 * @param symbols       the members, in the order the snapshot lists them. Carried on the group
 *                      rather than as a group id on every {@code Row} because membership is what
 *                      a group <i>is</i>, and because the browser needs it to place each row under
 *                      its header without a second pass over the rows to work out who belongs
 *                      where. It is also what keeps {@code Row} — which the Excel export and its
 *                      tests build by hand — unchanged.
 * @param lastPrice     deliberately absent, along with quantity, currency and accounts: prices of
 *                      different instruments in different currencies do not add, and neither do
 *                      share counts
 */
public record LiveGroup(
        String id,
        String label,
        String note,
        boolean residual,
        BigDecimal positionGbp,
        BigDecimal dayChangeGbp,
        BigDecimal dayChangePct,
        BigDecimal change5dPct,
        BigDecimal change10dPct,
        BigDecimal monthChangePct,
        BigDecimal change3mPct,
        BigDecimal change6mPct,
        Instant priceTs,
        String stalestSymbol,
        List<String> symbols) {

    public int memberCount() {
        return symbols.size();
    }
}
