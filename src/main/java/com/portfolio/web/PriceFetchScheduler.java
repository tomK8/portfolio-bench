package com.portfolio.web;

import com.portfolio.application.FundamentalsFetchJob;
import com.portfolio.application.GiltPriceFetchJob;
import com.portfolio.application.ImportGiltPricesService;
import com.portfolio.application.IntradayPriceFetchJob;
import com.portfolio.application.PriceFetchJob;
import com.portfolio.application.WatchlistAlertJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Thin Spring trigger for the framework-free price jobs. All three fire on startup (off the main
 * thread, so a first-run backfill doesn't delay the web UI). The daily Yahoo job runs each
 * evening after the US market close; the intraday Yahoo job runs every minute thereafter so
 * the Live tab feels live; the gilt scrape runs hourly because dividenddata itself doesn't
 * update faster.
 */
@Component
public class PriceFetchScheduler {

    private static final Logger log = LoggerFactory.getLogger(PriceFetchScheduler.class);

    private final PriceFetchJob job;
    private final IntradayPriceFetchJob intradayJob;
    private final GiltPriceFetchJob giltJob;
    private final ImportGiltPricesService giltImportService;
    private final FundamentalsFetchJob fundamentalsJob;
    private final WatchlistAlertJob watchlistAlertJob;

    public PriceFetchScheduler(PriceFetchJob job, IntradayPriceFetchJob intradayJob,
                               GiltPriceFetchJob giltJob,
                               ImportGiltPricesService giltImportService,
                               FundamentalsFetchJob fundamentalsJob,
                               WatchlistAlertJob watchlistAlertJob) {
        this.job = job;
        this.intradayJob = intradayJob;
        this.giltJob = giltJob;
        this.giltImportService = giltImportService;
        this.fundamentalsJob = fundamentalsJob;
        this.watchlistAlertJob = watchlistAlertJob;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        startDaemon("price-fetch-startup", this::runQuietly);
        startDaemon("price-fetch-intraday-startup", this::intradayQuietly);
        startDaemon("price-fetch-gilt-startup", this::giltQuietly);
        startDaemon("gilt-prices-import-startup", this::giltImportQuietly);
        startDaemon("fundamentals-refresh-startup", fundamentalsJob::run);
    }

    @Scheduled(cron = "0 0 22 * * *", zone = "Europe/London")
    public void daily() {
        runQuietly();
    }

    @Scheduled(fixedDelay = 60 * 1000, initialDelay = 60 * 1000)
    public void intraday() {
        intradayQuietly();
    }

    @Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 60 * 60 * 1000)
    public void gilt() {
        giltQuietly();
    }

    @Scheduled(fixedDelay = 4 * 60 * 60 * 1000, initialDelay = 4 * 60 * 60 * 1000)
    public void fundamentals() {
        fundamentalsJob.run();
    }

    /**
     * Evaluate the watchlist triggers every 5 minutes. Self-throttles to one email per day per
     * symbol per trigger, so this cadence just controls how quickly a fresh spike is caught, not
     * how often you're mailed. No-op unless SMTP is configured.
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 2 * 60 * 1000)
    public void watchlistAlerts() {
        try {
            watchlistAlertJob.run();
        } catch (RuntimeException e) {
            log.warn("Watchlist alert run failed", e);
        }
    }

    private static void startDaemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    private void runQuietly() {
        try {
            job.run();
        } catch (RuntimeException e) {
            log.warn("Price fetch run failed", e);
        }
    }

    private void intradayQuietly() {
        try {
            intradayJob.run();
        } catch (RuntimeException e) {
            log.warn("Intraday price fetch run failed", e);
        }
    }

    private void giltQuietly() {
        try {
            giltJob.run();
        } catch (RuntimeException e) {
            log.warn("Gilt price fetch run failed", e);
        }
    }

    private void giltImportQuietly() {
        try {
            giltImportService.importAll().forEach(r ->
                    log.info("Gilt prices import — {} {} {}",
                            r.status(), r.sourceFile() == null ? "" : r.sourceFile(),
                            r.detail() == null ? "" : r.detail()));
        } catch (RuntimeException e) {
            log.warn("Gilt prices import run failed", e);
        }
    }
}
