package com.portfolio.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background refresher for the EDGAR operating line. Same shape as
 * {@link FundamentalsFetchJob}: re-entrance is gated so overlapping triggers do not hit SEC in
 * parallel, and errors are swallowed so a failed run leaves the previous rows readable.
 *
 * <p>Runs well after the fundamentals job rather than beside it, because the reconciliation
 * needs each symbol's Yahoo revenue to already be cached.
 */
public class OperatingLineFetchJob {

    private static final Logger log = LoggerFactory.getLogger(OperatingLineFetchJob.class);

    private final OperatingLineService service;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public OperatingLineFetchJob(OperatingLineService service) {
        this.service = service;
    }

    public void run() {
        run(false);
    }

    /** Re-read SEC for every symbol, including rows the scheduled pass would leave alone. */
    public void runForced() {
        run(true);
    }

    private void run(boolean force) {
        if (!running.compareAndSet(false, true)) {
            log.info("Operating-line refresh already in progress — skipping");
            return;
        }
        try {
            service.refresh(force);
        } catch (RuntimeException e) {
            log.warn("Operating-line refresh failed", e);
        } finally {
            running.set(false);
        }
    }

    public boolean isRunning() {
        return running.get();
    }
}
