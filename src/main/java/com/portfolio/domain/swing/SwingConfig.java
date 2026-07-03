package com.portfolio.domain.swing;

import java.util.List;

/**
 * All knobs for the swing-strategy validation statistics, with the spec's first-pass defaults.
 * Every threshold, window, horizon and the watchlist itself is configurable so the user can sweep
 * them later — the web layer overrides any subset via query params; the rest fall back to
 * {@link Builder}'s defaults.
 *
 * <p>Percentage-like thresholds are stored as <em>fractions</em> ({@code 0.10} = 10%), matching
 * the rest of the codebase ({@link com.portfolio.domain.PriceStats}, the JSON endpoints). The web
 * layer accepts percents and divides by 100 before building this.
 *
 * @param symbols            the full watchlist to report per-symbol
 * @param cluster            the "semi cluster" subset pooled together and used for correlation
 * @param years              history depth to load/analyse
 * @param volWindows         trailing-return windows for realized-vol reporting (§1), in sessions
 * @param sigmaWindow        window (sessions of log returns) for the trailing daily σ that anchors
 *                           the σ-based triggers — the "unit" of §1
 * @param horizons           forward-return horizons (§2/§3), in sessions
 * @param sigmaMultiples     σ-based down-day trigger multiples, e.g. {@code [1,2,3]} → −1σ/−2σ/−3σ
 * @param absDrops           absolute down-day trigger magnitudes as fractions, e.g. −5%/−8%/−10%
 * @param continuationDrop   the "big" next-day drop used by §4, e.g. {@code 0.03} for −3%
 * @param flushDays          flush look-back lengths (§5), e.g. {@code [2,3]} sessions
 * @param flushThresholds    flush cumulative-drop magnitudes as fractions, e.g. −10%/−15%/−20%
 * @param takeProfit         barrier take-profit as a fraction (also the +% target in §5)
 * @param stopLoss           barrier stop-loss magnitude as a fraction
 * @param timeStops          time-stop horizons swept in §6, in sessions
 * @param followSessions     window (sessions) for §5's days-to-target and touch-first scan
 * @param drawdownWindow     window (sessions) for §5's further-drawdown-to-low measure
 * @param barrierFlushDays   the flush look-back that seeds the §6 barrier simulation
 * @param barrierFlushDrop   the flush magnitude that seeds the §6 barrier simulation
 * @param flushCooldown      sessions to suppress a repeat flush event after one fires (dedup)
 * @param clusterDownDay     cluster mean-return threshold defining a "down day" for tail
 *                           correlation (§7), as a magnitude ({@code 0.02} = ≤ −2%)
 * @param corrWindows        rolling correlation windows (§7), in sessions
 * @param smallNFloor        cells with fewer observations than this are flagged small-N
 * @param episodeFloor       pooled cells with fewer distinct event <em>dates</em> (episodes) than
 *                           this are flagged — cluster names flush on shared dates, so an event
 *                           count of 20 can be only a handful of independent episodes
 * @param bootstrapIterations iterations for the §6 matched-random-bootstrap control
 * @param randomSeed         seed for the bootstrap RNG, so a re-run reproduces the same figures
 */
