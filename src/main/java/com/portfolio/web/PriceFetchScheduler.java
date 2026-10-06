package com.portfolio.web;

import com.portfolio.application.CachedHistoricalFxRateProvider;
import com.portfolio.application.FundamentalsFetchJob;
import com.portfolio.application.OperatingLineFetchJob;
import com.portfolio.application.GiltPriceFetchJob;
import com.portfolio.application.BackupService;
import com.portfolio.application.ImportGiltPricesService;
import com.portfolio.application.IntradayPriceFetchJob;
import com.portfolio.application.PriceFetchJob;
import com.portfolio.application.ReminderJob;
import com.portfolio.application.WatchlistAlertJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Thin Spring trigger for the framework-free price jobs. All three fire on startup (off the main
 * thread, so a first-run backfill doesn't delay the web UI). The daily Yahoo job runs each
 * evening after the US market close; the intraday Yahoo job runs every minute thereafter so
 * the Live tab feels live; the gilt scrape runs hourly because the HL gilt page itself doesn't
 * update faster.
 */
@Component
public class PriceFetchScheduler {

    /** How stale the newest archive may get before another is written. */
    private static final int BACKUP_INTERVAL_DAYS = 30;

    private static final Logger log = LoggerFactory.getLogger(PriceFetchScheduler.class);

    private final PriceFetchJob job;
    private final IntradayPriceFetchJob intradayJob;
    private final GiltPriceFetchJob giltJob;
    private final ImportGiltPricesService giltImportService;
    private final FundamentalsFetchJob fundamentalsJob;
    private final OperatingLineFetchJob operatingLineJob;
    private final WatchlistAlertJob watchlistAlertJob;
    private final ReminderJob reminderJob;
    private final CachedHistoricalFxRateProvider historicalFx;
    private final BackupService backupService;

    public PriceFetchScheduler(PriceFetchJob job, IntradayPriceFetchJob intradayJob,
                               GiltPriceFetchJob giltJob,
                               ImportGiltPricesService giltImportService,
                               FundamentalsFetchJob fundamentalsJob,
                               OperatingLineFetchJob operatingLineJob,
                               WatchlistAlertJob watchlistAlertJob,
                               ReminderJob reminderJob,
                               CachedHistoricalFxRateProvider historicalFx,
                               BackupService backupService) {
        this.job = job;
        this.intradayJob = intradayJob;
        this.giltJob = giltJob;
        this.giltImportService = giltImportService;
        this.fundamentalsJob = fundamentalsJob;
        this.operatingLineJob = operatingLineJob;
        this.watchlistAlertJob = watchlistAlertJob;
        this.reminderJob = reminderJob;
        this.historicalFx = historicalFx;
        this.backupService = backupService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        startDaemon("price-fetch-startup", this::runQuietly);
        startDaemon("price-fetch-intraday-startup", this::intradayQuietly);
        startDaemon("price-fetch-gilt-startup", this::giltQuietly);
        startDaemon("gilt-prices-import-startup", this::giltImportQuietly);
        // One daemon, two jobs, in this order. The operating line is published only where
        // EDGAR's assembled revenue reconciles with the Yahoo revenue on the same row, so it
        // needs that row cached first — running them as separate daemons would race, and the
        // re-entrance guard would make the loser skip rather than wait. Rows fetched in the
        // last 20 hours are skipped inside the job, so a restart re-reads SEC for nothing.
        startDaemon("fundamentals-refresh-startup", () -> {
            fundamentalsJob.run();
            operatingLineJob.run();
        });
        // Warms the whole USD/EUR history in one call each, so a cold start during a Frankfurter
        // outage can still value non-GBP positions instead of silently reporting them as £0.
        startDaemon("fx-history-warm-startup", historicalFx::warm);
        startDaemon("backup-startup", this::backupIfDue);
    }

    /**
     * Monthly off-machine backup, checked every six hours rather than fired by a calendar cron.
     *
     * <p>A {@code cron} on the 1st assumes the machine is awake and the app running at that
     * instant. This is a laptop tool that is shut more often than not, so a date-triggered job
     * would skip whole months without saying so. Asking "is the newest archive older than a
     * month?" instead means the backup happens within hours of the laptop next being used,
     * whatever day that is, and re-running it is harmless.
     */
    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000, initialDelay = 6 * 60 * 60 * 1000)
    public void backup() {
        backupIfDue();
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
     * The operating line, daily. Filings land quarterly, so the only reason to look more often
     * than that is to catch one on the day it is filed; the job itself skips any row fetched in
     * the last 20 hours, so this cadence costs one SEC pass a day over ~40 US lines.
     */
    @Scheduled(fixedDelay = 24 * 60 * 60 * 1000, initialDelay = 30 * 60 * 1000)
    public void operatingLine() {
        operatingLineJob.run();
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

    /**
     * Send whatever reminders are due, checked every half hour rather than fired by a morning cron.
     *
     * <p>Same reasoning as the backup above, and it matters more here. A {@code cron} at 08:00
     * assumes the laptop is awake and the app running at 08:00; this one usually is not, and a
     * reminder that silently skips the day it was for is worse than no reminder at all. The job
     * de-duplicates by day internally, so this cadence buys latency — the reminder lands within half
     * an hour of the machine next being used — and costs nothing: a tick with nothing new to say
     * sends no mail.
     *
     * <p>No-op unless SMTP is configured, like the watchlist alerts.
     */
    @Scheduled(fixedDelay = 30 * 60 * 1000, initialDelay = 3 * 60 * 1000)
    public void reminders() {
        try {
            reminderJob.run();
        } catch (RuntimeException e) {
            log.warn("Reminder run failed", e);
        }
    }

    private static void startDaemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    private void backupIfDue() {
        try {
            LocalDate last = backupService.lastBackupDate();
            if (last != null && last.isAfter(LocalDate.now().minusDays(BACKUP_INTERVAL_DAYS))) {
                return;
            }
            backupService.run();
        } catch (RuntimeException e) {
            // Degrades like every other background job: a failed backup must not take the
            // scheduler down with it. It is logged at WARN because, unlike a missed price
            // fetch, nothing later repairs it.
            log.warn("Backup failed", e);
        }
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
