package com.portfolio.web;

import com.portfolio.application.MomentumAnalysisService;
import com.portfolio.application.MomentumReport;
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
 * Cross-sectional momentum rotation backtest (the "Momentum" tab under Tools). {@code GET /momentum}
 * builds the whole report; every knob (N, lookback, skip, costs, rf, holdout, universe, seed …) is
 * an optional query param so parameters sweep without touching stored config. Universe edits persist
 * and trigger a background backfill, mirroring {@link SwingController}.
 *
 * <p>Percentage-style params ({@code cost}, {@code rf}, {@code crashDrawdown}) are entered as their
 * natural units (bps for cost, percent for rf/drawdown). List params accept comma-separated values
 * (e.g. {@code topNs=3,4,5}).
 */
@Controller
public class MomentumController {

    private static final Logger log = LoggerFactory.getLogger(MomentumController.class);

    private final MomentumAnalysisService momentumService;

    private final ExecutorService backfillExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "momentum-backfill");
        t.setDaemon(true);
        return t;
    });

    public MomentumController(MomentumAnalysisService momentumService) {
        this.momentumService = momentumService;
    }

    @GetMapping("/momentum")
    @ResponseBody
    public MomentumReport momentum(
            @RequestParam(name = "universe", required = false) String universe,
            @RequestParam(name = "topNs", required = false) String topNs,
            @RequestParam(name = "lookback", required = false) Integer lookback,
            @RequestParam(name = "skip", required = false) Integer skip,
            @RequestParam(name = "cost", required = false) String cost,
            @RequestParam(name = "rf", required = false) String rf,
            @RequestParam(name = "holdoutMonths", required = false) Integer holdoutMonths,
            @RequestParam(name = "bootstrap", required = false) Integer bootstrap,
            @RequestParam(name = "seed", required = false) Long seed,
            @RequestParam(name = "maxFfill", required = false) Integer maxFfill,
            @RequestParam(name = "minEligibleFactor", required = false) Integer minEligibleFactor,
            @RequestParam(name = "regimeSymbol", required = false) String regimeSymbol,
            @RequestParam(name = "regimeMaWindow", required = false) Integer regimeMaWindow,
            @RequestParam(name = "crashDrawdown", required = false) String crashDrawdown,
            @RequestParam(name = "crashWindowMonths", required = false) Integer crashWindowMonths,
            @RequestParam(name = "benchmarks", required = false) String benchmarks) {

        MomentumConfig.Builder b = momentumService.baseConfigBuilder();
        if (universe != null) b.universe(splitSymbols(universe));
        if (topNs != null) b.topNs(parseInts(topNs, "topNs"));
        if (lookback != null) b.lookbackSessions(lookback);
        if (skip != null) b.skipSessions(skip);
        if (cost != null) b.costPerSideBps(parseDouble(cost.replace("bps", ""), "cost"));
        if (rf != null) b.rfAnnualPct(parseDouble(rf.replace("%", ""), "rf"));
        if (holdoutMonths != null) b.holdoutMonths(holdoutMonths);
        if (bootstrap != null) b.bootstrapIterations(bootstrap);
        if (seed != null) b.randomSeed(seed);
        if (maxFfill != null) b.maxFfillSessions(maxFfill);
        if (minEligibleFactor != null) b.minEligibleFactor(minEligibleFactor);
        if (regimeSymbol != null) b.regimeSymbol(regimeSymbol);
        if (regimeMaWindow != null) b.regimeMaWindow(regimeMaWindow);
        if (crashDrawdown != null) b.crashDrawdownPct(parseDouble(crashDrawdown.replace("%", ""), "crashDrawdown"));
        if (crashWindowMonths != null) b.crashWindowMonths(crashWindowMonths);
        if (benchmarks != null) b.benchmarkSymbols(splitSymbols(benchmarks));

        return momentumService.report(b.build());
    }

    /**
     * Update the persisted ranked universe, then backfill any newly added names in the background.
     * Returns the effective universe so the UI can re-render its input.
     */
    @PostMapping("/momentum/symbols")
    @ResponseBody
    public Map<String, Object> setSymbols(@RequestParam(name = "universe") String universe) {
        List<String> saved = momentumService.saveUniverse(splitSymbols(universe));
        backfillInBackground();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("universe", saved);
        return out;
    }

    /**
     * Kick a background deep backfill for any universe/benchmark symbol with missing or shallow
     * history. Returns immediately; the UI re-runs {@code GET /momentum} once prices land.
     */
    @PostMapping("/momentum/backfill")
    @ResponseBody
    public Map<String, Object> backfill() {
        backfillInBackground();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("started", true);
        return out;
    }

    private void backfillInBackground() {
        MomentumConfig cfg = momentumService.baseConfigBuilder().build();
        backfillExecutor.submit(() -> {
            try {
                List<String> fetched = momentumService.ensureBackfilled(cfg);
                log.info("Momentum backfill complete: {} symbols fetched", fetched.size());
            } catch (RuntimeException e) {
                log.warn("Momentum backfill failed", e);
            }
        });
    }

    // ---- param parsing ------------------------------------------------------

    private static List<String> splitSymbols(String csv) {
        List<String> out = new ArrayList<>();
        for (String part : csv.split("[,\\s]+")) {
            String t = part.trim().toUpperCase();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

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
