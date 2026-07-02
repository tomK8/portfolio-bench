package com.portfolio.web;

import com.portfolio.application.SwingAnalysisService;
import com.portfolio.application.SwingReport;
import com.portfolio.domain.swing.SwingConfig;
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
 * Swing-strategy validation statistics (the "Swing stats" tab under Tools). One read endpoint
 * builds the whole report; every threshold/window/horizon is an optional query param so the user
 * can sweep parameters without touching stored config. Symbol / cluster edits persist and trigger a
 * background backfill, mirroring {@link WatchlistController}.
 *
 * <p>Percentage-style params ({@code dropPcts}, {@code flushPcts}, {@code tp}, {@code sl},
 * {@code barrierPct}) are entered as percents and converted to the fractions the domain uses.
 * List params accept comma-separated values (e.g. {@code sigmas=1,2,3}).
 */
@Controller
public class SwingController {

    private static final Logger log = LoggerFactory.getLogger(SwingController.class);

    private final SwingAnalysisService swingService;

    private final ExecutorService backfillExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "swing-backfill");
        t.setDaemon(true);
        return t;
    });

    public SwingController(SwingAnalysisService swingService) {
        this.swingService = swingService;
    }

    @GetMapping("/swing")
    @ResponseBody
    public SwingReport swing(
            @RequestParam(name = "symbols", required = false) String symbols,
            @RequestParam(name = "cluster", required = false) String cluster,
            @RequestParam(name = "years", required = false) Integer years,
            @RequestParam(name = "sigmas", required = false) String sigmas,
            @RequestParam(name = "dropPcts", required = false) String dropPcts,
            @RequestParam(name = "flushPcts", required = false) String flushPcts,
            @RequestParam(name = "flushDays", required = false) String flushDays,
            @RequestParam(name = "horizons", required = false) String horizons,
            @RequestParam(name = "tp", required = false) String tp,
            @RequestParam(name = "sl", required = false) String sl,
            @RequestParam(name = "timeStops", required = false) String timeStops,
            @RequestParam(name = "barrierDays", required = false) Integer barrierDays,
            @RequestParam(name = "barrierPct", required = false) String barrierPct) {

        SwingConfig.Builder b = swingService.baseConfigBuilder();
        if (symbols != null) b.symbols(splitSymbols(symbols));
        if (cluster != null) b.cluster(splitSymbols(cluster));
        if (years != null) b.years(years);
        if (sigmas != null) b.sigmaMultiples(parseDoubles(sigmas, "sigmas"));
        if (dropPcts != null) b.absDrops(parsePctFractions(dropPcts, "dropPcts"));
        if (flushPcts != null) b.flushThresholds(parsePctFractions(flushPcts, "flushPcts"));
        if (flushDays != null) b.flushDays(parseInts(flushDays, "flushDays"));
        if (horizons != null) b.horizons(parseInts(horizons, "horizons"));
        if (tp != null) b.takeProfit(parsePct(tp, "tp"));
        if (sl != null) b.stopLoss(parsePct(sl, "sl"));
        if (timeStops != null) b.timeStops(parseInts(timeStops, "timeStops"));
        if (barrierDays != null) b.barrierFlushDays(barrierDays);
        if (barrierPct != null) b.barrierFlushDrop(parsePct(barrierPct, "barrierPct"));

        return swingService.report(b.build());
    }

    /**
     * Update the persisted symbol universe and/or cluster, then backfill any newly added names in
     * the background. Either param may be omitted to leave that list unchanged. Returns the
     * effective lists so the UI can re-render its inputs.
     */
    @PostMapping("/swing/symbols")
    @ResponseBody
    public Map<String, Object> setSymbols(
            @RequestParam(name = "symbols", required = false) String symbols,
            @RequestParam(name = "cluster", required = false) String cluster) {
        if (symbols != null) swingService.setSymbols(splitSymbols(symbols));
        if (cluster != null) swingService.setCluster(splitSymbols(cluster));
        backfillInBackground();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbols", swingService.symbols());
        out.put("cluster", swingService.cluster());
        return out;
    }

    /**
     * Kick a background backfill for any universe symbol missing daily history. Returns immediately;
     * the UI re-runs {@code GET /swing} once prices land (the nightly job also covers these).
     */
    @PostMapping("/swing/backfill")
    @ResponseBody
    public Map<String, Object> backfill() {
        backfillInBackground();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("started", true);
        return out;
    }

    private void backfillInBackground() {
        SwingConfig cfg = swingService.baseConfigBuilder().build();
        backfillExecutor.submit(() -> {
            try {
                List<String> fetched = swingService.ensureBackfilled(cfg);
                log.info("Swing backfill complete: {} symbols fetched", fetched.size());
            } catch (RuntimeException e) {
                log.warn("Swing backfill failed", e);
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

    private static double[] parseDoubles(String csv, String name) {
        String[] parts = csv.split("[,\\s]+");
        List<Double> vals = new ArrayList<>();
        for (String p : parts) {
            if (p.isBlank()) continue;
            vals.add(parseDouble(p, name));
        }
        return vals.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** Comma list of percents → fractions (e.g. "5,8,10" → [0.05,0.08,0.10]). */
    private static double[] parsePctFractions(String csv, String name) {
        double[] pcts = parseDoubles(csv.replace("%", ""), name);
        for (int i = 0; i < pcts.length; i++) pcts[i] = pcts[i] / 100.0;
        return pcts;
    }

    private static double parsePct(String s, String name) {
        return parseDouble(s.replace("%", ""), name) / 100.0;
    }

    private static double parseDouble(String s, String name) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " '" + s + "' is not a number.");
        }
    }
}
