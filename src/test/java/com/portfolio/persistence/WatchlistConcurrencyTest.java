package com.portfolio.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression guard for the "my inline watchlist edit sometimes doesn't save" class of bug.
 *
 * <p>The reported flow is a background price job holding SQLite's single writer lock while the user
 * edits a threshold — with a too-short (or zero) busy timeout the interactive write fails with
 * SQLITE_BUSY and, because the write path throws and the client collapsed it to a generic message,
 * the edit silently didn't persist. This test hammers the real on-disk SQLite through
 * {@link JdbcConnectionFactory} (WAL + busy_timeout) with many concurrent writers and readers and
 * asserts every write succeeds and persists, so dropping the busy timeout or reintroducing an
 * unshared long write lock regresses here rather than in the UI.
 */
class WatchlistConcurrencyTest {

    @Test
    void concurrentThresholdWrites_allPersist_noBusyFailures(@TempDir Path dir) throws Exception {
        JdbcConnectionFactory factory = new JdbcConnectionFactory(dir);
        KeyValueStore kv = new KeyValueStore(dir);
        WatchlistRepository repo = new WatchlistRepository(factory, kv);

        List<String> symbols = List.of("AAA", "BBB", "CCC", "DDD", "EEE");
        for (String s : symbols) repo.add(s, BigDecimal.ZERO, BigDecimal.ZERO);

        int writers = 10, readers = 3, iterations = 40;
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        List<Future<?>> tasks = new ArrayList<>();

        for (int w = 0; w < writers; w++) {
            final int seed = w;
            tasks.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        String sym = symbols.get((seed + i) % symbols.size());
                        BigDecimal below = new BigDecimal(100 + (i % 7));   // 100..106
                        repo.updateThresholds(sym, BigDecimal.ZERO, BigDecimal.ZERO, null, below);
                    }
                } catch (Throwable t) {
                    firstError.compareAndSet(null, t);
                }
            }));
        }
        // Concurrent readers, to prove reads never fail (or spuriously empty) under write load.
        for (int rdr = 0; rdr < readers; rdr++) {
            tasks.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations * 2; i++) {
                        assertFalse(repo.loadAll().isEmpty(), "read returned no rows under write load");
                    }
                } catch (Throwable t) {
                    firstError.compareAndSet(null, t);
                }
            }));
        }

        start.countDown();
        for (Future<?> f : tasks) f.get(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        if (firstError.get() != null) {
            fail("Concurrent watchlist access should not fail with SQLITE_BUSY", firstError.get());
        }

        // Every symbol ended with a valid persisted below-level (one of the written 100..106 values).
        List<WatchlistRepository.Entry> finalState = repo.loadAll();
        assertFalse(finalState.isEmpty(), "watchlist should still hold every symbol after the run");
        for (WatchlistRepository.Entry e : finalState) {
            assertTrue(e.priceBelow().compareTo(BigDecimal.valueOf(100)) >= 0
                            && e.priceBelow().compareTo(BigDecimal.valueOf(106)) <= 0,
                    e.symbol() + " should have persisted one of the written below-levels, got " + e.priceBelow());
        }
    }
}
