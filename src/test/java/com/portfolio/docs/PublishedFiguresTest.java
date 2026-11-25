package com.portfolio.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the size of the whole portfolio out of everything this public repository publishes.
 *
 * <p>Partial figures are fine — a position, a transfer, the gap between two views is often the
 * whole point of a postmortem. The value of the whole book is not, and neither is anything that pins
 * it. In this codebase's prose such a figure takes three shapes, each mechanical to spot:
 * <ul>
 *   <li>a £ figure beside its share of the whole — {@code "£X (4.2% of total)"},
 *       {@code "1.8% on total portfolio, £X/yr"} — where one division recovers the total;</li>
 *   <li>a level named as the whole book — {@code "Files RT read £X against a ledger/Live £Y"},
 *       {@code "Allocation totalled £X"}. Say how far apart two views are, not where each stood;</li>
 *   <li>the same in bare arithmetic — {@code "Files RT X + … = Y = the cash-ledger total"};</li>
 *   <li>a whole category's level — {@code "Financials is £X"}. The README screenshots publish each
 *       sector's and style's share of the book, so a category's £ level is a share-of-the-whole pair
 *       split across two files. A slice of a category ({@code "£X of it into Technology"}) is fine.</li>
 * </ul>
 *
 * <p>The patterns know nothing about the real figure, on purpose: a test that held the number it was
 * looking for would publish it. Commit messages are not files and this test cannot read them; the
 * rule is the same there.
 */
class PublishedFiguresTest {

    private static final Path ROOT = Path.of(System.getProperty("user.dir"));

    /** £1,234 · £1,234.56 · £12k · £1.2m. Atomic, so a following word cannot make it match shorter. */
    private static final String AMOUNT = "(?>£\\s?\\d[\\d,]*(?:\\.\\d+)?(?:\\s?(?:k|m|bn)\\b)?)";
    private static final String BOLD = "\\*{0,2}";
    private static final String SHARE =
            "\\d+(?:\\.\\d+)?\\s?%" + BOLD + "\\s(?:of|on)\\s(?:the\\s)?(?:total|book|portfolio|whole)\\b";

    static final Pattern SHARE_OF_WHOLE = Pattern.compile(
            AMOUNT + ".{0,80}?" + SHARE + "|" + SHARE + ".{0,80}?" + AMOUNT, Pattern.CASE_INSENSITIVE);

    /** A level named as the whole book. A difference — "£X below", "£X more" — is the safe phrasing. */
    static final Pattern NAMED_TOTAL = Pattern.compile(
            "(?:files rt|ledger/live|cash-ledger and live|totalled|total value|portfolio value|book value)"
                    + "\\W{0,4}(?:(?:read|reads|was|is|of|at)\\W{1,3})?" + BOLD + AMOUNT
                    + "(?!" + BOLD + "\\s(?:more|less|above|below|apart|short|over|under|higher|lower)\\b)",
            Pattern.CASE_INSENSITIVE);

    static final Pattern BARE_TOTAL = Pattern.compile(
            "(?:files rt|ledger total)\\W{0,4}\\d{1,3}(?:,\\d{3})+(?:\\.\\d{2})?"
                    + "|\\d{1,3}(?:,\\d{3})+(?:\\.\\d{2})?\\s?=\\s?the\\s(?:cash-)?ledger total",
            Pattern.CASE_INSENSITIVE);

    /** A sector or style bucket named, then its £ level — before any sentence break. */
    static final Pattern CATEGORY_LEVEL = Pattern.compile(
            "\\b(?:financials|technology(?: sector)?|healthcare|communications(?:/media)?"
                    + "|consumer (?:discretionary|staples)|discretionary|staples|industrials|utilities|energy"
                    + "|real estate|materials|fixed income|commodities|income holdings)\\b[^.£\\n]{0,25}?"
                    + BOLD + AMOUNT
                    + "(?!" + BOLD + "\\s(?:more|less|above|below|apart|short|over|under|higher|lower)\\b)",
            Pattern.CASE_INSENSITIVE);

    private static final List<Pattern> SHAPES = List.of(SHARE_OF_WHOLE, NAMED_TOTAL, BARE_TOTAL, CATEGORY_LEVEL);

    private static List<Path> published() throws IOException {
        List<Path> files = new ArrayList<>(List.of(ROOT.resolve("CLAUDE.md"), ROOT.resolve("README.md")));
        for (String dir : List.of("docs", "src/main", "src/test/java")) {
            try (Stream<Path> s = Files.walk(ROOT.resolve(dir))) {
                s.filter(p -> p.toString().matches(".*\\.(md|java|js|html|properties)$"))
                        .filter(p -> !p.toString().contains("/vendor/"))
                        .filter(p -> !p.getFileName().toString().equals("PublishedFiguresTest.java"))
                        .forEach(files::add);
            }
        }
        return files;
    }

    /** Paragraph by paragraph, whitespace flattened, so a figure and its share can't hide on two lines. */
    private static List<String> findings(String text) {
        List<String> out = new ArrayList<>();
        for (String paragraph : text.split("\\n\\s*\\n")) {
            String flat = paragraph.replaceAll("\\s+", " ");
            for (Pattern shape : SHAPES) {
                Matcher m = shape.matcher(flat);
                if (m.find()) out.add(m.group());
            }
        }
        return out;
    }

    @Test
    void nothingPublishedStatesOrPinsTheWholeBook() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path f : published()) {
            for (String hit : findings(Files.readString(f))) hits.add(ROOT.relativize(f) + ": " + hit);
        }
        assertTrue(hits.isEmpty(),
                "these read as the size of the whole portfolio, or pin it: " + hits
                        + " — keep the partial figure and drop the level or the share "
                        + "(\"£X below the ledger total\", \"38.5% of the book\").");
    }

    /** A guard is not a guard until it has been seen to fail. */
    @Test
    void eachShapeIsCaught() {
        for (String shape : List.of(
                "Files RT read £123,456 against a ledger/Live £124,000, and BNP was too big",
                "Allocation → Breakdown totalled **£123,456.78** against a Cash-ledger and Live\n**£120,000.00**",
                "Discretionary £12,345 (4.2% of total; £6,000 direct)",
                "a yield of 1.8% on total portfolio, £2,345/yr",
                "Income holdings run £20k → **£50k** (38.5%\nof total)",
                "`Files RT 123,456.78 + GLE 6,034.47 = 129,491.25 = the cash-ledger total`",
                "Financials is £16.8k of banks, insurers and a data vendor",
                "Technology sector £29,675 + AI names filed elsewhere £14,629")) {
            assertFalse(findings(shape).isEmpty(), shape);
        }
    }

    @Test
    void partialFiguresAndGapsPass() {
        for (String fine : List.of(
                "Files RT read £16,607 below the ledger and Live",
                "Allocation → Breakdown totalled **£30,541.85 more** than the Cash ledger",
                "GLE sat in the ledger at £6,034.47 with the panel silent",
                "`Files RT + GLE 6,034.47 − Roth rounding 0.52 = the cash-ledger total`, to the penny",
                "Income holdings rise to **38.5%** of\nthe book as a result",
                "50 LSEG at an £85.05 limit books at £4,276.26, because 0.5% stamp duty",
                "spread £6,780 of parked cash across sectors, £2,431 of it into Technology",
                "Healthcare drill-down read £764.89 more than the sector table had shown")) {
            assertTrue(findings(fine).isEmpty(), fine + " -> " + findings(fine));
        }
    }
}
