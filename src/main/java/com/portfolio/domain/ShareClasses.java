package com.portfolio.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The listings the user has declared to be the same fund, and the folding of a per-symbol figure
 * onto one row per <em>holding</em> rather than one per listing.
 *
 * <p>EQQQ and EQQU are one position: same fund, same ISIN, same holdings, different trading
 * currency and exchange line. A half-finished move between them would otherwise read as two
 * positions of half the size each — a concentration number that says the book is more diversified
 * than it is, an allocation chart with one line falling as another rises, and a currency mix that
 * swings while nothing the user owns has changed. None of those is true, and all three are
 * artefacts of keying exposure by listing.
 *
 * <p><b>Declared, never detected.</b> Two tickers four characters apart on the same exchange are not
 * evidence of anything; the equivalence comes from a share-class switch the user created, and that
 * is the only thing that puts a pair in here. Nothing infers a share class from a name, an ISIN
 * lookup or a price ratio.
 *
 * <p><b>The canonical is the line the switch is leaving.</b> That listing is the one with the price
 * history, the ticker mapping and the sector/style classification already attached, so the folded
 * row keeps working the moment the pair is declared rather than becoming unclassified; and the
 * historical series stays unbroken, which is the point — the holding did not change, only where it
 * is held. Chains resolve to a fixed point, so declaring EQQQ→EQQU and later EQQU→XYZ lands all
 * three on EQQQ.
 *
 * <p><b>Values are folded, never quantities.</b> Each listing is valued on its own quote in its own
 * currency and only the GBP results are added together, so nothing here assumes one unit of the old
 * line equals one unit of the new. (The switch panel does assume that — a share-class pair by
 * definition — and checks it live against the two lines' prices.)
 */
public final class ShareClasses {

    /** No pairs declared — every symbol is its own holding. */
    public static final ShareClasses NONE = new ShareClasses(Map.of(), List.of());

    private final Map<String, String> aliasToCanonical;
    private final List<String> warnings;

    private ShareClasses(Map<String, String> aliasToCanonical, List<String> warnings) {
        this.aliasToCanonical = aliasToCanonical;
        this.warnings = warnings;
    }

    /**
     * Build from declared pairs. Each pair is {@code (leaving, entering)} — the listing the holding
     * is moving out of, and the one it is moving into — and it is the second that becomes an alias
     * of the first.
     *
     * <p>A pair that points a symbol at itself is dropped (it says nothing). A cycle — which can
     * only arise from switching a pair back and forth — is dropped at the symbol that closes it and
     * reported, rather than resolved by picking a winner: two listings that are each other's
     * canonical have no answer, and inventing one would silently pick which of two names the user's
     * charts are keyed by.
     */
    public static ShareClasses of(List<Pair> pairs) {
        if (pairs == null || pairs.isEmpty()) return NONE;
        Map<String, String> direct = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (Pair p : pairs) {
            String alias = norm(p.entering());
            String canonical = norm(p.leaving());
            if (alias == null || canonical == null || alias.equals(canonical)) continue;
            String existing = direct.get(alias);
            if (existing != null && !existing.equals(canonical)) {
                warnings.add(alias + " is declared as a second listing of both " + existing
                        + " and " + canonical + "; the first declaration stands.");
                continue;
            }
            direct.put(alias, canonical);
        }
        if (direct.isEmpty()) return NONE;

        Map<String, String> resolved = new LinkedHashMap<>();
        for (String alias : direct.keySet()) {
            Set<String> seen = new LinkedHashSet<>();
            seen.add(alias);
            String at = direct.get(alias);
            while (direct.containsKey(at) && seen.add(at)) {
                at = direct.get(at);
            }
            if (direct.containsKey(at)) {
                warnings.add(alias + " and " + at + " are declared as second listings of each "
                        + "other, so neither can be the holding's name; they stay separate rows.");
                continue;
            }
            resolved.put(alias, at);
        }
        return new ShareClasses(resolved, List.copyOf(warnings));
    }

    /** One declared pair: the listing being left, and the listing being entered. */
    public record Pair(String leaving, String entering) {
    }

    public boolean isEmpty() {
        return aliasToCanonical.isEmpty();
    }

    /** What the holding declarations were unable to make sense of — surfaced, never swallowed. */
    public List<String> warnings() {
        return warnings;
    }

    /** The name this listing's holding is counted under. Returns the symbol itself when it is not an alias. */
    public String canonical(String symbol) {
        String key = norm(symbol);
        if (key == null) return symbol;
        String c = aliasToCanonical.get(key);
        return c == null ? symbol : c;
    }

    /** True when this listing is folded into another — i.e. it is the second line of a declared pair. */
    public boolean isAlias(String symbol) {
        String key = norm(symbol);
        return key != null && aliasToCanonical.containsKey(key);
    }

    /** The other listings counted under {@code canonical}, for a view that wants to name them. */
    public Set<String> aliasesOf(String canonical) {
        String key = norm(canonical);
        if (key == null) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : aliasToCanonical.entrySet()) {
            if (e.getValue().equals(key)) out.add(e.getKey());
        }
        return out;
    }

    /**
     * Fold a per-listing figure onto one entry per holding, preserving the map's iteration order and
     * summing an alias into whichever entry carries its canonical name. The canonical's own position
     * keeps its place in the order; a pair whose canonical is not in the map at all lands under the
     * canonical name at the alias's position, so a holding entirely moved to the new line still has
     * a row.
     *
     * <p>Returns the input unchanged when nothing is declared, so every caller pays nothing for a
     * feature it is not using.
     */
    public Map<String, BigDecimal> fold(Map<String, BigDecimal> bySymbol) {
        if (isEmpty() || bySymbol == null || bySymbol.isEmpty()) return bySymbol;
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : bySymbol.entrySet()) {
            String key = canonical(e.getKey());
            out.merge(key, e.getValue(), BigDecimal::add);
        }
        return out;
    }

    private static String norm(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT);
    }
}
