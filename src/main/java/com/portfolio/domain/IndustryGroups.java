package com.portfolio.domain;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Collapses Yahoo's industry taxonomy into the handful of groups the sector drill-down actually
 * wants to read. Pure domain: a properties file in, a group name out, no network and no cache.
 *
 * <p>Yahoo files the Financials bucket as six industries — "Banks - Diversified", "Banks -
 * Regional", "Insurance - Diversified", "Credit Services", "Financial Data &amp; Stock Exchanges",
 * "Asset Management" — which splits four banks and three insurers into groups of one and two and
 * buries the thing the drill-down exists to show. Grouping them is a judgement, so it lives in
 * {@code sectors.properties} beside the other judgements rather than in code.
 *
 * <h2>Two layers, and the order between them is the whole design</h2>
 * <ol>
 *   <li>{@code industry.group.<slug>} maps a <b>Yahoo industry</b> to a group. This layer is
 *       self-regulating: buy another regional bank tomorrow and it lands in Banks with no edit,
 *       because the mapping is keyed on what the feed says rather than on a list of symbols.</li>
 *   <li>{@code industry.override.<SYMBOL>} maps a <b>single holding</b>, and wins. Reserved for
 *       where the feed is wrong about the business: Yahoo files L&amp;G under "Asset Management",
 *       which describes what LGEN does with the float rather than what it sells.</li>
 * </ol>
 *
 * <p><b>An unmapped industry falls through to its own raw name</b>, never to an "Other" bin. The
 * drill-down then shows a new industry as its own one-row group the day it appears, which is the
 * signal that a mapping is missing — an "Other" row would absorb it silently.
 *
 * <h2>Why the raw value stays on the row</h2>
 * A per-symbol override pins a holding to a group no matter what the feed later says, so it is the
 * one layer that can go quietly wrong. The caller keeps Yahoo's own string alongside the group and
 * the drill-down prints it in its own column, so a reclassification upstream is visible rather than
 * silently overridden. {@link Group#bySymbol()} is what lets the UI mark which rows are asserted
 * rather than derived.
 */
public class IndustryGroups {

    private static final String RESOURCE = "/sectors.properties";
    private static final String GROUP_PREFIX = "industry.group.";
    private static final String OVERRIDE_PREFIX = "industry.override.";

    private final Map<String, String> byIndustry;
    private final Map<String, String> bySymbol;

    public IndustryGroups() {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream(RESOURCE)) {
            if (in != null) props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> industries = new LinkedHashMap<>();
        Map<String, String> symbols = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            String value = props.getProperty(name, "").trim();
            if (value.isEmpty()) continue;
            if (name.startsWith(GROUP_PREFIX)) {
                String slug = slug(name.substring(GROUP_PREFIX.length()));
                if (!slug.isEmpty()) industries.put(slug, value);
            } else if (name.startsWith(OVERRIDE_PREFIX)) {
                String symbol = name.substring(OVERRIDE_PREFIX.length()).trim().toUpperCase();
                if (!symbol.isEmpty()) symbols.put(symbol, value);
            }
        }
        this.byIndustry = Map.copyOf(industries);
        this.bySymbol = Map.copyOf(symbols);
    }

    /** Visible for tests — bypasses the classpath resource. */
    IndustryGroups(Map<String, String> byIndustry, Map<String, String> bySymbol) {
        Map<String, String> slugged = new LinkedHashMap<>();
        byIndustry.forEach((k, v) -> slugged.put(slug(k), v));
        this.byIndustry = Map.copyOf(slugged);
        this.bySymbol = Map.copyOf(bySymbol);
    }

    /**
     * The group a holding belongs to, or {@code null} when neither layer names one — in which case
     * the caller keeps grouping by {@code yahooIndustry} exactly as it did before.
     */
    public Group groupFor(String symbol, String yahooIndustry) {
        String overridden = symbol == null ? null : bySymbol.get(symbol.trim().toUpperCase());
        if (overridden != null) return new Group(overridden, true);
        String mapped = byIndustry.get(slug(yahooIndustry));
        return mapped == null ? null : new Group(mapped, false);
    }

    /**
     * Normalises an industry to a properties-safe key: lowercase, every run of non-alphanumeric
     * characters collapsed to one hyphen. That is what lets one key match "Banks - Diversified",
     * "Banks—Diversified" and "Banks Diversified" — Yahoo has used the hyphen and the em dash for
     * the same industry, and a key that broke on the difference would be a mapping that silently
     * stopped applying.
     */
    static String slug(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    /**
     * @param name     the group to file the holding under
     * @param bySymbol true when it came from a per-symbol override, i.e. the group is asserted in
     *                 spite of the feed rather than derived from it — the case worth marking on
     *                 screen, since it is the one that survives an upstream reclassification
     */
    public record Group(String name, boolean bySymbol) {
    }
}
