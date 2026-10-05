package com.portfolio.domain;

import com.portfolio.domain.model.Account;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which AJ Bell account an export belongs to, when the export does not say.
 *
 * <p>AJ Bell's holdings export names its account twice — in the filename
 * ({@code portfolio-A50742S-SIPP.csv} / {@code portfolio-A50742D-Dealing account.csv}) and in a
 * {@code Portfolio} column — so that file routes itself. The cash statement and the active-orders
 * export name it <em>nowhere</em>: same filename pattern ({@code cashstatements*.csv},
 * {@code activeorders*.csv}), same header, same reference format, no account number. Two accounts
 * behind one login therefore produce files that are indistinguishable by anything the file states
 * about itself.
 *
 * <p>What is left is what the file is <em>about</em>. A statement or order list names instruments,
 * and each account's own holdings export says which instruments it holds. So the routing rule is:
 * <b>a file belongs to the account whose holdings it exclusively names.</b> "Exclusively" is
 * load-bearing — a symbol held in both accounts is no evidence at all, and only a symbol unique to
 * one of them decides anything.
 *
 * <p>This is deliberately not a list of "the dealing account's tickers" kept somewhere. Such a list
 * can only ever be wrong: it goes stale the first time a position is opened or closed and nothing
 * makes it notice. The holdings export is a live source that restates the same fact every time it
 * is downloaded, which is the repo's standing preference — self-regulating over maintained.
 *
 * <p><b>Three outcomes, and two of them are not an account.</b> Guessing is the one thing this must
 * never do: a cash statement filed against the wrong account corrupts two ledgers at once, and the
 * AJ Bell dedup key is scoped per account, so the next import would not repair it. So the evidence
 * either resolves ({@code RESOLVED}), or points at two accounts at once ({@code CONFLICTING}), or
 * points at none ({@code UNRECOGNISED}) — and in both of the latter the caller imports nothing and
 * says so loudly. {@code CONFLICTING} is also the alarm the user asked for: it is exactly what a
 * SIPP that has started holding a dealing-account name would produce.
 */
public final class AjBellPortfolios {

    private AjBellPortfolios() {
    }

    /** Whether the evidence named one account, two, or none. */
    public enum Kind {
        /** Exactly one account was exclusively named. {@link Verdict#account()} is it. */
        RESOLVED,
        /** Two or more accounts were exclusively named — the file cannot be about both. */
        CONFLICTING,
        /** Nothing in the file is unique to any one account. */
        UNRECOGNISED
    }

    /**
     * @param account  the account the file belongs to, or null unless {@link Kind#RESOLVED}
     * @param evidence the exclusively-held symbols that decided it, per account, in registry order
     *                 — carried so a conflict can name the symbols that caused it rather than
     *                 saying only that one happened
     */
    public record Verdict(Kind kind, Account account, Map<Account, Set<String>> evidence) {

        public boolean resolved() {
            return kind == Kind.RESOLVED;
        }

        /** The accounts the evidence pointed at, in registry order. */
        public List<Account> claimants() {
            return List.copyOf(evidence.keySet());
        }

        /** One line naming what was found, for a warning strip or an import result. */
        public String explain() {
            return switch (kind) {
                case RESOLVED -> account.displayName() + " (holds "
                        + String.join(", ", evidence.get(account)) + ")";
                case CONFLICTING -> evidence.entrySet().stream()
                        .map(e -> e.getKey().displayName() + " holds "
                                + String.join(", ", e.getValue()))
                        .reduce((a, b) -> a + "; " + b).orElse("");
                case UNRECOGNISED -> "nothing in it is held by only one AJ Bell account";
            };
        }
    }

    /**
     * Routes a file by the symbols it names.
     *
     * @param symbolsInFile every instrument the file refers to, however the caller resolves them —
     *                      an order's investment name mapped to a symbol, a statement row's
     *                      description. Unresolvable ones may be passed through as-is; they simply
     *                      match nothing.
     * @param registry      each candidate account's currently held symbols, from its own holdings
     *                      export. Iteration order is preserved in the verdict.
     */
    public static Verdict route(Collection<String> symbolsInFile,
                                Map<Account, ? extends Collection<String>> registry) {
        Set<String> named = normalise(symbolsInFile);
        Map<Account, Set<String>> evidence = new LinkedHashMap<>();

        for (Map.Entry<Account, ? extends Collection<String>> entry : registry.entrySet()) {
            Set<String> exclusive = normalise(entry.getValue());
            // A symbol both accounts hold says nothing about which export this is, so it is
            // removed from both sides rather than counted for both.
            for (Map.Entry<Account, ? extends Collection<String>> other : registry.entrySet()) {
                if (other.getKey() != entry.getKey()) exclusive.removeAll(normalise(other.getValue()));
            }
            exclusive.retainAll(named);
            if (!exclusive.isEmpty()) evidence.put(entry.getKey(), exclusive);
        }

        // Collections.unmodifiableMap, not Map.copyOf: the latter does not keep insertion order,
        // and a conflict is reported by naming the accounts in the order the registry declares them.
        Map<Account, Set<String>> found = Collections.unmodifiableMap(evidence);
        if (evidence.isEmpty()) return new Verdict(Kind.UNRECOGNISED, null, found);
        if (evidence.size() > 1) return new Verdict(Kind.CONFLICTING, null, found);
        return new Verdict(Kind.RESOLVED, evidence.keySet().iterator().next(), found);
    }

    private static Set<String> normalise(Collection<String> symbols) {
        Set<String> out = new LinkedHashSet<>();
        if (symbols == null) return out;
        for (String s : symbols) {
            if (s == null) continue;
            String t = s.trim().toUpperCase();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
