package com.portfolio.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tiny file-backed key/value store for scalar settings (last II SIPP cash balance,
 * RothIRA brought-forward balance, …). Each key maps to its own {@code <key>.txt}
 * file under the db directory. Failures are logged and treated as "not set".
 */
public class KeyValueStore {

    private static final Logger log = LoggerFactory.getLogger(KeyValueStore.class);

    private final Path dir;

    public KeyValueStore(Path dir) {
        this.dir = dir;
    }

    public BigDecimal getBigDecimal(String key, BigDecimal defaultValue) {
        Path file = dir.resolve(key + ".txt");
        if (!Files.exists(file)) return defaultValue;
        try {
            String saved = Files.readString(file).trim();
            return saved.isEmpty() ? defaultValue : new BigDecimal(saved);
        } catch (IOException | NumberFormatException e) {
            log.warn("Could not read setting {}", key, e);
            return defaultValue;
        }
    }

    public void putBigDecimal(String key, BigDecimal value) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(key + ".txt"), value.toPlainString());
        } catch (IOException e) {
            log.warn("Could not write setting {}", key, e);
        }
    }

    /**
     * A stored flag. Anything other than a literal {@code true}/{@code false} (a missing file, an
     * empty one, a half-written one) is "not set" and returns {@code defaultValue} — the same
     * degrade-rather-than-throw contract the other accessors keep, and the reason a caller can
     * treat the default as the shipped behaviour rather than as an error state.
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        Path file = dir.resolve(key + ".txt");
        if (!Files.exists(file)) return defaultValue;
        try {
            String saved = Files.readString(file).trim();
            if (saved.equalsIgnoreCase("true")) return true;
            if (saved.equalsIgnoreCase("false")) return false;
            return defaultValue;
        } catch (IOException e) {
            log.warn("Could not read setting {}", key, e);
            return defaultValue;
        }
    }

    public void putBoolean(String key, boolean value) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(key + ".txt"), Boolean.toString(value));
        } catch (IOException e) {
            log.warn("Could not write setting {}", key, e);
        }
    }

    /** Newline-separated string set; blanks dropped. Returns an empty set if the file is missing. */
    public Set<String> getStringSet(String key) {
        Path file = dir.resolve(key + ".txt");
        if (!Files.exists(file)) return Set.of();
        try {
            Set<String> out = new LinkedHashSet<>();
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) out.add(trimmed);
            }
            return out;
        } catch (IOException e) {
            log.warn("Could not read setting {}", key, e);
            return Set.of();
        }
    }

    public void putStringSet(String key, Collection<String> values) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(key + ".txt"), String.join("\n", values));
        } catch (IOException e) {
            log.warn("Could not write setting {}", key, e);
        }
    }

    /**
     * Newline-separated {@code key=value} lines. Insertion-ordered, blanks and lines with no
     * {@code =} dropped, first {@code =} wins so a value may contain one (a gilt id does not, but
     * the next map stored here might). Returns an empty map if the file is missing.
     */
    public Map<String, String> getStringMap(String key) {
        Path file = dir.resolve(key + ".txt");
        if (!Files.exists(file)) return Map.of();
        try {
            Map<String, String> out = new LinkedHashMap<>();
            for (String line : Files.readAllLines(file)) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if (!k.isEmpty() && !v.isEmpty()) out.put(k, v);
            }
            return out;
        } catch (IOException e) {
            log.warn("Could not read setting {}", key, e);
            return Map.of();
        }
    }

    public void putStringMap(String key, Map<String, String> values) {
        try {
            Files.createDirectories(dir);
            StringBuilder sb = new StringBuilder();
            values.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
            Files.writeString(dir.resolve(key + ".txt"), sb.toString());
        } catch (IOException e) {
            log.warn("Could not write setting {}", key, e);
        }
    }
}
