package com.portfolio.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate that repairs the app's data after the laptop has been shut for a day or two.
 *
 * <p>What is being pinned down is that the trigger is the background jobs' own silence rather than
 * the age of the prices — a closed market must not look like a fault — and that the repair runs
 * once however many requests arrive, finishes every step it can, and re-arms itself afterwards so
 * a single dormant spell cannot turn into a catch-up on every page load.
 */
class CatchUpServiceTest {

    private static final Duration DORMANT_AFTER = Duration.ofMinutes(20);

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final List<String> ran = new CopyOnWriteArrayList<>();

    private CatchUpService service(CatchUpService.Step... steps) {
        return new CatchUpService(List.of(steps), DORMANT_AFTER, clock::get);
    }

    private CatchUpService.Step step(String name) {
        return new CatchUpService.Step(name, () -> ran.add(name));
    }

    private void advance(Duration d) {
        clock.addAndGet(d.toMillis());
    }

    @Test
    void staysShutWhileThePassesAreStillRunning() {
        CatchUpService svc = service(step("daily"));

        advance(Duration.ofMinutes(19));
        assertFalse(svc.noteActivity(), "nineteen minutes is not dormant");
        assertTrue(ran.isEmpty());
    }

    /**
     * The point of heartbeating on the pass rather than on the prices. Hours can pass with the
     * market shut and nothing new to fetch; the jobs are still running, so there is nothing to
     * repair and a catch-up would just re-walk the whole universe for no reason.
     */
    @Test
    void aTickingSchedulerKeepsTheGateShutHoweverOldThePricesAre() {
        CatchUpService svc = service(step("daily"));

        for (int hour = 0; hour < 14; hour++) {
            advance(Duration.ofMinutes(60));
            svc.heartbeat();                 // the once-a-minute pass, still ticking
            assertFalse(svc.noteActivity(), "hour " + hour);
        }
        assertTrue(ran.isEmpty());
    }

    /**
     * Slow is not stuck. The scheduler heartbeats at both ends of a pass, so one crawling through
     * a Yahoo outage keeps marking its starts and keeps the gate shut — a catch-up would only walk
     * the same universe again. One parked in a dead socket marks its start once, and that single
     * mark is what falls silent.
     */
    @Test
    void aCrawlingPassKeepsTheGateShutButAParkedOneDoesNot() {
        CatchUpService slow = service(step("daily"));
        for (int pass = 0; pass < 5; pass++) {
            slow.heartbeat();                      // this attempt started
            advance(Duration.ofMinutes(15));       // and took a quarter of an hour
            assertFalse(slow.noteActivity(), "pass " + pass + " is slow, not stuck");
        }

        CatchUpService parked = service(step("daily"));
        parked.heartbeat();                        // the attempt that never came back
        advance(Duration.ofMinutes(21));
        assertTrue(parked.noteActivity());
    }

    @Test
    void repairsEverythingInOrderOnceDormant() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        CatchUpService svc = service(step("daily"), step("intraday"),
                new CatchUpService.Step("gilts", () -> {
                    ran.add("gilts");
                    done.countDown();
                }));

        advance(Duration.ofDays(2));
        assertTrue(svc.noteActivity(), "two days dormant should open the gate");
        assertTrue(done.await(5, TimeUnit.SECONDS), "catch-up did not finish");

        // Daily bars before the quotes that are judged against them: Day Δ reads a quote over the
        // close before its trading day, and repairing the quotes first would price every row
        // against a close from before the machine slept.
        assertEquals(List.of("daily", "intraday", "gilts"), ran);
    }

    @Test
    void concurrentRequestsCollapseIntoOneRun() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CatchUpService svc = service(new CatchUpService.Step("daily", () -> {
            ran.add("daily");
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        advance(Duration.ofDays(2));
        assertTrue(svc.noteActivity());
        assertTrue(started.await(5, TimeUnit.SECONDS));

        assertTrue(svc.catchingUp(), "the tab should be able to say a refresh is in flight");
        assertFalse(svc.noteActivity(), "a second request must not start a second catch-up");
        assertFalse(svc.noteActivity());

        release.countDown();
        assertEquals(List.of("daily"), ran);
    }

    @Test
    void aFailingStepDoesNotCostTheOthersTheirCatchUp() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        CatchUpService svc = service(
                new CatchUpService.Step("daily", () -> {
                    throw new IllegalStateException("Yahoo unreachable");
                }),
                step("intraday"),
                new CatchUpService.Step("gilts", () -> {
                    ran.add("gilts");
                    done.countDown();
                }));

        advance(Duration.ofDays(2));
        assertTrue(svc.noteActivity());
        assertTrue(done.await(5, TimeUnit.SECONDS));

        assertEquals(List.of("intraday", "gilts"), ran);
    }

    /**
     * A catch-up re-arms the clock itself, so the next request does not start another one. Without
     * this, a feed that is simply down would mean a fresh full refetch on every page load.
     */
    @Test
    void reArmsAfterwardsEvenWhenEveryStepFailed() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        CatchUpService svc = service(new CatchUpService.Step("daily", () -> {
            done.countDown();
            throw new IllegalStateException("Yahoo unreachable");
        }));

        advance(Duration.ofDays(2));
        assertTrue(svc.noteActivity());
        assertTrue(done.await(5, TimeUnit.SECONDS));

        // The worker sets running=false after the last step; wait for it rather than racing it.
        for (int i = 0; i < 50 && svc.catchingUp(); i++) Thread.sleep(20);
        assertFalse(svc.catchingUp());

        assertFalse(svc.noteActivity(), "the catch-up's own completion is a heartbeat");
        advance(Duration.ofMinutes(21));
        assertTrue(svc.noteActivity(), "and dormancy after it still opens the gate");
    }
}
