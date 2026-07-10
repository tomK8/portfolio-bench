package com.portfolio.web;

import com.portfolio.application.RangeAnalysisService;
import com.portfolio.application.RangeReport;
import com.portfolio.domain.range.RangeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Percentile-floor range-strategy backtest (the "Range floor" tab under Tools). One read endpoint
 * builds the whole report; every knob is an optional query param, mirroring
 * {@link TrendController}. Percent-like params arrive as percents ({@code entryPct=10} → 0.10;
 * {@code tp=10} → 0.10); {@code commissionBps} as basis points per side; {@code rf} as an annual
 * percent. Overriding any pre-registered parameter flags the run exploratory in the report.
 */
@Controller
public class RangeController {

    private static final Logger log = LoggerFactory.getLogger(RangeController.class);

    private final RangeAnalysisService rangeService;

    private final ExecutorService backfillExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "range-backfill");
        t.setDaemon(true);
        return t;
    });

    public RangeController(RangeAnalysisService rangeService) {
        this.rangeService = rangeService;
    }

    @GetMapping("/range")
    @ResponseBody
    public RangeReport range(
            @RequestParam(name = "familyA", required = false) String familyA,
            @RequestParam(name = "familyB", required = false) String familyB,
            @RequestParam(name = "featured", required = false) String featured,
            @RequestParam(name = "entryPct", required = false) String entryPct,
            @RequestParam(name = "exitPct", required = false) String exitPct,
            @RequestParam(name = "window", required = false) Integer window,
            @RequestParam(name = "aboveRun", required = false) Integer aboveRun,
            @RequestParam(name = "tp", required = false) String tp,
            @RequestParam(name = "sl", required = false) String sl,
            @RequestParam(name = "rf", required = false) String rf,
            @RequestParam(name = "notional", required = false) Double notional,
            @RequestParam(name = "commissionBps", required = false) String commissionBps,
            @RequestParam(name = "trailingMonths", required = false) Integer trailingMonths,
            @RequestParam(name = "dedupDays", required = false) Integer dedupDays,
            @RequestParam(name = "episodeFloor", required = false) Integer episodeFloor,
            @RequestParam(name = "bootstrap", required = false) Integer bootstrap,
            @RequestParam(name = "seed", required = false) Long seed,
            @RequestParam(name = "sweepPcts", required = false) String sweepPcts,
            @RequestParam(name = "sweepTps", required = false) String sweepTps) {

        RangeConfig.Builder b = rangeService.baseConfigBuilder();
        if (familyA != null) b.familyA(parseSymbols(familyA));
        if (familyB != null) b.familyB(parseSymbols(familyB));
        if (featured != null && !featured.isBlank()) b.featuredSymbol(featured.trim());
        if (entryPct != null) b.entryPercentile(parsePct(entryPct, "entryPct"));
        if (exitPct != null) b.exitPercentile(parsePct(exitPct, "exitPct"));
        if (window != null) b.percentileWindow(window);
        if (aboveRun != null) b.aboveRunSessions(aboveRun);
        if (tp != null) b.takeProfit(parsePct(tp, "tp"));
        if (sl != null) b.stopLoss(parsePct(sl, "sl"));
        if (rf != null) b.rfAnnualPct(parseDouble(rf, "rf"));
        if (notional != null) b.notionalGbp(notional);
        if (commissionBps != null) b.commissionBps(parseDouble(commissionBps, "commissionBps"));
        if (trailingMonths != null) b.trailingMonths(trailingMonths);
        if (dedupDays != null) b.dedupCalendarDays(dedupDays);
        if (episodeFloor != null) b.episodeFloor(episodeFloor);
        if (bootstrap != null) b.bootstrapIterations(bootstrap);
        if (seed != null) b.randomSeed(seed);
        if (sweepPcts != null) b.sweepPercentiles(parsePcts(sweepPcts, "sweepPcts"));
        if (sweepTps != null) b.sweepTakeProfits(parsePcts(sweepTps, "sweepTps"));

        return rangeService.report(b.build());
    }

    /**
     * Kick a background fetch for any cohort symbol missing daily history. Returns immediately;
     * the UI re-runs {@code GET /range} once prices land.
     */
    @PostMapping("/range/backfill")
    @ResponseBody
    public Map<String, Object> backfill() {
        RangeConfig cfg = rangeService.baseConfigBuilder().build();
        backfillExecutor.submit(() -> {
            try {
                List<String> fetched = rangeService.ensureBackfilled(cfg);
                log.info("Range backfill complete: {} symbols fetched", fetched.size());
            } catch (RuntimeException e) {
                log.warn("Range backfill failed", e);
            }
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("started", true);
        return out;
    }

    // ---- param parsing --------------------------------------------------------

    private static List<String> parseSymbols(String csv) {
        List<String> out = new ArrayList<>();
        for (String s : csv.split("[,\\s]+")) {
            if (!s.isBlank()) out.add(s.trim().toUpperCase());
        }
        return out;
    }

    private static double[] parsePcts(String csv, String name) {
        return Arrays.stream(csv.split("[,\\s]+"))
                .filter(s -> !s.isBlank())
                .mapToDouble(s -> parsePct(s, name))
                .toArray();
    }

    /** Percent in, fraction out ("10" → 0.10). */
    private static double parsePct(String s, String name) {
        return parseDouble(s, name) / 100.0;
    }

    private static double parseDouble(String s, String name) {
        try {
            return Double.parseDouble(s.replace("%", "").trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " '" + s + "' is not a number.");
        }
    }
}
