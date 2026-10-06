package com.portfolio.application;

import com.portfolio.adapter.EdgarXbrlFetcher;
import com.portfolio.adapter.EdgarXbrlFetcher.Series;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.FiledPeriods;
import com.portfolio.domain.FiledPeriods.Fact;
import com.portfolio.domain.FiledPeriods.Ttm;
import com.portfolio.domain.model.OperatingLine;
import com.portfolio.persistence.FundamentalsRepository;
import com.portfolio.persistence.FundamentalsRepository.Cached;
import com.portfolio.persistence.OperatingLineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the operating line — TTM operating income, its margin, and the four quarters behind
 * it — from each US filer's own 10-K/10-Q XBRL, and caches it for the fundamentals panel.
 *
 * <p>This exists because the panel had no operating figure at all. Yahoo's
 * {@code operatingMargins} is a single quarter sitting in a block of TTM fields and was deleted
 * for it, and the hollowed-out quarterly statements carry no {@code ebit}. The consequence was
 * visible on GOOG, whose reported net margin of 54.8% is inflated by a large non-operating
 * equity gain: the operating business runs at 33.1%, a figure nothing on the screen could show.
 *
 * <h2>Two gates, and what each one is for</h2>
 *
 * <p><b>The symbol must be a plain US ticker.</b> SEC's registry is keyed on bare tickers, and
 * our internal symbols are bare too, so they collide: {@code BLND} is British Land here and
 * Blend Labs at the SEC. The test is that the symbol needs no Yahoo mapping to reach its own
 * listing — {@code AAPL} maps to {@code AAPL}, while {@code BLND} maps to {@code BLND.L} and is
 * therefore not the company whose filings that CIK holds. This also costs nothing: a London
 * line is skipped before any request.
 *
 * <p><b>EDGAR's revenue must reconcile with Yahoo's.</b> Filers do not agree on one revenue
 * tag, so rather than ranking the candidates by hand, the tag is chosen by which one
 * <em>reproduces the TTM revenue already on the row</em> (within {@link #RECONCILE_TOLERANCE}).
 * One test then does four jobs at once: it confirms the CIK is the same company, that the
 * window is current, that the revenue definition matches the column shown beside the margin,
 * and that the margin's denominator is the panel's own revenue rather than a second opinion.
 * Measured over the universe, 26 of 30 candidates reproduced Yahoo's revenue <b>to the dollar</b>
 * and the four that did not were all genuinely wrong to publish: BLND's ticker collision, KLAC's
 * latest tagged window being 2015, MU's being a quarter behind Yahoo's, and WMT's contract-
 * revenue tag excluding membership income — which the gate fixed by selecting {@code Revenues}
 * instead.
 *
 * <p>A symbol that passes the first gate and fails anything after it is stored as a
 * <b>refusal with its reason</b>, which the panel shows in place of the figure. The ones that
 * fail the first gate store nothing: a London line has no SEC filing to withhold.
 */
public class OperatingLineService {

    private static final Logger log = LoggerFactory.getLogger(OperatingLineService.class);

    private static final String OPERATING_INCOME = "OperatingIncomeLoss";

    /**
     * The revenue tags filers actually use. Order is irrelevant to correctness — the one that
     * reconciles is the one used, and all of them are tried — it only decides which is asked
     * for first, so the common case costs one request.
     */
    private static final List<String> REVENUE_CONCEPTS = List.of(
            "Revenues",
            "RevenueFromContractWithCustomerExcludingAssessedTax",
            "RevenueFromContractWithCustomerIncludingAssessedTax",
            "SalesRevenueNet");

    /**
     * How close EDGAR's assembled TTM revenue must come to Yahoo's for the line to publish.
     * Not a plausibility band on a figure — a reconciliation between two independent sources
     * of the <em>same</em> quantity, the same device {@code DividendService} uses on share
     * counts. 0.5% is loose against the 26 exact matches and tight enough to reject WMT's
     * 1.0% definition gap.
     */
    private static final BigDecimal RECONCILE_TOLERANCE = new BigDecimal("0.005");

    /** SEC asks for under 10 req/s; this is one every 150ms at most. */
    private static final long THROTTLE_MS = 150;

    /** Filings move quarterly, so a row younger than this is left alone (restarts stay cheap). */
    private static final Duration FRESH_FOR = Duration.ofHours(20);

    private final EdgarXbrlFetcher edgar;
    private final YahooTickerMap tickerMap;
    private final FundamentalsRepository fundamentals;
    private final OperatingLineRepository repo;

    public OperatingLineService(EdgarXbrlFetcher edgar, YahooTickerMap tickerMap,
                                FundamentalsRepository fundamentals,
                                OperatingLineRepository repo) {
        this.edgar = edgar;
        this.tickerMap = tickerMap;
        this.fundamentals = fundamentals;
        this.repo = repo;
    }

    /** Every cached line, keyed by internal symbol — what the snapshot read merges in. */
    public Map<String, OperatingLine> all() {
        return repo.loadAll();
    }

    /**
     * Refresh the operating line for every symbol that has a Yahoo row to reconcile against.
     * Rows fetched within {@link #FRESH_FOR} are skipped. Never throws: a symbol that fails is
     * logged and the batch continues, same as the other background fetchers.
     *
     * <p>The cached fundamentals rows are the universe, which can include a symbol that has
     * since left it — that table outlives the holding on purpose. Deriving the live universe
     * here instead would mean a second copy of the held-plus-watched-plus-extras rule, so the
     * handful of extra requests is the cheaper mistake; the read scopes them back out.
     */
    public RefreshResult refresh() {
        return refresh(false);
    }

    /**
     * @param force re-read SEC even for rows inside {@link #FRESH_FOR}. What a person pressing
     *              Refresh means, and the only way a change to how a line is assembled — or to
     *              the wording of a stored refusal — reaches rows that are merely recent.
     */
    public RefreshResult refresh(boolean force) {
        Map<String, Cached> cached = fundamentals.loadAll();
        Map<String, OperatingLine> existing = repo.loadAll();
        Instant now = Instant.now();
        long start = System.currentTimeMillis();
        int fetched = 0, published = 0, withheld = 0, skipped = 0;

        for (Map.Entry<String, Cached> entry : cached.entrySet()) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("Operating-line refresh interrupted — stopping early");
                break;
            }
            String symbol = entry.getKey();
            if (!isPlainUsTicker(symbol)) continue;

            OperatingLine prior = existing.get(symbol);
            if (!force && prior != null
                    && Duration.between(prior.fetchedAt(), now).compareTo(FRESH_FOR) < 0) {
                skipped++;
                continue;
            }
            BigDecimal yahooRevenue = revenueOf(entry.getValue());
            if (yahooRevenue == null || yahooRevenue.signum() <= 0) continue;

            try {
                if (fetched > 0) PriceFetchSupport.sleep(THROTTLE_MS);
                OperatingLine line = build(symbol, yahooRevenue, now);
                fetched++;
                if (line == null) continue;        // no CIK — nothing to say about this symbol
                repo.save(line);
                if (line.withheld()) withheld++;
                else published++;
            } catch (RuntimeException e) {
                log.warn("Operating-line refresh failed for {}", symbol, e);
            }
        }
        long elapsedMs = System.currentTimeMillis() - start;
        log.info("Operating line: {} published, {} withheld, {} still fresh ({} fetched in {} ms)",
                published, withheld, skipped, fetched, elapsedMs);
        return new RefreshResult(published, withheld, skipped, elapsedMs);
    }

    /**
     * Assemble one symbol's line, or a refusal carrying why. Null where there is nothing to say
     * rather than something withheld: no CIK for the symbol at all, or an SEC read that failed.
     * Storing an outage as a verdict would leave a refusal on the row until someone noticed.
     */
    OperatingLine build(String symbol, BigDecimal yahooRevenue, Instant now) {
        if (edgar.cik(symbol).isEmpty()) return null;

        Series income = edgar.series(symbol, OPERATING_INCOME);
        if (income.isEmpty()) {
            String reason = switch (income.status()) {
                case UNTAGGED -> "this filer never tags an operating income — banks have no "
                        + "operating-income subtotal, and several large caps present straight "
                        + "to pre-tax income";
                case NOT_DOMESTIC -> "this company reports on Form 20-F rather than 10-K/10-Q, "
                        + "so its figures are on a different basis (and often in another "
                        + "currency) than every other row here";
                default -> "the SEC could not be read for this symbol just now";
            };
            // UNAVAILABLE says nothing about the filer, so it is not worth storing as a verdict:
            // the next pass will ask again rather than leave a refusal that was really an outage.
            if (income.status() == EdgarXbrlFetcher.Status.UNAVAILABLE) return null;
            return OperatingLine.refused(symbol, reason, now);
        }
        Optional<Ttm> incomeTtm = FiledPeriods.trailingFourQuarters(income.facts());
        if (incomeTtm.isEmpty()) {
            return OperatingLine.refused(symbol,
                    "operating income is on file but four quarters do not tile a year, so no "
                            + "trailing twelve months can be assembled from it", now);
        }
        Ttm oi = incomeTtm.get();

        Reconciled revenue = reconcileRevenue(symbol, oi.periodEnd(), yahooRevenue);
        if (revenue == null) {
            return OperatingLine.refused(symbol,
                    "no revenue tag reproduces this row's TTM revenue over the window ending "
                            + oi.periodEnd() + ", so the operating margin would be a ratio of "
                            + "two different periods", now);
        }

        BigDecimal margin = oi.total().divide(revenue.revenue(), 6, RoundingMode.HALF_UP);
        return new OperatingLine(symbol, oi.total(), revenue.revenue(), margin,
                oi.periodEnd(), income.unit(), revenue.concept(),
                oi.derivedQuarterEnd(), quartersOf(oi, revenue.ttm()), null, now);
    }

    private record Reconciled(BigDecimal revenue, String concept, Ttm ttm) {}

    /**
     * The first revenue tag whose own trailing four quarters end where operating income's do
     * <em>and</em> reproduce Yahoo's TTM revenue. Null when none does.
     */
    private Reconciled reconcileRevenue(String symbol, java.time.LocalDate periodEnd,
                                        BigDecimal yahooRevenue) {
        for (String concept : REVENUE_CONCEPTS) {
            PriceFetchSupport.sleep(THROTTLE_MS);
            Series series = edgar.series(symbol, concept);
            if (series.isEmpty()) continue;
            Optional<Ttm> ttm = FiledPeriods.trailingFourQuarters(series.facts());
            if (ttm.isEmpty() || !ttm.get().periodEnd().equals(periodEnd)) continue;
            if (reconciles(ttm.get().total(), yahooRevenue)) {
                return new Reconciled(ttm.get().total(), concept, ttm.get());
            }
        }
        return null;
    }

    private static boolean reconciles(BigDecimal assembled, BigDecimal reported) {
        if (reported.signum() == 0) return false;
        return assembled.subtract(reported).abs()
                .divide(reported.abs(), 6, RoundingMode.HALF_UP)
                .compareTo(RECONCILE_TOLERANCE) <= 0;
    }

    /**
     * Pair the two series into one row per quarter, <b>matched on period end</b>. Position would
     * be wrong: a filer can tag quarterly revenue in a 10-K while leaving operating income to be
     * derived, so the two lists need not have been built the same way even though both tile the
     * same year. A quarter with no revenue counterpart simply has no margin of its own.
     */
    private static List<OperatingLine.Quarter> quartersOf(Ttm income, Ttm revenue) {
        Map<java.time.LocalDate, BigDecimal> revenueByEnd = new java.util.HashMap<>();
        if (revenue != null) {
            for (Fact f : revenue.quarters()) revenueByEnd.put(f.end(), f.value());
        }
        List<OperatingLine.Quarter> out = new ArrayList<>(income.quarters().size());
        for (Fact f : income.quarters()) {
            out.add(new OperatingLine.Quarter(f.start(), f.end(), f.value(),
                    revenueByEnd.get(f.end()), f.end().equals(income.derivedQuarterEnd())));
        }
        return out;
    }

    /**
     * Does this symbol reach its own listing without a Yahoo mapping? If it needs one, the bare
     * ticker SEC would resolve belongs to a different company — see the class note on BLND.
     */
    boolean isPlainUsTicker(String symbol) {
        return symbol != null && symbol.equals(tickerMap.tickerFor(symbol));
    }

    private static BigDecimal revenueOf(Cached cached) {
        Map<String, BigDecimal> extra = cached.quote().extra();
        return extra == null ? null : extra.get("totalRevenue");
    }

    public record RefreshResult(int published, int withheld, int stillFresh, long elapsedMs) {}
}
