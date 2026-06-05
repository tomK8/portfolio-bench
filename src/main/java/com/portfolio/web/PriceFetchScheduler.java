package com.portfolio.web;

import com.portfolio.application.GiltPriceFetchJob;
import com.portfolio.application.ImportGiltPricesService;
import com.portfolio.application.IntradayPriceFetchJob;
import com.portfolio.application.PriceFetchJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Thin Spring trigger for the framework-free price jobs. All three fire on startup (off the main
 * thread, so a first-run backfill doesn't delay the web UI). The daily Yahoo job runs each
 * evening after the US market close; the intraday Yahoo job runs every 5 minutes thereafter;
 * the gilt scrape runs hourly because dividenddata itself doesn't update faster.
 */
@Component
public class PriceFetchScheduler {

    private static final Logger log = LoggerFactory.getLogger(PriceFetchScheduler.class);

    private final PriceFetchJob job;
    private final IntradayPriceFetchJob intradayJob;
    private final GiltPriceFetchJob giltJob;
    private final ImportGiltPricesService giltImportService;

    public PriceFetchScheduler(PriceFetchJob job, IntradayPriceFetchJob intradayJob,
                               GiltPriceFetchJob giltJob,
                               ImportGiltPricesService giltImportService) {
        this.job = job;
        this.intradayJob = intradayJob;
        this.giltJob = giltJob;
        this.giltImportService = giltImportService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        startDaemon("price-fetch-startup", this::runQuietly);
        startDaemon("price-fetch-intraday-startup", this::intradayQuietly);
        startDaemon("price-fetch-gilt-startup", this::giltQuietly);
        startDaemon("gilt-prices-import-startup", this::giltImportQuietly);
    }

    @Scheduled(cron = "0 0 22 * * *", zone = "Europe/London")
    public void daily() {
        runQuietly();
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    public void intraday() {
        intradayQuietly();
    }

    @Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 60 * 60 * 1000)
    public void gilt() {
        giltQuietly();
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
