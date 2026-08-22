package com.portfolio.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The file-backed settings store. Its whole contract is that a caller never has to handle a
 * failure: an absent, empty or corrupt file reads as "not set" and returns the caller's default.
 */
class KeyValueStoreTest {

    @TempDir
    Path dir;

    @Test
    void booleanRoundTrips() {
        KeyValueStore kv = new KeyValueStore(dir);
        kv.putBoolean("orders_use_drift", true);
        assertTrue(kv.getBoolean("orders_use_drift", false));
        kv.putBoolean("orders_use_drift", false);
        assertFalse(kv.getBoolean("orders_use_drift", true));
    }

    @Test
    void anUnsetBooleanIsTheCallersDefault() {
        KeyValueStore kv = new KeyValueStore(dir);
        assertFalse(kv.getBoolean("never_written", false));
        assertTrue(kv.getBoolean("never_written", true),
                "the default is the shipped behaviour, not a fixed false");
    }

    @Test
    void anUnreadableBooleanFallsBackRatherThanGuessing() throws Exception {
        // A half-written or hand-edited file must not silently become `true` — a settings store
        // that reads garbage as "on" would switch a model on by accident.
        KeyValueStore kv = new KeyValueStore(dir);
        for (String junk : new String[]{"", "  ", "yes", "1", "TRUE-ish"}) {
            Files.writeString(dir.resolve("flag.txt"), junk);
            assertFalse(kv.getBoolean("flag", false), "junk [" + junk + "] must not read as set");
        }
        // …but the two literals are accepted whatever their case, since that is what we write.
        Files.writeString(dir.resolve("flag.txt"), "TRUE");
        assertTrue(kv.getBoolean("flag", false));
        Files.writeString(dir.resolve("flag.txt"), "False\n");
        assertFalse(kv.getBoolean("flag", true));
    }

    @Test
    void theOtherAccessorsKeepTheirOwnContracts() {
        KeyValueStore kv = new KeyValueStore(dir);
        kv.putBigDecimal("cash", new BigDecimal("1234.56"));
        assertEquals(0, new BigDecimal("1234.56").compareTo(kv.getBigDecimal("cash", BigDecimal.ZERO)));
        kv.putStringSet("symbols", java.util.List.of("NVDA", "WMT"));
        assertEquals(java.util.Set.of("NVDA", "WMT"), kv.getStringSet("symbols"));
    }
}
