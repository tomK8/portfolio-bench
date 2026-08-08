package com.portfolio.domain.range;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All knobs for the percentile-floor range-strategy backtest, with the pre-registered defaults.
 * The web layer overrides any subset via query params; the rest fall back to {@link Builder}'s
 * defaults — same pattern as {@code SwingConfig}/{@code TrendConfig}. Percentage-like values are
 * stored as <em>fractions</em> ({@code 0.10} = 10%); the web layer accepts percents.
 *
 * <p><b>Pre-registration:</b> the defaults below are the pre-registered parameters. The
 * sensitivity sweep ({@code sweepPercentiles} × {@code sweepTakeProfits}) is reported for
 * robustness only; no cell is ever selected because it scored best. Cohort membership is fixed:
 * Family A (exchanges / market infrastructure) and Family B (data / analytics / professional
 * publishing), featured name LSEG.L.
 *
 * @param familyA            exchanges / market-infrastructure cohort (Yahoo tickers)
 * @param familyB            data / analytics / professional-publishing cohort (Yahoo tickers)
 * @param featuredSymbol     the name given a dedicated annotated-chart section
 * @param entryPercentile    trailing-window percentile whose level arms the entry (0.10 = P10)
 * @param exitPercentile     trailing-window percentile for the variant exit column (0.75 = P75)
 * @param percentileWindow   trailing window, in sessions, the percentile band is computed over;
 *                           strictly <em>prior</em> sessions — the current close is excluded so a
 *                           crash day cannot drag its own floor down with it
 * @param aboveRunSessions   consecutive closes above the (moving) entry level required to arm an
 *                           entry — buys dips from above, not recoveries from below; also the
 *                           re-arm requirement after every exit (the anti-machine-gun rule)
 * @param takeProfit         take-profit as a fraction of the entry fill (OHLC-touch convention)
 * @param stopLoss           trading-mode hard stop as a fraction (accumulation mode has none)
 * @param rfAnnualPct        flat annual rate earned by undeployed cash, in percent
 * @param notionalGbp        fixed notional per entry, GBP-equivalent
 * @param commissionBps      commission + spread per side, basis points
 * @param stampBySymbol      purchase-only stamp duty by symbol, as fractions (UK names 0.005)
 * @param trailingMonths     the trailing regime window reported beside full history
 * @param dedupCalendarDays  pooled entries within this many calendar days of an episode's first
 *                           entry count as the same episode (≈ one trading week)
 * @param episodeFloor       pooled/family cells with fewer episodes than this are dimmed
 * @param bootstrapIterations Arm-C random-accumulation draws
 * @param randomSeed         RNG seed, logged in the report so a re-run reproduces the figures
 * @param sweepPercentiles   entry percentiles for the sensitivity grid (report, never select)
 * @param sweepTakeProfits   TP fractions for the sensitivity grid (report, never select)
 * @param maxDailyMove       |close-to-close| move above this fraction flags a data error
 */
