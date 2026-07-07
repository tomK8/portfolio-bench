package com.portfolio.domain.trend;

/**
 * All knobs for the trend-filter backtest, with the spec's first-pass defaults. Every threshold,
 * window, cost and rate is configurable — the web layer overrides any subset via query params;
 * the rest fall back to {@link Builder}'s defaults.
 *
 * <p>Percentage-like values are stored as <em>fractions</em> ({@code 0.02} = 2%) except the two
 * explicitly bps/percent-named knobs: {@code costsBps}/{@code headlineCostBps} are basis points
 * per side, and {@code flatCashRatePct} is an annual percent (it reads naturally as "2%"). The
 * web layer converts user-facing percents to fractions before building this.
 *
 * @param primarySymbol       the traded series (total return via {@code adj_close}), e.g. QQQ
 * @param extensionSymbol     price-only index spliced in before the primary's first bar to extend
 *                            history (e.g. ^NDX); blank/null disables the splice
 * @param cashSymbol          short-rate series for the cash leg (e.g. ^IRX, 13-week T-bill
 *                            discount yield in percent); blank/null → flat rate only
 * @param flatCashRatePct     annual cash rate (percent) used before the cash series starts or
 *                            when {@code cashSymbol} is blank
 * @param maLengths           daily-close SMA lengths (sessions) swept in the headline grid
 * @param monthlyMaLengths    month-count SMA lengths for the classic monthly-close cross-check
 * @param anchorEvery         sessions between month-anchored checks (~one trading month)
 * @param anchorOffset        which session within the anchor cycle the headline tables use;
 *                            the anchor sweep runs all {@code anchorEvery} offsets regardless
 * @param bufferPcts          buffered-daily band half-widths as fractions (exit below
 *                            MA×(1−x), re-enter above MA×(1+x))
 * @param bufferNs            buffered-daily consecutive-close counts (exit after N closes below,
 *                            re-enter after N above)
 * @param costsBps            per-side switch costs (basis points) swept in the cost table
 * @param headlineCostBps     the per-side cost the headline grid uses
 * @param halfSwitchFraction  invested weight while "out" in half-switch mode (0.5 = 50% invested)
 * @param signalSource        {@code "close"} (split-adjusted price, per the classic formulation)
 *                            or {@code "adjClose"} (total-return series) for the MA and its input
 * @param bootstrapIterations iterations for the matched random-exposure bootstrap control
 * @param randomSeed          seed for the bootstrap RNG, so a re-run reproduces the same figures
 */
public record TrendConfig(
        String primarySymbol,
        String extensionSymbol,
        String cashSymbol,
        double flatCashRatePct,
        int[] maLengths,
        int[] monthlyMaLengths,
        int anchorEvery,
        int anchorOffset,
        double[] bufferPcts,
        int[] bufferNs,
        double[] costsBps,
        double headlineCostBps,
        double halfSwitchFraction,
        String signalSource,
        int bootstrapIterations,
        long randomSeed) {

    public static final String DEFAULT_PRIMARY = "QQQ";
    public static final String DEFAULT_EXTENSION = "^NDX";
    public static final String DEFAULT_CASH = "^IRX";

    public boolean signalOnAdjClose() {
        return "adjClose".equalsIgnoreCase(signalSource);
    }

    public boolean spliceEnabled() {
        return extensionSymbol != null && !extensionSymbol.isBlank();
    }

    public boolean cashSeriesEnabled() {
        return cashSymbol != null && !cashSymbol.isBlank();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder so the web layer can override an arbitrary subset and keep the rest default. */
    public static final class Builder {
        private String primarySymbol = DEFAULT_PRIMARY;
        private String extensionSymbol = DEFAULT_EXTENSION;
        private String cashSymbol = DEFAULT_CASH;
        private double flatCashRatePct = 2.0;
        private int[] maLengths = {150, 175, 200, 225, 250};
        private int[] monthlyMaLengths = {8, 10, 12};
        private int anchorEvery = 21;
        private int anchorOffset = 0;
        private double[] bufferPcts = {0.01, 0.02, 0.03};
        private int[] bufferNs = {3, 5};
        private double[] costsBps = {0, 10, 30};
        private double headlineCostBps = 10;
        private double halfSwitchFraction = 0.5;
        private String signalSource = "close";
        private int bootstrapIterations = 1000;
        private long randomSeed = 42L;

        public Builder primarySymbol(String v) { if (v != null && !v.isBlank()) primarySymbol = v.trim().toUpperCase(); return this; }
        /** Blank (unlike null) explicitly disables the splice, so the web layer can pass "" through. */
        public Builder extensionSymbol(String v) { if (v != null) extensionSymbol = v.trim().toUpperCase(); return this; }
        /** Blank explicitly disables the cash series (flat rate only). */
        public Builder cashSymbol(String v) { if (v != null) cashSymbol = v.trim().toUpperCase(); return this; }
        public Builder flatCashRatePct(double v) { if (v >= 0) flatCashRatePct = v; return this; }
        public Builder maLengths(int[] v) { if (v != null && v.length > 0) maLengths = v; return this; }
        public Builder monthlyMaLengths(int[] v) { if (v != null && v.length > 0) monthlyMaLengths = v; return this; }
        public Builder anchorEvery(int v) { if (v > 0) anchorEvery = v; return this; }
        public Builder anchorOffset(int v) { if (v >= 0) anchorOffset = v; return this; }
        public Builder bufferPcts(double[] v) { if (v != null && v.length > 0) bufferPcts = v; return this; }
        public Builder bufferNs(int[] v) { if (v != null && v.length > 0) bufferNs = v; return this; }
        public Builder costsBps(double[] v) { if (v != null && v.length > 0) costsBps = v; return this; }
        public Builder headlineCostBps(double v) { if (v >= 0) headlineCostBps = v; return this; }
        public Builder halfSwitchFraction(double v) { if (v > 0 && v < 1) halfSwitchFraction = v; return this; }
        public Builder signalSource(String v) {
            if ("close".equalsIgnoreCase(v)) signalSource = "close";
            if ("adjClose".equalsIgnoreCase(v)) signalSource = "adjClose";
            return this;
        }
        public Builder bootstrapIterations(int v) { if (v > 0) bootstrapIterations = v; return this; }
        public Builder randomSeed(long v) { randomSeed = v; return this; }

        public TrendConfig build() {
            return new TrendConfig(primarySymbol, extensionSymbol, cashSymbol, flatCashRatePct,
                    maLengths, monthlyMaLengths, anchorEvery, anchorOffset % anchorEvery,
                    bufferPcts, bufferNs, costsBps, headlineCostBps, halfSwitchFraction,
                    signalSource, bootstrapIterations, randomSeed);
        }
    }
}
