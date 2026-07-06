package com.portfolio.domain.swing;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * §E — does the dip-buy edge exist below the 200d MA at all, or is it strictly a buy-dips-in-uptrends
 * signal? Splits the §6 flush-event outcomes (non-overlapping, at the reference time stop
 * {@link SwingConfig#eventLogTimeStop()}) into above-MA and below-MA subsets by the entry bar's
 * 200d-MA state, and splits the all-days placebo pool by the same flag. Within each state, when the
 * pool is large enough ({@code ≥ 100}) and there are enough flush draws ({@code ≥ 5}), the matched
 * bootstrap is re-run within the state; otherwise the state is reported descriptively and flagged
 * {@code underpowered}. Entries whose 200d MA isn't defined yet are excluded from the split.
 */
public final class MaSplit {

    static final int MIN_POOL = 100;
    static final int MIN_DRAWS = 5;

    private MaSplit() {
    }

    public static Result split(List<SwingSeries> seriesList, LocalDate cutoff, SwingConfig cfg, FlushTrigger trig) {
        int timeStop = cfg.eventLogTimeStop();

        List<Flushes.Trade> flushAbove = new ArrayList<>(), flushBelow = new ArrayList<>();
        Set<LocalDate> datesAbove = new HashSet<>(), datesBelow = new HashSet<>();
        for (SwingSeries s : seriesList) {
            for (Flushes.MaTrade mt : Flushes.barrierTrades(s, cutoff, cfg, trig, timeStop, null)) {
                if (mt.aboveMa() == null) continue;             // MA undefined → not classifiable
                if (mt.aboveMa()) { flushAbove.add(mt.trade()); datesAbove.add(mt.trade().date()); }
                else { flushBelow.add(mt.trade()); datesBelow.add(mt.trade().date()); }
            }
        }

        List<Flushes.Trade> poolAbove = new ArrayList<>(), poolBelow = new ArrayList<>();
        for (Flushes.MaTrade mt : Placebo.poolMa(seriesList, cutoff, cfg, timeStop, null)) {
            if (mt.aboveMa() == null) continue;
            if (mt.aboveMa()) poolAbove.add(mt.trade()); else poolBelow.add(mt.trade());
        }

        return new Result(timeStop,
                state("above", timeStop, flushAbove, datesAbove, poolAbove, cfg),
                state("below", timeStop, flushBelow, datesBelow, poolBelow, cfg));
    }

    private static State state(String label, int timeStop, List<Flushes.Trade> flush,
                               Set<LocalDate> flushDates, List<Flushes.Trade> pool, SwingConfig cfg) {
        Flushes.BarrierResult flushRes = Flushes.barrierResult(timeStop, flush, flushDates, cfg.stopLoss(), cfg);
        Set<LocalDate> poolDates = new HashSet<>();
        for (Flushes.Trade t : pool) poolDates.add(t.date());
        Flushes.BarrierResult placeboRes = Flushes.barrierResult(timeStop, pool, poolDates, cfg.stopLoss(), cfg);

        boolean underpowered = pool.size() < MIN_POOL || flush.size() < MIN_DRAWS;
        Placebo.Bootstrap boot = null;
        if (!underpowered) {
            Double actualTp = flushRes.closed() == 0 ? null : flushRes.pctTp();
            boot = Placebo.bootstrapFromPool(pool, cfg, timeStop, flush.size(), actualTp, flushRes.meanReturn());
        }
        return new State(label, flushRes, placeboRes, boot, underpowered);
    }

    /** Above/below-200d-MA split for one family at the reference time stop. */
    public record Result(int timeStop, State above, State below) {
    }

    /**
     * One MA state's flush sim vs its placebo, with an optional within-state bootstrap. {@code flush}
     * and {@code placebo} reconcile as usual; {@code bootstrap} is {@code null} and
     * {@code underpowered} is {@code true} when the state has too few pool entries / flush draws to
     * bootstrap meaningfully.
     */
    public record State(String label, Flushes.BarrierResult flush, Flushes.BarrierResult placebo,
                        Placebo.Bootstrap bootstrap, boolean underpowered) {
    }
}
