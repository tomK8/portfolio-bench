package com.portfolio.application;

import com.portfolio.adapter.YahooPriceFetcher;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;
import com.portfolio.persistence.PriceHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * When the daily close job decides it owes a pass. Nothing here reaches the network — the
 * question is only whether the evening is covered, which is answered from one stored instant.
 */
class PriceFetchJobDueTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    @TempDir
    Path dir;

    private PriceFetchJob job(KeyValueStore kv) {
        JdbcConnectionFactory cf = new JdbcConnectionFactory(dir);
        return new PriceFetchJob(new CashTransactionRepository(cf, kv),
                new PriceHistoryRepository(cf), new YahooPriceFetcher(), new YahooTickerMap(), kv);
    }

    private static Instant london(String isoLocal) {
        return ZonedDateTime.of(java.time.LocalDateTime.parse(isoLocal), LONDON).toInstant();
    }

    @Test
    void neverRunMeansDue() {
        assertTrue(job(new KeyValueStore(dir)).isDue(london("2026-11-22T09:00:00")),
                "a fresh install owes its whole backfill");
    }

    @Test
    void aPassSinceTheEveningCoversIt() {
        var kv = new KeyValueStore(dir);
        kv.putInstant(PriceFetchJob.LAST_RUN_KEY, london("2026-11-22T22:04:00"));

        assertFalse(job(kv).isDue(london("2026-11-22T23:30:00")));
        assertFalse(job(kv).isDue(london("2026-11-23T21:00:00")), "still the same evening's closes");
        assertTrue(job(kv).isDue(london("2026-11-23T22:30:00")), "a new evening is owed");
    }

    /**
     * The 2026-11-22 incident in one assertion. The laptop was shut at 22:00 for four nights
     * running while the app stayed up, so a cron fired for none of them; the question "is the
     * most recent 22:00 still uncovered?" is answered yes the moment the lid opens.
     */
    @Test
    void anEveningSleptThroughIsOwedTheMomentTheMachineWakes() {
        var kv = new KeyValueStore(dir);
        kv.putInstant(PriceFetchJob.LAST_RUN_KEY, london("2026-11-18T13:24:00"));

        assertTrue(job(kv).isDue(london("2026-11-22T19:12:00")));
    }

    /**
     * A midday pass does not stand in for the evening one. Yahoo reports an in-progress session's
     * current price as its {@code close}, so a run at noon stores a snapshot, not a close — the
     * shape behind the 2026-08-20 MRNA +12.13%. Covering yesterday's evening is all it may claim.
     */
    @Test
    void aMiddayPassStillLeavesTonightsClosesOwed() {
        var kv = new KeyValueStore(dir);
        kv.putInstant(PriceFetchJob.LAST_RUN_KEY, london("2026-11-23T12:00:00"));

        assertFalse(job(kv).isDue(london("2026-11-23T13:00:00")), "yesterday's evening is covered");
        assertTrue(job(kv).isDue(london("2026-11-23T22:30:00")), "today's closes are not");
    }

    @Test
    void theClosingTimeIsTenAtNightLondonWhicheverSideOfTheDstShift() {
        // 16:00 New York is 21:00 London in both halves of the year, so 22:00 London is an hour
        // after the US close all year and needs no seasonal adjustment.
        assertEquals(london("2026-11-22T22:00:00"),
                PriceFetchJob.lastClosingTime(london("2026-11-23T09:00:00")));
        assertEquals(london("2026-07-14T22:00:00"),
                PriceFetchJob.lastClosingTime(london("2026-07-15T09:00:00")));
        // The boundary is strict: at exactly 22:00 the evening has arrived but not passed, so
        // the covered evening is still yesterday's. The gate opens a tick later, which on a
        // half-hourly poll is a distinction no run can observe — and it errs towards waiting,
        // never towards calling a session closed before it is.
        assertEquals(london("2026-11-22T22:00:00"),
                PriceFetchJob.lastClosingTime(london("2026-11-23T22:00:00")));
        assertEquals(london("2026-11-23T22:00:00"),
                PriceFetchJob.lastClosingTime(london("2026-11-23T22:00:01")));
    }

    /**
     * The trigger asks every half hour and the marker is only stamped when a pass finishes, so a
     * slow pass — eleven hours on 2026-11-18, against a Yahoo that was timing out — would have
     * the next tick start a second one on top of it. There is nothing the second could do that
     * the first is not already doing, and two of them break the politeness throttle.
     */
    @Test
    void aSecondPassSkipsWhileOneIsStillWalking() {
        var kv = new KeyValueStore(dir);
        JdbcConnectionFactory cf = new JdbcConnectionFactory(dir);
        var cashRepo = new CashTransactionRepository(cf, kv);
        cashRepo.saveAjBell(List.of(new CashTransaction("2026-01-02", Account.AJBELL,
                TransactionType.TRANSACTION, "AAPL", 1, -100, "GBP", 1.0, -100, null, null, "buy")));

        PriceFetchJob[] holder = new PriceFetchJob[1];
        AtomicInteger fetches = new AtomicInteger();
        AtomicInteger addedByInnerRun = new AtomicInteger(-1);
        // Re-enters on the first ticker, which is what a concurrent tick would do.
        var reentrant = new YahooPriceFetcher() {
            @Override
            public DailySeries fetchSeries(String ticker, java.time.LocalDate from, java.time.LocalDate to) {
                if (fetches.incrementAndGet() == 1) {
                    int before = fetches.get();
                    holder[0].run();
                    addedByInnerRun.set(fetches.get() - before);
                }
                return new DailySeries(List.of(), List.of());
            }
        };
        holder[0] = new PriceFetchJob(cashRepo, new PriceHistoryRepository(cf), reentrant,
                new YahooTickerMap(), kv);

        holder[0].run();

        assertEquals(0, addedByInnerRun.get(),
                "the re-entrant pass found the gate shut and fetched nothing");
        assertTrue(fetches.get() > 0, "the outer pass did walk the universe");
        assertFalse(holder[0].isRunning(), "the gate is released once the pass is done");
        assertNotNull(kv.getInstant(PriceFetchJob.LAST_RUN_KEY), "the outer pass still completed");
    }

    @Test
    void anInterruptedPassLeavesTheEveningOwed() {
        var kv = new KeyValueStore(dir);
        PriceFetchJob job = job(kv);
        Thread t = new Thread(() -> {
            Thread.currentThread().interrupt();
            job.run();
        });
        t.start();
        assertDoesNotThrow(() -> t.join(5000));

        assertNull(kv.getInstant(PriceFetchJob.LAST_RUN_KEY),
                "a half-finished pass leaves exactly the gap the marker exists to notice");
    }
}
