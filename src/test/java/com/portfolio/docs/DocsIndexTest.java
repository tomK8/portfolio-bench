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
    void theIndexStaysSmallEnoughToLoadEverySession() {
        long chars = read(CLAUDE_MD).length();
        assertTrue(chars < 60_000,
                "CLAUDE.md has grown to " + chars + " characters. It is loaded in full on every "
                        + "session, so detail belongs in a docs/ file with an index row pointing "
                        + "at it — not here.");
    }
}