public record RangeConfig(
        List<String> familyA,
        List<String> familyB,
        String featuredSymbol,
        double entryPercentile,
        double exitPercentile,
        int percentileWindow,
        int aboveRunSessions,
        double takeProfit,
        double stopLoss,
        double rfAnnualPct,
        double notionalGbp,
        double commissionBps,
        Map<String, Double> stampBySymbol,
        int trailingMonths,
        int dedupCalendarDays,
        int episodeFloor,
        int bootstrapIterations,
        long randomSeed,
        double[] sweepPercentiles,
        double[] sweepTakeProfits,
        double maxDailyMove) {

    /** Family A ∪ Family B in declaration order. */
    public List<String> symbols() {
        List<String> out = new java.util.ArrayList<>(familyA);
        out.addAll(familyB);
        return out;
    }

    /** Purchase-only stamp duty fraction for {@code symbol} (0 when unmapped). */
    public double stampFor(String symbol) {
        Double v = stampBySymbol.get(symbol);
        return v == null ? 0.0 : v;
    }

    /** The Arm-D mirrored entry percentile (P10 floor → P90 ceiling). */
    public double mirrorPercentile() {
        return 1.0 - entryPercentile;
    }

    public double commissionFraction() {
        return commissionBps / 10_000.0;
    }

    public double rfAnnual() {
        return rfAnnualPct / 100.0;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private List<String> familyA = List.of("LSEG.L", "DB1.DE", "ICE", "CME", "NDAQ");
        private List<String> familyB = List.of("MSCI", "SPGI", "MCO", "FDS", "REL.L", "WKL.AS", "TRI");
        private String featuredSymbol = "LSEG.L";
        private double entryPercentile = 0.10;
        private double exitPercentile = 0.75;
        private int percentileWindow = 252;
        private int aboveRunSessions = 10;
        private double takeProfit = 0.10;
        private double stopLoss = 0.15;
        private double rfAnnualPct = 4.0;
        private double notionalGbp = 10_000;
        private double commissionBps = 10.0;
        private Map<String, Double> stampBySymbol = defaultStamp();
        private int trailingMonths = 24;
        private int dedupCalendarDays = 7;
        private int episodeFloor = 5;
        private int bootstrapIterations = 1000;
        private long randomSeed = 42L;
        private double[] sweepPercentiles = {0.05, 0.10, 0.15};
        private double[] sweepTakeProfits = {0.08, 0.10, 0.12};
        private double maxDailyMove = 0.40;

        private static Map<String, Double> defaultStamp() {
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("LSEG.L", 0.005);
            m.put("REL.L", 0.005);
            return m;
        }

        public Builder familyA(List<String> v) { familyA = List.copyOf(v); return this; }
        public Builder familyB(List<String> v) { familyB = List.copyOf(v); return this; }
        public Builder featuredSymbol(String v) { featuredSymbol = v; return this; }
        public Builder entryPercentile(double v) { entryPercentile = v; return this; }
        public Builder exitPercentile(double v) { exitPercentile = v; return this; }
        public Builder percentileWindow(int v) { percentileWindow = v; return this; }
        public Builder aboveRunSessions(int v) { aboveRunSessions = v; return this; }
        public Builder takeProfit(double v) { takeProfit = v; return this; }
        public Builder stopLoss(double v) { stopLoss = v; return this; }
        public Builder rfAnnualPct(double v) { rfAnnualPct = v; return this; }
        public Builder notionalGbp(double v) { notionalGbp = v; return this; }
        public Builder commissionBps(double v) { commissionBps = v; return this; }
        public Builder stampBySymbol(Map<String, Double> v) { stampBySymbol = Map.copyOf(v); return this; }
        public Builder trailingMonths(int v) { trailingMonths = v; return this; }
        public Builder dedupCalendarDays(int v) { dedupCalendarDays = v; return this; }
        public Builder episodeFloor(int v) { episodeFloor = v; return this; }
        public Builder bootstrapIterations(int v) { bootstrapIterations = v; return this; }
        public Builder randomSeed(long v) { randomSeed = v; return this; }
        public Builder sweepPercentiles(double[] v) { sweepPercentiles = v.clone(); return this; }
        public Builder sweepTakeProfits(double[] v) { sweepTakeProfits = v.clone(); return this; }
        public Builder maxDailyMove(double v) { maxDailyMove = v; return this; }

        public RangeConfig build() {
            return new RangeConfig(familyA, familyB, featuredSymbol,
                    entryPercentile, exitPercentile, percentileWindow, aboveRunSessions,
                    takeProfit, stopLoss, rfAnnualPct, notionalGbp, commissionBps, stampBySymbol,
                    trailingMonths, dedupCalendarDays, episodeFloor,
                    bootstrapIterations, randomSeed, sweepPercentiles, sweepTakeProfits,
                    maxDailyMove);
        }
    }
}
