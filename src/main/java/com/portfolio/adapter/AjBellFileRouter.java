package com.portfolio.adapter;

import com.portfolio.domain.AjBellPortfolios;
import com.portfolio.domain.AjBellPortfolios.Verdict;
import com.portfolio.domain.model.Account;
import com.portfolio.parser.AJBellDealingParser;
import com.portfolio.parser.AJBellPortfolioParser;
import com.portfolio.parser.AJBellSippParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Decides which AJ Bell account a {@code cashstatements*.csv} or {@code activeorders*.csv} belongs
 * to, and which file is the current one for each account.
 *
 * <p>Two accounts behind one AJ Bell login export files that are indistinguishable: same filename
 * pattern, same header, same reference format, no account number anywhere in them. Only the
 * holdings export names its account. So this builds a registry from the holdings exports sitting in
 * the same input directory — account to currently-held tickers — and hands it to
 * {@link AjBellPortfolios#route} along with whatever instruments the file names. Nothing is stored
 * and nothing is maintained: the registry is rebuilt from whatever the broker last exported.
 *
 * <p><b>The other half of the problem is selection.</b> Both sides of the app previously took the
 * single most recent file matching a pattern. With a second account that silently hides one
 * account's orders behind the other's — the dealing account's {@code activeorders (69).csv} is
 * newer than the SIPP's {@code (68)}, so twelve SIPP orders would have vanished from the panel with
 * no error anywhere. {@link #select} returns the newest file <em>per account</em> instead.
 *
 * <p><b>What it refuses to do is guess.</b> A statement filed against the wrong account corrupts
 * two ledgers at once and the AJ Bell dedup key is scoped per account, so the next import would not
 * repair it. A file whose evidence names two accounts, or none, is therefore routed to neither and
 * reported — see {@link Selection#warnings()}, which the orders panel and the import result both
 * render as a warning strip.
 */
public class AjBellFileRouter {

    private static final Logger log = LoggerFactory.getLogger(AjBellFileRouter.class);

    /**
     * The AJ Bell accounts and the holdings parser that recognises each one's export. The single
     * declaration of the pairing — the registry, the holdings parse and the routing all read it.
     */
    private static final Map<Account, AJBellPortfolioParser> HOLDINGS_PARSERS = new LinkedHashMap<>();

    static {
        HOLDINGS_PARSERS.put(Account.AJBELL, new AJBellSippParser());
        HOLDINGS_PARSERS.put(Account.AJBELL_DEALING, new AJBellDealingParser());
    }

    /** Verdicts, keyed by path and invalidated when the file's mtime or size changes. */
    private final Map<String, CachedVerdict> cache = new ConcurrentHashMap<>();

    /**
     * The last registry and the directory state it was read from. Rebuilding it opens every
     * {@code portfolio*.csv} in the input directory to read its {@code Portfolio} column, and
     * {@link #accountFor} is called once per candidate file — so without this, one orders-panel
     * load would re-read seventy holdings exports seventy times over. The fingerprint is names and
     * mtimes only, so an export downloaded mid-session still takes effect immediately.
     */
    private volatile CachedRegistry registryCache;

    private final HoldingFileLocator fileLocator;

    public AjBellFileRouter(HoldingFileLocator fileLocator) {
        this.fileLocator = fileLocator;
    }

    /** The AJ Bell accounts this router knows about, in declaration order. */
    public static List<Account> accounts() {
        return List.copyOf(HOLDINGS_PARSERS.keySet());
    }

    /**
     * Each AJ Bell account's currently held tickers, read from its own most recent holdings export.
     * An account whose export is not in the input directory contributes an empty set — it cannot
     * claim anything, which is the safe failure: files route to the account that can be evidenced,
     * or to nobody.
     */
    public Map<Account, Set<String>> registry() {
        String state = holdingsDirState();
        CachedRegistry hit = registryCache;
        if (hit != null && hit.state().equals(state)) return hit.registry();

        Map<Account, Set<String>> out = new LinkedHashMap<>();
        for (Map.Entry<Account, AJBellPortfolioParser> e : HOLDINGS_PARSERS.entrySet()) {
            Path file = null;
            try {
                file = fileLocator.findMostRecent(e.getValue()).orElse(null);
            } catch (IOException io) {
                log.warn("Could not scan {} for the {} holdings export",
                        fileLocator.inputDir(), e.getKey().displayName(), io);
            }
            out.put(e.getKey(), file == null ? Set.of() : AJBellPortfolioParser.tickersIn(file));
        }
        Map<Account, Set<String>> registry = Collections.unmodifiableMap(out);
        registryCache = new CachedRegistry(state, registry);
        return registry;
    }

    /** Names and mtimes of the holdings exports — enough to know the registry has to be rebuilt. */
    private String holdingsDirState() {
        try {
            return list(fileLocator.inputDir(), AJBellPortfolioParser::isAjBellPortfolioFile).stream()
                    .map(p -> p.getFileName() + "@" + p.toFile().lastModified())
                    .reduce("", (a, b) -> a + "|" + b);
        } catch (IOException e) {
            return "unreadable@" + System.identityHashCode(e);
        }
    }

    /**
     * The account a single file belongs to, or null when the evidence names two accounts or none.
     * Suitable as an {@code OrderParser.supports} predicate — each AJ Bell orders parser accepts
     * exactly the files routed to its own account, so the existing "newest supported file wins"
     * lookup then picks the newest file per account with no further change.
     */
    public Account accountFor(Path file, Function<Path, Collection<String>> symbols) {
        return verdict(file, symbols, registry()).account();
    }

    /**
     * The current file for each AJ Bell account, newest first, plus a warning for every file that
     * could not be routed and <em>mattered</em>.
     *
     * <p>"Mattered" is the newest-resolved-file cutoff: an input directory holds dozens of archived
     * exports, and an old one naming positions since closed is unroutable but uninteresting. A file
     * that cannot be placed yet is <b>newer</b> than one that could is the real signal — something
     * was just downloaded that this cannot account for.
     */
    public Selection select(Path inputDir, Predicate<Path> candidate,
                            Function<Path, Collection<String>> symbols) {
        List<Path> files;
        try {
            files = list(inputDir, candidate);
        } catch (IOException e) {
            return new Selection(Map.of(), List.of(
                    new Unplaceable(inputDir, "could not scan the input directory (" + e.getMessage() + ")")));
        }
        Map<Account, Set<String>> registry = registry();

        Map<Account, Path> chosen = new LinkedHashMap<>();
        List<Unplaceable> unplaceable = new ArrayList<>();
        boolean anyResolved = false;
        for (Path file : files) {                       // newest first
            Verdict v = verdict(file, symbols, registry);
            if (v.resolved()) {
                anyResolved = true;
                chosen.putIfAbsent(v.account(), file);
                if (chosen.size() == registry.size()) break;
                continue;
            }
            // Older than everything that could be placed — archived history, not a problem.
            if (anyResolved) continue;
            unplaceable.add(new Unplaceable(file, v.explain()));
        }
        return new Selection(chosen, unplaceable);
    }

    private Verdict verdict(Path file, Function<Path, Collection<String>> symbols,
                            Map<Account, Set<String>> registry) {
        String fingerprint = fingerprint(file, registry);
        CachedVerdict hit = cache.get(file.toString());
        if (hit != null && hit.fingerprint().equals(fingerprint)) return hit.verdict();
        Verdict v = AjBellPortfolios.route(symbols.apply(file), registry);
        cache.put(file.toString(), new CachedVerdict(fingerprint, v));
        return v;
    }

    /**
     * Cache key. Covers the registry as well as the file: a downloaded export never changes, but
     * the holdings file it is judged against does, and the same orders file can route differently
     * once the other account's positions are known.
     */
    private static String fingerprint(Path file, Map<Account, Set<String>> registry) {
        long mtime = 0;
        long size = 0;
        try {
            mtime = Files.getLastModifiedTime(file).toMillis();
            size = Files.size(file);
        } catch (IOException ignored) {
            // An unreadable file re-routes every call; it has nothing to cache anyway.
        }
        return mtime + ":" + size + ":" + registry;
    }

    private static List<Path> list(Path inputDir, Predicate<Path> candidate) throws IOException {
        if (!Files.isDirectory(inputDir)) return List.of();
        try (var stream = Files.list(inputDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(candidate)
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .toList();
        }
    }

    /**
     * @param byAccount   the current file per account; an account with no file is simply absent
     * @param unplaceable files that could not be routed and were left untouched on disk
     */
    public record Selection(Map<Account, Path> byAccount, List<Unplaceable> unplaceable) {

        /** The unplaceable files as warning-strip lines. */
        public List<String> warnings() {
            return unplaceable.stream().map(Unplaceable::warning).toList();
        }
    }

    /** A file that matched the pattern but could not be attributed to an account. */
    public record Unplaceable(Path file, String reason) {

        public String warning() {
            return "AJ Bell: could not tell which account " + file.getFileName() + " is for — "
                    + reason + ". Nothing was read from it, and it is still in the input directory. "
                    + "The two accounts' exports are identical in shape, so the only evidence is "
                    + "which account holds the instruments the file names: download both "
                    + "portfolio*.csv holdings exports so each account's positions are known.";
        }
    }

    private record CachedVerdict(String fingerprint, Verdict verdict) {
    }

    private record CachedRegistry(String state, Map<Account, Set<String>> registry) {
    }
}
