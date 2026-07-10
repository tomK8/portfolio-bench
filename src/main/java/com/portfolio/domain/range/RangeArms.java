package com.portfolio.domain.range;

import com.portfolio.domain.swing.Stats;
import com.portfolio.domain.range.RangeBacktester.Trade;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The capital-basis arms that decide the verdict, all through one accounting path.
 *
 * <ul>
 *   <li><b>Arm A</b> (strategy): fixed notional per entry, one position per name, idle cash at a
 *       flat rf (symmetric — a cash balance driven negative by purchase costs pays rf). Open
 *       positions at end of data are marked at the total-return end value with no exit cost.</li>
 *   <li><b>Arm B</b> (buy &amp; hold): the same notional bought at the close of Arm A's first
 *       signal, held to end of data. Purchase costs identical; no exit cost on the end mark.</li>
 *   <li><b>Arm C</b> (random accumulation): the same number of purchases at random session dates
 *       in the same window, held to end of data, seeded bootstrap over {@code iterations} draws.</li>
 *   <li><b>Arm D</b> (reversed signal) reuses the Arm-A path over the ceiling-entry trades.</li>
 * </ul>
 *
 * <p><b>Capital bases.</b> {@code returnOnAllocated} divides by each arm's own standing capital —
 * A/B commit one notional per name (A recycles it), C commits {@code purchases × notional} — so
 * allocated bases are <em>not</em> identical across arms; the report says so. {@code
 * returnOnDeployed} is trade P&amp;L per pound-year actually in the market (simple, not
 * compounded), which normalises recycling vs parallel deployment and is the basis the A-vs-C
 * bootstrap percentile uses.
 *
 * <p><b>FX.</b> {@link FxLookup} supplies units-of-currency-per-GBP on a date. Per-name (local
 * currency) runs use {@link FxLookup#IDENTITY} — all math is in return ratios, so the pence/EUR/
 * USD unit cancels. Pooled GBP runs convert each trade's growth by
 * {@code rate(entry)/rate(exit)}, folding real FX into pooled results (documented in the report).
 */
public final class RangeArms {

    private static final double DAYS_PER_YEAR = 365.25;

    private RangeArms() {
    }

    /** Units of the name's currency per 1 GBP on a date (ceil-filled upstream). */
    public interface FxLookup {
        double rate(LocalDate d);

        FxLookup IDENTITY = d -> 1.0;
    }

    /** One name's inputs to a capital arm: its series, its trades, its stamp rate, its FX. */
    public record NameInput(RangeSeries series, List<Trade> trades, double stamp, FxLookup fx) {
    }

    /**
     * One arm's outcome. {@code tradePnl} excludes rf; {@code deployedPoundYears} is
     * Σ notional × days-in-market / 365.25; {@code returnOnDeployed} = tradePnl per pound-year.
     */
    public record ArmOutcome(int purchases, int openPositions, double allocated,
                             double finalWealth, double tradePnl, double rfEarned,
                             double stampPaid, double commissionPaid,
                             double deployedPoundYears, long windowDays,
                             double returnOnAllocated, double returnOnAllocatedAnn,
                             double returnOnDeployed) {
    }

    /** Arm A (and Arm D over ceiling trades): walk the cash ledger through the trade list. */
    public static ArmOutcome strategyArm(NameInput in, double notional, double commFrac,
                                         double rf, double ambiguousRet) {
        List<Trade> trades = in.trades();
        if (trades.isEmpty()) return null;
        RangeSeries s = in.series();
        LocalDate start = trades.get(0).entryDate();
        LocalDate end = s.lastDate();

        double cash = notional;
        double rfEarned = 0;
        double stampPaid = 0;
        double commissionPaid = 0;
        double deployedPy = 0;
        double openMarks = 0;
        int open = 0;
        LocalDate at = start;

        for (Trade t : trades) {
            double inc = accrueAmount(cash, at, t.entryDate(), rf);
            rfEarned += inc;
            cash += inc;
            at = t.entryDate();
            double stamp = notional * in.stamp();
            double comm = notional * commFrac;
            cash -= notional + stamp + comm;
            stampPaid += stamp;
            commissionPaid += comm;
            deployedPy += notional * t.daysHeld() / DAYS_PER_YEAR;

            // Ambiguous sessions (trading mode only) book pessimistically at the stop.
            double ret = t.exitType() == RangeBacktester.ExitType.AMBIGUOUS ? ambiguousRet : t.trRet();
            double fxRatio = in.fx().rate(t.entryDate()) / in.fx().rate(t.exitDate());
            if (t.open()) {
                openMarks += notional * (1 + ret) * fxRatio;   // end mark, no exit cost
                open++;
            } else {
                inc = accrueAmount(cash, at, t.exitDate(), rf);
                rfEarned += inc;
                cash += inc;
                at = t.exitDate();
                double proceeds = notional * (1 + ret) * fxRatio;
                double exitComm = proceeds * commFrac;
                cash += proceeds - exitComm;
                commissionPaid += exitComm;
            }
        }
        double inc = accrueAmount(cash, at, end, rf);
        rfEarned += inc;
        cash += inc;

        double finalWealth = cash + openMarks;
        return outcome(trades.size(), open, notional, finalWealth, rfEarned,
                stampPaid, commissionPaid, deployedPy, start, end);
    }

    /** Arm B: one purchase at the close of Arm A's first signal, held (TR-marked) to end. */
    public static ArmOutcome buyHoldArm(NameInput in, double notional, double commFrac, double rf) {
        List<Trade> trades = in.trades();
        if (trades.isEmpty()) return null;
        RangeSeries s = in.series();
        Trade first = trades.get(0);
        int e = first.entryIdx();
        int last = s.size() - 1;
        LocalDate start = first.entryDate();
        LocalDate end = s.lastDate();

        double growth = s.adjCloseAt(last) / s.adjCloseAt(e);
        double fxRatio = in.fx().rate(start) / in.fx().rate(end);
        double stamp = notional * in.stamp();
        double comm = notional * commFrac;
        double cash = -(stamp + comm);
        double rfEarned = accrueAmount(cash, start, end, rf);
        cash += rfEarned;
        double finalWealth = notional * growth * fxRatio + cash;
        double deployedPy = notional * ChronoUnit.DAYS.between(start, end) / DAYS_PER_YEAR;
        return outcome(1, 0, notional, finalWealth, rfEarned, stamp, comm, deployedPy, start, end);
    }

    /** Sum per-name outcomes into a pooled outcome over the widest window. */
    public static ArmOutcome combine(List<ArmOutcome> parts) {
        List<ArmOutcome> present = new ArrayList<>();
        for (ArmOutcome p : parts) if (p != null) present.add(p);
        if (present.isEmpty()) return null;
        int purchases = 0, open = 0;
        double alloc = 0, fw = 0, rf = 0, stamp = 0, comm = 0, dep = 0;
        long windowDays = 0;
        for (ArmOutcome p : present) {
            purchases += p.purchases();
            open += p.openPositions();
            alloc += p.allocated();
            fw += p.finalWealth();
            rf += p.rfEarned();
            stamp += p.stampPaid();
            comm += p.commissionPaid();
            dep += p.deployedPoundYears();
            windowDays = Math.max(windowDays, p.windowDays());
        }
        return finish(purchases, open, alloc, fw, rf, stamp, comm, dep, windowDays);
    }

    // ---- Arm C bootstrap -----------------------------------------------------

    /**
     * Bootstrap distributions plus the actual arm's percentile rank within each (fraction of
     * draws below, half-weight for ties, ×100). {@code null} dist fields when no name has trades.
     */
    public record Bootstrap(int iterations, long seed,
                            Stats.Dist roAllocatedAnn, Stats.Dist roDeployed,
                            Double actualPctlAllocatedAnn, Double actualPctlDeployed) {
    }

    /**
     * Arm C: for each name, the same number of purchases as Arm A at random distinct sessions in
     * [first signal, end], held to end of data with full purchase costs and rf on undeployed
     * cash. One iteration = one draw per name, summed. Seeded and reproducible.
     */
    public static Bootstrap randomArm(List<NameInput> names, double notional, double commFrac,
                                      double rf, int iterations, long seed, ArmOutcome actual) {
        List<CSpec> specs = new ArrayList<>();
        long windowDays = 0;
        for (NameInput in : names) {
            if (in.trades().isEmpty()) continue;
            CSpec spec = CSpec.of(in, notional, commFrac, rf);
            specs.add(spec);
            windowDays = Math.max(windowDays, spec.windowDays);
        }
        if (specs.isEmpty()) return new Bootstrap(iterations, seed, null, null, null, null);

        Random rng = new Random(seed);
        double[] roAAnn = new double[iterations];
        double[] roDep = new double[iterations];
        for (int it = 0; it < iterations; it++) {
            double alloc = 0, fw = 0, rfIdle = 0, dep = 0;
            for (CSpec spec : specs) {
                spec.draw(rng);
                alloc += spec.drawAllocated;
                fw += spec.drawFinal;
                rfIdle += spec.drawRfIdle;
                dep += spec.drawDeployedPy;
            }
            roAAnn[it] = annualised(fw / alloc, windowDays);
            roDep[it] = dep > 0 ? (fw - alloc - rfIdle) / dep : Double.NaN;
        }
        return new Bootstrap(iterations, seed, Stats.dist(roAAnn), Stats.dist(roDep),
                actual == null ? null : pctlRank(roAAnn, actual.returnOnAllocatedAnn()),
                actual == null ? null : pctlRank(roDep, actual.returnOnDeployed()));
    }

    /** Precomputed per-name Arm-C machinery: hold-to-end growth per eligible session. */
    private static final class CSpec {
        int lo;                 // first eligible index (Arm A's first signal)
        int k;                  // purchases to draw
        double notional, cost, rf;
        double[] growth;        // GBP growth of a purchase at index i held to end
        double[] daysToEnd;
        double[] daysFromStart;
        long windowDays;
        // per-draw accumulators
        double drawAllocated, drawFinal, drawRfIdle, drawDeployedPy;
        private final Set<Integer> picked = new HashSet<>();

        static CSpec of(NameInput in, double notional, double commFrac, double rf) {
            CSpec c = new CSpec();
            RangeSeries s = in.series();
            int n = s.size();
            int last = n - 1;
            c.lo = in.trades().get(0).entryIdx();
            c.k = in.trades().size();
            c.notional = notional;
            c.cost = notional * (in.stamp() + commFrac);
            c.rf = rf;
            LocalDate start = s.dateAt(c.lo);
            LocalDate end = s.lastDate();
            c.windowDays = ChronoUnit.DAYS.between(start, end);
            double rateEnd = in.fx().rate(end);
            c.growth = new double[n];
            c.daysToEnd = new double[n];
            c.daysFromStart = new double[n];
            for (int i = c.lo; i < n; i++) {
                LocalDate d = s.dateAt(i);
                c.growth[i] = s.adjCloseAt(last) / s.adjCloseAt(i) * (in.fx().rate(d) / rateEnd);
                c.daysToEnd[i] = ChronoUnit.DAYS.between(d, end);
                c.daysFromStart[i] = ChronoUnit.DAYS.between(start, d);
            }
            return c;
        }

        void draw(Random rng) {
            int m = growth.length - lo;
            picked.clear();
            drawAllocated = k * notional;
            drawFinal = drawAllocated;
            drawRfIdle = 0;
            drawDeployedPy = 0;
            int want = Math.min(k, m);
            while (picked.size() < want) {
                int i = lo + rng.nextInt(m);
                if (!picked.add(i)) continue;
                double idle = notional * rf * daysFromStart[i] / DAYS_PER_YEAR;
                drawRfIdle += idle;
                drawFinal += idle
                        + notional * (growth[i] - 1)
                        - cost * (1 + rf * daysToEnd[i] / DAYS_PER_YEAR);
                drawDeployedPy += notional * daysToEnd[i] / DAYS_PER_YEAR;
            }
        }
    }

    // ---- helpers -------------------------------------------------------------

    private static ArmOutcome outcome(int purchases, int open, double allocated, double finalWealth,
                                      double rfEarned, double stamp, double comm, double deployedPy,
                                      LocalDate start, LocalDate end) {
        return finish(purchases, open, allocated, finalWealth, rfEarned, stamp, comm, deployedPy,
                ChronoUnit.DAYS.between(start, end));
    }

    private static ArmOutcome finish(int purchases, int open, double allocated, double finalWealth,
                                     double rfEarned, double stamp, double comm, double deployedPy,
                                     long windowDays) {
        double tradePnl = finalWealth - allocated - rfEarned;
        double roA = allocated > 0 ? (finalWealth - allocated) / allocated : Double.NaN;
        double roAAnn = allocated > 0 ? annualised(finalWealth / allocated, windowDays) : Double.NaN;
        double roD = deployedPy > 0 ? tradePnl / deployedPy : Double.NaN;
        return new ArmOutcome(purchases, open, allocated, finalWealth, tradePnl, rfEarned,
                stamp, comm, deployedPy, windowDays, roA, roAAnn, roD);
    }

    private static double annualised(double growthFactor, long windowDays) {
        if (windowDays <= 0 || growthFactor <= 0) return Double.NaN;
        return Math.pow(growthFactor, DAYS_PER_YEAR / windowDays) - 1.0;
    }

    /** Simple interest earned by {@code balance} (may be negative) over [from, to]. */
    private static double accrueAmount(double balance, LocalDate from, LocalDate to, double rf) {
        long days = ChronoUnit.DAYS.between(from, to);
        return days <= 0 ? 0 : balance * rf * days / DAYS_PER_YEAR;
    }

    /** Percentile rank of {@code actual} within {@code draws}: below + half of ties, ×100. */
    static double pctlRank(double[] draws, double actual) {
        if (Double.isNaN(actual)) return Double.NaN;
        int below = 0, ties = 0, n = 0;
        for (double d : draws) {
            if (Double.isNaN(d)) continue;
            n++;
            if (d < actual) below++;
            else if (d == actual) ties++;
        }
        return n == 0 ? Double.NaN : 100.0 * (below + 0.5 * ties) / n;
    }
}
