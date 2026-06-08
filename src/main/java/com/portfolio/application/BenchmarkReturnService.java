package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.application.PortfolioReturnService.ReturnPoint;
import com.portfolio.application.PortfolioReturnService.Summary;
import com.portfolio.application.PortfolioValueService.DailyValue;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.PriceHistoryRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Cumulative return of a single benchmark instrument (an ETF, an index proxy, or a holding you
 * already own), expressed as a growth-of-£1 multiple anchored to the portfolio's inception date.
 *
 * <p>Reads {@code price_history.adj_close} — Yahoo's dividend- and split-adjusted close —
 * so the series is a total-return number directly comparable to {@link PortfolioReturnService}'s
 * TWR line on the same chart. Currency is irrelevant: the ratio {@code adj_t / adj_anchor} is
 * unitless, so a {@code GBp}-listed ETF (e.g. EQQQ.L) and a USD-listed one (e.g. QQQ) both
 * yield "native" returns without any FX conversion.
 *
 * <p>The input may be either an internal symbol from the holdings dropdown
 * ({@code EQQQ}, picked up by {@link YahooTickerMap} → {@code EQQQ.L}) or a raw Yahoo ticker
 * the user typed (e.g. {@code VWRL.L}, {@code QQQ}). Either way it's funnelled through the
 * ticker map before reading {@code price_history} — unknowns map to themselves, so a
 * directly-typed Yahoo ticker also works.
 *
 * <p>Inception is the portfolio's own — the first day {@link PortfolioValueService#dailyValues()}
 * reports a positive GBP value. The benchmark anchors at its first trading day on or after that
 * date, so the two series start at ×1.0 simultaneously on the chart.
 */
public class BenchmarkReturnService {

    private static final int SCALE = 10;
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365.25");
    private static final int WINDOW_TOLERANCE_DAYS = 7;

    private final PortfolioValueService valueService;
    private final PriceHistoryRepository priceRepo;
    private final YahooTickerMap tickerMap;

    public BenchmarkReturnService(PortfolioValueService valueService,
                                  PriceHistoryRepository priceRepo,
                                  YahooTickerMap tickerMap) {
        this.valueService = valueService;
        this.priceRepo = priceRepo;
        this.tickerMap = tickerMap;
    }

    public BenchmarkTimeline timeline(String symbol) {
        String sym = symbol == null ? "" : symbol.trim().toUpperCase();
        if (sym.isEmpty()) {
            return new BenchmarkTimeline(sym, List.of(), Summary.empty(), false);
        }

        LocalDate from = inceptionDate();
        if (from == null) {
            return new BenchmarkTimeline(sym, List.of(), Summary.empty(), false);
        }
        LocalDate to = LocalDate.now();

        // Internal symbols (EQQQ) map to Yahoo tickers (EQQQ.L) — that's the key used by the
        // price-fetch path. Unknowns map to themselves, so a Yahoo ticker typed directly works too.
        String storageKey = Instruments.isBond(sym) ? sym : tickerMap.tickerFor(sym);
        List<PriceBar> bars = priceRepo.getPriceHistory(storageKey, from, to);
        if (bars.isEmpty()) {
            return new BenchmarkTimeline(sym, List.of(), Summary.empty(), true);
        }

        double anchor = bars.get(0).adjClose();
        if (anchor <= 0) {
            return new BenchmarkTimeline(sym, List.of(), Summary.empty(), false);
        }

        List<ReturnPoint> growth = new ArrayList<>(bars.size());
        for (PriceBar b : bars) {
            BigDecimal g = BigDecimal.valueOf(b.adjClose() / anchor)
                    .setScale(SCALE, RoundingMode.HALF_UP);
            growth.add(new ReturnPoint(b.date().toString(), g));
        }
        return new BenchmarkTimeline(sym, growth, summarise(growth), false);
    }

    private LocalDate inceptionDate() {
        for (DailyValue dv : valueService.dailyValues()) {
            if (dv.valueGbp().signum() > 0) return dv.date();
        }
        return null;
    }

    /** Mirrors {@link PortfolioReturnService}'s trailing-window logic so the two stat boxes are comparable. */
    private static Summary summarise(List<ReturnPoint> points) {
        if (points.isEmpty()) return Summary.empty();
        ReturnPoint last = points.get(points.size() - 1);
        LocalDate now = LocalDate.parse(last.date());
        BigDecimal gNow = last.growth();
        return new Summary(
                trailing(points, now.minusYears(1), gNow),
                trailing(points, now.minusYears(3), gNow),
                trailing(points, now.minusYears(5), gNow),
                trailing(points, LocalDate.parse(points.get(0).date()), gNow));
    }

    private static BigDecimal trailing(List<ReturnPoint> points, LocalDate windowStart, BigDecimal gNow) {
        ReturnPoint anchor = findOnOrBefore(points, windowStart);
        if (anchor == null) return null;
        LocalDate anchorDate = LocalDate.parse(anchor.date());
        if (anchorDate.isBefore(windowStart.minusDays(WINDOW_TOLERANCE_DAYS))) return null;
        BigDecimal gThen = anchor.growth();
        if (gThen.signum() <= 0) return null;
        BigDecimal ratio = gNow.divide(gThen, SCALE, RoundingMode.HALF_UP);
        long days = ChronoUnit.DAYS.between(anchorDate,
                LocalDate.parse(points.get(points.size() - 1).date()));
        if (days <= 366) {
            return ratio.subtract(BigDecimal.ONE);
        }
        double annual = Math.pow(ratio.doubleValue(),
                DAYS_PER_YEAR.doubleValue() / days) - 1.0;
        return BigDecimal.valueOf(annual).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static ReturnPoint findOnOrBefore(List<ReturnPoint> points, LocalDate target) {
        ReturnPoint best = null;
        for (ReturnPoint p : points) {
            LocalDate d = LocalDate.parse(p.date());
            if (d.isAfter(target)) break;
            best = p;
        }
        return best;
    }

    /**
     * @param symbol     normalised (upper-cased, trimmed) input
     * @param missing    true when there are zero rows for {@code symbol} in {@code price_history};
     *                   the UI uses this to prompt the user before triggering a Yahoo backfill
     */
    public record BenchmarkTimeline(String symbol,
                                    List<ReturnPoint> growthPoints,
                                    Summary summary,
                                    boolean missing) {
    }
}
