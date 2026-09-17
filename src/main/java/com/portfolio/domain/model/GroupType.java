package com.portfolio.domain.model;

import java.util.Locale;

/**
 * What kind of intention a group of orders records.
 *
 * <p>Two shapes, and they are genuinely different questions rather than one question with a flag.
 * A {@link #LADDER} is rungs on <em>one</em> security laid out around its price; a
 * {@link #SHARE_CLASS_SWITCH} is one holding moving between two listings of the <em>same</em> fund,
 * where there is no single price axis to lay anything on — the two lines are quoted in different
 * currencies and their ratio <em>is</em> the exchange rate. Flattening the switch into a ladder
 * would put a sterling price and a dollar price on one axis and answer none of the questions a
 * switch actually raises: which leg is blocked on which, how much of the gap between the two prices
 * is the fund and how much is FX, and how much of the position has actually moved.
 *
 * <p>Concretely, what "one group, one security" costs is paid in the ladder assembler and nowhere
 * else: the symbol lives on {@code order_group}, membership is offered from same-symbol orders
 * only, and the ladder view lays rungs on a single price axis. All three are decisions of that
 * type's assembler, not assumptions in the storage — which is why the switch needed a second
 * assembler and one extra nullable column rather than a schema change.
 */
public enum GroupType {

    /** Rungs on one security — buys below the market, sells above, one intention. */
    LADDER("Ladder"),

    /**
     * One holding moving from one listing of a fund to another listing of the <em>same</em> fund —
     * same ISIN, same holdings, different trading currency, exchange line and account.
     *
     * <p><b>Not a rotation.</b> Nothing about what the user owns changes, which is why the panel
     * refuses to draw the two legs as two positions and why the declaration also folds the pair into
     * one holding everywhere exposure is measured. A move between two <em>different</em> investments
     * is a third shape with different questions, and this constant is deliberately not it.
     */
    SHARE_CLASS_SWITCH("Share-class switch");

    private final String label;

    GroupType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * Read a stored type. Returns null for a value this build does not know — the caller drops the
     * group and says so rather than guessing it into a type that exists, which would put a switch
     * on a price axis that means nothing for it, or a ladder's rungs into two legs it does not have.
     */
    public static GroupType fromDb(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
