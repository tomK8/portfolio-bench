package com.portfolio.domain.grouping;

import com.portfolio.domain.model.AggHolding;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Groups by the account(s) a position is held in, read straight off the ledger — {@link
 * AggHolding#sources()} is the comma-joined set of account labels {@code SyncFromCashService}
 * built from the reconstructed position's own transactions, so no lookup and no I/O.
 *
 * <p><b>A position held in two accounts forms its own group rather than being split between
 * them.</b> The Cash-ledger view aggregates a symbol across accounts into a single row — one
 * quantity, one cost basis, one IRR episode walk — so there is no per-account half of that row to
 * file anywhere; splitting it would mean rebuilding the view around a different unit and would
 * break the property that the groups add back to the ungrouped total. The combined group is
 * labelled with every account it spans and carries a note saying so, which is the honest reading:
 * the position genuinely is shared, and a reader looking for "what is in the SIPP" needs to see
 * that rather than have half of it silently assigned.
 *
 * <p><b>The accounts are sorted before the key is built, and that is a correctness fix rather
 * than tidiness.</b> {@code sources} is built from a {@code LinkedHashSet} filled in trade order,
 * so the same pair of accounts arrives as "II SIPP, AJ Bell SIPP" on one position and "AJ Bell
 * SIPP, II SIPP" on another purely according to which broker saw the first trade. Keyed on that
 * string as it stands, one set of accounts would split into two groups that a reader has no way
 * to tell apart — the live book already carries both orderings. Sorting makes the key a property
 * of the set.
 */
public final class AccountGroupKey implements HoldingGroupKey {

    private static final String UNATTRIBUTED_ID = "no-account";
    /** " + " rather than a comma: the label is a set of accounts, not the raw Sources cell. */
    private static final String JOINER = " + ";

    @Override
    public Group keyFor(AggHolding holding) {
        String sources = holding.sources();
        List<String> accounts = sources == null ? List.of()
                : Arrays.stream(sources.split(","))
                        .map(String::trim)
                        .filter(a -> !a.isEmpty())
                        .distinct()
                        .sorted()
                        .toList();
        if (accounts.isEmpty()) {
            return Group.residual(UNATTRIBUTED_ID, "No account recorded",
                    "The ledger rows behind this position name no account.");
        }
        String label = String.join(JOINER, accounts);
        return Group.of(slug(label), label,
                accounts.size() > 1
                        ? "Held across " + accounts.size() + " accounts. The ledger view "
                          + "aggregates a symbol into one row, so a shared position is reported "
                          + "as shared rather than split between the accounts."
                        : null);
    }

    /** DOM-safe id: the expand/collapse toggle keys on it, and account labels carry spaces. */
    private static String slug(String label) {
        String s = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        return s.isEmpty() ? UNATTRIBUTED_ID : s;
    }
}
