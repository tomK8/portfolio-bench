package com.portfolio.domain.trend;

import com.portfolio.domain.model.PriceBar;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The spliced, cash-aware daily series every trend engine reads, pre-processed into aligned
 * primitive arrays. Built once per report and shared. Index {@code i} of every array refers to
 * the same trading day.
 *
 * <p><b>Splice.</b> When an extension series is supplied (a price-only index, e.g. ^NDX), its
 * bars strictly before the primary's first bar are prepended, scaled so both {@code close} and
 * {@code adjClose} are continuous at the splice (anchored on the extension bar at — or latest
 * before — the primary's first date). The pre-splice segment is a <em>price-only total-return
 * proxy</em>: an index pays no dividends, so pre-splice "total return" understates the real one
 * by the dividend yield. {@code spliceIndex} marks the first primary bar; callers must surface
 * the proxied range, never hide it.
 *
 * <p><b>Prices.</b> {@code close} is the split-adjusted price series (the classic signal input);
 * {@code adjClose} is the total-return series (all P&amp;L math); {@code adjOpen} is the
 * TR-consistent open ({@code open × adjClose/close}) used for realistic next-day-open fills,
 * falling back to the same day's {@code adjClose} when the source bar has no usable open (early
 * index history) — a close-for-open fallback, not a dropped day.
 *
 * <p><b>Cash leg.</b> {@code cashDaily[i]} is the simple return the out-of-market capital earns
 * <em>into</em> bar {@code i}: the latest cash-series close strictly before {@code dates[i]}
 * (look-ahead-free), read as an annual percent, divided by 100×252. Before the first cash bar —
 * or with no cash series at all — the flat configured rate applies; {@code cashFallbackUntil}
 * reports how far that fallback reached. The discount-yield-as-short-rate reading and the /252
 * day count are deliberate approximations, stated in the report.
 *
 * <p>{@code sma(len)[i]} is the trailing simple moving average of the <em>signal source</em>
 * ({@code close} by default, {@code adjClose} by config) over {@code len} sessions ending at and
 * including bar {@code i}; {@code NaN} until a full window exists. {@code monthEndIdx} lists the
 * indices of each <em>completed</em> calendar month's last trading day (the final, possibly
 * partial month is excluded so the monthly signal never reads a month that hasn't closed).
 */
public final class TrendSeries {

    final String symbol;
    final LocalDate[] dates;
    final double[] close;
    final double[] adjClose;
    final double[] adjOpen;
    final double[] cashDaily;
    final double[] signal;
    final int[] monthEndIdx;
    final int spliceIndex;
    final LocalDate cashFrom;             // first cash-series bar date; null when flat-only
    final LocalDate cashFallbackUntil;    // last series date priced at the flat rate; null if never

    private final Map<Integer, double[]> smaByLen = new HashMap<>();

    private TrendSeries(String symbol, LocalDate[] dates, double[] close, double[] adjClose,
                        double[] adjOpen, double[] cashDaily, double[] signal, int[] monthEndIdx,
                        int spliceIndex, LocalDate cashFrom, LocalDate cashFallbackUntil) {
        this.symbol = symbol;
        this.dates = dates;
        this.close = close;
        this.adjClose = adjClose;
        this.adjOpen = adjOpen;
        this.cashDaily = cashDaily;
        this.signal = signal;
        this.monthEndIdx = monthEndIdx;
        this.spliceIndex = spliceIndex;
        this.cashFrom = cashFrom;
        this.cashFallbackUntil = cashFallbackUntil;
    }

