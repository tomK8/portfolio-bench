package com.portfolio.adapter;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bound that stops a dead connection parking a scheduled job for good.
 *
 * <p>The case that matters is a server which accepts the connection and then never answers, with
 * the request's own timeout unable to help — which is what a suspended laptop leaves behind, and
 * what left the intraday pass parked inside {@code HttpClient.send} for 35 hours on 2026-10-22.
 * The test reproduces it by giving the request a timeout far longer than the budget, so only the
 * caller-side deadline can end the call.
 */
class BoundedHttpTest {

    @Test
    void budgetIsTheRequestsOwnTimeoutPlusHeadroom() {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost/x"))
                .timeout(Duration.ofSeconds(20)).GET().build();

        assertEquals(Duration.ofSeconds(20).plus(BoundedHttp.GRACE), BoundedHttp.budgetFor(req));
    }

    /** Headroom, so the client's own timer wins every race it can — this is only the backstop. */
    @Test
    void budgetExceedsTheRequestTimeoutItBacks() {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost/x"))
                .timeout(Duration.ofSeconds(5)).GET().build();

        assertTrue(BoundedHttp.budgetFor(req).compareTo(Duration.ofSeconds(5)) > 0);
    }

    @Test
    void budgetFallsBackWhenTheRequestStatesNoTimeout() {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost/x")).GET().build();

        assertEquals(BoundedHttp.NO_TIMEOUT_BUDGET, BoundedHttp.budgetFor(req));
    }

    @Test
    void aServerThatNeverAnswersStillReleasesTheCaller() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            release.await(30, TimeUnit.SECONDS);     // the connection that sleep left behind
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        try {
            // A request timeout of an hour: if the call comes back at all, it is the budget here
            // that brought it back, not anything inside the client.
            HttpRequest req = HttpRequest.newBuilder(uri(server))
                    .timeout(Duration.ofHours(1)).GET().build();

            long start = System.nanoTime();
            assertThrows(HttpTimeoutException.class,
                    () -> BoundedHttp.send(HttpClient.newHttpClient(), req, Duration.ofMillis(300)));
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertTrue(took.compareTo(Duration.ofSeconds(10)) < 0, "took " + took);
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void anOrdinaryResponseIsUnaffected() throws Exception {
        HttpServer server = server(exchange -> {
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        try {
            HttpRequest req = HttpRequest.newBuilder(uri(server))
                    .timeout(Duration.ofSeconds(10)).GET().build();

            HttpResponse<String> resp = BoundedHttp.send(HttpClient.newHttpClient(), req);

            assertEquals(200, resp.statusCode());
            assertEquals("{\"ok\":true}", resp.body());
        } finally {
            server.stop(0);
        }
    }

    private interface Handler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws Exception;
    }

    private static HttpServer server(Handler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception e) {
                exchange.close();
            }
        });
        // Daemon threads: a handler still blocked when the test ends must not hold the JVM open.
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "test-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        return server;
    }

    private static URI uri(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/x");
    }
}
