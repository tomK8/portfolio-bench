package com.portfolio.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Keeps the CLAUDE.md routing index honest.
 *
 * <p>CLAUDE.md was split out of a 228k-character single file so that per-feature detail loads on
 * demand instead of on every session. That trade only works while the index actually routes: a
 * {@code docs/} file nothing points at is a file nobody will read, and an index line pointing at a
 * renamed file is worse than no line at all, because it reads as coverage that isn't there.
 *
 * <p>The guard is mechanical for the same reason the rest of this codebase prefers derived data to
 * maintained data — an index kept correct by discipline alone is one that goes stale silently.
 */
class DocsIndexTest {

    private static final Path ROOT = Path.of(System.getProperty("user.dir"));
    private static final Path DOCS = ROOT.resolve("docs");
    private static final Path CLAUDE_MD = ROOT.resolve("CLAUDE.md");

    /** Markdown links, e.g. {@code [persistence.md](docs/persistence.md)}. */
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)#]+?\\.md)(?:#[^)]*)?\\)");

    /**
     * Character budget for CLAUDE.md. A <em>ratchet</em>, not a ceiling: it is checked from both
     * sides, so it tracks the file rather than leaving it room to grow into. Raising it is a
     * deliberate one-line diff; lowering it is required once the file shrinks.
     */
    private static final int BUDGET = 21_000;

    /** How far {@link #BUDGET} may sit above the file before it counts as drift waiting to happen. */
    private static final int SLACK = 2_000;

    /** Whitespace-separated tokens in one routing-table row, table pipes included. */
    private static final int MAX_ROW_WORDS = 45;

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> docFileNames() {
        try (Stream<Path> s = Files.list(DOCS)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The rows of the "docs/ — read before changing the area" table. */
    private static List<String> routingRows(String index) {
        return index.lines()
                .filter(l -> l.startsWith("| [") && l.contains("](docs/"))
                .toList();
    }

    private static List<String> linksIn(String markdown) {
        List<String> out = new ArrayList<>();
        Matcher m = LINK.matcher(markdown);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    @Test
    void everyDocIsReachableFromTheIndex() {
        String index = read(CLAUDE_MD);
        Set<String> linked = linksIn(index).stream()
                .filter(l -> l.startsWith("docs/"))
                .map(l -> l.substring("docs/".length()))
                .collect(Collectors.toCollection(TreeSet::new));

        Set<String> orphans = new TreeSet<>(docFileNames());
        orphans.removeAll(linked);
        if (!orphans.isEmpty()) {
            fail("docs/ files not referenced from the CLAUDE.md index: " + orphans
                    + " — add an index row naming the invariant the file guards, "
                    + "or delete the file. An unreferenced doc is one nobody will read.");
        }
    }

    @Test
    void everyIndexLinkResolves() {
        String index = read(CLAUDE_MD);
        List<String> broken = linksIn(index).stream()
                .filter(l -> !Files.exists(ROOT.resolve(l)))
                .toList();
        assertTrue(broken.isEmpty(),
                "CLAUDE.md links to files that do not exist: " + broken);
    }

    @Test
    void everyCrossReferenceBetweenDocsResolves() {
        List<String> broken = new ArrayList<>();
        for (String name : docFileNames()) {
            Path doc = DOCS.resolve(name);
            for (String link : linksIn(read(doc))) {
                if (link.startsWith("http")) continue;
                if (!Files.exists(doc.getParent().resolve(link).normalize())) {
                    broken.add(name + " -> " + link);
                }
            }
        }
        assertTrue(broken.isEmpty(), "broken links between docs/ files: " + broken);
    }

    @Test
    void theIndexStaysWithinItsBudget() {
        int chars = read(CLAUDE_MD).length();
        assertTrue(chars <= BUDGET,
                "CLAUDE.md is " + chars + " characters against a budget of " + BUDGET + ". It is "
                        + "loaded in full on every session, so detail belongs in a docs/ file with "
                        + "an index row pointing at it. Move the detail out, or — if this really is "
                        + "a new invariant that has to load every time — raise BUDGET deliberately.");
    }

    /**
     * The other half of the ratchet, and the one that matters.
     *
     * <p>The first split left a round 60,000-character cap with 36k of headroom, and the file grew
     * into every byte of it in seven weeks: 23k to 60k, stopping 7 characters short. The cap was
     * never breached, so it never fired. A ceiling with slack behind it licenses exactly as much
     * growth as the slack. Checking the budget from below is what stops headroom accumulating
     * silently — when the file shrinks, the budget has to come down with it.
     */
    @Test
    void theBudgetLeavesNoRoomToDriftInto() {
        int chars = read(CLAUDE_MD).length();
        assertTrue(BUDGET - chars <= SLACK,
                "CLAUDE.md is down to " + chars + " characters but BUDGET is still " + BUDGET
                        + ", leaving " + (BUDGET - chars) + " characters of headroom. Lower BUDGET "
                        + "to about " + chars + ": unclaimed headroom is what the file grew into "
                        + "last time.");
    }

    /**
     * The rule that was actually broken. An index row is meant to name one invariant so it fires
     * without the file being opened; when it starts explaining the invariant it has become a second
     * copy of the doc, maintained in the one place that loads every session. By the time this guard
     * was written two rows had reached 979 and 815 words — a fifth of the file between them,
     * restating docs they already pointed at.
     */
    @Test
    void noRoutingRowCarriesTheDetailItRoutesTo() {
        List<String> tooWide = routingRows(read(CLAUDE_MD)).stream()
                .filter(r -> r.split("\\s+").length > MAX_ROW_WORDS)
                .map(r -> {
                    Matcher m = LINK.matcher(r);
                    String doc = m.find() ? m.group(1) : r.substring(0, Math.min(40, r.length()));
                    return doc + " (" + r.split("\\s+").length + " words)";
                })
                .toList();
        assertTrue(tooWide.isEmpty(),
                "CLAUDE.md index rows over " + MAX_ROW_WORDS + " words: " + tooWide
                        + " — a row names the invariant and stops. The evidence, the worked example "
                        + "and the count belong in the docs/ file it points at.");
    }

    @Test
    void theIndexHasARowForEveryDoc() {
        int rows = routingRows(read(CLAUDE_MD)).size();
        int docs = docFileNames().size();
        assertTrue(rows == docs,
                "the routing table has " + rows + " rows for " + docs + " docs/ files. Every doc "
                        + "gets exactly one row; everyDocIsReachableFromTheIndex() allows a link "
                        + "from anywhere in the file, which is not the same as being routed to.");
    }
}
