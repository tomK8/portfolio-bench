package com.portfolio.application;

import com.portfolio.adapter.NasdaqCompanyEarningsFetcher;
import com.portfolio.adapter.YahooQuoteSummaryFetcher;
import com.portfolio.adapter.YahooQuoteSummaryFetcher.EarningsDetail;
import com.portfolio.adapter.YahooQuoteSummaryFetcher.ReportedQuarter;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.PrintReaction;
import com.portfolio.domain.PrintReaction.Session;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.EarningsPrintRepository;
import com.portfolio.persistence.EarningsPrintRepository.ConsensusCapture;
import com.portfolio.persistence.EarningsPrintRepository.PrintDetail;
import com.portfolio.persistence.OrderHistoryRepository;
import com.portfolio.persistence.OrderHistoryRepository.RestingOrder;
import com.portfolio.persistence.PriceHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * What a print that has already happened actually meant — the half of the <i>Just reported</i>
 * section that beat-or-miss cannot say.
 *
 * <p>Two jobs in one class, on opposite sides of the network, for the same reason
 * {@code OperatingLineService} is shaped that way:
 *
 * <ul>
 *   <li>{@link #refresh()} is the only thing here that fetches, and it is driven by
 *       {@code EarningsPrintFetchJob}. It is <b>self-limiting by content</b>: a print whose detail
 *       is already stored is not asked about again, so a pass costs a handful of requests in the
 *       days around a company's results and nothing at all the rest of the quarter.</li>
 *   <li>{@link #report()} is <b>cache-only</b> and never touches a feed. It is also deliberately
 *       <b>not</b> part of {@code ReminderService.report()}: that payload is read by the
 *       half-hourly mail job, and none of this is a reminder — it fires nothing, mails nothing and
 *       must cost the job nothing. The tab asks for it separately, which is the same separation
 *       the section itself already has.</li>
 * </ul>
 *
 * <h2>Why the consensus has to be captured rather than looked up</h2>
 *
 * The EPS consensus a print was measured against survives in Nasdaq's own per-company history, so
 * the beat/miss on the row is always reproducible. <b>Revenue is not like that.</b> No free source
 * publishes "what revenue was expected of the quarter that has just been reported": Yahoo carries
 * one set of estimates for the next report and rolls them forward within hours of the print, after
 * which the number sitting under that company's name belongs to the following quarter. So the
 * figure is taken while the date is still ahead and kept. Where it was never taken the row says
 * so — "no consensus was captured before this print" is a different statement from "revenue came
 * in short", and conflating them is the whole failure mode this app keeps writing rules against.
 *
 * <h2>Why "since the print" is measured against our own earlier reading</h2>
 *
 * Nasdaq states the forward consensus by named fiscal period and counts the analysts who moved it
 * over the last four weeks, but it publishes no history of the level. A percentage change needs
 * two readings, so this takes one regularly and dates it. The change is then reported against the
 * last reading <b>strictly before the print</b>, with that date named — never against "a month
 * ago", which on a four-week revision window would be a different question answered in the same
 * column.
 */
public class EarningsPrintService {

    private static final Logger log = LoggerFactory.getLogger(EarningsPrintService.class);

    /**
     * How far ahead a print's consensus is captured. Two weeks is comfortably longer than the
     * reminders screen's own week of notice, so the figure is in the store well before the night
     * it stops being obtainable, and short enough that a pass is a handful of symbols.
     */
    static final int CAPTURE_LEAD_DAYS = 14;

    /** How many of the feed's forward periods are kept per reading. See {@link #captureForecast}. */
    static final int CAPTURE_QUARTERS = 3;
    static final int CAPTURE_YEARS = 2;

    /** How many of a name's own previous prints the baseline and the surprise history look at. */
    static final int MAX_BASELINE_PRINTS = 12;
    static final int SURPRISE_HISTORY_SHOWN = 4;

    /**
     * The index a listing's move is taken net of, by the exchange its Yahoo ticker names.
     *
     * <p>Two markets, not a table that tries to cover every exchange. This is a definition rather
     * than data — no feed publishes "which index is this listing's market" — so the honest shape is
     * a short list of the ones the portfolio actually trades on, with everything else withheld and
     * named. A wrong index is worse than none: it would turn a market-relative move into a
     * difference between two unrelated markets and still print it as a number.
     */
    private static final Map<String, String> MARKET_INDEX = Map.of(
            "", "SPY",       // plain tickers are the US listing
            ".L", "ISF.L");  // iShares Core FTSE 100

    private final ReminderService reminders;
    private final EarningsCalendarService earnings;
    private final EarningsPrintRepository repo;
    private final PriceHistoryRepository prices;
    private final YahooTickerMap tickerMap;
    private final YahooQuoteSummaryFetcher yahoo;
    private final NasdaqCompanyEarningsFetcher nasdaq;
    private final AllocationService allocation;
    private final OrderHistoryRepository orderHistory;

    public EarningsPrintService(ReminderService reminders, EarningsCalendarService earnings,
                                EarningsPrintRepository repo, PriceHistoryRepository prices,
                                YahooTickerMap tickerMap, YahooQuoteSummaryFetcher yahoo,
                                NasdaqCompanyEarningsFetcher nasdaq, AllocationService allocation,
                                OrderHistoryRepository orderHistory) {
        this.reminders = reminders;
        this.earnings = earnings;
        this.repo = repo;
        this.prices = prices;
        this.tickerMap = tickerMap;
        this.yahoo = yahoo;
        this.nasdaq = nasdaq;
        this.allocation = allocation;
        this.orderHistory = orderHistory;
    }

    // =====================================================================================
    // The warm half
    // =====================================================================================

    /**
     * Capture what is about to stop being obtainable, and fill in what has just become so.
     *
     * <p>Both loops skip a symbol whose answer is already stored, which is what bounds the pass.
     * Every fetch degrades on its own: one company's unreachable feed costs that company's row and
     * nothing else.
     */
    public void refresh() {
        Set<String> universe = reminders.universe();
        if (universe.isEmpty()) return;
        LocalDate today = LocalDate.now();

        try {
            captureAhead(universe, today);
        } catch (RuntimeException e) {
            log.warn("Could not capture pre-print consensus", e);
        }
        try {
            fillReported(universe, today);
        } catch (RuntimeException e) {
            log.warn("Could not fill in reported print detail", e);
        }
    }

    /** Take the consensus for every print inside the capture window that we have not taken yet. */
    private void captureAhead(Set<String> universe, LocalDate today) {
        EarningsCalendarService.Lookup lookup = earnings.nextEarnings(universe, CAPTURE_LEAD_DAYS);
        List<EarningsCalendarService.NextEarnings> due = new ArrayList<>();
        for (EarningsCalendarService.NextEarnings ne : lookup.dated().values()) {
            if (ne.date() == null || ne.date().isBefore(today)) continue;
            if (ne.date().isAfter(today.plusDays(CAPTURE_LEAD_DAYS))) continue;
            if (Instruments.isBond(ne.symbol())) continue;
            due.add(ne);
        }
        if (due.isEmpty()) return;

        List<String> symbols = due.stream().map(EarningsCalendarService.NextEarnings::symbol).toList();
        Map<String, PrintDetail> have = repo.details(symbols, today, today.plusDays(CAPTURE_LEAD_DAYS));

        for (EarningsCalendarService.NextEarnings ne : due) {
            PrintDetail stored = have.get(ne.symbol() + "|" + ne.date());
            if (stored != null && stored.revenueConsensus() != null) continue;
            if (stored != null && today.equals(stored.attemptedOn())) continue;
            repo.markAttempted(ne.symbol(), ne.date(), today);
            captureConsensusFor(ne.symbol(), ne.date());
            captureForecast(ne.symbol(), today);
        }
    }

    /**
     * Store Yahoo's estimates for one upcoming print — <b>only if the feed still names that date</b>.
     *
     * <p>That test is the whole safety of this figure. Yahoo publishes a single "next earnings"
     * block per company and advances it after the print, so the same fields describe a different
     * quarter depending on when they are read. Measured on 2026-11-03: VRTX's block still named
     * 2026-11-02 and carried the quarter just reported, while AAPL's had moved to 2027-01-28 and
     * PLTR's to the following January — and PLTR's $2.68bn would have been set against the $2.31bn
     * it had just reported and published as a 14% revenue miss on a quarter it did not describe.
     */
    private void captureConsensusFor(String symbol, LocalDate date) {
        EarningsDetail detail = yahoo.fetchEarningsDetail(tickerFor(symbol));
        if (!describesPrint(detail, date)) return;
        repo.saveConsensus(symbol, date, toDouble(detail.nextEpsConsensus()),
                toDouble(detail.nextRevenueConsensus()), detail.financialCurrency());
    }

    /**
     * Whether Yahoo's estimates block is still describing the print we are asking about.
     *
     * <p>The one test standing between a measured revenue surprise and a fabricated one. Yahoo
     * carries a single "next earnings" block per company and advances it after the print, so the
     * same fields describe a different quarter depending on the hour they are read; the date it
     * names is the only thing on the payload that says which. Nothing is inferred from how
     * plausible the numbers look beside the reported ones — that is the heuristic
     * {@code docs/fundamentals.md} rejects for BRK.B's P/B, and it would have to be wrong about
     * PLTR, whose rolled-forward estimate sat a believable distance from what it had just
     * reported.
     */
    static boolean describesPrint(EarningsDetail detail, LocalDate date) {
        return detail != null && !detail.missing() && date != null
                && date.equals(detail.nextEarningsDate());
    }

    /**
     * One dated reading of the forward consensus, kept so a later one has something to be a change
     * from. Nearest few periods only: the far years move on nobody's news and would be rows per
     * symbol per day for a question nothing asks.
     */
    private void captureForecast(String symbol, LocalDate today) {
        NasdaqCompanyEarningsFetcher.Forecast f = nasdaq.forecast(symbol);
        if (f == null) return;
        List<ConsensusCapture> rows = new ArrayList<>();
        addCaptures(rows, symbol, today, "Q", f.quarterly(), CAPTURE_QUARTERS);
        addCaptures(rows, symbol, today, "Y", f.yearly(), CAPTURE_YEARS);
        repo.saveCaptures(rows);
    }

    private static void addCaptures(List<ConsensusCapture> out, String symbol, LocalDate today,
                                    String kind, List<NasdaqCompanyEarningsFetcher.ForecastRow> rows,
                                    int limit) {
        if (rows == null) return;
        for (int i = 0; i < Math.min(limit, rows.size()); i++) {
            NasdaqCompanyEarningsFetcher.ForecastRow r = rows.get(i);
            out.add(new ConsensusCapture(symbol, today, kind, r.fiscalEnd(), r.consensus(),
                    r.numEstimates(), r.up(), r.down()));
        }
    }

    /** Fill in the revenue, the fiscal quarter and the print history for each recent report. */
    private void fillReported(Set<String> universe, LocalDate today) {
        List<EarningsCalendarService.Reported> reported =
                earnings.recentlyReported(universe, ReminderService.LOOKBACK_DAYS);
        if (reported.isEmpty()) return;
        List<String> symbols = reported.stream()
                .map(EarningsCalendarService.Reported::symbol).distinct().toList();
        Map<String, PrintDetail> have = repo.details(symbols,
                today.minusDays(ReminderService.LOOKBACK_DAYS), today);

        for (EarningsCalendarService.Reported r : reported) {
            PrintDetail stored = have.get(r.symbol() + "|" + r.date());
            boolean complete = stored != null && stored.revenueActual() != null
                    && stored.fiscalQuarter() != null;
            if (complete) continue;
            if (stored != null && today.equals(stored.attemptedOn())) continue;
            repo.markAttempted(r.symbol(), r.date(), today);
            fetchReportedDetail(r, stored, today);
        }
    }

    private void fetchReportedDetail(EarningsCalendarService.Reported r, PrintDetail stored,
                                     LocalDate today) {
        String symbol = r.symbol();
        List<NasdaqCompanyEarningsFetcher.PriorPrint> history = nasdaq.surpriseHistory(symbol);
        EarningsDetail detail = yahoo.fetchEarningsDetail(tickerFor(symbol));

        // The fiscal quarter comes from the row the feed itself dates to this print, never from
        // "the newest row" — a company that has reported twice inside the window would otherwise
        // be labelled with the wrong one.
        String fiscalQuarter = null;
        if (history != null) {
            for (NasdaqCompanyEarningsFetcher.PriorPrint p : history) {
                if (r.date().equals(p.reported())) fiscalQuarter = p.fiscalQuarterEnd();
            }
        }

        if (!detail.missing()) {
            ReportedQuarter q = detail.reportedOn(r.date());
            if (q != null || fiscalQuarter != null) {
                repo.saveReported(symbol, r.date(), fiscalQuarter,
                        q == null ? null : toDouble(q.revenue()), detail.financialCurrency());
            }
            // Last chance at the consensus: still ours to take while the feed has not rolled.
            if ((stored == null || stored.revenueConsensus() == null)
                    && describesPrint(detail, r.date())) {
                repo.saveConsensus(symbol, r.date(), toDouble(detail.nextEpsConsensus()),
                        toDouble(detail.nextRevenueConsensus()), detail.financialCurrency());
            }
        }

        repo.savePriorPrints(mergeHistory(symbol, r, history, detail));
        captureForecast(symbol, today);
    }

    /**
     * Build the previous-print rows from the two sources, each answering what only it can.
     *
     * <p>Nasdaq states the fiscal quarter, the EPS and the consensus <b>on the same basis as the
     * calendar row above</b> — the figures reproduce it exactly — so the surprise history is one
     * basis throughout. Yahoo states a publication <i>time</i>, which is the only free way to know
     * whether a past print landed before the open or after the close, and therefore the only way a
     * past move can be measured over the same window as this one.
     *
     * <p>The sessions are taken as a set or not at all. A company whose surviving stamps claim it
     * reports on both sides of the session is a company whose stamps are not about sessions, and
     * the baseline is better short than built from windows pointing the wrong way.
     */
    private List<EarningsPrintRepository.PriorPrint> mergeHistory(
            String symbol, EarningsCalendarService.Reported current,
            List<NasdaqCompanyEarningsFetcher.PriorPrint> history, EarningsDetail detail) {

        Map<LocalDate, Session> sessions = new LinkedHashMap<>();
        if (!detail.missing()) {
            List<Session> seen = new ArrayList<>();
            for (ReportedQuarter q : detail.quarters()) {
                LocalDate day = PrintReaction.usDayOf(q.reportedAt());
                Session s = PrintReaction.sessionAt(q.reportedAt());
                if (day == null || s == null) continue;
                sessions.put(day, s);
                seen.add(s);
            }
            if (!PrintReaction.sessionsConsistent(seen)) sessions.clear();
        }

        List<EarningsPrintRepository.PriorPrint> out = new ArrayList<>();
        if (history != null) {
            for (NasdaqCompanyEarningsFetcher.PriorPrint p : history) {
                Session s = sessions.get(p.reported());
                out.add(new EarningsPrintRepository.PriorPrint(symbol, p.reported(),
                        p.fiscalQuarterEnd(), p.eps(), p.consensus(), s == null ? null : s.name()));
            }
        }
        // The print being looked at now is next quarter's history, and its session is the one the
        // calendar stated rather than one inferred from a timestamp — so it is written with the
        // strongest marker available, not the weakest.
        String session = sessionName(current.time());
        out.add(new EarningsPrintRepository.PriorPrint(symbol, current.date(), null,
                current.actualEps(), current.epsForecast(), session));
        return out;
    }

    // =====================================================================================
    // The read half
    // =====================================================================================

    /** Everything the <i>Just reported</i> section shows beyond beat-or-miss. Never fetches. */
    public PrintReport report() {
        LocalDate today = LocalDate.now();
        List<String> warnings = new ArrayList<>();
        Set<String> universe = reminders.universe();
        if (universe.isEmpty()) {
            return new PrintReport(today, ReminderService.LOOKBACK_DAYS, List.of(), warnings);
        }

        List<EarningsCalendarService.Reported> reported;
        try {
            reported = earnings.recentlyReported(universe, ReminderService.LOOKBACK_DAYS);
        } catch (RuntimeException e) {
            log.warn("Could not read the recently-reported list", e);
            warnings.add("The reported-print detail is unavailable.");
            return new PrintReport(today, ReminderService.LOOKBACK_DAYS, List.of(), warnings);
        }
        if (reported.isEmpty()) {
            return new PrintReport(today, ReminderService.LOOKBACK_DAYS, List.of(), warnings);
        }

        List<String> symbols = reported.stream()
                .map(EarningsCalendarService.Reported::symbol).distinct().toList();
        Map<String, PrintDetail> details = repo.details(symbols,
                today.minusDays(ReminderService.LOOKBACK_DAYS), today);
        Map<String, List<EarningsPrintRepository.PriorPrint>> priors = repo.priorPrints(symbols);
        Map<String, List<ConsensusCapture>> captures = repo.captures(symbols);
        Map<String, BigDecimal> positions = positions(warnings);
        Map<String, List<RestingOrder>> resting = resting(symbols);

        List<Row> rows = new ArrayList<>();
        for (EarningsCalendarService.Reported r : reported) {
            rows.add(buildRow(r, details.get(r.symbol() + "|" + r.date()),
                    priors.getOrDefault(r.symbol(), List.of()),
                    captures.getOrDefault(r.symbol(), List.of()),
                    positions.get(r.symbol()),
                    resting.getOrDefault(r.symbol(), List.of())));
        }
        return new PrintReport(today, ReminderService.LOOKBACK_DAYS, rows, warnings);
    }

    private Row buildRow(EarningsCalendarService.Reported r, PrintDetail detail,
                         List<EarningsPrintRepository.PriorPrint> priors,
                         List<ConsensusCapture> captures, BigDecimal positionGbp,
                         List<RestingOrder> orders) {
        Reaction reaction = reaction(r, priors);
        Revenue revenue = revenue(r, detail);
        List<Surprise> history = surpriseHistory(r, priors);

        return new Row(r.symbol(), r.companyName(), r.date(), r.time(), r.daysAgo(),
                detail == null ? null : detail.fiscalQuarter(),
                r.numEstimates(),
                r.epsForecast(), r.actualEps(), r.surprisePct(),
                epsGrowth(r),
                history, medianOf(history),
                revenue,
                revenue.state().equals("FIGURES") && r.surprisePct() != null
                        && r.surprisePct() > 0 && revenue.surprisePct() != null
                        && revenue.surprisePct() < 0,
                reaction,
                positionGbp,
                positionEffect(positionGbp, reaction),
                orderDistances(orders, reaction),
                revisions(captures, r.date()));
    }

    // ---- price reaction -------------------------------------------------------------

    /**
     * The move across the print, net of its market, in units of this name's usual one.
     *
     * <p>Measured on the listing the position is actually in — {@code AZN} reports on the US
     * calendar but is held in London, and it is the London line whose close the position is valued
     * at. The session rule carries over unchanged: a release before the US open is before the
     * London close too, and one after the US close is after everything.
     */
    private Reaction reaction(EarningsCalendarService.Reported r,
                              List<EarningsPrintRepository.PriorPrint> priors) {
        String ticker = tickerFor(r.symbol());
        Session session = sessionOf(r.time());
        if (session == null) {
            return Reaction.unavailable(ticker, null, "NO_SESSION");
        }
        LocalDate earliest = priors.stream()
                .map(EarningsPrintRepository.PriorPrint::reported)
                .filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(r.date());

        NavigableMap<LocalDate, Double> closes =
                closes(ticker, earliest.minusDays(10), LocalDate.now());
        if (closes.isEmpty()) return Reaction.unavailable(ticker, null, "NO_PRICES");

        PrintReaction.Move move = PrintReaction.move(r.date(), session, closes);
        if (move == null) {
            // The session that will carry the reaction has not closed yet — the ordinary state for
            // an after-the-close print read the next morning, and a different thing entirely from
            // having no prices for the name. One is "wait"; the other is a gap.
            boolean pending = !closes.lastKey().isAfter(r.date());
            return Reaction.unavailable(ticker, null, pending ? "PENDING" : "NO_PRICES");
        }

        String indexTicker = marketIndexFor(ticker);
        Double excess = null;
        Double marketPct = null;
        if (indexTicker != null) {
            NavigableMap<LocalDate, Double> index =
                    closes(indexTicker, move.from().minusDays(5), move.to().plusDays(5));
            Double raw = PrintReaction.excess(move, index);
            if (raw != null) {
                excess = pct(raw);
                marketPct = pct(move.pct() - raw);
            }
        }

        // Previous prints, each measured by the identical rule — same session test, same anchors.
        List<PrintReaction.Move> previous = new ArrayList<>();
        int dropped = 0;
        List<EarningsPrintRepository.PriorPrint> sorted = new ArrayList<>(priors);
        sorted.sort(Comparator.comparing(EarningsPrintRepository.PriorPrint::reported).reversed());
        for (EarningsPrintRepository.PriorPrint p : sorted) {
            if (p.reported() == null || !p.reported().isBefore(r.date())) continue;
            if (previous.size() >= MAX_BASELINE_PRINTS) break;
            Session ps = sessionOf(p.session());
            PrintReaction.Move m = ps == null ? null : PrintReaction.move(p.reported(), ps, closes);
            if (m == null) dropped++;
            else previous.add(m);
        }
        PrintReaction.Baseline baseline = PrintReaction.baseline(move, previous, dropped);

        String currency = closes.isEmpty() ? null : currencyOf(ticker);
        return new Reaction(ticker, "MEASURED", currency,
                move.from(), move.fromClose(), move.to(), move.toClose(), pct(move.pct()),
                marketPct, excess, indexTicker,
                pct(baseline.median()), baseline.prints(), baseline.dropped(), baseline.multiple());
    }

    /**
     * What the print did to the position, in pounds.
     *
     * <p>The position's own GBP value times the price move. Deliberately the price move and not a
     * revaluation of the whole holding between two days: the question is what the print cost or
     * made, and a day's currency move is not the print's doing.
     */
    static BigDecimal positionEffect(BigDecimal positionGbp, Reaction reaction) {
        if (positionGbp == null || reaction == null || reaction.movePct() == null) return null;
        return positionGbp.multiply(BigDecimal.valueOf(reaction.movePct() / 100.0))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * How far each resting limit sat from the price on either side of the print — signed as the
     * move still needed to reach it, the same convention the watchlist's levels use, so a figure
     * with the wrong sign for its side is one the price has already gone through.
     */
    static List<OrderDistance> orderDistances(List<RestingOrder> orders, Reaction reaction) {
        List<OrderDistance> out = new ArrayList<>();
        if (orders.isEmpty() || reaction == null || reaction.fromClose() == null) return out;
        for (RestingOrder o : orders) {
            if (o.price() == null || o.price() <= 0) continue;
            out.add(new OrderDistance(o.broker(), o.side(), o.orderType(), o.price(), o.currency(),
                    distance(o.price(), reaction.fromClose()),
                    distance(o.price(), reaction.toClose())));
        }
        return out;
    }

    static Double distance(Double limit, Double close) {
        if (limit == null || close == null || close <= 0) return null;
        return pct(limit / close - 1.0);
    }

    // ---- revenue ---------------------------------------------------------------------

    /**
     * Revenue against what was expected of it, or the reason there is no comparison.
     *
     * <p>Four states, because one blank cell would say all of them: the figures are in; the print
     * has happened and the revenue has not reached us; the revenue is in but no consensus for it
     * was ever captured (see the class note — it cannot be recovered afterwards); or the feed
     * publishes no revenue for this company at all.
     */
    static Revenue revenue(EarningsCalendarService.Reported r, PrintDetail detail) {
        if (detail == null) {
            return new Revenue(null, null, null, null, false, r.actualEps() == null
                    ? "AWAITING" : "NOT_FETCHED");
        }
        Double actual = detail.revenueActual();
        Double consensus = detail.revenueConsensus();
        String currency = detail.revenueCurrency();
        boolean prePrint = detail.consensusIsPrePrint();
        if (actual == null) return new Revenue(null, consensus, null, currency, prePrint, "AWAITING");
        if (consensus == null || consensus == 0) {
            return new Revenue(actual, null, null, currency, false, "NO_CONSENSUS");
        }
        // Divided by the magnitude, like the EPS surprise beside it, so a company that was expected
        // to shrink and shrank further is a miss rather than an arithmetic beat.
        double surprise = 100.0 * (actual - consensus) / Math.abs(consensus);
        return new Revenue(actual, consensus, surprise, currency, prePrint, "FIGURES");
    }

    // ---- context ---------------------------------------------------------------------

    /**
     * EPS against the same quarter a year ago, from the two figures the calendar already caches.
     *
     * <p>Null where the year-ago quarter was a loss or a zero: a move from −0.40 to +0.10 is a real
     * and important change, and there is no percentage of it. "Returned to profit" is a sentence,
     * not a growth rate, and printing one would be the signed-denominator mistake the surprise
     * column already refuses.
     */
    static Double epsGrowth(EarningsCalendarService.Reported r) {
        if (r.actualEps() == null || r.lastYearEps() == null || r.lastYearEps() <= 0) return null;
        return pct(r.actualEps() / r.lastYearEps() - 1.0);
    }

    /**
     * This company's own recent surprises, recomputed from the two figures each quarter reported —
     * never the vendor's own column, for the reason {@code docs/earnings.md} records.
     */
    static List<Surprise> surpriseHistory(EarningsCalendarService.Reported r,
                                                  List<EarningsPrintRepository.PriorPrint> priors) {
        List<Surprise> out = new ArrayList<>();
        List<EarningsPrintRepository.PriorPrint> sorted = new ArrayList<>(priors);
        sorted.sort(Comparator.comparing(EarningsPrintRepository.PriorPrint::reported).reversed());
        for (EarningsPrintRepository.PriorPrint p : sorted) {
            if (p.reported() == null || !p.reported().isBefore(r.date())) continue;
            Double pct = EarningsCalendarService.surprisePct(p.eps(), p.consensus());
            if (pct == null) continue;
            out.add(new Surprise(p.reported(), p.fiscalQuarter(), p.eps(), p.consensus(), pct));
            if (out.size() >= SURPRISE_HISTORY_SHOWN) break;
        }
        return out;
    }

    static Double medianOf(List<Surprise> history) {
        if (history.isEmpty()) return null;
        List<Double> v = new ArrayList<>(history.stream().map(Surprise::surprisePct).toList());
        v.sort(Comparator.naturalOrder());
        int n = v.size();
        return n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2.0;
    }

    /**
     * How the forward consensus has moved since the print, by named fiscal period.
     *
     * <p>The "before" is the last reading taken <b>strictly before the print date</b> and its date
     * is carried on the row, because that is what the percentage is a change from. Where there is
     * no such reading — a name added to the portfolio after it reported, or a print from before
     * this was ever captured — the change is absent and the revision counts still stand on their
     * own: the feed states its own four-week window for those, and a count is not a difference.
     */
    static List<Revision> revisions(List<ConsensusCapture> captures, LocalDate printDate) {
        if (captures.isEmpty()) return List.of();
        Map<String, List<ConsensusCapture>> byPeriod = new LinkedHashMap<>();
        for (ConsensusCapture c : captures) {
            byPeriod.computeIfAbsent(c.periodKind() + "|" + c.fiscalEnd(), k -> new ArrayList<>()).add(c);
        }
        // One period of each kind: the nearest quarter and the nearest year still ahead, which is
        // what the latest reading lists first. Picking them by the newest capture keeps the pair
        // following the calendar rather than a period that has since been reported.
        LocalDate newest = captures.stream().map(ConsensusCapture::capturedOn)
                .max(Comparator.naturalOrder()).orElse(null);
        List<Revision> out = new ArrayList<>();
        for (String kind : List.of("Q", "Y")) {
            ConsensusCapture latest = null;
            for (ConsensusCapture c : captures) {
                if (!kind.equals(c.periodKind()) || !c.capturedOn().equals(newest)) continue;
                if (latest == null || c.fiscalEnd().compareTo(latest.fiscalEnd()) < 0) latest = c;
            }
            if (latest == null) continue;
            ConsensusCapture before = null;
            for (ConsensusCapture c : byPeriod.getOrDefault(kind + "|" + latest.fiscalEnd(), List.of())) {
                if (c.capturedOn().isBefore(printDate)
                        && (before == null || c.capturedOn().isAfter(before.capturedOn()))) {
                    before = c;
                }
            }
            Double change = null;
            if (before != null && before.consensus() != null && before.consensus() != 0
                    && latest.consensus() != null) {
                // Over the magnitude, like every other surprise and change in this app: a loss
                // estimate that deepened must read as a cut, not as growth.
                change = 100.0 * (latest.consensus() - before.consensus())
                        / Math.abs(before.consensus());
            }
            out.add(new Revision(kind, latest.fiscalEnd(), latest.consensus(),
                    before == null ? null : before.consensus(),
                    before == null ? null : before.capturedOn(), change,
                    latest.up(), latest.down(), latest.numEstimates()));
        }
        return out;
    }

    // ---- plumbing --------------------------------------------------------------------

    private Map<String, BigDecimal> positions(List<String> warnings) {
        try {
            AllocationService.AllocationPoint p = allocation.currentPoint();
            return p == null ? Map.of() : p.symbolGbp();
        } catch (RuntimeException e) {
            log.warn("Could not value positions for the reported-print detail", e);
            warnings.add("Position sizes unavailable — the £ effect of each print is not shown.");
            return Map.of();
        }
    }

    private Map<String, List<RestingOrder>> resting(List<String> symbols) {
        Map<String, List<RestingOrder>> out = new LinkedHashMap<>();
        try {
            for (RestingOrder o : orderHistory.resting(symbols)) {
                if (o.symbol() == null) continue;
                out.computeIfAbsent(o.symbol().toUpperCase(), k -> new ArrayList<>()).add(o);
            }
        } catch (RuntimeException e) {
            log.warn("Could not read resting orders for the reported-print detail", e);
        }
        return out;
    }

    private NavigableMap<LocalDate, Double> closes(String ticker, LocalDate from, LocalDate to) {
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        if (ticker == null) return out;
        try {
            for (PriceBar b : prices.getPriceHistory(ticker, from, to)) {
                if (b.close() > 0) out.put(b.date(), b.close());
            }
        } catch (RuntimeException e) {
            log.warn("Could not read price history for {}", ticker, e);
        }
        return out;
    }

    private String currencyOf(String ticker) {
        try {
            PriceBar latest = prices.getPriceOn(ticker, prices.getLatestPriceDate(ticker));
            return latest == null ? null : latest.currency();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String tickerFor(String symbol) {
        return Instruments.isBond(symbol) ? symbol : tickerMap.tickerFor(symbol);
    }

    /** The index for a listing's own market, or null where this app has not named one. */
    static String marketIndexFor(String ticker) {
        if (ticker == null) return null;
        int dot = ticker.lastIndexOf('.');
        String suffix = dot < 0 ? "" : ticker.substring(dot).toUpperCase();
        return MARKET_INDEX.get(suffix);
    }

    /** Every index this app may need warm, so the price job keeps them so. */
    public static Set<String> marketIndexTickers() {
        return new LinkedHashSet<>(MARKET_INDEX.values());
    }

    static Session sessionOf(String time) {
        if (time == null) return null;
        String t = time.trim().toUpperCase();
        if (t.equals("BMO")) return Session.BMO;
        if (t.equals("AMC")) return Session.AMC;
        return null;
    }

    private static String sessionName(String time) {
        Session s = sessionOf(time);
        return s == null ? null : s.name();
    }

    /**
     * Fractions to percentage points. Every {@code *Pct} on this payload is in percentage points,
     * because the one it sits beside already is — the surprise the <i>Just reported</i> table has
     * always shown comes from {@code EarningsCalendarService.surprisePct} that way. Two adjacent
     * rate columns on different scales is the kind of thing nobody notices until a 3% move reads
     * as 0.03%. The domain keeps fractions; the conversion happens here, at the boundary.
     */
    static Double pct(Double fraction) {
        return fraction == null ? null : fraction * 100.0;
    }

    private static Double toDouble(BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }

    // =====================================================================================
    // Payload
    // =====================================================================================

    public record PrintReport(LocalDate asOf, int lookbackDays, List<Row> rows,
                              List<String> warnings) {
    }

    /**
     * One print, with everything known about what it meant. Every figure is nullable and a null is
     * "we do not know" — the states beside them ({@link Revenue#state}, {@link Reaction#state}) are
     * what say which kind of not-knowing it is.
     */
    public record Row(String symbol, String companyName, LocalDate date, String session,
                      long daysAgo, String fiscalQuarter, Integer numEstimates,
                      Double epsConsensus, Double epsActual, Double epsSurprisePct,
                      Double epsGrowthYoY, List<Surprise> surpriseHistory, Double medianSurprisePct,
                      Revenue revenue, boolean epsBeatRevenueMiss, Reaction reaction,
                      BigDecimal positionGbp, BigDecimal positionEffectGbp,
                      List<OrderDistance> orders, List<Revision> revisions) {
    }

    /** One of the company's own earlier quarters, for this one to be read against. */
    public record Surprise(LocalDate reported, String fiscalQuarter, Double eps, Double consensus,
                           Double surprisePct) {
    }

    /**
     * @param prePrintConsensus whether the consensus was captured before the print — the only
     *                          circumstance in which it describes this quarter
     * @param state             {@code FIGURES} / {@code AWAITING} / {@code NO_CONSENSUS} /
     *                          {@code NOT_FETCHED}
     */
    public record Revenue(Double actual, Double consensus, Double surprisePct, String currency,
                          boolean prePrintConsensus, String state) {
    }

    /**
     * @param state {@code MEASURED}, or why not: {@code NO_SESSION} (the feed never said whether
     *              the print was before the open or after the close, and the two windows are
     *              different days), {@code NO_PRICES}
     */
    public record Reaction(String ticker, String state, String currency,
                           LocalDate from, Double fromClose, LocalDate to, Double toClose,
                           Double movePct, Double marketPct, Double excessPct, String indexTicker,
                           Double baselineMedianPct, Integer baselinePrints, Integer baselineDropped,
                           Double multiple) {

        static Reaction unavailable(String ticker, String currency, String state) {
            return new Reaction(ticker, state, currency, null, null, null, null,
                    null, null, null, null, null, null, null, null);
        }
    }

    /** A resting limit, and how far the price had to travel to reach it either side of the print. */
    public record OrderDistance(String broker, String side, String orderType, Double limit,
                                String currency, Double beforePct, Double afterPct) {
    }

    /**
     * @param periodKind {@code Q} for the nearest fiscal quarter ahead, {@code Y} for the year
     * @param before     the consensus at {@code beforeOn}, the last reading taken before the print
     * @param up         analysts who raised their estimate over the feed's own four-week window
     */
    public record Revision(String periodKind, String fiscalEnd, Double consensus, Double before,
                           LocalDate beforeOn, Double changePct, Integer up, Integer down,
                           Integer numEstimates) {
    }
}
