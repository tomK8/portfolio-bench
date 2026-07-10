package com.portfolio.domain.range;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * The single trade-generation path for every variant, mode and control of the range strategy.
 *
 * <p><b>Entry state machine</b> (FLOOR direction): a signal is <em>armed</em> once the name has
 * closed strictly above the (moving) entry level for {@code aboveRun} consecutive sessions since
 * the last exit (or since the band first exists, before the first trade). The first armed session
 * whose close is at or below the level enters at that close. While unarmed, a close at/below the
 * level resets the run — so dips are bought from above, never recoveries from below, and after
 * any exit the full run must recur before the next entry (the anti-machine-gun rule). CEILING
 * mirrors every comparison (Arm D: ten closes below the ceiling arm; entry at/above it).
 *
 * <p><b>Exits</b> use the flush harness's OHLC-touch convention: TP touches when the session
 * high reaches {@code fill × (1+tp)} (fill at the barrier); the trading-mode stop touches when
 * the session low reaches {@code fill × (1−sl)}; both in one session is AMBIGUOUS (excluded from
 * standard returns; booked as a stop in the conservative column downstream). The trailing-
 * percentile exit variant touches when the high reaches that session's exit level (fill at the
 * level — which can be <em>below</em> the entry after a long hold, a legitimate losing exit).
 * Barriers are checked from the session after entry. A position still open at the last bar is
 * OPEN — censored, never dropped; its {@code trRet} is the end-of-data total-return mark so the
 * capital arms can value it, but outcome statistics must exclude it.
 *
 * <p><b>Returns</b>: {@code priceRet} is the raw barrier arithmetic on split-adjusted price;
 * {@code trRet} maps both fills into total-return space ({@code fill × adj/close} on the fill
 * bar), so dividends received during the hold accrue to the trade — the honest basis for
 * multi-year accumulation holds.
 */
public final class RangeBacktester {

    /** Tolerance so a round barrier (e.g. ×1.1 of a repeating-decimal fill) still registers. */
    static final double EPS = 1e-9;

    private RangeBacktester() {
    }

    public enum Direction { FLOOR, CEILING }

    public enum ExitType { TP, SL, PCTL, AMBIGUOUS, OPEN }

    /**
     * @param entryLevel  per-bar entry band (NaN until the window fills)
     * @param direction   FLOOR buys the low band; CEILING (Arm D) buys the high band
     * @param aboveRun    consecutive closes beyond the level required to arm
     * @param tp          take-profit fraction; NaN switches to the trailing-percentile exit
     * @param exitLevel   per-bar exit band for the percentile-exit variant (ignored when tp set)
     * @param stopLoss    hard-stop fraction; NaN = accumulation mode (no stop)
     * @param rankWindow  trailing window used only for the entry percentile-rank diagnostic
     */
    public record Params(double[] entryLevel, Direction direction, int aboveRun,
                         double tp, double[] exitLevel, double stopLoss, int rankWindow) {
    }

    /**
     * One simulated trade. {@code exitFill} is the barrier/level price (NaN for OPEN);
     * {@code trRet} is the total-return trade return — for OPEN it is the censored end-of-data
     * mark. {@code mae} is the worst intraday-low excursion from the fill (≤ 0);
     * {@code underwaterSessions} counts post-entry sessions closing below the fill.
     */
    public record Trade(int entryIdx, LocalDate entryDate, double fill, double level,
                        double pctlRank, ExitType exitType, int exitIdx, LocalDate exitDate,
                        double exitFill, double priceRet, double trRet, double mae,
                        int underwaterSessions, int sessionsHeld, long daysHeld) {

        public boolean open() {
            return exitType == ExitType.OPEN;
        }
    }

