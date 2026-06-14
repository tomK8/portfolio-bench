package com.portfolio.application;

import com.portfolio.adapter.EdgarFundamentalsFetcher;
import com.portfolio.adapter.EdgarFundamentalsFetcher.EpsQuarter;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.PriceHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decomposes a stock's historical return into <em>earnings growth</em> vs <em>multiple
 * expansion</em>. P/E at any date = price ÷ trailing-twelve-month diluted EPS, where TTM EPS
 * is the sum of the four most recent quarters whose 10-Q/10-K was already filed at that
 * date — so the series only uses information that was actually public at the time.
 *
 * <p>EPS comes from {@link EdgarFundamentalsFetcher} (SEC XBRL); prices from
 * {@code price_history.close} (already populated by {@link PriceFetchJob} for held names,
 * triggerable on demand for any other). Cached per ticker for {@link #CACHE_TTL} so the UI
 * stays snappy on repeat tab visits without hitting EDGAR every time.
 *
 * <p>Decomposition uses the identity {@code priceMult ≡ epsMult × peMult}, which holds
 * exactly when both series are positive at the window endpoints. The summary surfaces all
 * three multiples so the user can read off which side of the equation drove the move.
 */
public class FundamentalsService {

    private static final Logger log = LoggerFactory.getLogger(FundamentalsService.class);

    private static final int SCALE = 6;
    private static final java.time.Duration CACHE_TTL = java.time.Duration.ofHours(24);

    private final EdgarFundamentalsFetcher edgar;
    private final PriceHistoryRepository priceRepo;

    private final Map<String, CachedSeries> cache = new ConcurrentHashMap<>();

    public FundamentalsService(EdgarFundamentalsFetcher edgar,
                               PriceHistoryRepository priceRepo) {
        this.edgar = edgar;
        this.priceRepo = priceRepo;
    }

    public List<String> supportedTickers() {
        return EdgarFundamentalsFetcher.supportedTickers();
    }

    public FundamentalsReport report(String tickerRaw, int years) {
        String ticker = tickerRaw == null ? "" : tickerRaw.trim().toUpperCase();
        if (!EdgarFundamentalsFetcher.isSupported(ticker)) {
            return FundamentalsReport.empty(ticker, false, "Ticker " + ticker +
                    " is not in the supported set (currently US hyperscalers only).");
        }

        List<EpsQuarter> eps = cachedEps(ticker);
        if (eps.isEmpty()) {
            return FundamentalsReport.empty(ticker, false,
                    "EDGAR returned no diluted-EPS history for " + ticker + ".");
        }

        LocalDate to = LocalDate.now();
        LocalDate from = to.minusYears(Math.max(1, years));
        List<PriceBar> bars = priceRepo.getPriceHistory(ticker, from, to);
        if (bars.isEmpty()) {
            return FundamentalsReport.empty(ticker, true,
                    "No price_history for " + ticker + " — backfill from Yahoo first.");
        }

        // Stored prices are split-adjusted (today's basis); EDGAR EPS is as-filed (the basis
        // in effect at filing time). For GOOG, AAPL, NVDA, AMZN — all of which split during
        // the window — those bases disagree. Rescale each quarter's EPS by the splitFactor
        // at its period end so the entire series ends up in today's per-share basis. With
        // numerator and denominator now on the same footing, close ÷ TTM-EPS yields the real
        // P/E that the market saw at the time.
        List<EpsQuarter> epsAdjusted = rescaleEpsToTodayBasis(ticker, eps);

        // Walk bars + quarters together. For each price date we keep a window of "the 4 most
        // recent quarters whose 10-Q was already filed"; expanding it lazily as the date
        // advances means we do one pass over both series instead of nested scans.
        List<Point> points = new ArrayList<>(bars.size());
        int qi = 0;
        for (PriceBar b : bars) {
            while (qi < epsAdjusted.size() && !epsAdjusted.get(qi).availableFrom().isAfter(b.date())) qi++;
            if (qi < 4) continue;     // not enough history filed yet
            BigDecimal ttm = BigDecimal.ZERO;
            for (int k = qi - 4; k < qi; k++) ttm = ttm.add(epsAdjusted.get(k).eps());
            BigDecimal price = BigDecimal.valueOf(b.close());
            BigDecimal pe = ttm.signum() > 0
                    ? price.divide(ttm, SCALE, RoundingMode.HALF_UP)
                    : null;          // negative TTM → undefined P/E, leave a gap
            points.add(new Point(b.date().toString(), price.setScale(2, RoundingMode.HALF_UP),
                    ttm.setScale(4, RoundingMode.HALF_UP), pe));
        }

        return new FundamentalsReport(ticker, points, summarise(points), false, null);
    }

    /**
     * Convert each quarter's reported EPS to today's per-share basis. The right pivot is the
     * splitFactor at the date the <em>value</em> was filed, not the date the quarter first
     * became public. Restated comparative values in a later (post-split) 10-K are already on
     * today's basis (splitFactor at filing = 1); original pre-split values from a 10-Q
     * filed years earlier are still raw (splitFactor at filing = N). Dividing each value by
     * splitFactor-at-its-own-filed-date normalises both into today's per-share basis.
     */
    private List<EpsQuarter> rescaleEpsToTodayBasis(String ticker, List<EpsQuarter> eps) {
        List<EpsQuarter> out = new ArrayList<>(eps.size());
        for (EpsQuarter q : eps) {
            PriceBar ref = priceRepo.getPriceOn(ticker, q.valueFiled());
            double sf = ref != null ? ref.splitFactor() : 1.0;
            BigDecimal adj = sf == 1.0 ? q.eps()
                    : q.eps().divide(BigDecimal.valueOf(sf), 8, RoundingMode.HALF_UP);
            out.add(new EpsQuarter(q.periodEnd(), q.fy(), q.fp(), adj, q.availableFrom(),
                    q.valueFiled()));
        }
        return out;
    }

    private List<EpsQuarter> cachedEps(String ticker) {
        CachedSeries c = cache.get(ticker);
        if (c != null && Instant.now().isBefore(c.expiresAt)) return c.eps;
        List<EpsQuarter> fresh = edgar.fetchQuarterlyEps(ticker);
        cache.put(ticker, new CachedSeries(fresh, Instant.now().plus(CACHE_TTL)));
        return fresh;
    }

    /**
     * First and last points with a finite P/E define the decomposition window. Picking the
     * first finite (rather than the literal series start) skips any leading gaps from
     * negative-earnings periods — so AMZN's 2014 loss-year doesn't blow up the multiplier.
     */
    private static Summary summarise(List<Point> pts) {
        Point start = null;
        for (Point p : pts) {
            if (p.pe() != null) { start = p; break; }
        }
        Point end = null;
        for (int i = pts.size() - 1; i >= 0; i--) {
            if (pts.get(i).pe() != null) { end = pts.get(i); break; }
        }
        if (start == null || end == null || start == end) return null;

        BigDecimal priceMult = end.price().divide(start.price(), SCALE, RoundingMode.HALF_UP);
        BigDecimal epsMult = end.ttmEps().divide(start.ttmEps(), SCALE, RoundingMode.HALF_UP);
        BigDecimal peMult = end.pe().divide(start.pe(), SCALE, RoundingMode.HALF_UP);
        long days = ChronoUnit.DAYS.between(LocalDate.parse(start.date()),
                LocalDate.parse(end.date()));
        return new Summary(start, end, priceMult, epsMult, peMult, days);
    }

    private record CachedSeries(List<EpsQuarter> eps, Instant expiresAt) {}

    /** One (date, price, TTM EPS, P/E) sample. {@code pe} is null if TTM EPS ≤ 0. */
    public record Point(String date, BigDecimal price, BigDecimal ttmEps, BigDecimal pe) {}

    public record Summary(Point start, Point end,
                          BigDecimal priceMult, BigDecimal epsMult, BigDecimal peMult,
                          long spanDays) {}

    public record FundamentalsReport(String symbol,
                                     List<Point> points,
                                     Summary summary,
                                     boolean missingPrices,
                                     String message) {
        public static FundamentalsReport empty(String sym, boolean missingPrices, String msg) {
            return new FundamentalsReport(sym, List.of(), null, missingPrices, msg);
        }
    }
}
