package com.portfolio.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Assembles a trailing-twelve-month figure out of the period-tagged facts in an SEC XBRL
 * filing. Pure arithmetic over {@code (start, end, value)} triples — the adapter does the
 * fetching and the de-duplication, this decides which periods may be added together.
 *
 * <p>Unlike Yahoo's TTM fields, which state no period at all, every fact here carries the
 * window the filer tagged it with. That is the whole reason for going to EDGAR, so this class
 * refuses rather than approximates: a TTM is published only when four quarters
 * <b>tile a year contiguously</b>, and otherwise not at all. Three quarters summed and called
 * a year would be the manufactured figure this repo keeps deleting.
 *
 * <h2>The two traps</h2>
 *
 * <p><b>A 10-Q reports more than its own quarter.</b> The filing for a fiscal Q3 carries Q3
 * alone (~91 days) <em>and</em> the nine-month year-to-date (~273 days); a Q2 filing carries
 * the half-year beside the quarter. Summing everything a filer tagged would count the same
 * months two or three times, so facts are bucketed by the length of their own window and only
 * the quarter-length ones are added.
 *
 * <p><b>The final quarter is usually not filed as a quarter.</b> A 10-K presents the year, not
 * its fourth quarter — for {@code OperatingIncomeLoss} the Q4 window is simply absent (verified
 * on NVDA, whose FY2026 shows Q1-Q3 and the year but no Nov-Jan quarter). It is recoverable by
 * subtraction, and that is arithmetic on two filed figures rather than an estimate: the year
 * minus the nine months that share its start leaves the quarter between them. NVDA FY2026:
 * 130.387bn − 86.088bn = 44.299bn, which lands in sequence between the 36.010bn before it and
 * the 53.536bn after. Where no nine-month window was tagged, three contiguous quarters that
 * start with the year serve the same purpose.
 *
 * <p>Dates are compared with a few days of slack ({@link #SLACK_DAYS}) because fiscal quarters
 * end on a weekday — NVDA's Q3 FY2026 ends 2025-10-26 and Q4 starts 2025-10-27, but a filer
 * whose period boundaries straddle a weekend can leave a day or two unaccounted for.
 */
public final class FiledPeriods {

    /** Day-count bands for the four window lengths a 10-K/10-Q tags. */
    private static final int QUARTER_MIN = 75, QUARTER_MAX = 105;
    private static final int NINE_MONTH_MIN = 260, NINE_MONTH_MAX = 290;
    private static final int ANNUAL_MIN = 350, ANNUAL_MAX = 380;

    /** Tolerance when testing whether one period resumes where another stopped. */
    private static final int SLACK_DAYS = 3;

    /** Four quarters and an annual, each rounded to a cent, can disagree by this much. */
    private static final BigDecimal ROUNDING_SLACK = new BigDecimal("0.03");

    /** The same tolerance for a filer whose per-share figures make a cent immaterial. */
    private static final BigDecimal ROUNDING_RELATIVE = new BigDecimal("0.01");

    private FiledPeriods() {}

    /** One period-tagged figure from a filing. */
    public record Fact(LocalDate start, LocalDate end, BigDecimal value) {

        public long days() {
            return ChronoUnit.DAYS.between(start, end);
        }
    }

    /**
     * A trailing twelve months and the four quarters it was built from, oldest first.
     *
     * @param derivedQuarterEnd the quarter recovered by subtraction, or null when all four were
     *                          filed as quarters — the panel marks a derived quarter rather than
     *                          presenting it as though the filer had tagged it
     */
    public record Ttm(BigDecimal total, LocalDate periodEnd, List<Fact> quarters,
                      LocalDate derivedQuarterEnd) {}

    /**
     * The four most recent contiguous quarters, newest ending last. Empty when they do not
     * tile a year — a missing middle quarter that cannot be recovered by subtraction, or fewer
     * than four quarters on file.
     */
    public static Optional<Ttm> trailingFourQuarters(Collection<Fact> facts) {
        if (facts == null || facts.isEmpty()) return Optional.empty();

        Map<LocalDate, Fact> quarters = new LinkedHashMap<>();
        List<Fact> nineMonths = new ArrayList<>();
        List<Fact> annuals = new ArrayList<>();
        for (Fact f : facts) {
            long days = f.days();
            if (days >= QUARTER_MIN && days <= QUARTER_MAX) quarters.putIfAbsent(f.end(), f);
            else if (days >= NINE_MONTH_MIN && days <= NINE_MONTH_MAX) nineMonths.add(f);
            else if (days >= ANNUAL_MIN && days <= ANNUAL_MAX) annuals.add(f);
        }

        LocalDate derived = null;
        for (Fact year : annuals) {
            Fact recovered = finalQuarterOf(year, quarters, nineMonths);
            if (recovered != null) {
                quarters.put(recovered.end(), recovered);
                // Only the most recent recovery is worth naming: an older one cannot reach the
                // trailing window, and the chain below keeps at most one year of quarters.
                if (derived == null || recovered.end().isAfter(derived)) derived = recovered.end();
            }
        }
        if (quarters.isEmpty()) return Optional.empty();

        List<Fact> chain = contiguousChainEndingLatest(quarters);
        if (chain.size() != 4) return Optional.empty();

        BigDecimal total = BigDecimal.ZERO;
        for (Fact f : chain) total = total.add(f.value());
        LocalDate periodEnd = chain.get(chain.size() - 1).end();
        LocalDate derivedInWindow = null;
        for (Fact f : chain) {
            if (f.end().equals(derived)) derivedInWindow = derived;
        }
        return Optional.of(new Ttm(total, periodEnd, List.copyOf(chain), derivedInWindow));
    }

    /**
     * The filer's own annual figure for {@code window} — a trailing twelve months already
     * assembled, passed as one {@code (start, end, total)} — where it <b>contradicts</b> the
     * quarters that were summed to build it. Empty when no annual covers that window, or when the
     * two agree.
     *
     * <h2>Why four filed quarters can fail to add up to the filed year</h2>
     *
     * <p>A restatement does not always reach the quarters. When Microsoft adopted ASC 606 it
     * restated FY2016 in the FY2018 10-K — the annual went from $2.10 to $2.56 of diluted EPS —
     * but never re-tagged that year's four quarters, which stand to this day at
     * 0.61 + 0.62 + 0.47 + 0.39 = <b>2.09</b> on the pre-606 basis. FY2017 beside it <em>was</em>
     * re-tagged all the way down (0.72 + 0.80 + 0.70 + 1.03 = 3.25, matching its restated annual),
     * so this is the filer's inconsistency and not a rule with an exception.
     *
     * <p>{@link #trailingFourQuarters} is right to prefer the quarters: they are what makes a
     * <em>trailing</em> window possible at all, and the annual only ever covers a fiscal year. But
     * where the filer's own latest annual covers exactly the window just assembled and says
     * something else, that is a filed fact about the window, and a caller comparing two dates ten
     * years apart needs to know its base sits on an accounting basis the filer has since left.
     *
     * <p>The test is arithmetic, not judgement: five figures each rounded to a cent can disagree
     * by $0.025, so a gap inside {@link #ROUNDING_SLACK} — or inside {@link #ROUNDING_RELATIVE} of
     * the annual, for a filer whose per-share figures are large enough that a cent is not the
     * granularity — is rounding and nothing is reported. Across the fundamentals universe on
     * 2026-10-09 this fires on nine windows, led by AMD FY2010 (69%), MSFT FY2016 (18%) and MU
     * FY2013 (12%); every large gap that is a <em>split</em> rather than a restatement is already
     * gone by the time this is called, because the caller rescales before assembling.
     */
    public static Optional<Fact> annualContradicting(Fact window, Collection<Fact> facts) {
        if (window == null || facts == null) return Optional.empty();

        Fact annual = null;
        for (Fact f : facts) {
            long days = f.days();
            if (days < ANNUAL_MIN || days > ANNUAL_MAX) continue;
            if (near(f.start(), window.start()) && near(f.end(), window.end())) annual = f;
        }
        if (annual == null) return Optional.empty();

        BigDecimal gap = annual.value().subtract(window.value()).abs();
        BigDecimal slack = annual.value().abs()
                .multiply(ROUNDING_RELATIVE).max(ROUNDING_SLACK);
        return gap.compareTo(slack) > 0 ? Optional.of(annual) : Optional.empty();
    }

    /**
     * The quarter a 10-K leaves implicit: the year minus the nine months sharing its start, or
     * minus the three contiguous quarters that open it. Null when the year's final quarter is
     * already on file, or when neither subtraction is available.
     */
    private static Fact finalQuarterOf(Fact year, Map<LocalDate, Fact> quarters,
                                       List<Fact> nineMonths) {
        if (quarters.containsKey(year.end())) return null;

        for (Fact nine : nineMonths) {
            if (near(nine.start(), year.start()) && nine.end().isBefore(year.end())) {
                return new Fact(nine.end().plusDays(1), year.end(),
                        year.value().subtract(nine.value()));
            }
        }

        List<Fact> inside = new ArrayList<>();
        for (Fact q : quarters.values()) {
            if (!q.start().isBefore(year.start()) && !q.end().isAfter(year.end())) inside.add(q);
        }
        inside.sort((a, b) -> a.end().compareTo(b.end()));
        if (inside.size() != 3 || !near(inside.get(0).start(), year.start())) return null;
        // The three must themselves be contiguous, or what is left over is not one quarter.
        for (int i = 1; i < inside.size(); i++) {
            if (!resumes(inside.get(i), inside.get(i - 1))) return null;
        }
        BigDecimal soFar = BigDecimal.ZERO;
        for (Fact q : inside) soFar = soFar.add(q.value());
        return new Fact(inside.get(2).end().plusDays(1), year.end(),
                year.value().subtract(soFar));
    }

    /** Walk back from the latest quarter, taking the one that ends where this starts. */
    private static List<Fact> contiguousChainEndingLatest(Map<LocalDate, Fact> quarters) {
        List<Fact> sorted = new ArrayList<>(quarters.values());
        sorted.sort((a, b) -> a.end().compareTo(b.end()));

        List<Fact> chain = new ArrayList<>(4);
        chain.add(sorted.get(sorted.size() - 1));
        while (chain.size() < 4) {
            Fact head = chain.get(0);
            Fact previous = null;
            for (Fact candidate : sorted) {
                if (candidate.end().isBefore(head.end()) && resumes(head, candidate)
                        && (previous == null || candidate.end().isAfter(previous.end()))) {
                    previous = candidate;
                }
            }
            if (previous == null) break;
            chain.add(0, previous);
        }
        return chain;
    }

    /** Does {@code later} pick up where {@code earlier} left off, within the slack? */
    private static boolean resumes(Fact later, Fact earlier) {
        return Math.abs(ChronoUnit.DAYS.between(earlier.end().plusDays(1), later.start()))
                <= SLACK_DAYS;
    }

    private static boolean near(LocalDate a, LocalDate b) {
        return Math.abs(ChronoUnit.DAYS.between(a, b)) <= SLACK_DAYS;
    }
}
