package com.portfolio.adapter;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * Maps internal instrument symbols to Yahoo Finance tickers. US listings need no entry — they map
 * to themselves.
 *
 * <p>Two layers, in precedence order:
 * <ol>
 *   <li><b>User overrides</b> — rows the dashboard writes to {@code ticker_mappings}, supplied
 *       here as a {@link Supplier} so this adapter needs no persistence dependency. They win over
 *       the committed file, so a mapping that turns out to be wrong (the trap is a live-but-wrong
 *       US ticker, not a dead one) can be corrected without a release.</li>
 *   <li><b>{@code /yahoo-tickers.properties}</b> — the committed baseline.</li>
 * </ol>
 *
 * <p>The overlay is read once and cached; {@link #reload()} re-reads it after a mutation. The
 * cached map is replaced wholesale rather than mutated, so readers on other threads always see a
 * consistent snapshot.
 */
public class YahooTickerMap {

    private final Properties props = new Properties();
    private final Supplier<Map<String, String>> overrideSource;
    private volatile Map<String, String> overrides = Map.of();

    /** Committed mappings only — the default for tests and for any DB-less context. */
    public YahooTickerMap() {
        this(Map::of);
    }

    public YahooTickerMap(Supplier<Map<String, String>> overrideSource) {
        this.overrideSource = overrideSource == null ? Map::of : overrideSource;
        try (InputStream in = getClass().getResourceAsStream("/yahoo-tickers.properties")) {
            if (in != null) props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        reload();
    }

    /** Re-read the user overrides. Called after the dashboard adds or removes a mapping. */
    public final void reload() {
        Map<String, String> loaded = overrideSource.get();
        Map<String, String> copy = new LinkedHashMap<>();
        if (loaded != null) {
            loaded.forEach((k, v) -> {
                if (k != null && v != null && !k.isBlank() && !v.isBlank()) {
                    copy.put(k.trim().toUpperCase(), v.trim());
                }
            });
        }
        overrides = Map.copyOf(copy);
    }

    /** Yahoo ticker for an internal symbol; identity for US listings without an override. */
    public String tickerFor(String internalSymbol) {
        String override = override(internalSymbol);
        if (override != null) return override;
        return props.getProperty(internalSymbol, internalSymbol);
    }

    /**
     * Whether the symbol has an explicit entry (user override or committed), as opposed to falling
     * through to the identity mapping. Absence is not by itself an error — US listings are
     * deliberately unmapped — but it is what separates "Yahoo has no data for this US ticker" from
     * "this non-US listing needs an exchange suffix here", so a symbol that yields no prices can be
     * reported with the right fix. Identity is also a silent trap on its own: an unmapped
     * {@code PHP} resolves to a delisted US mutual fund rather than to nothing at all.
     */
    public boolean hasMapping(String internalSymbol) {
        return override(internalSymbol) != null || props.getProperty(internalSymbol) != null;
    }

    /**
     * Whether a resolved Yahoo ticker can only have come from the identity fallback — nothing maps
     * to it. The fetch jobs iterate resolved tickers, by which point {@link #hasMapping} would be
     * asking about the wrong string (an {@code EQQQ.L} never appears on the left-hand side).
     */
    public boolean resolvedByIdentity(String yahooTicker) {
        return !overrides.containsValue(yahooTicker) && !props.containsValue(yahooTicker);
    }

    /** The committed baseline, for the dashboard's read-only mapping list. */
    public Map<String, String> builtInMappings() {
        Map<String, String> out = new LinkedHashMap<>();
        props.stringPropertyNames().stream().sorted()
                .forEach(k -> out.put(k, props.getProperty(k)));
        return out;
    }

    private String override(String internalSymbol) {
        if (internalSymbol == null) return null;
        String hit = overrides.get(internalSymbol);
        return hit != null ? hit : overrides.get(internalSymbol.toUpperCase());
    }
}
