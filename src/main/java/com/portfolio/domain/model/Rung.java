package com.portfolio.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One step of a ladder, whichever of the two things it is: an order that exists at a broker, or an
 * intention the user recorded here and has not placed.
 *
 * <p>The two are deliberately one type. Everything the panel does with a rung — order it by price,
 * total the cash it moves, ask how likely it is to fill — is the same question for both, and the
 * spec is explicit that an intended rung carries the same analytics as a real one wherever that is
 * possible. Keeping them apart would have meant two of every calculation and, eventually, two
 * answers. What differs is recorded in {@link #state} and nowhere else.
 *
 * @param key   what the UI addresses this rung by: the broker order identity for a real rung, or
 *              {@code intent:<id>} for an intended one. Distinct namespaces, so a remove button
 *              can never take the wrong kind.
 * @param order the enriched order behind the rung — the live one for a working rung, the synthetic
 *              one built for an intended rung and pushed through the <em>same</em> enrichment pass.
 *              That is what makes "the same analytics" true by construction rather than by
 *              duplication. Null for a closed rung: nothing resting means nothing to price, and a
 *              touch probability on a filled order would be an answer to a question nobody has.
 */
public record Rung(String key, Long intentId, String account, String side,
                   BigDecimal quantity, BigDecimal price, String currency,
                   State state, LocalDate closedAt, String note, OpenOrder order) {

    /**
     * Where a rung stands. The four non-intended values are {@link OrderStatus} as the history
     * reads it, including the one no broker writes: an order that closed by simply disappearing
     * from the export.
     */
    public enum State {
        /** Decided here, not placed. Exists only in this panel. */
        INTENDED("Intended"),
        /** Resting at the broker right now. */
        WORKING("Working"),
        /** Traded. Stays visible — a ladder whose sell filled and whose buy filled later is the
         *  completed round trip, which is the thing the panel exists to show. */
        FILLED("Filled"),
        /** Cancelled, expired or rejected. Kept, because it is part of how the intention played
         *  out, but counted in nothing. */
        CANCELLED("Cancelled"),
        /** The broker stopped listing it without ever saying what became of it. */
        UNLISTED("No longer listed");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public boolean intended() {
        return state == State.INTENDED;
    }

    /** Fills still to come: what the net-position and cash-needed figures are about. */
    public boolean remaining() {
        return state == State.INTENDED || state == State.WORKING;
    }

    public boolean filled() {
        return state == State.FILLED;
    }

    /** Cancelled, or stopped being listed. Kept on the ladder, counted in nothing. */
    public boolean gone() {
        return state == State.CANCELLED || state == State.UNLISTED;
    }

    public boolean buy() {
        return "buy".equalsIgnoreCase(side);
    }

    public boolean sell() {
        return "sell".equalsIgnoreCase(side);
    }

    /** Signed by side — a buy adds to the position, a sell takes away. Null when either is absent. */
    public BigDecimal signedQuantity() {
        if (quantity == null || (!buy() && !sell())) return null;
        return buy() ? quantity : quantity.negate();
    }

    /** Quantity × price in {@link #currency}. Null when either is missing — never zero. */
    public BigDecimal cash() {
        if (quantity == null || price == null) return null;
        return quantity.multiply(price);
    }

    /**
     * The symbol the rung's prices print with. Deliberately the same mapping {@link OpenOrder} uses
     * rather than a second one — a rung and the order behind it must never print different money.
     */
    public String currencySymbol() {
        return OpenOrder.symbolFor(currency);
    }

    /** A label for the rung in messages about what had to be left out of a total. */
    public String describe() {
        return account + " " + (side == null ? "?" : side)
                + (quantity == null ? "" : " " + quantity.stripTrailingZeros().toPlainString())
                + (price == null ? "" : " @ " + price.stripTrailingZeros().toPlainString());
    }
}