    public static List<Trade> run(RangeSeries s, Params p) {
        List<Trade> out = new ArrayList<>();
        int n = s.size();
        boolean armed = false;
        int run = 0;
        int i = 0;
        while (i < n) {
            double level = p.entryLevel[i];
            if (Double.isNaN(level) || s.close[i] <= 0) {
                i++;
                continue;
            }
            boolean beyond = p.direction == Direction.FLOOR
                    ? s.close[i] > level + EPS
                    : s.close[i] < level - EPS;
            boolean atBand = p.direction == Direction.FLOOR
                    ? s.close[i] <= level + EPS
                    : s.close[i] >= level - EPS;
            if (armed && atBand) {
                Trade t = simulate(s, i, level, p);
                out.add(t);
                armed = false;
                run = 0;
                i = t.exitIdx() + 1;   // non-overlapping; re-arm run restarts after the exit bar
                continue;
            }
            if (beyond) {
                run++;
                if (run >= p.aboveRun) armed = true;
            } else {
                run = 0;
            }
            i++;
        }
        return out;
    }

    private static Trade simulate(RangeSeries s, int e, double level, Params p) {
        int n = s.size();
        double fill = s.close[e];
        double entryTr = s.trBasis(e, fill);
        double tpLevel = Double.isNaN(p.tp) ? Double.NaN : fill * (1 + p.tp);
        double slLevel = Double.isNaN(p.stopLoss) ? Double.NaN : fill * (1 - p.stopLoss);
        double rank = s.percentileRankAt(e, p.rankWindow);

        double worstLow = fill;
        int underwater = 0;
        for (int j = e + 1; j < n; j++) {
            if (s.low[j] > 0) worstLow = Math.min(worstLow, s.low[j]);
            if (s.close[j] < fill) underwater++;

            double exitBarrier = Double.isNaN(p.tp) ? p.exitLevel[j] : tpLevel;
            boolean upTouch = !Double.isNaN(exitBarrier) && s.high[j] >= exitBarrier - EPS;
            boolean slTouch = !Double.isNaN(slLevel) && s.low[j] <= slLevel + EPS;
            if (upTouch && slTouch) {
                // Both barriers touched in one session: outcome unknowable from daily OHLC.
                // No resolved return — standard stats exclude it; the conservative column
                // books it at −sl downstream.
                return new Trade(e, s.dates[e], fill, level, rank, ExitType.AMBIGUOUS, j,
                        s.dates[j], Double.NaN, Double.NaN, Double.NaN,
                        worstLow / fill - 1.0, underwater, j - e,
                        ChronoUnit.DAYS.between(s.dates[e], s.dates[j]));
            }
            if (upTouch) {
                ExitType type = Double.isNaN(p.tp) ? ExitType.PCTL : ExitType.TP;
                return trade(s, e, fill, level, rank, type, j, exitBarrier,
                        entryTr, worstLow, underwater);
            }
            if (slTouch) {
                return trade(s, e, fill, level, rank, ExitType.SL, j, slLevel,
                        entryTr, worstLow, underwater);
            }
        }
        // Censored: data ends before an exit. trRet carries the end-of-data TR mark.
        int last = n - 1;
        double markTr = s.adjClose[last] / entryTr - 1.0;
        return new Trade(e, s.dates[e], fill, level, rank, ExitType.OPEN, last, s.dates[last],
                Double.NaN, s.close[last] / fill - 1.0, markTr,
                worstLow / fill - 1.0, underwater, last - e,
                ChronoUnit.DAYS.between(s.dates[e], s.dates[last]));
    }

    private static Trade trade(RangeSeries s, int e, double fill, double level, double rank,
                               ExitType type, int j, double exitFill, double entryTr,
                               double worstLow, int underwater) {
        double exitTr = s.trBasis(j, exitFill);
        return new Trade(e, s.dates[e], fill, level, rank, type, j, s.dates[j], exitFill,
                exitFill / fill - 1.0, exitTr / entryTr - 1.0,
                worstLow / fill - 1.0, underwater, j - e,
                ChronoUnit.DAYS.between(s.dates[e], s.dates[j]));
    }
}
