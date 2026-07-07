package com.portfolio.web;

import com.portfolio.application.TrendAnalysisService;
import com.portfolio.application.TrendReport;
import com.portfolio.domain.trend.TrendConfig;
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
 * Trend-filter backtest (the "Trend filter" tab under Tools). One read endpoint builds the whole
 * report; every window, buffer, cost and rate is an optional query param so the user can sweep
 * parameters without stored config, mirroring {@link SwingController}. {@code bufferPcts} arrives
 * as percents (1,2,3 → 0.01…), {@code costs}/{@code headlineCost} as basis points per side,
 * {@code flatCash} as an annual percent.
 */
@Controller
public class TrendController {

    private static final Logger log = LoggerFactory.getLogger(TrendController.class);

    private final TrendAnalysisService trendService;

    private final ExecutorService backfillExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "trend-backfill");
        t.setDaemon(true);
        return t;
    });

    public TrendController(TrendAnalysisService trendService) {
        this.trendService = trendService;
    }

    @GetMapping("/trend")
    @ResponseBody
    public TrendReport trend(
            @RequestParam(name = "primary", required = false) String primary,
            @RequestParam(name = "extension", required = false) String extension,
            @RequestParam(name = "cash", required = false) String cash,
            @RequestParam(name = "flatCash", required = false) String flatCash,
            @RequestParam(name = "maLengths", required = false) String maLengths,
            @RequestParam(name = "monthlyMa", required = false) String monthlyMa,
            @RequestParam(name = "anchorEvery", required = false) Integer anchorEvery,
            @RequestParam(name = "anchorOffset", required = false) Integer anchorOffset,
            @RequestParam(name = "bufferPcts", required = false) String bufferPcts,
            @RequestParam(name = "bufferNs", required = false) String bufferNs,
            @RequestParam(name = "costs", required = false) String costs,
            @RequestParam(name = "headlineCost", required = false) String headlineCost,
            @RequestParam(name = "halfFraction", required = false) String halfFraction,
            @RequestParam(name = "signalSource", required = false) String signalSource,
            @RequestParam(name = "bootstrap", required = false) Integer bootstrap,
            @RequestParam(name = "seed", required = false) Long seed) {

        TrendConfig.Builder b = trendService.baseConfigBuilder();
        if (primary != null) b.primarySymbol(primary);
        if (extension != null) b.extensionSymbol(extension);
        if (cash != null) b.cashSymbol(cash);
        if (flatCash != null) b.flatCashRatePct(parseDouble(flatCash, "flatCash"));
        if (maLengths != null) b.maLengths(parseInts(maLengths, "maLengths"));
        if (monthlyMa != null) b.monthlyMaLengths(parseInts(monthlyMa, "monthlyMa"));
        if (anchorEvery != null) b.anchorEvery(anchorEvery);
        if (anchorOffset != null) b.anchorOffset(anchorOffset);
        if (bufferPcts != null) b.bufferPcts(parsePctFractions(bufferPcts, "bufferPcts"));
        if (bufferNs != null) b.bufferNs(parseInts(bufferNs, "bufferNs"));
        if (costs != null) b.costsBps(parseDoubles(costs, "costs"));
        if (headlineCost != null) b.headlineCostBps(parseDouble(headlineCost, "headlineCost"));
        if (halfFraction != null) b.halfSwitchFraction(parseDouble(halfFraction, "halfFraction"));
        if (signalSource != null) b.signalSource(signalSource);
        if (bootstrap != null) b.bootstrapIterations(bootstrap);
        if (seed != null) b.randomSeed(seed);

        return trendService.report(b.build());
    }

    /**
     * Kick a background deep backfill for any configured symbol missing daily history. Returns
     * immediately; the UI re-runs {@code GET /trend} once prices land.
     */
    @PostMapping("/trend/backfill")
    @ResponseBody
    public Map<String, Object> backfill() {
        TrendConfig cfg = trendService.baseConfigBuilder().build();
        backfillExecutor.submit(() -> {
            try {
                List<String> fetched = trendService.ensureBackfilled(cfg);
                log.info("Trend backfill complete: {} symbols fetched", fetched.size());
            } catch (RuntimeException e) {
                log.warn("Trend backfill failed", e);
            }
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("started", true);
        return out;
    }

    // ---- param parsing ------------------------------------------------------

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

    private static double[] parseDoubles(String csv, String name) {
        String[] parts = csv.split("[,\\s]+");
        List<Double> vals = new ArrayList<>();
        for (String p : parts) {
            if (p.isBlank()) continue;
            vals.add(parseDouble(p, name));
        }
        return vals.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** Comma list of percents → fractions (e.g. "1,2,3" → [0.01,0.02,0.03]). */
    private static double[] parsePctFractions(String csv, String name) {
        double[] pcts = parseDoubles(csv.replace("%", ""), name);
        for (int i = 0; i < pcts.length; i++) pcts[i] = pcts[i] / 100.0;
        return pcts;
    }

    private static double parseDouble(String s, String name) {
        try {
            return Double.parseDouble(s.replace("%", "").trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " '" + s + "' is not a number.");
        }
    }
}
