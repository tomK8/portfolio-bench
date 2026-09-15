package com.portfolio.domain.model;

import java.util.Locale;

/**
 * What kind of intention a group of orders records.
 *
 * <p>One value today, and the discriminator exists for the one that is coming. A {@link #LADDER}
 * is rungs on <em>one</em> security; a switch — selling out of one holding to buy into another — is
 * a different shape with different questions (sequencing, ratio, preserving the weight), and
 * flattening it into a ladder would answer none of them. Keeping the column from the first
 * migration means adding that type later is a new constant and a second assembler, not a schema
 * change to a table that already holds the user's groups.
 *
 * <p>Concretely, what "one group, one security" costs today and where it is paid: the symbol lives
 * on {@code order_group}, membership is offered from same-symbol orders only, and the ladder view
 * lays rungs on a single price axis. All three are decisions of the assembler for <em>this</em>
 * type, not assumptions baked into the storage.
 */
public enum GroupType {

    /** Rungs on one security — buys below the market, sells above, one intention. */
    LADDER("Ladder");

    private final String label;

    GroupType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * Read a stored type. Returns null for a value this build does not know — the caller drops the
     * group and says so rather than guessing it into the only type that exists, which would put a
     * future switch group on a price axis that means nothing for it.
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
