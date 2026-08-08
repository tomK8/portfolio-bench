package com.portfolio.port;

/**
 * Outbound channel for watchlist alerts. Abstracted behind a port so the alert job stays
 * framework-free and testable with a fake, while the production wiring uses SMTP.
 */
public interface AlertNotifier {

    /**
     * Deliver one alert. Implementations must not throw — a delivery failure is logged and
     * swallowed so a flaky mail server never crashes the scheduler. Returns {@code true} if the
     * message was handed off successfully.
     */
    boolean notify(String subject, String body);

    /** Whether this notifier is configured and enabled. When false, the job skips delivery. */
    boolean isEnabled();
}
