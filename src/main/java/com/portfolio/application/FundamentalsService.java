package com.portfolio.application;

import com.portfolio.adapter.EdgarFundamentalsFetcher;
import com.portfolio.adapter.EdgarFundamentalsFetcher.EpsFact;
import com.portfolio.adapter.EdgarFundamentalsFetcher.EpsHistory;
import com.portfolio.adapter.EdgarXbrlFetcher.Status;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.FiledPeriods;
import com.portfolio.domain.FiledPeriods.Fact;
import com.portfolio.domain.FiledPeriods.Ttm;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.FundamentalsRepository;
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
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decomposes a stock's historical return into <em>earnings growth</em> vs <em>multiple
 * expansion</em>. P/E at any date = price ÷ trailing-twelve-month diluted EPS, where the TTM is
 * assembled from the quarters whose 10-Q/10-K was already filed at that date — so the series only
 * ever uses information that was public at the time.
 *
 * <p>EPS comes from {@link EdgarFundamentalsFetcher} (SEC XBRL); prices from
 * {@code price_history.close} (already populated by {@link PriceFetchJob} for held names,
 * triggerable on demand for any other). Cached per ticker for {@link #CACHE_TTL} so the UI stays
 * snappy on repeat tab visits without hitting EDGAR every time.
 *
 * <h2>The twelve months are assembled, not taken as the last four records</h2>
 *
 * <p>This used to sum the four most recently filed quarters. That is wrong for the great majority
 * of filers, because <b>a 10-K rarely tags its own fourth quarter</b>: the annual figure is there,
 * the Oct–Dec (or Nov–Jan, or Apr–Jun) quarter is not. Measured over EDGAR on 2026-10-08, the gap
 * recurs every single year for GOOGL, NVDA, MU, KLAC, LRCX, INTC, ORCL and PEP among others — so
 * "the last four records" was, for roughly half of each year, four quarters spanning <em>five</em>
 * quarters of calendar time with the fiscal Q4 missing from the middle. The resulting TTM EPS was
 * too small and the P/E correspondingly too high, on the chart's own flagship tickers.
 *
 * <p>{@link FiledPeriods} is the one place this repo assembles a trailing twelve months out of
 * filed XBRL periods, and it already solves exactly this: quarters must <b>tile a year
 * contiguously</b>, and the quarter a 10-K leaves implicit is recovered by subtracting the
 * nine-month year-to-date (or the three quarters that open the year) from the annual — arithmetic
 * on two filed figures, not an estimate. Where no such window can be built, the chart leaves a
 * gap rather than publishing a number that spans the wrong months.
 *
 * <p>The TTM therefore changes only when a filing lands, so it is built as a step function over
 * the distinct filing dates and then read off per price bar — one assembly per filing rather than
 * one per trading day.
 *
 * <p>Decomposition uses the identity {@code priceMult ≡ epsMult × peMult}, which holds exactly
 * when both series are positive at the window endpoints. The summary surfaces all three
 * multiples so the user can read off which side of the equation drove the move.
 */
public class FundamentalsService {

    private static final Logger log = LoggerFactory.getLogger(FundamentalsService.class);

    private static final int SCALE = 6;
    private static final java.time.Duration CACHE_TTL = java.time.Duration.ofHours(24);

    private final EdgarFundamentalsFetcher edgar;
    private final PriceHistoryRepository priceRepo;
    private final FundamentalsRepository fundamentals;
    private final YahooTickerMap tickerMap;

    private final Map<String, CachedSeries> cache = new ConcurrentHashMap<>();

    public FundamentalsService(EdgarFundamentalsFetcher edgar,
                               PriceHistoryRepository priceRepo,
                               FundamentalsRepository fundamentals,
                               YahooTickerMap tickerMap) {
        this.edgar = edgar;
        this.priceRepo = priceRepo;
        this.fundamentals = fundamentals;
        this.tickerMap = tickerMap;
    }

    /**
     * The tickers the picker offers: every symbol in the fundamentals universe that is its own US
     * ticker and that SEC's registry knows. Both gates are cheap and local — the ticker map is in
     * memory, the CIK registry is a cached file — so nothing here touches the network.
     *
     * <p>This is derived rather than listed. The hand-written map of seven hyperscalers it
     * replaces was the maintained list this repo rejects on principle, and in practice it hid
     * every semiconductor name the user holds behind a dropdown that could not offer them.
     *
     * <p>A symbol can be offered and still have nothing to draw — Cadence answers EDGAR with an
     * empty EPS array, GlobalFoundries with a 404. Finding that out costs a request, which is why
     * it is reported when the chart is asked for rather than used to prune the list.
     */
    public List<String> supportedTickers() {
        List<String> out = new ArrayList<>();
        for (String symbol : fundamentals.symbols()) {
            if (!isPlainUsTicker(symbol)) continue;
            if (edgar.cik(symbol).isEmpty()) continue;
            out.add(symbol);
        }
        return out;
    }

    public FundamentalsReport report(String tickerRaw, int years) {
        String ticker = tickerRaw == null ? "" : tickerRaw.trim().toUpperCase();
        if (ticker.isEmpty()) return FundamentalsReport.empty(ticker, false, "No ticker given.");

        // The same first gate the operating line uses, for the same reason: SEC keys bare tickers
        // and so do we, so they collide — BLND is British Land here and Blend Labs there.
        if (!isPlainUsTicker(ticker)) {
            return FundamentalsReport.empty(ticker, false, ticker + " is a "
                    + tickerMap.tickerFor(ticker) + " listing, so the bare ticker SEC would "
                    + "resolve belongs to a different company. US 10-K/10-Q filers only.");
        }
        if (edgar.cik(ticker).isEmpty()) {
            return FundamentalsReport.empty(ticker, false,
                    "SEC's company registry lists no filer under " + ticker + ".");
        }

        EpsHistory history = cachedEps(ticker);
        if (history.isEmpty()) {
            return FundamentalsReport.empty(ticker, false, switch (history.status()) {
                case UNTAGGED -> "SEC's XBRL carries no diluted EPS for " + ticker
                        + " — us-gaap:EarningsPerShareDiluted comes back absent or empty, so there "
                        + "is nothing to build a trailing twelve months from. The filing itself "
                        + "states the figure; this API does not return it. V, KO, MDLZ, CDNS and "
                        + "GFS are all like this.";
                case NOT_DOMESTIC -> ticker + " reports on a foreign form (20-F/40-F/6-K), where "
                        + "the figures are on another accounting basis and often another currency. "
                        + "This chart covers US 10-K/10-Q filers only.";
                default -> "EDGAR did not answer for " + ticker + " — that says nothing about the "
                        + "filer, so try again.";
            });
        }

        LocalDate to = LocalDate.now();
        LocalDate from = to.minusYears(Math.max(1, years));
        List<PriceBar> bars = priceRepo.getPriceHistory(ticker, from, to);
        if (bars.isEmpty()) {
            return FundamentalsReport.empty(ticker, true,
                    "No price_history for " + ticker + " — backfill from Yahoo first.");
        }

        // Stored prices are split-adjusted (today's basis); EDGAR EPS is as-filed (the basis in
        // effect at filing time). For GOOG, AAPL, NVDA, AMZN — all of which split during the
        // window — those bases disagree. Rescale every filed figure by the splitFactor at its own
        // filing date, *before* any of them are added or subtracted, so the whole series lands in
        // today's per-share basis and the recovered fourth quarter is a subtraction of two figures
        // on one footing.
        List<EpsFact> facts = rescaleEpsToTodayBasis(ticker, history.facts());

        List<Step> steps = ttmSteps(facts);
        if (steps.isEmpty()) {
            return FundamentalsReport.empty(ticker, false, "EDGAR has filed EPS for " + ticker
                    + ", but no four quarters that tile a year contiguously — so no trailing "
                    + "twelve months can be assembled without inventing a period.");
        }

        List<Point> points = new ArrayList<>(bars.size());
        int si = 0;
        Step current = null;
        for (PriceBar b : bars) {
            while (si < steps.size() && !steps.get(si).from().isAfter(b.date())) {
                current = steps.get(si++);
            }
            if (current == null) continue;          // nothing filed yet at this date
            BigDecimal price = BigDecimal.valueOf(b.close());
            BigDecimal ttm = current.ttmEps();
            BigDecimal pe = ttm.signum() > 0
                    ? price.divide(ttm, SCALE, RoundingMode.HALF_UP)
                    : null;                         // negative TTM → undefined P/E, leave a gap
            points.add(new Point(b.date().toString(), price.setScale(2, RoundingMode.HALF_UP),
                    ttm.setScale(4, RoundingMode.HALF_UP), pe));
        }

        Step last = steps.get(steps.size() - 1);
        return new FundamentalsReport(ticker, points, summarise(points), false, null,
                last.periodEnd().toString(), last.derived());
    }

    /** Does this symbol reach its own listing without a Yahoo mapping? See the class note. */
    private boolean isPlainUsTicker(String symbol) {
        return symbol != null && symbol.equals(tickerMap.tickerFor(symbol));
    }

    /**
     * The trailing twelve months as it stood after each filing — value, the window's own end, and
     * whether the fourth quarter in it was recovered from a 10-K by subtraction.
     *
     * <p>A step is emitted per distinct filing date and kept only when it moves the figure, so a
     * 10-Q that merely re-tags comparatives costs nothing. Walking price bars against these is
     * what keeps the series look-ahead-free: a bar sees the TTM that the market could see.
     */
    private static List<Step> ttmSteps(List<EpsFact> facts) {
        TreeSet<LocalDate> filingDates = new TreeSet<>();
        for (EpsFact f : facts) filingDates.add(f.availableFrom());

        List<Step> steps = new ArrayList<>();
        for (LocalDate asOf : filingDates) {
            List<Fact> visible = new ArrayList<>(facts.size());
            for (EpsFact f : facts) {
                if (!f.availableFrom().isAfter(asOf)) {
                    visible.add(new Fact(f.start(), f.periodEnd(), f.eps()));
                }
            }
            Optional<Ttm> ttm = FiledPeriods.trailingFourQuarters(visible);
            if (ttm.isEmpty()) continue;
            Step step = new Step(asOf, ttm.get().total(), ttm.get().periodEnd(),
                    ttm.get().derivedQuarterEnd() != null);
            Step previous = steps.isEmpty() ? null : steps.get(steps.size() - 1);
            if (previous == null || previous.ttmEps().compareTo(step.ttmEps()) != 0
                    || !previous.periodEnd().equals(step.periodEnd())) {
                steps.add(step);
            }
        }
        return steps;
    }

    /**
     * Convert each filed figure to today's per-share basis. The right pivot is the splitFactor at
     * the date the <em>value</em> was filed, not the date the period first became public.
     * Restated comparatives in a later (post-split) 10-K are already on today's basis
     * (splitFactor at filing = 1); original pre-split values from a 10-Q filed years earlier are
     * still raw (splitFactor at filing = N). Dividing each value by splitFactor-at-its-own-filed-
     * date normalises both.
     */
    private List<EpsFact> rescaleEpsToTodayBasis(String ticker, List<EpsFact> facts) {
        List<EpsFact> out = new ArrayList<>(facts.size());
        for (EpsFact f : facts) {
            PriceBar ref = priceRepo.getPriceOn(ticker, f.valueFiled());
            double sf = ref != null ? ref.splitFactor() : 1.0;
            BigDecimal adj = sf == 1.0 ? f.eps()
                    : f.eps().divide(BigDecimal.valueOf(sf), 8, RoundingMode.HALF_UP);
            out.add(new EpsFact(f.start(), f.periodEnd(), adj, f.availableFrom(), f.valueFiled()));
        }
        return out;
    }

    private EpsHistory cachedEps(String ticker) {
        CachedSeries c = cache.get(ticker);
        if (c != null && Instant.now().isBefore(c.expiresAt)) return c.history;
        EpsHistory fresh = edgar.fetch(ticker);
        // A failure is not cached for a day: the next visit to the tab should retry rather than
        // repeat an outage back at the user until tomorrow.
        if (fresh.isEmpty() && fresh.status() == Status.UNAVAILABLE) {
            return fresh;
        }
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

    private record CachedSeries(EpsHistory history, Instant expiresAt) {}

    /** One trailing-twelve-month figure, from the filing date it became readable on. */
    private record Step(LocalDate from, BigDecimal ttmEps, LocalDate periodEnd, boolean derived) {}

    /** One (date, price, TTM EPS, P/E) sample. {@code pe} is null if TTM EPS ≤ 0. */
    public record Point(String date, BigDecimal price, BigDecimal ttmEps, BigDecimal pe) {}

    public record Summary(Point start, Point end,
                          BigDecimal priceMult, BigDecimal epsMult, BigDecimal peMult,
                          long spanDays) {}

    /**
     * @param epsThrough      the end of the twelve months behind the latest point — a P/E is a
     *                        price over a window, and the window is a quarter or so behind today
     * @param finalQuarterDerived whether that window's fourth quarter was recovered from the 10-K
     *                        by subtraction rather than tagged by the filer
     */
    public record FundamentalsReport(String symbol,
                                     List<Point> points,
                                     Summary summary,
                                     boolean missingPrices,
                                     String message,
                                     String epsThrough,
                                     boolean finalQuarterDerived) {
        public static FundamentalsReport empty(String sym, boolean missingPrices, String msg) {
            return new FundamentalsReport(sym, List.of(), null, missingPrices, msg, null, false);
        }
    }
}
