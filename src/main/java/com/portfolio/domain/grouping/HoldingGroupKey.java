package com.portfolio.domain.grouping;

import com.portfolio.domain.model.AggHolding;

/**
 * Assigns each holding to exactly one group, for the Cash-ledger table's grouping dropdown.
 *
 * <p><b>A key type is a resolver, not a branch.</b> Everything the grouping feature does — the
 * dropdown, the header rows, the weighted percentages, the expand/collapse — is written against
 * this one method, so a new way to group the book is a new implementation and nothing else. That
 * is deliberate: a permanent/rotation label and a currency split are both wanted next, and neither
 * should reach into {@link HoldingGrouper}, the fragment or {@code dashboard.js}. If a future key
 * needs a code change outside its own class, the abstraction has been broken.
 *
 * <p><b>Exactly one group per holding</b> is the invariant the whole thing rests on. The ledger
 * table's rows are the unit of grouping, and a row is already an aggregate across accounts and
 * lots; splitting one across groups would mean the group totals no longer add back to the
 * ungrouped total, which is the only property that makes a grouped table trustworthy. A resolver
 * that cannot place a holding must therefore return a group saying so — never null, and never a
 * silent drop.
 */
public interface HoldingGroupKey {

    /** The group this holding belongs to. Never null. */
    Group keyFor(AggHolding holding);

    /**
     * One group's identity.
     *
     * @param id       stable machine key — the DOM id the expand/collapse toggle keys on, so it
     *                 must not contain characters that would need escaping in an attribute
     * @param label    what the header row shows
     * @param note     optional explanation rendered as the header's tooltip, for a group whose
     *                 membership rule is not obvious from its label (a fund spanning sectors,
     *                 a position held across two accounts). Null when the label speaks for itself.
     * @param residual whether this is the "couldn't place it" group. Pinned last in the table
     *                 regardless of value, so the unplaced never sit in the middle of a ranking
     *                 that implies they were classified. Declared by the resolver rather than
     *                 detected by name, so {@link HoldingGrouper} carries no per-key-type logic.
     */
    record Group(String id, String label, String note, boolean residual) {

        public static Group of(String id, String label) {
            return new Group(id, label, null, false);
        }

        public static Group of(String id, String label, String note) {
            return new Group(id, label, note, false);
        }

        public static Group residual(String id, String label, String note) {
            return new Group(id, label, note, true);
        }
    }
}
