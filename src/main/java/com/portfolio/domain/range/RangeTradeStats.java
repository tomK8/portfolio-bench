package com.portfolio.domain.range;

import com.portfolio.domain.swing.Stats;
import com.portfolio.domain.range.RangeBacktester.ExitType;
import com.portfolio.domain.range.RangeBacktester.Trade;

import java.util.ArrayList;
import java.util.List;

/**
 * Descriptive statistics over a set of trades, with the flush harness's five-way accounting
 * discipline: <b>events = TP + PCTL + SL + Ambiguous + Open</b> in every table. Standard return
 * statistics cover resolved trades only (TP/PCTL/SL); {@code meanTrRetConservative} additionally
 * books each ambiguous trade at the stop; Open trades are censored — counted, MAE'd, never
 * averaged into outcomes. MAE and time-underwater are reported over <em>all</em> trades
 * (including open ones — "hold until it bounces" is the declared plan, and what it required
 * historically includes the positions still waiting).
 */
public record RangeTradeStats(int events, int tp, int pctl, int sl, int ambiguous, int open,
                              Double meanTrRet, Double medianTrRet, Double meanPriceRet,
                              Double meanTrRetConservative,
                              Stats.Dist trRetDist, Stats.Dist maeDist,
                              Stats.Dist underwaterSessionsDist, Stats.Dist daysHeldDist) {

    public static RangeTradeStats of(List<Trade> trades, double stopLoss) {
        int tp = 0, pctl = 0, sl = 0, amb = 0, open = 0;
        List<Double> trRets = new ArrayList<>();
        List<Double> priceRets = new ArrayList<>();
        List<Double> cons = new ArrayList<>();
        List<Double> maes = new ArrayList<>();
        List<Double> underwater = new ArrayList<>();
        List<Double> daysHeld = new ArrayList<>();
        for (Trade t : trades) {
            maes.add(t.mae());
            underwater.add((double) t.underwaterSessions());
            switch (t.exitType()) {
                case TP -> tp++;
                case PCTL -> pctl++;
                case SL -> sl++;
                case AMBIGUOUS -> amb++;
                case OPEN -> open++;
            }
            if (t.exitType() == ExitType.AMBIGUOUS) {
                cons.add(-stopLoss);
            } else if (!t.open()) {
                trRets.add(t.trRet());
                priceRets.add(t.priceRet());
                cons.add(t.trRet());
                daysHeld.add((double) t.daysHeld());
            }
        }
        double[] trArr = toArray(trRets);
        return new RangeTradeStats(trades.size(), tp, pctl, sl, amb, open,
                finiteOrNull(Stats.mean(trArr)),
                finiteOrNull(Stats.median(trArr)),
                finiteOrNull(Stats.mean(toArray(priceRets))),
                finiteOrNull(Stats.mean(toArray(cons))),
                Stats.dist(trArr), Stats.dist(toArray(maes)),
                Stats.dist(toArray(underwater)), Stats.dist(toArray(daysHeld)));
    }

    private static double[] toArray(List<Double> xs) {
        double[] a = new double[xs.size()];
        for (int i = 0; i < a.length; i++) a[i] = xs.get(i);
        return a;
    }

    private static Double finiteOrNull(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
    }
}
