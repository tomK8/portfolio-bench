package com.portfolio.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background refresher for the per-holding fundamentals snapshot. Wraps
 * {@link PortfolioFundamentalsService#refresh()} and gates re-entrance so overlapping
 * triggers (startup + cron + manual button within ~30s) don't hammer Yahoo in parallel —
 * the second caller just skips. Errors are swallowed: a failed refresh leaves the previous
 * DB rows intact so the dashboard keeps rendering.
 */
public class FundamentalsFetchJob {

    private static final Logger log = LoggerFactory.getLogger(FundamentalsFetchJob.class);

    private final PortfolioFundamentalsService service;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public FundamentalsFetchJob(PortfolioFundamentalsService service) {
        this.service = service;
    }

    public void run() {
        if (!running.compareAndSet(false, true)) {
            log.info("Fundamentals refresh already in progress — skipping");
            return;
        }
        try {
            service.refresh();
        } catch (RuntimeException e) {
            log.warn("Fundamentals refresh failed", e);
        } finally {
            running.set(false);
        }
    }

    public boolean isRunning() {
        return running.get();
    }
}
