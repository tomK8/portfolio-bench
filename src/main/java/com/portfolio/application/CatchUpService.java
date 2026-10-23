package com.portfolio.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Notices that the app has been dormant and catches its data up — on demand, when you come back
 * to it, rather than on a timer.
 *
 * <p>This is a laptop tool and the laptop is shut more often than it is open. Close the lid on
 * Wednesday evening, open it on Friday, and the process is still there with every scheduled job
 * holding figures from the moment the machine suspended: two days of closes the 22:00 cron was
 * asleep for, and a Live tab quoting Wednesday. The triggers do come back on their own, but a
 * {@code fixedDelay} only promises to run <em>again</em> — it never backfills the ticks it missed,
 * and a cron does not fire for a day it slept through. Something has to ask for the repair.
 *
 * <p><b>The signal is the background machinery's own liveness, not the age of the prices.</b>
 * {@link #heartbeat()} is called by the scheduler at both ends of the once-a-minute intraday pass;
 * if neither end has been reached in {@link #DORMANT_AFTER} then whatever the clock says, this
 * process has not been running normally. Asking the question that
 * way is what stops a closed market looking like a fault: out of hours the prices are legitimately
 * hours old while the job ticks along fine, and the gate stays shut. It also catches the other
 * way this goes wrong, which has nothing to do with sleep — a pass that hangs and never returns
 * takes its {@code fixedDelay} with it, and no amount of waiting brings it back.
 *
 * <p><b>The elapsed time is wall-clock on purpose.</b> {@link System#nanoTime} is the usual right
 * answer for measuring an interval and is precisely the wrong one here: it stops while the machine
 * is suspended, which is the interval this class exists to notice. A clock that sleeps when the
 * laptop sleeps would report that no time had passed at all.
 *
 * <p><b>Cost on the request path is one volatile read and a subtraction.</b> {@link #noteActivity()}
 * runs from an interceptor on the way into every request and does nothing further unless the gate
 * is open; there is no poller, no watchdog thread and no timer. The catch-up itself then runs once
 * on its own daemon thread — concurrent requests collapse into the same run — so the page that
 * triggered it renders immediately and fills in as the fetches land.
 */
public class CatchUpService {

    private static final Logger log = LoggerFactory.getLogger(CatchUpService.class);

    /**
     * How long without a sign of life from the intraday pass means "this process has been dormant".
     * A healthy pass takes ~70s and then waits 60s, so the heartbeat lands about every minute; the
     * generous multiple is headroom for the pathological case, a pass crawling through a Yahoo
     * outage at a bounded 30s a ticker. That one is slow, not stuck, and a catch-up has nothing to
     * offer it but a second futile walk of the same universe.
     */
    static final Duration DORMANT_AFTER = Duration.ofMinutes(20);

    /** One thing to re-run, and what to call it when it fails. */
    record Step(String what, Runnable job) {
    }

    private final List<Step> steps;
    private final Duration dormantAfter;
    private final LongSupplier clock;

    private final AtomicBoolean running = new AtomicBoolean();
    private volatile long lastPassMillis;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "catch-up");
        t.setDaemon(true);
        return t;
    });

    /**
     * The steps are in the order the Live tab needs them.
     *
     * <p><b>Daily bars first, before the prices they are judged against.</b> Day Δ is a quote over
     * the close before that quote's trading day, so while {@code price_history} still ends on
     * Wednesday every row would read a three-day move as today's — the same shape as the MRNA
     * +12.13% recorded in {@code docs/live-tab.md}, and a wrong number that looks right is worse
     * than a stale one that admits it. Fetching the missed closes first means the anchor is sound
     * by the time fresh quotes land on top of it. Gilts come last: they are scraped hourly anyway
     * and nothing else is waiting on them.
     */
    public CatchUpService(PriceFetchJob dailyJob, IntradayPriceFetchJob intradayJob,
                          GiltPriceFetchJob giltJob) {
        this(List.of(new Step("daily bars", dailyJob::run),
                        new Step("intraday prices", intradayJob::run),
                        new Step("gilt prices", giltJob::run)),
                DORMANT_AFTER, System::currentTimeMillis);
    }

    CatchUpService(List<Step> steps, Duration dormantAfter, LongSupplier clock) {
        this.steps = List.copyOf(steps);
        this.dormantAfter = dormantAfter;
        this.clock = clock;
        this.lastPassMillis = clock.getAsLong();
    }

    /**
     * The scheduler's sign of life, called at both ends of every intraday pass.
     *
     * <p>Both ends, because the two together separate <em>slow</em> from <em>stuck</em>: a pass
     * that is merely crawling still marks the start of each attempt, while one parked inside a
     * dead socket marks its start once and is never heard from again. Neither end asks whether the
     * pass found anything — the question is whether this process is still running them, and a
     * catch-up runs these same jobs, so it has nothing to offer a feed that is simply down.
     */
    public void heartbeat() {
        lastPassMillis = clock.getAsLong();
    }

    /** True while a catch-up is in flight, so a panel can say its figures are still arriving. */
    public boolean catchingUp() {
        return running.get();
    }

    /** How long since the background jobs last completed a pass. */
    Duration dormantFor() {
        return Duration.ofMillis(Math.max(0, clock.getAsLong() - lastPassMillis));
    }

    /**
     * Called on the way into every request. Opens the gate only when the background jobs have been
     * quiet for {@link #DORMANT_AFTER}; otherwise returns having read one field.
     *
     * @return true if this call is the one that started a catch-up
     */
    public boolean noteActivity() {
        if (dormantFor().compareTo(dormantAfter) < 0) return false;
        if (!running.compareAndSet(false, true)) return false;   // one already in flight
        try {
            worker.execute(this::catchUp);
            return true;
        } catch (RejectedExecutionException e) {
            running.set(false);
            return false;
        }
    }

    /**
     * Re-run every step, in order.
     *
     * <p>No lock is shared with the scheduled passes. The two can overlap after a hang and the bar
     * writes are upserts, so the cost is a duplicated fetch; a lock would instead let a pass that
     * is <em>stuck</em> keep every catch-up out, which is the failure this class is here for.
     */
    private void catchUp() {
        log.info("Catch-up — no completed price pass for {}, refreshing", dormantFor());
        try {
            int failed = 0;
            for (Step step : steps) if (!run(step)) failed++;
            // Says which it was. A catch-up that reached none of its sources looks identical to a
            // successful one from the Live tab, which would just go on showing what it had.
            if (failed == 0) log.info("Catch-up complete — {} steps", steps.size());
            else log.warn("Catch-up finished with {} of {} steps failed", failed, steps.size());
        } finally {
            // Both of these in the finally: a catch-up that failed outright must still re-arm the
            // clock, or every request from here on would start another one.
            heartbeat();
            running.set(false);
        }
    }

    /** One source being unreachable must not cost the others their catch-up. */
    private boolean run(Step step) {
        try {
            step.job().run();
            return true;
        } catch (RuntimeException e) {
            log.warn("Catch-up step failed: {}", step.what(), e);
            return false;
        }
    }
}
