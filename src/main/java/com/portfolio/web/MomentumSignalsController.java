package com.portfolio.web;

import com.portfolio.application.MomentumSignalsReport;
import com.portfolio.application.MomentumSignalsService;
import com.portfolio.domain.momentum.MomentumConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The five-variant momentum signal comparison (the "Mom. signals" tab under Tools).
 * {@code GET /momentum-signals} builds the whole report on the pinned asset-class universe; the
 * base knobs (N, cost, rf, holdout, seed …) remain sweepable query params like the Momentum tab's,
 * but there is no persisted universe editing here — the run is defined by its pre-registration,
 * and the universe pin is part of it.
 */
@Controller
public class MomentumSignalsController {

    private static final Logger log = LoggerFactory.getLogger(MomentumSignalsController.class);

    private final MomentumSignalsService signalsService;

    private final ExecutorService backfillExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "momentum-signals-backfill");
        t.setDaemon(true);
        return t;
    });

    public MomentumSignalsController(MomentumSignalsService signalsService) {
        this.signalsService = signalsService;
    }

    @GetMapping("/momentum-signals")
    @ResponseBody
    public MomentumSignalsReport signals(
            @RequestParam(name = "topNs", required = false) String topNs,
            @RequestParam(name = "cost", required = false) String cost,
            @RequestParam(name = "rf", required = false) String rf,
            @RequestParam(name = "holdoutMonths", required = false) Integer holdoutMonths,
            @RequestParam(name = "bootstrap", required = false) Integer bootstrap,
            @RequestParam(name = "seed", required = false) Long seed,
            @RequestParam(name = "episodeN", required = false) Integer episodeN) {

        MomentumConfig.Builder b = signalsService.baseConfigBuilder();
        if (topNs != null) b.topNs(parseInts(topNs, "topNs"));
        if (cost != null) b.costPerSideBps(parseDouble(cost.replace("bps", ""), "cost"));
        if (rf != null) b.rfAnnualPct(parseDouble(rf.replace("%", ""), "rf"));
        if (holdoutMonths != null) b.holdoutMonths(holdoutMonths);
        if (bootstrap != null) b.bootstrapIterations(bootstrap);
        if (seed != null) b.randomSeed(seed);

        return signalsService.report(b.build(), episodeN);
    }

    /** Kick a background backfill for any cohort symbol with no coverage. Returns immediately. */
    @PostMapping("/momentum-signals/backfill")
    @ResponseBody
    public Map<String, Object> backfill() {
        MomentumConfig cfg = signalsService.baseConfigBuilder().build();
        backfillExecutor.submit(() -> {
            try {
                List<String> fetched = signalsService.ensureBackfilled(cfg);
                log.info("Momentum-signals backfill complete: {} symbols fetched", fetched.size());
            } catch (RuntimeException e) {
                log.warn("Momentum-signals backfill failed", e);
            }
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("started", true);
        return out;
    }

    // ---- param parsing (same conventions as MomentumController) --------------

    private static int[] parseInts(String csv, String name) {
        String[] parts = csv.split("[,\\s]+");
        List<Integer> vals = new ArrayList<>();
        for (String p : parts) {
            if (p.isBlank()) continue;
            try {
                vals.add(Integer.parseInt(p.trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " '" + p + "' is not an integer.");
            }
        }
        return vals.stream().mapToInt(Integer::intValue).toArray();
    }

    private static double parseDouble(String s, String name) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " '" + s + "' is not a number.");
        }
    }
}
