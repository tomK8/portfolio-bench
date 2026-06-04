package com.portfolio.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
