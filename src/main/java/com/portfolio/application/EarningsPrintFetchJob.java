package com.portfolio.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background warmer for the reported-print detail. Same shape as {@link OperatingLineFetchJob}:
 * re-entrance is gated so two triggers cannot hit the feeds in parallel, and a failure is swallowed
 * so the previous rows stay readable.
 *
 * <p><b>A plain timer is the right shape here because the work is self-limiting by content.</b>
 * {@link EarningsPrintService#refresh()} skips every print whose detail it already holds, so a run
 * during the quiet weeks of a quarter costs nothing at all, and one in results season costs a few
 * requests for the handful of names reporting. Nothing here needs to know when earnings season is —
 * the same argument that lets {@code earningsBackWarm} ride a six-hourly timer.
 *
 * <p>It must run <b>often enough to catch a consensus before it disappears</b>, which is the one
 * scheduling constraint: Yahoo rolls a company's estimates to the next quarter within hours of the
 * print, and what is not captured before then cannot be had afterwards at any price.
 */
public class EarningsPrintFetchJob {

    private static final Logger log = LoggerFactory.getLogger(EarningsPrintFetchJob.class);

    private final EarningsPrintService service;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public EarningsPrintFetchJob(EarningsPrintService service) {
        this.service = service;
    }

    public void run() {
        if (!running.compareAndSet(false, true)) {
            log.info("Earnings print refresh already in progress — skipping");
            return;
        }
        try {
            service.refresh();
        } catch (RuntimeException e) {
            log.warn("Earnings print refresh failed", e);
        } finally {
            running.set(false);
        }
    }

    public boolean isRunning() {
        return running.get();
    }
}
