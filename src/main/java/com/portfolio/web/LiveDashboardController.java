package com.portfolio.web;

import com.portfolio.application.LivePricesService;
import com.portfolio.application.LivePricesService.Snapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Live-prices feed. {@code GET /live} returns the current snapshot as JSON (used for the
 * initial render); {@code GET /live/stream} holds the connection open and pushes a fresh
 * snapshot every {@link #TICK_SECONDS}s via Server-Sent Events. The underlying intraday
 * data refreshes once a minute via {@link com.portfolio.application.IntradayPriceFetchJob};
 * the SSE tick just pushes whatever's currently cached to all connected clients.
 *
 * <p>Subscribed emitters live in a single shared list; one scheduled task broadcasts to all
 * of them. Each emitter is timeout-free ({@code new SseEmitter(0L)}) so the browser keeps
 * the channel open across long idle windows; explicit completion / error / browser close
 * removes it from the list.
 */
@Controller
public class LiveDashboardController {

    private static final Logger log = LoggerFactory.getLogger(LiveDashboardController.class);
    private static final int TICK_SECONDS = 15;

    private final LivePricesService service;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public LiveDashboardController(LivePricesService service) {
        this.service = service;
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "live-prices-sse");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::broadcast, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
    }

    @GetMapping("/live")
    @ResponseBody
    public Snapshot live() {
        return service.snapshot();
    }

    @GetMapping(path = "/live/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter em = new SseEmitter(0L);
        em.onCompletion(() -> emitters.remove(em));
        em.onTimeout(() -> {
            emitters.remove(em);
            em.complete();
        });
        em.onError(e -> emitters.remove(em));
        emitters.add(em);
        try {
            em.send(SseEmitter.event().name("snapshot").data(service.snapshot()));
        } catch (Exception e) {
            emitters.remove(em);
            log.debug("Live SSE: initial send failed, dropping emitter", e);
        }
        return em;
    }

    private void broadcast() {
        if (emitters.isEmpty()) return;
        Snapshot snap;
        try {
            snap = service.snapshot();
        } catch (Exception e) {
            log.warn("Live snapshot failed", e);
            return;
        }
        for (SseEmitter em : emitters) {
            try {
                em.send(SseEmitter.event().name("snapshot").data(snap));
            } catch (Exception e) {
                emitters.remove(em);
                try { em.complete(); } catch (Exception ignored) {}
            }
        }
    }
}
