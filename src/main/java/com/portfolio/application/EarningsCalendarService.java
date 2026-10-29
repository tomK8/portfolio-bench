package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.model.EarningsEvent;
import com.portfolio.persistence.FundamentalsRepository;
import com.portfolio.persistence.EarningsCalendarRepository;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.port.EarningsCalendarFeed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Assembles the Earnings tab report — the market-wide reporting calendar over a
 * {@code [today−back, today+forward]} window with the user's own US holdings tagged.
 *
 * <p>Cache policy ({@link EarningsCalendarRepository}): a <b>past</b> date's roster is settled, so any
 * cached copy is used forever; <b>today/future</b> dates re-fetch once their cache ages past
 * {@link #REFRESH_TTL} (estimates and the roster still move as companies confirm). On a live-fetch
 * failure the service degrades to whatever is cached and adds a warning — never throws, matching the
 * read-degrades convention.
 *
 * <p>"My holdings" comes from the {@code held_symbols} KV set that {@code SyncPortfolioService}
 * persists after each /sync. Non-US listings never appear on the US calendar, so matching held
 * internal symbols (which equal their US tickers) against the feed's symbols is sufficient — no FX,
 * no ticker map.
 *
 * <p><b>The holdings section reads two sources, the calendar and then the fundamentals snapshot.</b>
 * The calendar is stronger — a session, a consensus estimate, and authority over the window — but
 * it is US-only and window-bounded, which between them left most rows blank. The snapshot's
 * per-symbol {@code calendarEvents} date ({@link FundamentalsRepository}, cached by the
 * quoteSummary refresh) fills those in as {@code EXPECTED}: no estimate, usually beyond the window,
 * and never overriding a calendar event. See {@link #holdingState} for the precedence and
 * {@code docs/earnings.md} for why the two stay separate feeds rather than merging.
 */
public class EarningsCalendarService {

    private static final Logger log = LoggerFactory.getLogger(EarningsCalendarService.class);

    /** Today/future rosters re-fetch once their cache is older than this; past dates cache forever. */
    static final Duration REFRESH_TTL = Duration.ofHours(3);

    /**
     * Guardrails so a stray query param can't fan out into dozens of live Nasdaq calls. The
     * forward bound is generous because the holdings section looks further ahead than the daily
     * tables — each extra date is one cached GET, and past dates cache forever.
     */
    static final int MAX_BACK = 14;
    static final int MAX_FORWARD = 45;
    /** Upper bound on {@link #warmForward}: beyond a year ahead no roster is published anyway. */
    static final int MAX_WARM_DAYS = 400;
    /** Politeness gap between calendar fetches on the warm path. */
    private static final long WARM_THROTTLE_MS = 200;

    /**
     * The feed's own clock. Whether a reporting date has finished is a question about the US market
     * day, not about ours, so {@link #settled} judges "did we ask after it passed" here rather than
     * in local time — otherwise a fetch in the London small hours would permanently mark a date
     * asked-after-settlement while New York was still in the previous evening.
     */
    private static final java.time.ZoneId FEED_ZONE = java.time.ZoneId.of("America/New_York");

    /** Default look-ahead for the holdings section, independent of the daily tables' window. */
    static final int DEFAULT_HOLDINGS_FORWARD = 21;

    /** Yahoo suffixes that mark a listing the US-only Nasdaq calendar will never carry. */
    private static final java.util.regex.Pattern NON_US_SUFFIX =
            java.util.regex.Pattern.compile(".*\\.[A-Z]{1,3}$");

    private final EarningsCalendarFeed feed;
    private final EarningsCalendarRepository repo;
    private final KeyValueStore keyValueStore;
    private final AllocationService allocationService;
    private final FundamentalsRepository fundamentalsRepo;
    private final YahooTickerMap tickerMap;

    public EarningsCalendarService(EarningsCalendarFeed feed,
                                   EarningsCalendarRepository repo,
                                   KeyValueStore keyValueStore,
                                   AllocationService allocationService,
                                   FundamentalsRepository fundamentalsRepo,
                                   YahooTickerMap tickerMap) {
        this.feed = feed;
        this.repo = repo;
        this.keyValueStore = keyValueStore;
        this.allocationService = allocationService;
        this.fundamentalsRepo = fundamentalsRepo;
        this.tickerMap = tickerMap;
    }

    /**
     * Build the calendar. {@code minMarketCap} (whole dollars, nullable) drops smaller names from the
     * per-day tables — the "major earnings" filter — but never from {@code myHoldings}.
     * {@code forceRefresh} bypasses the cache freshness check (the Refresh button) so even today's
     * cached roster is re-pulled.
     */
    public EarningsCalendarReport report(int back, int forward, Long minMarketCap, boolean forceRefresh) {
        return report(back, forward, DEFAULT_HOLDINGS_FORWARD, minMarketCap, forceRefresh);
    }

    /**
     * @param holdingsForward look-ahead for the holdings section only. Deliberately separate from
     *                        {@code forward}: the daily tables are a browsing view of the whole
     *                        market where a few days is plenty, while "when do my own positions
     *                        report" is worth a few weeks of notice.
     */
    public EarningsCalendarReport report(int back, int forward, int holdingsForward,
                                         Long minMarketCap, boolean forceRefresh) {
        int b = clamp(back, 0, MAX_BACK);
        int f = clamp(forward, 0, MAX_FORWARD);
        int hf = clamp(holdingsForward, 0, MAX_FORWARD);
        LocalDate today = LocalDate.now();
        Set<String> held = heldSymbols();
        List<String> warnings = new ArrayList<>();

        List<EarningsCalendarReport.Day> days = new ArrayList<>();
        List<EarningsEvent> mine = new ArrayList<>();

        // Scan far enough to serve whichever section reaches further; only the daily-table window
        // becomes a Day, so the longer holdings look-ahead doesn't bloat the calendar itself.
        for (int off = -b; off <= Math.max(f, hf); off++) {
            LocalDate date = today.plusDays(off);
            List<EarningsEvent> all = eventsFor(date, today, forceRefresh, warnings);

            // Held events are collected pre-filter (holdings show regardless of size).
            if (off <= hf) {
                for (EarningsEvent e : all) {
                    if (e.symbol() != null && held.contains(e.symbol().toUpperCase())) mine.add(e);
                }
            }
            if (off > f) continue;

            List<EarningsEvent> shown = new ArrayList<>();
            for (EarningsEvent e : all) {
                if (minMarketCap != null && (e.marketCap() == null || e.marketCap() < minMarketCap)) continue;
                shown.add(e);
            }
            shown.sort(BY_CAP_DESC);

            days.add(new EarningsCalendarReport.Day(
                    date,
                    date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                    relativeLabel(off),
                    shown.size(),
                    shown));
        }

        mine.sort(Comparator.comparing(EarningsEvent::date).thenComparing(BY_CAP_DESC));

        PositionSizes sizes = positionSizes(warnings);
        List<EarningsCalendarReport.HoldingRow> holdings =
                holdingRows(held, mine, sizes, today);

        List<String> heldList = new ArrayList<>(new TreeSet<>(held));
        return new EarningsCalendarReport(today, b, f, hf, minMarketCap, days, heldList, mine,
                holdings, sizes.totalGbp(), feed.sourceName(), warnings);
    }

    /**
     * Ranks every held position by size and says, for each, when it next reports and which source
     * knows that. Funds and bonds are dropped outright — they have no earnings, so listing them
     * would be noise.
     *
     * <p><b>Two sources, in order of strength.</b> The market calendar is asked first: it is the
     * only one that carries a session and a consensus estimate, and it is authoritative about the
     * window. When it has nothing, the fundamentals snapshot is consulted — the per-symbol
     * {@code calendarEvents} date the quoteSummary refresh already caches, read out of the
     * {@code loadAll()} map this method loads anyway, so the fallback costs no query and no fetch.
     *
     * <p>That fallback is what makes the section answer its own question. Before it, 39 of 42
     * rows here were blank: 22 US names the calendar covers but has nothing due for inside the
     * look-ahead, and 17 non-US listings it cannot see at all. The snapshot has a forward date for
     * 32 of those 42. It is applied to <b>both</b> groups rather than only to the non-US one that
     * prompted it — the cached date exists either way, and a table that dated BARC at 2.5% while
     * leaving GOOG at 5.7% blank would be drawing a line the data does not.
     */
    private List<EarningsCalendarReport.HoldingRow> holdingRows(
            Set<String> held, List<EarningsEvent> mine, PositionSizes sizes, LocalDate today) {

        Map<String, EarningsEvent> eventBySymbol = new LinkedHashMap<>();
        for (EarningsEvent e : mine) {
            if (e.symbol() != null) eventBySymbol.putIfAbsent(e.symbol().toUpperCase(), e);
        }

        // Prefer the ledger-derived position set — it carries sizes and is what the rest of the
        // dashboard shows. The KV set is the fallback when that snapshot is unavailable.
        Set<String> universe = new LinkedHashSet<>(held);
        universe.addAll(sizes.gbpBySymbol().keySet());

        Map<String, FundamentalsRepository.Cached> cache = fundamentalsRepo.loadAll();
        List<EarningsCalendarReport.HoldingRow> rows = new ArrayList<>();
        int notCovered = 0;
        int expected = 0;

        for (String symbol : universe) {
            if (Instruments.isBond(symbol)) continue;
            FundamentalsRepository.Cached c = cache.get(symbol);
            // A fund reports no earnings of its own. Its Yahoo ticker carries a non-US suffix, so
            // without this it would be mislabelled "not covered" rather than simply irrelevant.
            if (c != null && !c.quote().sectorWeights().isEmpty()) continue;

            BigDecimal gbp = sizes.gbpBySymbol().get(symbol);
            if (gbp != null && gbp.signum() <= 0) continue;

            EarningsEvent e = eventBySymbol.get(symbol.toUpperCase());
            // Only consulted when the calendar came back empty for this name — the stronger
            // source keeps its own fields, and no row ever carries two competing dates.
            LocalDate snapshot = e == null ? snapshotDate(c) : null;
            String state = holdingState(e != null, snapshot, today, isNonUsListing(symbol));
            if ("NOT_COVERED".equals(state)) notCovered++;
            if ("EXPECTED".equals(state)) expected++;

            rows.add(new EarningsCalendarReport.HoldingRow(
                    symbol,
                    // The calendar names the company when it dated the row; otherwise the
                    // snapshot does, so an EXPECTED row is not a column of dashes.
                    e == null ? snapshotLabel(c, "companyName") : e.companyName(),
                    e == null ? null : e.date(),
                    e == null ? null : e.time(),
                    e == null ? null : e.epsForecast(),
                    e == null ? null : e.numEstimates(),
                    e == null ? null : e.lastYearEps(),
                    gbp,
                    share(gbp, sizes.totalGbp()),
                    state,
                    snapshot,
                    snapshot == null ? null : snapshotDateEstimate(c)));
        }

        rows.sort(Comparator.comparing(
                (EarningsCalendarReport.HoldingRow r) ->
                        r.gbpValue() == null ? BigDecimal.ZERO : r.gbpValue()).reversed()
                .thenComparing(EarningsCalendarReport.HoldingRow::symbol));

        // No warning for the NOT_COVERED rows. The section names every one of them in its own
        // strip, with its weight and the last date we do have, which is strictly more than a count
        // could say — and a second banner repeating it would blunt the warnings area, where
        // everything else is an actual failure (a fetch that died, a snapshot that would not value).
        log.debug("Earnings holdings: {} row(s), {} expected from the fundamentals snapshot, "
                + "{} with no date at all", rows.size(), expected, notCovered);
        return rows;
    }

    /**
     * Which of the four states a row is in, given what each source knows. Pure and package-visible
     * so the precedence can be pinned directly: the calendar outranks the snapshot, a snapshot date
     * only counts while it is still ahead, and the listing's market is the tie-breaker between the
     * two kinds of silence.
     *
     * <p>A snapshot date that has already passed deliberately does <b>not</b> yield
     * {@code EXPECTED}. Yahoo leaves the last date it knows in place until it learns the next one,
     * so a passed date says "this is where the rhythm was", not "this is when it next reports" —
     * and a state that claimed otherwise would be exactly the blank-row conflation the states exist
     * to prevent. The date still travels with the row as context.
     */
    static String holdingState(boolean calendarEvent, LocalDate snapshotDate, LocalDate today,
                               boolean nonUsListing) {
        if (calendarEvent) return "REPORTING";
        if (snapshotDate != null && !snapshotDate.isBefore(today)) return "EXPECTED";
        return nonUsListing ? "NOT_COVERED" : "NONE_SCHEDULED";
    }

    /**
     * The next-earnings date cached on the fundamentals row, or null. Parsing degrades: a blob that
     * somehow holds a non-date costs this one row its date, never the tab.
     */
    private static LocalDate snapshotDate(FundamentalsRepository.Cached c) {
        String iso = snapshotLabel(c, "earningsDate");
        if (iso == null) return null;
        try {
            return LocalDate.parse(iso);
        } catch (java.time.format.DateTimeParseException ex) {
            log.warn("Unparseable cached earnings date {}", iso);
            return null;
        }
    }

    /** Yahoo's estimate flag for that date — null when it said nothing either way. */
    private static Boolean snapshotDateEstimate(FundamentalsRepository.Cached c) {
        String flag = snapshotLabel(c, "earningsDateEstimate");
        return flag == null ? null : Boolean.valueOf("true".equalsIgnoreCase(flag));
    }

    private static String snapshotLabel(FundamentalsRepository.Cached c, String key) {
        if (c == null || c.quote() == null || c.quote().labels() == null) return null;
        String v = c.quote().labels().get(key);
        return (v == null || v.isBlank()) ? null : v;
    }

    /**
     * Current position values, used to rank the holdings section. Degrades to empty on any failure
     * — an unavailable snapshot must cost the ranking, not the whole tab.
     */
    private PositionSizes positionSizes(List<String> warnings) {
        try {
            AllocationService.AllocationPoint p = allocationService.currentPoint();
            if (p == null) return PositionSizes.empty();
            return new PositionSizes(p.symbolGbp(), p.totalGbp());
        } catch (RuntimeException ex) {
            log.warn("Could not value positions for the earnings holdings section", ex);
            warnings.add("Position sizes unavailable — the holdings section is unranked.");
            return PositionSizes.empty();
        }
    }

    /**
     * Whether the symbol's Yahoo ticker is a non-US listing. A dotted suffix is what marks one
     * (.L London, .PA Paris, .AS Amsterdam, .DE Frankfurt); US tickers are plain. This correctly
     * leaves ASML <b>covered</b> — it maps to itself, i.e. the Nasdaq ADR line, which the calendar
     * does carry.
     */
    private boolean isNonUsListing(String symbol) {
        String ticker = tickerMap.tickerFor(symbol);
        return ticker != null && NON_US_SUFFIX.matcher(ticker.toUpperCase()).matches();
    }

    private static BigDecimal share(BigDecimal part, BigDecimal whole) {
        if (part == null || whole == null || whole.signum() == 0) return null;
        return part.divide(whole, java.math.MathContext.DECIMAL64)
                .setScale(6, java.math.RoundingMode.HALF_UP);
    }

    private record PositionSizes(Map<String, BigDecimal> gbpBySymbol, BigDecimal totalGbp) {
        static PositionSizes empty() {
            return new PositionSizes(Map.of(), null);
        }
    }

    /**
     * When each of {@code symbols} next reports, as far as the two cached sources know — the lookup
     * behind the earnings reminders.
     *
     * <p><b>Reads only what is already stored.</b> No fetch, no clamp, no window walk: this is
     * called from a background job that runs every half hour, and a hundred symbols behind a live
     * feed would be a hundred HTTP calls to answer a question that changes once a quarter. The
     * calendar cache is kept current by {@link #warmForward} on its own schedule, and the
     * fundamentals snapshot by its own four-hourly refresh.
     *
     * <p><b>Same precedence as the holdings section, for the same reason.</b> The market calendar
     * is asked first — it is the stronger source, carrying the session and the consensus estimate
     * — and the snapshot's {@code calendarEvents} date answers for everything it cannot see, which
     * is every non-US listing and every US name beyond the warmed window. A snapshot date that has
     * already passed is dropped rather than returned: Yahoo leaves the last date it knows in place
     * until it learns the next one, so a passed date is where the rhythm was, not a forward date.
     *
     * <p><b>Three outcomes, not two.</b> A dated symbol lands in {@link Lookup#dated}; one the two
     * sources cannot date lands in {@link Lookup#undated}, so the caller can name it rather than
     * absorb it. Funds and bonds land in <b>neither</b> — they report no earnings, so a reminder
     * about theirs could only be a reminder about nothing, and calling that a gap would bury the
     * real gaps under every gilt in the ledger. That is why the two lists come back separately
     * instead of being read off the map's absences: only this method knows which silence is which.
     *
     * @param lookaheadDays how far ahead to consider a cached calendar row; clamped to
     *                      {@link #MAX_FORWARD}
     */
    public Lookup nextEarnings(Collection<String> symbols, int lookaheadDays) {
        Map<String, NextEarnings> out = new LinkedHashMap<>();
        List<String> undated = new ArrayList<>();
        if (symbols == null || symbols.isEmpty()) return new Lookup(out, undated);

        LocalDate today = LocalDate.now();
        LocalDate horizon = today.plusDays(clamp(lookaheadDays, 0, MAX_FORWARD));
        Map<String, EarningsEvent> calendar = repo.earliestPerSymbol(today, horizon);
        Map<String, FundamentalsRepository.Cached> cache = fundamentalsRepo.loadAll();

        for (String raw : symbols) {
            if (raw == null || raw.isBlank()) continue;
            String symbol = raw.trim().toUpperCase();
            if (Instruments.isBond(symbol)) continue;
            FundamentalsRepository.Cached c = cache.get(symbol);
            // A non-empty sectorWeights is what marks a fund — the same test the holdings section
            // uses, and the reason a held ETF is not reported as an undated company.
            if (c != null && !c.quote().sectorWeights().isEmpty()) continue;

            EarningsEvent e = calendar.get(symbol);
            if (e != null) {
                out.put(symbol, new NextEarnings(symbol, e.companyName(), e.date(), e.time(),
                        "calendar", null));
                continue;
            }
            LocalDate snapshot = snapshotDate(c);
            if (snapshot != null && !snapshot.isBefore(today)) {
                out.put(symbol, new NextEarnings(symbol, snapshotLabel(c, "companyName"), snapshot,
                        null, "snapshot", snapshotDateEstimate(c)));
            } else {
                undated.add(symbol);
            }
        }
        undated.sort(null);
        return new Lookup(out, undated);
    }

    /**
     * Which of {@code symbols} reported in the last {@code lookbackDays}, and how it went — the
     * backward counterpart to {@link #nextEarnings}, for the reminders screen's review section.
     *
     * <p><b>A separate method, not a fourth outcome of the forward one.</b> "When does this next
     * report" and "did this just report" are two questions, and the forward lookup's contract is
     * already a careful three-way split between a date, a gap and a silence that is an answer.
     * Folding a past report into it would mean a row that is neither dated nor undated, and every
     * existing caller would have to learn to ignore it. The same reason the orders panel keeps
     * <i>open</i> and <i>resting</i> as two questions rather than one flag.
     *
     * <p><b>Cache only, like the forward lookup, and for the same reason</b> — this is read by a
     * half-hourly job, and a hundred symbols behind a live feed would be a hundred requests for a
     * fact that changes once a quarter. {@link #warmBack} is what keeps the window current.
     *
     * <p><b>Only the market calendar answers here.</b> The fundamentals snapshot is no use for this
     * question: it holds one date per symbol and leaves the last one it knows in place until it
     * learns the next, so it cannot distinguish "reported on Tuesday" from "last reported in July",
     * and it carries no figures to judge either way. A name this US-only feed does not cover simply
     * has no reported row, which is the honest answer rather than a guessed one.
     *
     * <p><b>A row with no figures is still a row.</b> Inside a window of dates that have all passed,
     * a cached roster with no actual is a report that has happened whose figures have not reached us
     * yet — the ordinary state in the hours after a print, before {@link #warmBack} next runs. That
     * is a different thing from not having reported, so the row is returned with a null actual and
     * the screen says which it is, rather than being dropped and looking like a company that never
     * reported.
     */
    public List<Reported> recentlyReported(Collection<String> symbols, int lookbackDays) {
        List<Reported> out = new ArrayList<>();
        if (symbols == null || symbols.isEmpty()) return out;

        LocalDate today = LocalDate.now();
        int days = clamp(lookbackDays, 0, MAX_BACK);
        if (days == 0) return out;
        // Strictly before today: a date still in progress has no settled figures, and the point of
        // the section is the report you could not read on the day it happened.
        Map<String, EarningsEvent> recent = repo.latestPerSymbol(today.minusDays(days), today.minusDays(1));
        if (recent.isEmpty()) return out;

        Set<String> wanted = new LinkedHashSet<>();
        for (String raw : symbols) {
            if (raw != null && !raw.isBlank()) wanted.add(raw.trim().toUpperCase());
        }
        for (String symbol : wanted) {
            if (Instruments.isBond(symbol)) continue;
            EarningsEvent e = recent.get(symbol);
            if (e == null) continue;
            out.add(new Reported(symbol, e.companyName(), e.date(), e.time(),
                    e.epsForecast(), e.numEstimates(), e.actualEps(), e.lastYearEps(),
                    surprisePct(e.actualEps(), e.epsForecast()),
                    ChronoUnit.DAYS.between(e.date(), today)));
        }
        // Most recent first: the print you have not reviewed yet is the one just gone.
        out.sort(Comparator.comparing(Reported::date).reversed()
                .thenComparing(Reported::symbol));
        return out;
    }

    /**
     * One report that has already happened, with what was expected of it beside what it delivered.
     *
     * <p>{@code surprisePct} is computed by {@link #surprisePct} rather than taken from the feed,
     * and is null wherever there was no consensus to measure against — a company no analyst covers
     * reported a figure, and that is all that can be said about it. {@code actualEps} is null while
     * the figures have not reached the cache yet, which the screen distinguishes from a company that
     * reported nothing measurable. Both nulls mean "we do not know", never zero.
     */
    public record Reported(String symbol, String companyName, LocalDate date, String time,
                           Double epsForecast, Integer numEstimates, Double actualEps,
                           Double lastYearEps, Double surprisePct, long daysAgo) {
    }

    /**
     * What the lookup could and could not answer, kept as two lists rather than one map with holes.
     *
     * <p>The split is the point. {@code undated} is a company the two sources have no forward date
     * for — a real gap, and the caller is expected to say so by name. A bond or a fund is in
     * <b>neither</b> list: it reports no earnings at all, so its silence is the correct answer and
     * reporting it as a gap would bury the genuine ones under every gilt in the ledger. Only the
     * method that knows which is which can draw that line, which is why the caller is handed the
     * answer instead of re-deriving it from the map's absences.
     */
    public record Lookup(Map<String, NextEarnings> dated, List<String> undated) {
    }

    /**
     * One symbol's next reporting date and which source knew it. {@code time} is the session
     * (BMO/AMC/TBD) and only the calendar carries one; {@code estimate} is Yahoo's flag for a day
     * it inferred from the past reporting rhythm rather than one the company announced, and only
     * the snapshot carries that. Each stays null on the other source rather than being filled in
     * with a plausible default — a weaker answer must not borrow the stronger one's geometry.
     */
    public record NextEarnings(String symbol, String companyName, LocalDate date, String time,
                               String source, Boolean estimate) {
    }

    /**
     * Ask the feed once more for each recently passed date whose cached roster has not settled —
     * what puts the reported figures in front of a reader who never opens the Earnings tab.
     *
     * <p>This exists because of the order the cache is normally filled in. A date is almost always
     * first fetched while it is still ahead, so what lands is the scheduled shape: a consensus and a
     * session, no actual. The figures only appear in the roster after the date has passed, which
     * means somebody has to ask again, once, afterwards. The Earnings tab does that for the two days
     * its own back window covers, but the reminders screen has no fetch of its own — it is read by a
     * half-hourly job — so without this the figures would arrive only for someone who happened to
     * browse the calendar.
     *
     * <p><b>Self-limiting rather than scheduled carefully.</b> {@link #settled} is content-based, so
     * a date that has given up its actuals is never asked again and neither is a weekend; the whole
     * pass costs at most {@code days} requests once and nothing on every run after that. That is why
     * it is safe to call on a plain repeating timer rather than needing to know when earnings season
     * is. Failures are logged and skipped — a date that could not be re-read stays unsettled and is
     * simply retried next time, which is the behaviour wanted.
     *
     * @return how many dates were actually re-fetched
     */
    public int warmBack(int calendarDays) {
        int days = clamp(calendarDays, 0, MAX_BACK);
        LocalDate today = LocalDate.now();
        int fetched = 0;
        for (int i = 1; i <= days; i++) {
            LocalDate date = today.minusDays(i);
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;   // no roster is ever published, and an unfetched weekend is not a gap
            }
            Instant fetchedAt = repo.lastFetched(date);
            List<EarningsEvent> cachedRows = fetchedAt == null ? List.of() : repo.load(date);
            if (fetchedAt != null && settled(cachedRows, date, fetchedAt)) continue;
            List<EarningsEvent> live = feed.forDate(date);
            if (live == null) {
                log.debug("Earnings calendar back-warm: fetch failed for {}", date);
                continue;
            }
            repo.save(date, mergeSettled(live, cachedRows));
            fetched++;
            try {
                Thread.sleep(WARM_THROTTLE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (fetched > 0) log.info("Earnings calendar back-warm: re-fetched {} settled date(s)", fetched);
        return fetched;
    }

    /**
     * Fill the per-date cache forward from today, for readers that need the calendar further out
     * than the tab shows — the open-orders event-variance term, which prices horizons of a quarter
     * or more and needs to know whether a print falls inside one.
     *
     * <p>Deliberately not bounded by {@link #MAX_FORWARD}: that clamp exists so a stray query param
     * can't fan the *tab* out, while this is an internal caller asking for a specific, bounded
     * window. It is bounded all the same ({@link #MAX_WARM_DAYS}) and throttled, because each date
     * is one HTTP GET — a quarter is about 65 weekday fetches on a cold cache, which is why this
     * belongs on a background thread and never on a render path.
     *
     * <p>Past dates cache forever and future ones for {@link #REFRESH_TTL}, so a second call the
     * same afternoon costs almost nothing. Failures are logged and skipped: a partial calendar is
     * reported as partial by the caller, not papered over.
     *
     * @return how many dates were actually fetched (as opposed to already cached)
     */
    public int warmForward(int calendarDays) {
        int days = Math.min(Math.max(calendarDays, 0), MAX_WARM_DAYS);
        LocalDate today = LocalDate.now();
        int fetched = 0;
        for (int i = 0; i <= days; i++) {
            LocalDate date = today.plusDays(i);
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;   // no roster is ever published, and an unfetched weekend is not a gap
            }
            Instant fetchedAt = repo.lastFetched(date);
            boolean fresh = fetchedAt != null
                    && Duration.between(fetchedAt, Instant.now()).compareTo(REFRESH_TTL) < 0;
            if (fresh) continue;
            List<EarningsEvent> live = feed.forDate(date);
            if (live == null) {
                log.debug("Earnings calendar warm: fetch failed for {}", date);
                continue;
            }
            // Merged, not swapped in, for the same reason eventsFor merges: a save that replaced
            // the date wholesale would drop any field the new roster happens not to carry.
            repo.save(date, mergeSettled(live, repo.load(date)));
            fetched++;
            try {
                Thread.sleep(WARM_THROTTLE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (fetched > 0) log.info("Earnings calendar warm: fetched {} date(s) forward", fetched);
        return fetched;
    }

    /**
     * Events for one date: served from cache when fresh, otherwise live-fetched and cached. A live
     * failure degrades to cached rows (with a warning) so the tab still renders last-known data.
     */
    private List<EarningsEvent> eventsFor(LocalDate date, LocalDate today, boolean forceRefresh,
                                          List<String> warnings) {
        Instant fetchedAt = repo.lastFetched(date);
        boolean cached = fetchedAt != null;
        List<EarningsEvent> cachedRows = cached ? repo.load(date) : List.of();
        boolean fresh = cached && !forceRefresh
                && (date.isBefore(today)
                    ? settled(cachedRows, date, fetchedAt)
                    : Duration.between(fetchedAt, Instant.now()).compareTo(REFRESH_TTL) < 0);
        if (fresh) return cachedRows;

        List<EarningsEvent> live = feed.forDate(date);
        if (live != null) {
            List<EarningsEvent> merged = mergeSettled(live, cachedRows);
            repo.save(date, merged);
            return merged;
        }
        // Live fetch failed — fall back to cache if we have it, else nothing for this day.
        warnings.add(cached
                ? date + ": live fetch failed — showing cached data (" + cachedRows.size() + ")"
                : date + ": live fetch failed and nothing cached");
        return cachedRows;
    }

    /**
     * Whether a <b>past</b> date's cached roster is finished — the test that replaced an
     * unconditional "any cached past date is fresh forever".
     *
     * <p>That rule was right about the roster and wrong about the figures. The feed publishes a date
     * in two shapes and flips the whole roster at once once the date has passed: a scheduled row
     * carries a consensus and a session, a settled one carries what was actually reported. Dates are
     * normally first fetched while they are still ahead — that is what {@link #warmForward} and the
     * tab's forward window do — so caching the first answer forever meant the actual figures could
     * never arrive for precisely the dates whose reports have happened.
     *
     * <p>Three ways a past date is done with, and the third is the one that bounds the retry:
     * <ol>
     *   <li><b>Its rows carry actuals.</b> The settled shape arrived; there is nothing further to
     *       learn. Any row is enough — the feed flips the whole date together, and on settled dates
     *       sampled here every row carried an actual.
     *   <li><b>It has no rows.</b> A weekend or a holiday publishes no roster, now and forever, so
     *       asking again could only ever cost a request. Without this clause every past weekend in
     *       the back window would be re-fetched on every load.
     *   <li><b>We already asked it after it passed.</b> Whatever came back is the feed's answer,
     *       including "still no actuals", and asking a second time would be asking the same question
     *       of the same data. This is what keeps a date the feed never settles from being re-fetched
     *       on every single page load — the one failure mode a freshness rule based on content
     *       rather than bookkeeping can have.
     * </ol>
     *
     * <p><b>"After it passed" is judged on the feed's own clock, not ours.</b> The flip happens on
     * the US market's day, so a fetch at 00:30 in London is still the previous evening in New York
     * and has <i>not</i> asked a settled date. Comparing in {@link #FEED_ZONE} is what stops a
     * late-night fetch from recording a permanent "already asked" against a date that had not
     * finished, which would lose that day's actuals for good.
     */
    static boolean settled(List<EarningsEvent> cachedRows, LocalDate date, Instant fetchedAt) {
        if (cachedRows == null || cachedRows.isEmpty()) return true;
        for (EarningsEvent e : cachedRows) {
            if (e.reported()) return true;
        }
        if (fetchedAt == null) return false;
        return fetchedAt.atZone(FEED_ZONE).toLocalDate().isAfter(date);
    }

    /**
     * Fold a freshly fetched roster over the one already cached for the same date, keeping the
     * fields each shape alone carries.
     *
     * <p>Needed because the two shapes are not nested: the settled row brings the actual figure but
     * drops the session to {@code time-not-supplied} and drops last year's EPS entirely. The cache
     * writes replace-per-date, deliberately, so that a company dropped from a roster disappears —
     * which means a straight save of a settled roster would also erase the {@code BMO}/{@code AMC}
     * marker for every row of a date we had already fetched. Losing the session is not a cosmetic
     * loss: it is the difference between "reported before this morning's open" and "reports after
     * tonight's close", which is the whole reason to look at a settled day at all.
     *
     * <p><b>The live roster stays authoritative about everything it actually says.</b> Membership is
     * live (so a dropped company still disappears), and so are the actual and the consensus — the
     * consensus on a settled row is the final one, which is the one a beat should be judged against.
     * Only a field the live row leaves empty is taken from the cached row, and only when the cached
     * row has it. Nothing is invented and nothing stronger is overwritten by something weaker.
     */
    static List<EarningsEvent> mergeSettled(List<EarningsEvent> live, List<EarningsEvent> cached) {
        if (live == null) return List.of();
        if (cached == null || cached.isEmpty()) return live;
        Map<String, EarningsEvent> before = new LinkedHashMap<>();
        for (EarningsEvent e : cached) {
            if (e.symbol() != null) before.putIfAbsent(e.symbol().toUpperCase(), e);
        }
        List<EarningsEvent> out = new ArrayList<>(live.size());
        for (EarningsEvent e : live) {
            EarningsEvent old = e.symbol() == null ? null : before.get(e.symbol().toUpperCase());
            if (old == null) {
                out.add(e);
                continue;
            }
            String time = isSession(e.time()) ? e.time() : (isSession(old.time()) ? old.time() : e.time());
            out.add(new EarningsEvent(
                    e.date(),
                    e.symbol(),
                    e.companyName() != null ? e.companyName() : old.companyName(),
                    time,
                    e.marketCap() != null ? e.marketCap() : old.marketCap(),
                    e.epsForecast() != null ? e.epsForecast() : old.epsForecast(),
                    e.numEstimates() != null ? e.numEstimates() : old.numEstimates(),
                    e.lastYearEps() != null ? e.lastYearEps() : old.lastYearEps(),
                    e.actualEps() != null ? e.actualEps() : old.actualEps()));
        }
        return out;
    }

    /** Whether a session marker says something — {@code "TBD"} is the feed declining to. */
    private static boolean isSession(String time) {
        return time != null && !time.isBlank() && !"TBD".equals(time);
    }

    /**
     * How far the reported figure came in against consensus, in percent — <b>computed here, never
     * read from the feed</b>.
     *
     * <p>The feed publishes its own surprise column and it cannot be used. Across 481 settled rows
     * sampled it contradicted the two numbers printed beside it twice — MAA reported 2.10 against a
     * 2.10 consensus and was labelled −65.71%, VKTX −1.55 against 1.00 was labelled −55% where the
     * arithmetic gives −255% — and on 31 more it was simply blank because the company had met
     * consensus to the cent, publishing a real zero as an absence. Both failures are ones this
     * codebase already has a rule for: a vendor ratio that disagrees with its own inputs is the
     * {@code P/B} case, and a measured zero rendered as "unknown" is the inverse of "absent is never
     * zero". So the division is done here, from the two figures the row shows, and is reproducible
     * from them.
     *
     * <p><b>Divided by the magnitude of the consensus, not its signed value.</b> A company expected
     * to lose 0.21 that loses 0.34 has done worse, and dividing by a negative forecast would call
     * that a 62% beat. Verified against the feed wherever its own figure is present and consistent.
     *
     * <p>Null when there is nothing to divide: no actual, no consensus, or a consensus of exactly
     * zero, where the difference is real but no percentage of it exists. A caller shows the two
     * figures regardless — the percentage is the convenience, not the evidence.
     */
    public static Double surprisePct(Double actual, Double forecast) {
        if (actual == null || forecast == null || forecast == 0.0) return null;
        return (actual - forecast) / Math.abs(forecast) * 100.0;
    }

    private Set<String> heldSymbols() {
        Set<String> out = new LinkedHashSet<>();
        for (String s : keyValueStore.getStringSet(PriceFetchSupport.HELD_SYMBOLS_KEY)) {
            if (s != null && !s.isBlank()) out.add(s.trim().toUpperCase());
        }
        return out;
    }

    private static final Comparator<EarningsEvent> BY_CAP_DESC = (a, c) -> {
        long ca = a.marketCap() == null ? Long.MIN_VALUE : a.marketCap();
        long cc = c.marketCap() == null ? Long.MIN_VALUE : c.marketCap();
        int byCap = Long.compare(cc, ca); // descending
        if (byCap != 0) return byCap;
        return nullSafe(a.symbol()).compareTo(nullSafe(c.symbol()));
    };

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String relativeLabel(int offset) {
        return switch (offset) {
            case 0 -> "Today";
            case -1 -> "Yesterday";
            case 1 -> "Tomorrow";
            default -> offset < 0 ? (-offset) + " days ago" : "In " + offset + " days";
        };
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
