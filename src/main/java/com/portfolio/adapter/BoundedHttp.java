package com.portfolio.adapter;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One blocking GET that a sleeping laptop cannot park forever. Every adapter here sends through
 * this rather than calling {@link HttpClient#send} directly.
 *
 * <p>{@code send} waits on an internal future with no deadline the caller owns: the
 * {@linkplain HttpRequest#timeout(Duration) request timeout} is enforced by a timer that the
 * client's own selector thread services. On 2026-10-22 the laptop slept at 00:27 with a Yahoo
 * intraday fetch in flight. The connection died silently during sleep, that timer never fired,
 * and the scheduled thread was still parked inside {@code send} 35 hours later — its 20-second
 * request timeout long past. Because the intraday job runs on a {@code fixedDelay}, which only
 * re-arms once the previous run <em>returns</em>, one parked call left the Live tab showing
 * Wednesday's quotes on Friday lunchtime, and took the daily 22:00 cron's thread with it.
 *
 * <p>So the deadline is enforced on the calling thread instead, by a timed wait it performs
 * itself. That wait is driven by the monotonic clock, which stops while the machine is asleep
 * and resumes with it — so a call caught by a suspend returns within its own budget of waking
 * time, the job returns, and the {@code fixedDelay} re-arms without anyone restarting anything.
 *
 * <p>The request's own timeout still does the ordinary work; this is the backstop for when it
 * does not, which is why the budget is {@link #GRACE} longer than whatever the request asked for.
 * A healthy timeout therefore still surfaces as the {@link HttpTimeoutException} it always was,
 * and so does this one — callers already treat that as "the source is unreachable", which is
 * exactly what a connection that outlived its deadline is.
 */
public final class BoundedHttp {

    /**
     * Headroom over the request's own timeout. Long enough that the client's timer wins every
     * race it is capable of winning, so this bound only ever fires when that timer is gone.
     */
    static final Duration GRACE = Duration.ofSeconds(10);

    /** Budget for a request that sets no timeout of its own. Nothing here does; it is a floor. */
    static final Duration NO_TIMEOUT_BUDGET = Duration.ofSeconds(30);

    private BoundedHttp() {
    }

    /** The budget a given request gets: its own timeout plus {@link #GRACE}. */
    static Duration budgetFor(HttpRequest req) {
        return req.timeout().map(t -> t.plus(GRACE)).orElse(NO_TIMEOUT_BUDGET);
    }

    /**
     * Send {@code req} and read the body as a string, returning within {@link #budgetFor} of
     * waking time whatever the connection does.
     *
     * @throws HttpTimeoutException if the budget runs out — the in-flight exchange is cancelled
     * @throws IOException          as {@link HttpClient#send} would have thrown it
     */
    public static HttpResponse<String> send(HttpClient client, HttpRequest req)
            throws IOException, InterruptedException {
        return send(client, req, budgetFor(req));
    }

    /** As {@link #send(HttpClient, HttpRequest)}, with the budget stated — for the tests. */
    static HttpResponse<String> send(HttpClient client, HttpRequest req, Duration budget)
            throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<String>> pending =
                client.sendAsync(req, HttpResponse.BodyHandlers.ofString());
        try {
            return pending.get(budget.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new HttpTimeoutException("no response within " + budget + " for " + req.uri());
        } catch (InterruptedException e) {
            // The fetch jobs poll the interrupt flag between tickers to stop early; propagate it
            // rather than leaving the exchange running behind a thread that is shutting down.
            pending.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    /**
     * Re-throw what {@code send} would have thrown. {@code sendAsync} wraps the same failures in
     * an {@link ExecutionException}, and a caller distinguishing "the ticker is wrong" from
     * "Yahoo is unreachable" needs the original, not a wrapper around it.
     */
    private static IOException unwrap(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof IOException io) return io;
        if (cause instanceof RuntimeException re) throw re;
        if (cause instanceof Error err) throw err;
        return new IOException(cause == null ? e.toString() : cause.toString(), cause);
    }
}
