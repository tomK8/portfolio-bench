package com.portfolio.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A rung the user has decided on but has not placed at a broker — because the cash isn't in that
 * account yet, or the broker won't take a limit on that instrument.
 *
 * <p>These are real commitments and they were invisible before: adding up what a ladder needs from
 * each wrapper while half of it exists only in the user's head is how the cash gets counted wrong.
 * They live only in this panel. <b>An intended rung is never shown in the orders table</b>, which
 * is broker truth and has to stay that way, and it is never promoted to a real order automatically
 * when a matching one appears in a feed — a guess would attach the reasoning written here to an
 * order that may not be the one it was written about. The user makes that link explicitly.
 *
 * <p>No currency is stored. The instrument's quote already publishes it, so asking for it would be
 * a second, staler answer to a question the feed settles; the rung is priced through the same
 * enrichment a real order takes and reads its currency off the same quote. An instrument that has
 * never priced yields no currency, and the rung is then excluded from the cash totals by name
 * rather than being summed under a guessed one.
 *
 * @param symbol the listing the rung trades. A ladder leaves it null: its rungs are the group's one
 *               security by definition. A share-class switch stores it, because its two sides are
 *               sells and buys and each may sit on <em>either</em> of the pair's listings, so the
 *               side no longer names the line. Null on a switch's intent means a row recorded before
 *               that was true, and the reader falls back to the old rule — sell is the line being
 *               left, buy the line being entered — which is what those rows were recorded under.
 */
public record IntendedRung(long id, long groupId, String account, String symbol, String side,
                           BigDecimal quantity, BigDecimal price, String note,
                           LocalDate createdAt) {

    /** The synthetic order reference this rung is addressed by — see {@code Rung.key()}. */
    public String reference() {
        return "intent:" + id;
    }
}