    /**
     * Build from ascending daily bars. {@code extension} and {@code cash} may be null/empty.
     * Returns {@code null} when the primary has too few bars to be useful.
     */
    public static TrendSeries of(TrendConfig cfg, List<PriceBar> primary,
                                 List<PriceBar> extension, List<PriceBar> cash) {
        if (primary == null || primary.size() < 2) return null;

        LocalDate primaryFirst = primary.get(0).date();
        List<PriceBar> prefix = new ArrayList<>();
        double closeFactor = 1, adjFactor = 1;
        if (extension != null && !extension.isEmpty()) {
            PriceBar anchor = null;
            for (PriceBar b : extension) {
                if (b.date().isBefore(primaryFirst)) prefix.add(b);
                if (!b.date().isAfter(primaryFirst)) anchor = b;   // latest ext bar ≤ primary start
            }
            if (!prefix.isEmpty() && anchor != null && anchor.close() > 0 && anchor.adjClose() > 0) {
                closeFactor = primary.get(0).close() / anchor.close();
                adjFactor = primary.get(0).adjClose() / anchor.adjClose();
            } else {
                prefix.clear();   // nothing usable to anchor on — skip the splice
            }
        }

        int n = prefix.size() + primary.size();
        LocalDate[] dates = new LocalDate[n];
        double[] close = new double[n];
        double[] adjClose = new double[n];
        double[] adjOpen = new double[n];
        for (int i = 0; i < n; i++) {
            boolean ext = i < prefix.size();
            PriceBar b = ext ? prefix.get(i) : primary.get(i - prefix.size());
            double cf = ext ? closeFactor : 1, af = ext ? adjFactor : 1;
            dates[i] = b.date();
            close[i] = b.close() * cf;
            adjClose[i] = b.adjClose() * af;
            Double open = b.open();
            adjOpen[i] = (open != null && open > 0 && b.close() > 0)
                    ? open * cf * (adjClose[i] / close[i])
                    : adjClose[i];
        }

        double[] cashDaily = new double[n];
        LocalDate cashFrom = (cash == null || cash.isEmpty()) ? null : cash.get(0).date();
        LocalDate fallbackUntil = null;
        double flatDaily = cfg.flatCashRatePct() / 100.0 / 252.0;
        int ci = -1;   // index of latest cash bar strictly before dates[i]
        for (int i = 0; i < n; i++) {
            if (cash != null) {
                while (ci + 1 < cash.size() && cash.get(ci + 1).date().isBefore(dates[i])) ci++;
            }
            if (ci >= 0) {
                cashDaily[i] = cash.get(ci).close() / 100.0 / 252.0;
            } else {
                cashDaily[i] = flatDaily;
                fallbackUntil = dates[i];
            }
        }
        cashDaily[0] = 0;   // no return accrues into the first bar

        double[] signal = cfg.signalOnAdjClose() ? adjClose : close;

        List<Integer> monthEnds = new ArrayList<>();
        for (int i = 0; i + 1 < n; i++) {
            if (!YearMonth.from(dates[i]).equals(YearMonth.from(dates[i + 1]))) monthEnds.add(i);
        }
        int[] monthEndIdx = monthEnds.stream().mapToInt(Integer::intValue).toArray();

        TrendSeries s = new TrendSeries(cfg.primarySymbol(), dates, close, adjClose, adjOpen,
                cashDaily, signal, monthEndIdx, prefix.size(), cashFrom, fallbackUntil);
        for (int len : cfg.maLengths()) s.smaByLen.put(len, trailingSma(signal, len));
        for (int m : cfg.monthlyMaLengths()) s.smaByLen.put(-m, monthlySma(signal, monthEndIdx, m));
        return s;
    }

    /** Trailing SMA of the signal source over {@code len} sessions; computed lazily if not prebuilt. */
    double[] sma(int len) {
        return smaByLen.computeIfAbsent(len, l -> trailingSma(signal, l));
    }

    /**
     * The monthly-close SMA of {@code m} months, defined only at completed-month-end bars
     * (mean of the last {@code m} month-end signal values including that bar; NaN elsewhere
     * and until {@code m} month-ends exist). Stored under the negative key {@code -m}.
     */
    double[] monthlySmaFor(int m) {
        return smaByLen.computeIfAbsent(-m, k -> monthlySma(signal, monthEndIdx, m));
    }

    private static double[] trailingSma(double[] src, int window) {
        int n = src.length;
        double[] out = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += src[i];
            if (i >= window) sum -= src[i - window];
            out[i] = (i + 1 >= window) ? sum / window : Double.NaN;
        }
        return out;
    }

    private static double[] monthlySma(double[] src, int[] monthEndIdx, int months) {
        double[] out = new double[src.length];
        java.util.Arrays.fill(out, Double.NaN);
        for (int k = 0; k < monthEndIdx.length; k++) {
            if (k + 1 < months) continue;
            double sum = 0;
            for (int j = k - months + 1; j <= k; j++) sum += src[monthEndIdx[j]];
            out[monthEndIdx[k]] = sum / months;
        }
        return out;
    }

    public int size() {
        return dates.length;
    }

    public String symbol() {
        return symbol;
    }

    public LocalDate firstDate() {
        return dates[0];
    }

    public LocalDate lastDate() {
        return dates[dates.length - 1];
    }

    /** Date of the first primary (non-proxied) bar; equals {@link #firstDate} when no splice. */
    public LocalDate spliceDate() {
        return dates[spliceIndex];
    }

    public boolean spliced() {
        return spliceIndex > 0;
    }
}
