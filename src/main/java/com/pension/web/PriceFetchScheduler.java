package com.pension.web;

import com.pension.application.PriceFetchJob;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Thin Spring trigger for the framework-free {@link PriceFetchJob}. Fetches on startup
 * (off the main thread, so a first-run 10-year backfill doesn't delay the web UI) and
 * again each evening after the US market close.
 */
@Component
public class PriceFetchScheduler {

    private final PriceFetchJob job;

    public PriceFetchScheduler(PriceFetchJob job) {
        this.job = job;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        Thread t = new Thread(this::runQuietly, "price-fetch-startup");
        t.setDaemon(true);
        t.start();
    }

    @Scheduled(cron = "0 0 22 * * *", zone = "Europe/London")
    public void daily() {
        runQuietly();
    }

    private void runQuietly() {
        try {
            job.run();
        } catch (RuntimeException e) {
            System.err.println("Price fetch run failed — " + e.getMessage());
        }
    }
}
