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
import java.util.ArrayList;
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
            repo.save(date, live);
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
        boolean fresh = cached && !forceRefresh
                && (date.isBefore(today)
                    || Duration.between(fetchedAt, Instant.now()).compareTo(REFRESH_TTL) < 0);
        if (fresh) return repo.load(date);

        List<EarningsEvent> live = feed.forDate(date);
        if (live != null) {
            repo.save(date, live);
            return live;
        }
        // Live fetch failed — fall back to cache if we have it, else nothing for this day.
        List<EarningsEvent> fallback = cached ? repo.load(date) : List.of();
        warnings.add(cached
                ? date + ": live fetch failed — showing cached data (" + fallback.size() + ")"
                : date + ": live fetch failed and nothing cached");
        return fallback;
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
