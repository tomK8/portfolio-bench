package com.portfolio.application;

import com.portfolio.application.ConcentrationService.ConcentrationMetrics;
import com.portfolio.application.ConcentrationService.SymbolWeight;
import com.portfolio.persistence.KeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Target allocation vs actual + drift + rebalance suggestion.
 *
 * <p>Target weights are stored in the {@link KeyValueStore} under {@link #TARGETS_KEY} as
 * newline-separated {@code SYMBOL=PERCENT} entries (re-using the string-set primitive that
 * already drives {@code held_symbols}). Weights are fractional in flight, percentages on disk
 * so the file stays human-editable. Targets need not sum to 100 — over-/under-allocated books
 * are valid expressions of intent (e.g. "70% equity, 20% bonds, rest cash" is 90 by
 * construction). The drift formula treats the entered weights as the source of truth and the
 * invested base from {@link ConcentrationService} as the denominator.
 *
 * <p>The rebalance suggestion is a deliberately blunt tool: per symbol with a target,
 * compute the GBP gap; flag as "trim" / "add" when |gap| exceeds {@link #REBALANCE_THRESHOLD_GBP}
 * absolute or {@link #REBALANCE_THRESHOLD_PCT} relative to target. No assumption about
 * trading costs or tax — the user reads the list, decides what to act on. Symbols you hold
 * but don't have a target for surface as "untargeted" rows so they're visible but not flagged.
 */
public class TargetAllocationService {

    private static final Logger log = LoggerFactory.getLogger(TargetAllocationService.class);

    public static final String TARGETS_KEY = "allocation_targets";
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int WEIGHT_SCALE = 6;
    private static final int GBP_SCALE = 2;

    /** Absolute GBP drift below which we don't bother flagging a rebalance. */
    private static final BigDecimal REBALANCE_THRESHOLD_GBP = new BigDecimal("500");
    /** Relative drift below which a position is considered "on target". */
    private static final BigDecimal REBALANCE_THRESHOLD_PCT = new BigDecimal("0.05");

    private final ConcentrationService concentrationService;
    private final KeyValueStore settings;

    public TargetAllocationService(ConcentrationService concentrationService,
                                   KeyValueStore settings) {
        this.concentrationService = concentrationService;
        this.settings = settings;
    }

    public TargetReport report() {
        Map<String, BigDecimal> targets = loadTargets();
        ConcentrationMetrics m = concentrationService.metrics();
        BigDecimal investedTotal = m.snapshot().investedGbp();
        if (investedTotal == null) investedTotal = BigDecimal.ZERO;

        Map<String, BigDecimal> actualBySymbol = new LinkedHashMap<>();
        for (SymbolWeight sw : m.snapshot().bySymbol()) {
            actualBySymbol.put(sw.symbol(), sw.gbp());
        }

        List<DriftRow> rows = new ArrayList<>();
        BigDecimal targetSum = BigDecimal.ZERO;
        for (var e : targets.entrySet()) {
            String sym = e.getKey();
            BigDecimal targetWeight = e.getValue();
            targetSum = targetSum.add(targetWeight);
            BigDecimal actualGbp = actualBySymbol.getOrDefault(sym, BigDecimal.ZERO);
            BigDecimal actualWeight = investedTotal.signum() > 0
                    ? actualGbp.divide(investedTotal, WEIGHT_SCALE, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            BigDecimal targetGbp = investedTotal.multiply(targetWeight)
                    .setScale(GBP_SCALE, RoundingMode.HALF_UP);
            BigDecimal driftGbp = actualGbp.subtract(targetGbp);
            BigDecimal driftPct = targetGbp.signum() > 0
                    ? driftGbp.divide(targetGbp, WEIGHT_SCALE, RoundingMode.HALF_UP)
                    : null;
            String action = suggestAction(driftGbp, driftPct);
            rows.add(new DriftRow(sym, targetWeight, actualWeight, targetGbp,
                    actualGbp.setScale(GBP_SCALE, RoundingMode.HALF_UP),
                    driftGbp.setScale(GBP_SCALE, RoundingMode.HALF_UP),
                    driftPct, action, true));
        }
        // Untargeted holdings appended at the end so the user can see what they own but
        // haven't expressed a target for.
        for (var e : actualBySymbol.entrySet()) {
            if (targets.containsKey(e.getKey())) continue;
            if (e.getValue().signum() <= 0) continue;
            BigDecimal actualWeight = investedTotal.signum() > 0
                    ? e.getValue().divide(investedTotal, WEIGHT_SCALE, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            rows.add(new DriftRow(e.getKey(), null, actualWeight,
                    null, e.getValue().setScale(GBP_SCALE, RoundingMode.HALF_UP),
                    null, null, null, false));
        }

        return new TargetReport(targetSum, investedTotal.setScale(GBP_SCALE, RoundingMode.HALF_UP),
                rows);
    }

    public Map<String, BigDecimal> loadTargets() {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (String line : settings.getStringSet(TARGETS_KEY)) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String sym = line.substring(0, eq).trim().toUpperCase();
            String val = line.substring(eq + 1).trim().replace("%", "");
            if (sym.isEmpty() || val.isEmpty()) continue;
            try {
                BigDecimal pct = new BigDecimal(val);
                // Stored as %; convert to fraction.
                out.put(sym, pct.divide(HUNDRED, WEIGHT_SCALE, RoundingMode.HALF_UP));
            } catch (NumberFormatException e) {
                log.warn("Invalid target weight for '{}': '{}'", sym, val);
            }
        }
        return out;
    }

    /**
     * Persist targets as {@code SYMBOL=PERCENT} entries. {@code targets} is in fraction form;
     * we multiply by 100 on the way to disk to keep the file human-editable in percentage
     * units.
     */
    public void saveTargets(Map<String, BigDecimal> targets) {
        List<String> lines = new ArrayList<>();
        for (var e : targets.entrySet()) {
            BigDecimal pct = e.getValue().multiply(HUNDRED)
                    .setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
            // Use toPlainString so a 5e+1 doesn't leak out.
            lines.add(e.getKey().toUpperCase() + "=" + pct.toPlainString());
        }
        settings.putStringSet(TARGETS_KEY, lines);
    }

    private static String suggestAction(BigDecimal driftGbp, BigDecimal driftPct) {
        if (driftGbp == null) return null;
        boolean overGbp = driftGbp.abs().compareTo(REBALANCE_THRESHOLD_GBP) >= 0;
        boolean overPct = driftPct != null
                && driftPct.abs().compareTo(REBALANCE_THRESHOLD_PCT) >= 0;
        if (!overGbp || !overPct) return "hold";
        return driftGbp.signum() > 0 ? "trim" : "add";
    }

    // ---- DTOs ---------------------------------------------------------------

    /**
     * One row of the drift table. Targeted rows have all fields populated and {@code targeted=true};
     * untargeted rows (holdings without a saved target) have target* fields null and
     * {@code targeted=false} so the UI can render them differently.
     */
    public record DriftRow(String symbol,
                           BigDecimal targetWeight,
                           BigDecimal actualWeight,
                           BigDecimal targetGbp,
                           BigDecimal actualGbp,
                           BigDecimal driftGbp,
                           BigDecimal driftPct,
                           String suggestedAction,
                           boolean targeted) {
    }

    /**
     * Full report. {@code targetSum} is the user's intended weight total (often not 1.0 —
     * a cash reserve is implicit). {@code investedTotalGbp} is the denominator used for the
     * drift calc — invested only, cash excluded so the user's target weights compare against
     * the invested base, not total.
     */
    public record TargetReport(BigDecimal targetSum,
                               BigDecimal investedTotalGbp,
                               List<DriftRow> rows) {
    }
}
