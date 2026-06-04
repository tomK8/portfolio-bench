package com.portfolio.web;

import com.portfolio.application.IntradayPriceFetchJob;
import com.portfolio.application.PriceFetchJob;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Thin Spring trigger for the framework-free price jobs. Both fire on startup (off the main
 * thread, so a first-run backfill doesn't delay the web UI). The daily job runs each evening
 * after the US market close; the intraday job runs every 5 minutes thereafter.
 */
@Component
public class PriceFetchScheduler {

    private final PriceFetchJob job;
    private final IntradayPriceFetchJob intradayJob;

    public PriceFetchScheduler(PriceFetchJob job, IntradayPriceFetchJob intradayJob) {
        this.job = job;
        this.intradayJob = intradayJob;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        startDaemon("price-fetch-startup", this::runQuietly);
        startDaemon("price-fetch-intraday-startup", this::intradayQuietly);
    }

    @Scheduled(cron = "0 0 22 * * *", zone = "Europe/London")
    public void daily() {
        runQuietly();
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    public void intraday() {
        intradayQuietly();
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
            System.err.println("Price fetch run failed — " + e.getMessage());
        }
    }

    private void intradayQuietly() {
        try {
            intradayJob.run();
        } catch (RuntimeException e) {
            System.err.println("Intraday price fetch run failed — " + e.getMessage());
        }
    }
}