public record SwingConfig(
        List<String> symbols,
        List<String> cluster,
        int years,
        int[] volWindows,
        int sigmaWindow,
        int[] horizons,
        double[] sigmaMultiples,
        double[] absDrops,
        double continuationDrop,
        int[] flushDays,
        double[] flushThresholds,
        double takeProfit,
        double stopLoss,
        int[] timeStops,
        int followSessions,
        int drawdownWindow,
        int barrierFlushDays,
        double barrierFlushDrop,
        int flushCooldown,
        double clusterDownDay,
        int[] corrWindows,
        int smallNFloor,
        int episodeFloor,
        int bootstrapIterations,
        long randomSeed) {

    public static final List<String> DEFAULT_SYMBOLS = List.of(
            "MU", "AMAT", "KLAC", "LRCX", "ASML", "AVGO", "INTC", "MRVL",
            "SNDK", "ANET", "NVDA", "GOOG", "TSLA", "MSFT", "SEMI.L");

    public static final List<String> DEFAULT_CLUSTER = List.of(
            "MU", "AMAT", "KLAC", "LRCX", "ASML", "AVGO", "MRVL", "SNDK", "NVDA", "SEMI.L");

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder so the web layer can override an arbitrary subset and keep the rest default. */
    public static final class Builder {
        private List<String> symbols = DEFAULT_SYMBOLS;
        private List<String> cluster = DEFAULT_CLUSTER;
        private int years = 3;
        private int[] volWindows = {10, 30, 90};
        private int sigmaWindow = 30;
        private int[] horizons = {1, 3, 5, 10};
        private double[] sigmaMultiples = {1, 2, 3};
        private double[] absDrops = {0.05, 0.08, 0.10};
        private double continuationDrop = 0.03;
        private int[] flushDays = {2, 3};
        private double[] flushThresholds = {0.10, 0.15, 0.20};
        private double takeProfit = 0.10;
        private double stopLoss = 0.15;
        private int[] timeStops = {5, 10, 15, 20};
        private int followSessions = 30;
        private int drawdownWindow = 10;
        private int barrierFlushDays = 2;
        private double barrierFlushDrop = 0.10;
        private int flushCooldown = 2;
        private double clusterDownDay = 0.02;
        private int[] corrWindows = {30, 60};
        private int smallNFloor = 20;
        private int episodeFloor = 5;
        private int bootstrapIterations = 1000;
        private long randomSeed = 42L;

        public Builder symbols(List<String> v) { if (v != null && !v.isEmpty()) symbols = v; return this; }
        public Builder cluster(List<String> v) { if (v != null && !v.isEmpty()) cluster = v; return this; }
        public Builder years(int v) { if (v > 0) years = v; return this; }
        public Builder sigmaMultiples(double[] v) { if (v != null && v.length > 0) sigmaMultiples = v; return this; }
        public Builder absDrops(double[] v) { if (v != null && v.length > 0) absDrops = v; return this; }
        public Builder horizons(int[] v) { if (v != null && v.length > 0) horizons = v; return this; }
        public Builder flushDays(int[] v) { if (v != null && v.length > 0) flushDays = v; return this; }
        public Builder flushThresholds(double[] v) { if (v != null && v.length > 0) flushThresholds = v; return this; }
        public Builder takeProfit(double v) { if (v > 0) takeProfit = v; return this; }
        public Builder stopLoss(double v) { if (v > 0) stopLoss = v; return this; }
        public Builder timeStops(int[] v) { if (v != null && v.length > 0) timeStops = v; return this; }
        public Builder barrierFlushDays(int v) { if (v > 0) barrierFlushDays = v; return this; }
        public Builder barrierFlushDrop(double v) { if (v > 0) barrierFlushDrop = v; return this; }
        public Builder smallNFloor(int v) { if (v > 0) smallNFloor = v; return this; }
        public Builder episodeFloor(int v) { if (v > 0) episodeFloor = v; return this; }
        public Builder bootstrapIterations(int v) { if (v > 0) bootstrapIterations = v; return this; }
        public Builder randomSeed(long v) { randomSeed = v; return this; }

        public SwingConfig build() {
            return new SwingConfig(symbols, cluster, years, volWindows, sigmaWindow, horizons,
                    sigmaMultiples, absDrops, continuationDrop, flushDays, flushThresholds,
                    takeProfit, stopLoss, timeStops, followSessions, drawdownWindow,
                    barrierFlushDays, barrierFlushDrop, flushCooldown, clusterDownDay,
                    corrWindows, smallNFloor, episodeFloor, bootstrapIterations, randomSeed);
        }
    }
}
