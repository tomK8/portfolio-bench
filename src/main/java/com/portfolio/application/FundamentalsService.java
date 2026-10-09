package com.portfolio.application;

import com.portfolio.adapter.EdgarFundamentalsFetcher;
import com.portfolio.adapter.EdgarFundamentalsFetcher.FiledFact;
import com.portfolio.adapter.EdgarFundamentalsFetcher.FiledHistory;
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
 *
 * <h2>The EPS term is split again, because GAAP EPS is not all operating</h2>
 *
 * <p>Diluted EPS is the right denominator for a P/E — it is what the market prices, and it means
 * the same thing in every row. It is also, for some filers in some years, mostly something other
 * than the operating business. GOOG's twelve months to 2026-06-30 carry $244.2bn of net income
 * against $147.6bn of operating income: 40% of it arrived below the operating line, chiefly as
 * marks on equity stakes, against 14% in the window's base year. Two-term decomposition then
 * credits those marks to <em>earnings growth</em> (×9.12 rather than ×6.44) and, because the
 * price did not follow them, books the residual as a 33% <em>de-rating</em> that never happened.
 * The same distortion is why {@link OperatingLineService} exists at all.
 *
 * <p>So the EPS term is split by a second exact identity:
 *
 * <pre>priceMult ≡ opEpsMult × mixMult × peMult</pre>
 *
 * where {@code opEpsMult} is the move in operating income per share and {@code mixMult} is the
 * move in net income ÷ operating income. Both come from the filer's own
 * {@code OperatingIncomeLoss} and {@code NetIncomeLoss}, assembled into a trailing twelve months
 * by the same {@link FiledPeriods} rules and read as a <b>ratio</b>. Taking the ratio is what
 * makes this cheap and exact: the share count cancels (a weighted-average share count is a stock,
 * and would not survive quarter-tiling), and so does the split basis, so the two dollar series
 * need no rescaling.
 *
 * <p><b>The mix term is not "non-operating income".</b> Two things sit between the operating line
 * and the bottom line — non-operating items <em>and tax</em> — and nothing in the filings
 * separates them into a per-share figure without a modelled tax rate, which is the manufactured
 * number this repo keeps deleting. So the term is named for what it literally is, a ratio of two
 * filed figures, and the sentence under the cards says both things are in it. It is also why the
 * ratio is published net ÷ operating rather than the other way up: tax usually exceeds
 * non-operating income, so operating income exceeds net income for most filers and "operating is
 * 132% of net income" reads as an error.
 *
 * <p>Operating income is <b>pre-tax</b>, so the level of price ÷ operating income per share is
 * not a P/E and is never labelled one. Only its <em>change</em> over the window is published, and
 * only beside the GAAP P/E rather than in place of it.
 *
 * <p>Both legs are read on the <b>latest filed</b> value, the convention the EPS beside them
 * already uses — so a restated year is on its restated basis. GOOG's 2018 operating income was
 * $26.321bn as filed and $27.524bn as restated a year later, and the window's base ratio is the
 * second: the same figure the rest of the chart is drawn on.
 *
 * <p>Plenty of real filers tag no operating-income subtotal — banks have none, and several large
 * caps present straight to pre-tax income. Those get the split <b>withheld with its reason</b>,
 * the same way the operating line stores a refusal; the GAAP decomposition above it is unaffected.
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

        FiledHistory history = cachedEps(ticker);
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
        List<FiledFact> facts = rescaleEpsToTodayBasis(ticker, history.facts());

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
            BigDecimal ttm = current.ttm();
            BigDecimal pe = ttm.signum() > 0
                    ? price.divide(ttm, SCALE, RoundingMode.HALF_UP)
                    : null;                         // negative TTM → undefined P/E, leave a gap
            points.add(new Point(b.date().toString(), price.setScale(2, RoundingMode.HALF_UP),
                    ttm.setScale(4, RoundingMode.HALF_UP), pe));
        }

        Step last = steps.get(steps.size() - 1);
        Summary summary = summarise(points);
        Split split = summary == null ? new Split(null, null) : operatingSplit(ticker, steps, summary);
        Restated restated = summary == null ? null : baseRestated(facts, steps, summary);
        return new FundamentalsReport(ticker, points, summary, false, null,
                last.periodEnd().toString(), last.derived(),
                split.operating(), split.withheld(), restated);
    }

    /**
     * Whether the window's <b>base</b> twelve months is one the filer has since restated, and what
     * the decomposition reads on the restated figure.
     *
     * <p>A decomposition is a two-point comparison, so it is only ever as good as its endpoints —
     * and the base endpoint is the one that can have been superseded. Microsoft's FY2016 is the
     * case that found this: the four quarters this chart sums are the pre-ASC-606 ones, which the
     * filer never re-tagged, while its own latest annual for that identical window reads $2.56
     * against their $2.09. Dividing a 2016 price by the smaller figure put the base multiple at
     * 27.8× where the filer's current basis says 22.7×, and the whole difference landed in the
     * multiple term — 7% of the ten-year move rather than a third of it.
     *
     * <p><b>Nothing is substituted.</b> The chart's line is look-ahead-free by construction and
     * the restated figure was not public until 2018, so moving the line would be a different
     * promise than the one the panel makes. The cards keep the assembled figure too, so they
     * cannot disagree with the chart beneath them. What changes is that the panel now <em>says</em>
     * the base sits on a basis the filer has left, and prints the decomposition both ways — which
     * is the thing a reader cannot derive for themselves from anything on screen.
     *
     * <p>This is also why a "base year below trend" heuristic was not built instead. That needs a
     * trend model and a threshold, it fires on genuine cyclical troughs where the base is exactly
     * right, and what it would be approximating is the company's own non-GAAP adjustment — a
     * number nothing filed can reproduce or check. The filer's own annual is a filed fact, and it
     * disagrees or it does not.
     */
    private static Restated baseRestated(List<FiledFact> facts, List<Step> steps, Summary summary) {
        Step base = stepAt(steps, LocalDate.parse(summary.start().date()));
        if (base == null) return null;
        List<Fact> all = new ArrayList<>(facts.size());
        for (FiledFact f : facts) all.add(new Fact(f.start(), f.periodEnd(), f.value()));
        Fact window = new Fact(base.windowStart(), base.periodEnd(), base.ttm());
        Optional<Fact> annual = FiledPeriods.annualContradicting(window, all);
        if (annual.isEmpty() || annual.get().value().signum() <= 0) return null;

        BigDecimal filed = annual.get().value();
        BigDecimal epsMult = summary.end().ttmEps().divide(filed, SCALE, RoundingMode.HALF_UP);
        if (epsMult.signum() <= 0) return null;
        BigDecimal peMult = summary.priceMult().divide(epsMult, SCALE, RoundingMode.HALF_UP);
        return new Restated(base.periodEnd().toString(), base.ttm().setScale(4, RoundingMode.HALF_UP),
                filed.setScale(4, RoundingMode.HALF_UP), epsMult, peMult);
    }

    /**
     * The second identity: {@code epsMult ≡ opEpsMult × mixMult}, where the mix is net income ÷
     * operating income — everything below the operating line, as one factor. Computed at the two
     * endpoints of the window the summary already chose, so the three published multiples and the
     * two new ones close on {@code priceMult} exactly.
     *
     * <p>Both legs are read off the <em>same twelve months</em> as the EPS behind them. A filer
     * can be a quarter further along in one concept than another — EDGAR carries whatever each
     * filing tagged — and a ratio of two differently-dated windows is not the mix of either, so
     * that case is withheld rather than approximated.
     *
     * <p>Everything here is a ratio of two aggregate dollar figures, which is why neither needs
     * the split rescaling the per-share series does, and why no share count is fetched.
     */
    private Split operatingSplit(String ticker, List<Step> epsSteps, Summary summary) {
        FiledHistory operating = cachedConcept(ticker, EdgarFundamentalsFetcher.OPERATING_INCOME,
                EdgarFundamentalsFetcher.DOLLARS);
        if (operating.isEmpty()) {
            return Split.withheld(switch (operating.status()) {
                case UNTAGGED -> ticker + " tags no operating-income subtotal "
                        + "(us-gaap:OperatingIncomeLoss). Banks have none, and several large caps "
                        + "present straight to pre-tax income — so there is nothing to split the "
                        + "EPS term with.";
                case NOT_DOMESTIC -> ticker + " tags operating income only on foreign forms, "
                        + "which are on another basis.";
                default -> "EDGAR did not answer for " + ticker
                        + "'s operating income — that says nothing about the filer, so try again.";
            });
        }
        FiledHistory net = cachedConcept(ticker, EdgarFundamentalsFetcher.NET_INCOME,
                EdgarFundamentalsFetcher.DOLLARS);
        if (net.isEmpty()) {
            return Split.withheld(net.status() == Status.UNAVAILABLE
                    ? "EDGAR did not answer for " + ticker + "'s net income, so try again."
                    : ticker + " tags no us-gaap:NetIncomeLoss, so the operating share of net "
                            + "income cannot be formed.");
        }

        List<Step> opSteps = ttmSteps(operating.facts());
        List<Step> niSteps = ttmSteps(net.facts());
        LocalDate startDate = LocalDate.parse(summary.start().date());
        LocalDate endDate = LocalDate.parse(summary.end().date());
        Step epsStart = stepAt(epsSteps, startDate);
        Step epsEnd = stepAt(epsSteps, endDate);
        if (epsStart == null || epsEnd == null) return Split.withheld(null);

        BigDecimal start = netToOperating(opSteps, niSteps, startDate, epsStart.periodEnd());
        BigDecimal end = netToOperating(opSteps, niSteps, endDate, epsEnd.periodEnd());
        if (start == null || end == null) {
            return Split.withheld("Operating and net income do not assemble into the same twelve "
                    + "months as the EPS at " + (start == null ? summary.start().date()
                    : summary.end().date()) + ", so the ratio of the two there would span two "
                    + "different windows.");
        }

        // mixMult first, so the identity closes on the epsMult the cards already print rather
        // than on a second rounding of the same quantity.
        BigDecimal mixMult = end.divide(start, SCALE, RoundingMode.HALF_UP);
        BigDecimal opEpsMult = summary.epsMult().divide(mixMult, SCALE, RoundingMode.HALF_UP);
        if (opEpsMult.signum() <= 0) return Split.withheld(null);
        BigDecimal opMultipleMult = summary.priceMult().divide(opEpsMult, SCALE, RoundingMode.HALF_UP);
        return new Split(new OperatingSplit(opEpsMult, mixMult, start, end, opMultipleMult), null);
    }

    /**
     * Net income ÷ operating income over the twelve months ending {@code epsThrough}, as of
     * {@code date}. Null when either series has nothing filed by then, when either window ends
     * somewhere other than the EPS window's own end, or when either figure is not positive — a
     * ratio spanning a loss is not a mix of anything.
     *
     * <p>This direction, not operating ÷ net. Tax sits between the two, so for the great
     * majority of filers operating income <em>exceeds</em> net income and "operating is 132% of
     * net income" reads as an error. Net ÷ operating is below 1 for the ordinary case, above it
     * for a filer carrying large non-operating income, and is the factor the mix multiple is a
     * move in.
     */
    private static BigDecimal netToOperating(List<Step> opSteps, List<Step> niSteps,
                                             LocalDate date, LocalDate epsThrough) {
        Step op = stepAt(opSteps, date);
        Step ni = stepAt(niSteps, date);
        if (op == null || ni == null) return null;
        if (!op.periodEnd().equals(epsThrough) || !ni.periodEnd().equals(epsThrough)) return null;
        if (ni.ttm().signum() <= 0 || op.ttm().signum() <= 0) return null;
        return ni.ttm().divide(op.ttm(), SCALE, RoundingMode.HALF_UP);
    }

    /** The last step that had landed on or before {@code date}, or null if none had. */
    private static Step stepAt(List<Step> steps, LocalDate date) {
        Step current = null;
        for (Step s : steps) {
            if (s.from().isAfter(date)) break;
            current = s;
        }
        return current;
    }

    /** Either the split or the reason there isn't one; a null reason means "nothing to say". */
    private record Split(OperatingSplit operating, String withheld) {
        static Split withheld(String why) {
            return new Split(null, why);
        }
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
     *
     * <p>Concept-agnostic — the same walk builds the EPS series and the two aggregate series the
     * operating split is a ratio of.
     */
    private static List<Step> ttmSteps(List<FiledFact> facts) {
        TreeSet<LocalDate> filingDates = new TreeSet<>();
        for (FiledFact f : facts) filingDates.add(f.availableFrom());

        List<Step> steps = new ArrayList<>();
        for (LocalDate asOf : filingDates) {
            List<Fact> visible = new ArrayList<>(facts.size());
            for (FiledFact f : facts) {
                if (!f.availableFrom().isAfter(asOf)) {
                    visible.add(new Fact(f.start(), f.periodEnd(), f.value()));
                }
            }
            Optional<Ttm> ttm = FiledPeriods.trailingFourQuarters(visible);
            if (ttm.isEmpty()) continue;
            Step step = new Step(asOf, ttm.get().total(),
                    ttm.get().quarters().get(0).start(), ttm.get().periodEnd(),
                    ttm.get().derivedQuarterEnd() != null);
            Step previous = steps.isEmpty() ? null : steps.get(steps.size() - 1);
            if (previous == null || previous.ttm().compareTo(step.ttm()) != 0
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
    private List<FiledFact> rescaleEpsToTodayBasis(String ticker, List<FiledFact> facts) {
        List<FiledFact> out = new ArrayList<>(facts.size());
        for (FiledFact f : facts) {
            PriceBar ref = priceRepo.getPriceOn(ticker, f.valueFiled());
            double sf = ref != null ? ref.splitFactor() : 1.0;
            BigDecimal adj = sf == 1.0 ? f.value()
                    : f.value().divide(BigDecimal.valueOf(sf), 8, RoundingMode.HALF_UP);
            out.add(new FiledFact(f.start(), f.periodEnd(), adj, f.availableFrom(), f.valueFiled()));
        }
        return out;
    }

    private FiledHistory cachedEps(String ticker) {
        return cachedConcept(ticker, EdgarFundamentalsFetcher.EPS_DILUTED,
                EdgarFundamentalsFetcher.PER_SHARE);
    }

    private FiledHistory cachedConcept(String ticker, String concept, String unit) {
        String key = ticker + '/' + concept;
        CachedSeries c = cache.get(key);
        if (c != null && Instant.now().isBefore(c.expiresAt)) return c.history;
        FiledHistory fresh = edgar.fetch(ticker, concept, unit);
        // A failure is not cached for a day: the next visit to the tab should retry rather than
        // repeat an outage back at the user until tomorrow.
        if (fresh.isEmpty() && fresh.status() == Status.UNAVAILABLE) {
            return fresh;
        }
        cache.put(key, new CachedSeries(fresh, Instant.now().plus(CACHE_TTL)));
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

    private record CachedSeries(FiledHistory history, Instant expiresAt) {}

    /** One trailing-twelve-month figure of one concept, from the filing date it became readable on. */
    private record Step(LocalDate from, BigDecimal ttm, LocalDate windowStart,
                        LocalDate periodEnd, boolean derived) {}

    /** One (date, price, TTM EPS, P/E) sample. {@code pe} is null if TTM EPS ≤ 0. */
    public record Point(String date, BigDecimal price, BigDecimal ttmEps, BigDecimal pe) {}

    public record Summary(Point start, Point end,
                          BigDecimal priceMult, BigDecimal epsMult, BigDecimal peMult,
                          long spanDays) {}

    /**
     * The EPS term split in two: {@code epsMult ≡ opEpsMult × mixMult}. Published only beside the
     * GAAP decomposition, never instead of it.
     *
     * @param opEpsMult        the move in operating income per share — <b>pre-tax</b>, so a level
     *                         built on it is not a P/E and is not published as one
     * @param mixMult          the move in net income ÷ operating income: everything between the
     *                         operating line and the bottom line, as one factor
     * @param netToOperatingStart net income ÷ operating income over the window's base twelve
     *                         months. Not a share of anything — tax sits between the two, so the
     *                         ratio is below 1 for most filers and above it for one carrying
     *                         large non-operating income
     * @param netToOperatingEnd   the same ratio over the window's latest twelve months
     * @param opMultipleMult   {@code priceMult ÷ opEpsMult} — what the multiple did once the EPS
     *                         term is the operating business alone
     */
    public record OperatingSplit(BigDecimal opEpsMult, BigDecimal mixMult,
                                 BigDecimal netToOperatingStart, BigDecimal netToOperatingEnd,
                                 BigDecimal opMultipleMult) {}

    /**
     * The window's base twelve months, where the filer's own latest annual for it contradicts the
     * quarters this chart assembled. Nothing is substituted — see {@code baseRestated}.
     *
     * @param periodEnd   the end of the base window both figures describe
     * @param assembled   what the four filed quarters sum to, which is what the cards and the
     *                    chart are drawn on
     * @param filedAnnual the filer's latest annual for the identical window
     * @param epsMult     the EPS term recomputed on {@code filedAnnual} as the base
     * @param peMult      the multiple term that follows, {@code priceMult ÷ epsMult}
     */
    public record Restated(String periodEnd, BigDecimal assembled, BigDecimal filedAnnual,
                           BigDecimal epsMult, BigDecimal peMult) {}

    /**
     * @param epsThrough      the end of the twelve months behind the latest point — a P/E is a
     *                        price over a window, and the window is a quarter or so behind today
     * @param finalQuarterDerived whether that window's fourth quarter was recovered from the 10-K
     *                        by subtraction rather than tagged by the filer
     * @param operatingSplit  the EPS term split into operating and everything below the operating
     *                        line, or null
     * @param operatingSplitWithheld why there is no split, when that is worth saying — a filer
     *                        with no operating-income subtotal is a fact about the filer, so it
     *                        is reported rather than left as a blank card
     * @param baseRestated    set where the filer has since restated the window's base twelve
     *                        months, which a two-point decomposition cannot otherwise disclose
     */
    public record FundamentalsReport(String symbol,
                                     List<Point> points,
                                     Summary summary,
                                     boolean missingPrices,
                                     String message,
                                     String epsThrough,
                                     boolean finalQuarterDerived,
                                     OperatingSplit operatingSplit,
                                     String operatingSplitWithheld,
                                     Restated baseRestated) {
        public static FundamentalsReport empty(String sym, boolean missingPrices, String msg) {
            return new FundamentalsReport(sym, List.of(), null, missingPrices, msg, null, false,
                    null, null, null);
        }
    }
}
