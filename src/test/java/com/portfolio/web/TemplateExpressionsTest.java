package com.portfolio.web;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.context.ExpressionContext;
import org.thymeleaf.standard.expression.AssignationUtils;
import org.thymeleaf.standard.expression.EachUtils;
import org.thymeleaf.standard.expression.StandardExpressions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every Thymeleaf expression in every template parses.
 *
 * <p><b>Why this exists.</b> Thymeleaf parses an attribute only when it actually renders the element
 * carrying it, and {@code th:if} is evaluated first — so an expression inside a block that needs a
 * populated switch, a filled leg and a priced line to appear is never parsed by a test that renders
 * the empty case. One did not parse ({@code 'The two sides'' markets are '} — the doubled quote is
 * not an escape here), and the first thing that reached it was the running app, where a mid-render
 * failure truncates the response after the headers have gone out and Spring cannot swap in an error
 * page. The panel simply stopped, halfway down.
 *
 * <p>So this reads the files rather than rendering them: every {@code th:*} value, through the same
 * parser the engine uses, with no data and no context needed. It cannot tell whether an expression
 * is <em>right</em> — that is what the service and rendering tests are for — only that it is
 * well-formed, which is the half that fails invisibly.
 */
class TemplateExpressionsTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    /** Attributes whose value is a plain expression, in the syntax {@code parseExpression} takes. */
    private static final Set<String> EXPRESSION_ATTRS = Set.of(
            "th:text", "th:utext", "th:if", "th:unless", "th:value", "th:classappend",
            "th:styleappend", "th:style", "th:title", "th:href", "th:src", "th:action",
            "th:replace", "th:insert", "th:include", "th:placeholder", "th:alt", "th:id",
            "th:name", "th:selected", "th:checked", "th:disabled", "th:colspan", "th:object");

    /** {@code th:with} and {@code th:attr} are assignation sequences, not expressions. */
    private static final Set<String> ASSIGNATION_ATTRS = Set.of("th:with", "th:attr");

    private static final Pattern ATTR = Pattern.compile(
            "(th:[a-zA-Z-]+)\\s*=\\s*\"([^\"]*)\"", Pattern.DOTALL);

    @TestFactory
    Stream<DynamicTest> everyExpressionInEveryTemplateParses() throws IOException {
        // The Spring dialect, not the plain one: this app renders through
        // SpringTemplateEngine, so its expressions are SpringEL and the stock engine
        // would parse them under OGNL — a grammar that is not even on the classpath.
        SpringTemplateEngine engine = new SpringTemplateEngine();
        ExpressionContext context = new ExpressionContext(engine.getConfiguration());

        List<DynamicTest> tests = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                String body = Files.readString(file);
                String name = TEMPLATES.relativize(file).toString();
                Matcher m = ATTR.matcher(stripComments(body));
                while (m.find()) {
                    String attr = m.group(1);
                    String value = unescape(m.group(2));
                    if (value.isBlank()) continue;
                    tests.add(DynamicTest.dynamicTest(name + " " + attr,
                            () -> parse(context, attr, value, name)));
                }
            }
        }
        if (tests.isEmpty()) fail("no templates found under " + TEMPLATES.toAbsolutePath());
        return tests.stream();
    }

    private static void parse(ExpressionContext context, String attr, String value, String file) {
        try {
            if (EXPRESSION_ATTRS.contains(attr)) {
                StandardExpressions.getExpressionParser(context.getConfiguration())
                        .parseExpression(context, value);
            } else if (ASSIGNATION_ATTRS.contains(attr)) {
                AssignationUtils.parseAssignationSequence(context, value, false);
            } else if ("th:each".equals(attr)) {
                EachUtils.parseEach(context, value);
            }
            // Anything else (th:fragment, th:block, th:remove, data-th-*) has its own grammar and
            // is left alone rather than parsed under the wrong one, which would fail spuriously.
        } catch (Exception e) {
            fail(file + ": " + attr + "=\"" + value + "\" does not parse — " + e.getMessage(), e);
        }
    }

    /** HTML comments hold prose with quotes and apostrophes, and none of it is an expression. */
    private static String stripComments(String html) {
        return html.replaceAll("(?s)<!--.*?-->", "");
    }

    private static String unescape(String s) {
        return s.replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
    }
}
