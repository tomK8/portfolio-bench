package com.portfolio.application;

import com.portfolio.ExcelReportWriter;
import com.portfolio.adapter.HoldingFileLocator;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.PriceStats;
import com.portfolio.domain.model.IntradayPrice;
import com.portfolio.domain.model.MarketSession;
import com.portfolio.domain.model.OpenOrder;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.parser.OrderParser;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import com.portfolio.port.FxRateProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Read-only view of resting (open) orders across brokers. Locates the most recent order-status
 * export per broker in the input directory (same "newest file wins" rule as holdings), parses each
 * with its {@link OrderParser}, and keeps only orders whose status is non-terminal. Otherwise a pure
 * disk read — the one non-file dependency is a current FX snapshot, used to fill each order's GBP
 * amount (degrades to null + a warning if the fetch fails).
 */
public class OpenOrdersService {

    private static final Logger log = LoggerFactory.getLogger(OpenOrdersService.class);

    /**
     * Statuses that mean the order is no longer working. Everything else (In progress / PENDING /
     * Open / partially-executed variants) counts as open — a denylist so an unseen "still working"
     * status is shown rather than silently dropped.
     */
    private static final Set<String> TERMINAL = Set.of(
            "dealt", "executed", "filled", "part executed", "cancelled", "canceled",
            "expired", "rejected", "completed", "done", "settled");

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Quote-age labels are wall-clock times for the user, not ISO instants. */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * How old a stored price may be and still be printed as {@code Current}. Six calendar days
     * covers a long weekend plus a holiday on either side; past it the quote is reported as stale
     * rather than shown. Applies to the intraday quote <em>and</em> the daily-close fallback: an
     * unbounded fallback is what let a resting WMT order print a two-week-old $111.79 beside a
     * $115 market (2026-08-19), with the σ→limit and touch-probability columns computed off it.
     */
    private static final int MAX_PRICE_AGE_DAYS = 6;

    /** Key under which the resolved order symbols are published to the price-fetch universe. */
    public static final String ORDER_SYMBOLS_KEY = "order_symbols";

    /**
     * How far a quote may sit behind the newest quote from the same market before the row is
     * annotated and the symbol warmed on demand. Comparing against peers rather than against the
     * clock is what keeps this quiet out of hours — at 22:00 every US quote is hours old and none
     * of them is stale.
     *
     * <p>An hour, not the few minutes the polling cycle implies (~106 tickers at a 500ms throttle,
     * then a 60s wait). Yahoo writes a 1-minute bar only when something trades, so a thin ETF is
     * permanently "behind" its liquid peers — XWHS.L last printed at 16:13 against a 16:30 LSE
     * close and tripped a 15-minute rule every time the tab was opened, for a quote that was
     * perfectly current. The gap worth reporting is a feed that has stopped, not one nobody is
     * trading into.
     */
    private static final Duration QUOTE_LAG_TOLERANCE = Duration.ofMinutes(60);

    /**
     * How old an extended-hours quote may get before the symbol is queued for a warm re-fetch.
     *
     * <p>There is deliberately no <em>tolerance</em> for extended hours — no threshold above which
     * the row is called stale — because the peer comparison that works in regular hours cannot
     * work here. Pre/post volume is a fraction of regular volume, so sparse prints are the norm,
     * and any threshold is wrong in one of two directions: at 60 minutes MDLZ's perfectly ordinary
     * 72-minute pre-market gap read as a stalled feed, and at three hours the panel showed an
     * 88-minute-old $63.74 against Yahoo's $64.15 and said nothing at all. Instead every
     * extended-hours row states its quote time outright (see {@link #sessionNote}) and the reader
     * judges; this constant only governs how eagerly we go and look for something newer.
     */
    private static final Duration EXTENDED_HOURS_REFRESH_AFTER = Duration.ofMinutes(10);

    /** Don't re-warm the same symbol more often than this, however many times the tab is opened. */
    private static final Duration WARM_COOLDOWN = Duration.ofMinutes(5);

    /** Realized-daily-vol windows (in sessions): RV10 (short) + RV30 (long). */
    private static final int RV_SHORT = 10;
    private static final int RV_LONG = 30;
    /** Horizons (sessions) for the touch-probability columns; the σ-distance column uses the short one. */
    private static final int TOUCH_HORIZON_SESSIONS = 5;
    private static final int TOUCH_HORIZON_SESSIONS_LONG = 20;
    /**
     * Upper bound on the user-supplied horizon for the {@code P(N)} column: about five years of
     * sessions, well past any resting order's life. The cap exists so a stray keystroke can't ask
     * for an absurd horizon, not because 1,250 sessions is a defensible modelling window — it
     * isn't (see {@link OpenOrder#LONG_HORIZON_SESSIONS}).
     */
    static final int MAX_HORIZON_SESSIONS = 1250;
    /**
     * Horizon the {@code P(N)} column starts on, for every row — <b>trading sessions</b>, the same
     * unit as {@link #TOUCH_HORIZON_SESSIONS} and as the σ scaling itself.
     *
     * <p>Sessions rather than calendar days because that is what the whole app counts: the
     * watchlist's momentum windows are {@code closeNSessionsBefore}, its vol horizons are session
     * windows, and every strategy tab counts sessions. A calendar horizon here made {@code P(N)}
     * the one place in the codebase where a "day" meant something different from the two columns
     * beside it.
     *
     * <p>Deliberately uniform rather than each order's own time-to-expiry. The column's job is to
     * compare orders, and a horizon that varies per row compares nothing — a nearly-expired limit
     * reading 7% beside a long-dated one reading 64% says more about the two deadlines than about
     * the two markets. One horizon across the table makes the numbers commensurable, the way the
     * fixed P(5d)/P(20d) columns already are; the difference is that this one is editable, so a
     * specific order's own deadline is a keystroke away when that is the question (the view offers
     * it via {@link #sessionsToExpiry}). Sixty sessions — about a quarter — is long enough to be a
     * real question about a resting order and short enough that the estimate still means something.
     */
    static final int DEFAULT_HORIZON_SESSIONS = 60;
    /** Calendar-day lookback loaded per symbol to compute the daily-vol windows (≈ 60 sessions of margin). */
    private static final int VOL_LOOKBACK_DAYS = 90;

    /** Words taken from an instrument description when suggesting a match keyword. */
    private static final int KEYWORD_WORDS = 4;

    private final HoldingFileLocator fileLocator;
    private final List<OrderParser> parsers;
    private final FxRateProvider fxRateProvider;
    private final ExcelReportWriter excelReportWriter;
    private final Path outputDir;
    private final IntradayPriceRepository intraday;
    private final PriceHistoryRepository history;
    private final YahooTickerMap tickerMap;
    private final KeyValueStore kv;
    private final Consumer<Collection<String>> quoteWarmer;
    private final Map<String, Instant> lastWarmed = new ConcurrentHashMap<>();

    public OpenOrdersService(HoldingFileLocator fileLocator, List<OrderParser> parsers,
                             FxRateProvider fxRateProvider, ExcelReportWriter excelReportWriter,
                             Path outputDir, IntradayPriceRepository intraday,
                             PriceHistoryRepository history, YahooTickerMap tickerMap,
                             KeyValueStore kv) {
        this(fileLocator, parsers, fxRateProvider, excelReportWriter, outputDir, intraday, history,
                tickerMap, kv, tickers -> { });
    }

    /**
     * @param quoteWarmer handed the tickers whose quotes are visibly behind their market peers, to
     *                    fetch off the polling cycle. Expected to return immediately (the wiring
     *                    hands the work to a background executor) — the panel must render at disk
     *                    speed, and the freshly fetched price shows up on the next reload.
     */
    public OpenOrdersService(HoldingFileLocator fileLocator, List<OrderParser> parsers,
                             FxRateProvider fxRateProvider, ExcelReportWriter excelReportWriter,
                             Path outputDir, IntradayPriceRepository intraday,
                             PriceHistoryRepository history, YahooTickerMap tickerMap,
                             KeyValueStore kv, Consumer<Collection<String>> quoteWarmer) {
        this.fileLocator = fileLocator;
        this.parsers = parsers;
        this.fxRateProvider = fxRateProvider;
        this.excelReportWriter = excelReportWriter;
        this.outputDir = outputDir;
        this.intraday = intraday;
        this.history = history;
        this.tickerMap = tickerMap;
        this.kv = kv;
        this.quoteWarmer = quoteWarmer;
    }

    /**
     * Writes the current open orders to a date-stamped {@code Open Orders-YYYYMMDD.xlsx} in the
     * output directory (defaults to ~/Documents — same place the portfolio exports land, not the
     * browser's download folder). Returns the written path, or {@link ExportResult#nothing()} when
     * no order files were found at all.
     */
    public ExportResult export() {
        OrdersReport report = load();
        if (report.empty()) return ExportResult.nothing();
        try {
            Files.createDirectories(outputDir);
            Path file = outputDir.resolve("Open Orders-" + LocalDate.now().format(DATE_FMT) + ".xlsx");
            Files.write(file, excelReportWriter.ordersWorkbook(report.orders()));
            return new ExportResult(false, List.of(file.toString()));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write open-orders Excel to " + outputDir, e);
        }
    }

    public OrdersReport load() {
        List<OpenOrder> orders = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Map<String, BigDecimal> rates = Map.of();
        try {
            rates = fxRateProvider.fetchRates();
        } catch (Exception e) {
            warnings.add("FX rates unavailable — GBP amounts not shown (" + e.getMessage() + ")");
            log.warn("FX fetch failed for open-orders GBP conversion", e);
        }

        for (OrderParser parser : parsers) {
            Optional<Path> file;
            try {
                file = fileLocator.findMostRecent(parser::supports);
            } catch (Exception e) {
                warnings.add(parser.sourceName() + ": could not scan input directory (" + e.getMessage() + ")");
                log.warn("Order file scan failed for {}", parser.sourceName(), e);
                continue;
            }
            if (file.isEmpty()) continue;

            try {
                List<OpenOrder> open = new ArrayList<>();
                for (OpenOrder o : parser.parse(file.get())) {
                    if (isOpen(o.status())) open.add(o.withAmountGbp(toGbp(o.amount(), o.currency(), rates)));
                }
                open.sort(Comparator
                        .comparing(OpenOrder::dateEntered, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(o -> displayKey(o), Comparator.nullsLast(Comparator.naturalOrder())));
                orders.addAll(open);
                sources.add(parser.sourceName() + " — " + file.get().getFileName());
            } catch (Exception e) {
                warnings.add(parser.sourceName() + ": failed to parse " + file.get().getFileName()
                        + " (" + e.getMessage() + ")");
                log.warn("Order parse failed for {} ({})", parser.sourceName(), file.get(), e);
            }
        }

        publishOrderSymbols(orders, sources);
        Quotes quotes = enrichWithCurrentPrice(orders, rates);
        warnings.addAll(quotes.warnings());
        warmLaggingQuotes(quotes.warm());
        return new OrdersReport(orders, sources, warnings, orders.isEmpty() && sources.isEmpty(),
                unmappedInstruments(orders), quotes.notes(), quotes.asOf(), chartableSymbols(orders));
    }

    /**
     * Fill each order's live-price and volatility columns. Live price: {@link OpenOrder#currentPrice()}
     * (in the order's own currency) and the signed {@link OpenOrder#distancePct()} from the limit,
     * from the bulk-loaded latest intraday quote of <em>any</em> session (one query for all symbols),
     * falling back to the most recent stored daily close within {@link #DAILY_FALLBACK_DAYS}; a
     * listing quoted in a different currency (or {@code GBp} pence vs GBP pounds) is cross-converted
     * through the FX snapshot. Pre/post-market prints are used when they are the newest thing there
     * is — at 08:00 London a US limit is far better served by a live pre-market bid than by last
     * night's close — but they are always labelled on the row, and they never raise the
     * through-limit flag, since a resting regular-hours order does not fill in extended hours.
     * Both sources are age-bounded by {@link #MAX_PRICE_AGE_DAYS} — a resting order can name an
     * instrument that is neither held nor traded, so nothing else in the app keeps its feed warm,
     * and a stale quote printed as {@code Current} is worse than a blank: every column right of it
     * (vs limit, σ→lim, both touch probabilities) is derived from it. Stale symbols come back as
     * warnings for the caller to surface.
     * Volatility: realized daily σ over the {@link #RV_SHORT}/{@link #RV_LONG}-session windows (not
     * annualised) from the symbol's daily series, plus — when a limit and a live price both exist — the
     * signed {@link #TOUCH_HORIZON_SESSIONS}-session σ distance to the limit and the driftless
     * first-passage touch probability. σ figures are currency-free return ratios, so they need no FX.
     * Degrades silently — anything we can't compute stays null ("—").
     */
    private Quotes enrichWithCurrentPrice(List<OpenOrder> orders, Map<String, BigDecimal> rates) {
        Set<String> tickers = new LinkedHashSet<>();
        for (OpenOrder o : orders) {
            String tk = tickerFor(o.symbol());
            if (tk != null) tickers.add(tk);
        }
        if (tickers.isEmpty()) return Quotes.none();

        Map<String, IntradayPrice> latest = intraday.loadLatestQuotes(tickers);
        Map<String, BigDecimal[]> dailyVols = loadDailyVols(tickers);   // ticker → [RV10, RV30]
        Instant quoteCutoff = Instant.now().minus(Duration.ofDays(MAX_PRICE_AGE_DAYS));
        Map<String, String> stale = new LinkedHashMap<>();               // symbol → date last seen
        Map<String, String> notes = new LinkedHashMap<>();               // symbol → "as of …" label
        Set<String> warm = new LinkedHashSet<>();                        // tickers to fetch now
        // Newest quote per market *and session*, so "behind" is judged against the symbols trading
        // alongside it rather than against the wall clock: after the LSE close every GBp quote is
        // hours old and perfectly current, while a US name that stopped updating at lunchtime is
        // not. The session half of the key matters as soon as pre/post quotes are in play — only
        // the liquid US names print pre-market, so a perfectly current regular close would read as
        // fifteen hours behind its pre-market peers and be flagged and re-fetched every morning.
        Map<String, Instant> newestByMarket = new HashMap<>();
        Instant newestOverall = null;
        for (String tk : tickers) {
            IntradayPrice ip = latest.get(tk);
            if (ip == null || !ip.ts().isAfter(quoteCutoff)) continue;
            if (Instruments.isBond(tk)) continue;   // hourly gilt feed — not a peer for equity quotes
            newestByMarket.merge(peerKey(ip), ip.ts(), (a, b) -> a.isAfter(b) ? a : b);
            if (newestOverall == null || ip.ts().isAfter(newestOverall)) newestOverall = ip.ts();
        }

        for (int i = 0; i < orders.size(); i++) {
            OpenOrder o = orders.get(i);
            String tk = tickerFor(o.symbol());
            if (tk == null) continue;

            BigDecimal[] vols = dailyVols.getOrDefault(tk, new BigDecimal[]{null, null});
            BigDecimal dv10 = vols[0], dv30 = vols[1];

            BigDecimal rawClose = null;
            String rawCcy = null;
            String quoteSession = null;
            IntradayPrice ip = latest.get(tk);
            // Gilts are on their own feed (the hourly HL scrape, not Yahoo), so they are neither
            // compared against equity quotes — they would read as an hour behind all day — nor
            // handed to the intraday warmer, which has nothing to fetch them from.
            boolean yahooFed = !Instruments.isBond(o.symbol());
            if (ip != null && ip.ts().isAfter(quoteCutoff)) {
                rawClose = BigDecimal.valueOf(ip.close());
                rawCcy = ip.currency();
                // A pre/post-market print is a real, tradeable price and far more current at 08:00
                // than last night's close — but it is thin, wide-spread and not what a resting
                // regular-hours order transacts against, so it is always labelled.
                if (ip.session().isExtendedHours()) quoteSession = ip.session().name();
                boolean extended = ip.session().isExtendedHours();
                Instant peer = newestByMarket.get(peerKey(ip));
                boolean lagging = yahooFed && !extended && peer != null
                        && ip.ts().isBefore(peer.minus(QUOTE_LAG_TOLERANCE));
                String note = sessionNote(ip.session(), ip.ts(), lagging);
                if (note != null) notes.put(o.symbol(), note);
                // Regular hours: only a feed behind its peers is worth re-fetching. Extended hours:
                // go looking whenever the quote has some age on it, since there is no peer signal
                // and Yahoo publishes extended-hours minutes with a lag of its own.
                if (lagging || (extended && yahooFed
                        && ip.ts().isBefore(Instant.now().minus(EXTENDED_HOURS_REFRESH_AFTER)))) {
                    warm.add(tk);
                }
            } else {
                // No usable live quote: fall back to the daily close, say so on the row, and put the
                // symbol at the front of the fetch queue rather than waiting for the polling pass.
                if (yahooFed) warm.add(tk);
                PriceBar bar = latestDailyClose(tk);
                if (bar != null) {
                    rawClose = BigDecimal.valueOf(bar.close());
                    rawCcy = bar.currency();
                    notes.put(o.symbol(), "close " + bar.date());
                } else if (ip != null) {
                    stale.put(o.symbol(), ip.ts().atZone(ZoneId.systemDefault()).toLocalDate().toString());
                }
            }

            BigDecimal current = rawClose == null ? null : priceInCurrency(rawClose, rawCcy, o.currency(), rates);
            if (current != null) {
                BigDecimal distance = (o.price() != null && o.price().signum() != 0)
                        ? current.subtract(o.price()).divide(o.price(), 6, RoundingMode.HALF_UP)
                        : null;
                o = o.withCurrentPrice(current.setScale(4, RoundingMode.HALF_UP), distance, quoteSession);
            }

            // σ distance / touch prob need a limit and a live price; the short window is the near-term
            // vol estimate, falling back to the 30-session one when the 10-session window is too thin.
            // Both touch probabilities share one base vol — they differ only by the √horizon scaling.
            BigDecimal baseVol = dv10 != null ? dv10 : dv30;
            BigDecimal sigmaToLimit = PriceStats.sigmaDistance(current, o.price(), baseVol, TOUCH_HORIZON_SESSIONS);
            BigDecimal touchProb = PriceStats.touchProbability(sigmaToLimit);
            BigDecimal touchProb20 = PriceStats.touchProbability(
                    PriceStats.sigmaDistance(current, o.price(), baseVol, TOUCH_HORIZON_SESSIONS_LONG));
            o = o.withVolatility(dv10, dv30, sigmaToLimit, touchProb, touchProb20);

            // The third touch column: the same estimate as 5d/20d over a longer, *editable* horizon,
            // on the same session scale. Every row starts on DEFAULT_HORIZON_SESSIONS so the column
            // is comparable across orders; the reader retunes any row — to the order's own
            // sessions-to-expiry, or to anything else — from the box in the cell.
            int horizon = DEFAULT_HORIZON_SESSIONS;
            orders.set(i, o.withHorizonTouch(horizon,
                    PriceStats.touchProbability(
                            PriceStats.sigmaDistance(current, o.price(), baseVol, horizon)),
                    sessionsToExpiry(o.expiry())));
        }
        String asOf = newestOverall == null ? null : TIME_FMT.format(
                newestOverall.atZone(ZoneId.systemDefault()));
        if (stale.isEmpty()) return new Quotes(List.of(), notes, asOf, warm);
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> e : stale.entrySet()) {
            lines.add(e.getKey() + " (last priced " + e.getValue() + ")");
        }
        log.warn("Open orders: price feed has gone quiet for {}", lines);
        return new Quotes(List.of("No current price for " + String.join(", ", lines)
                + " — nothing had been refreshing those feeds. They are in the fetch universe now; "
                + "the price columns fill on the next tick."), notes, asOf, warm);
    }

    /**
     * Trading sessions from today to {@code expiry} — display only, so the view can offer the
     * order's own deadline as a horizon worth typing into the box ("expires 2026-08-28, about 41
     * sessions away"). Null for a GTC order and for an expiry today or already past: there is no
     * forward horizon left to name, and the Expiry column beside it is saying exactly that.
     *
     * <p>This is the only remaining caller of {@link PriceStats#tradingSessionsBetween} — the
     * probability itself never converts, because the box is already in sessions.
     */
    static Integer sessionsToExpiry(LocalDate expiry) {
        if (expiry == null) return null;
        int sessions = PriceStats.tradingSessionsBetween(LocalDate.now(), expiry);
        return sessions > 0 ? Math.min(sessions, MAX_HORIZON_SESSIONS) : null;
    }

    /**
     * Re-price the touch probability over an arbitrary horizon, for the editable sessions box on
     * the {@code P(N)} column. Pure arithmetic on numbers the view already has on the row — no
     * re-parse, no FX call, no database read — so typing into the box costs a loopback round trip
     * rather than a disk scan. The maths stays here rather than being reimplemented in the browser
     * so this column and the panel's other two touch columns can never disagree; now that the
     * horizon is in sessions, {@code sessions=5} reproduces the row's P(5d) exactly.
     *
     * @param sessions trading sessions; clamped to {@code [1, MAX_HORIZON_SESSIONS]}.
     */
    public TouchEstimate touchEstimate(BigDecimal current, BigDecimal limit, BigDecimal vol, int sessions) {
        int clamped = Math.max(1, Math.min(sessions, MAX_HORIZON_SESSIONS));
        BigDecimal sigma = PriceStats.sigmaDistance(current, limit, vol, clamped);
        return new TouchEstimate(clamped, sigma, PriceStats.touchProbability(sigma),
                clamped > OpenOrder.LONG_HORIZON_SESSIONS);
    }

    /**
     * One re-priced horizon for the P(N) column.
     *
     * @param longHorizon true when the horizon is long enough that the driftless assumption is
     *                    doing real work — surfaced on screen rather than silently tolerated.
     */
    public record TouchEstimate(int sessions, BigDecimal sigma, BigDecimal probability,
                                boolean longHorizon) {
    }

    /** What a quote's age reads as on the row: a time for today, a date for anything older. */
    private static String asOfLabel(Instant ts) {
        ZonedDateTime z = ts.atZone(ZoneId.systemDefault());
        return z.toLocalDate().equals(LocalDate.now())
                ? "as of " + TIME_FMT.format(z)
                : "as of " + z.toLocalDate();
    }

    /** Market grouping for the peer comparison — the quote's own currency, pence folded into pounds. */
    private static String market(String quoteCurrency) {
        return "GBp".equals(quoteCurrency) ? "GBP" : String.valueOf(quoteCurrency);
    }

    /** Peer bucket for the lag check: same market <em>and</em> same trading session. */
    private static String peerKey(IntradayPrice ip) {
        return market(ip.currency()) + "|" + ip.session().name();
    }

    /**
     * The row's price note.
     *
     * <p>An extended-hours quote <b>always</b> states its own time — "pre-market 13:58" — because
     * a sparse session is exactly where a price silently ages: MDLZ printed at 13:58 and then not
     * again for a quarter of an hour, and a row reading only "pre-market" gives the reader no way
     * to tell that from a live tick. Stating the time needs no threshold, is never a false alarm,
     * and answers the only question the reader actually has.
     *
     * <p>Regular-hours quotes stay bare unless they are behind their peers, which is what keeps
     * the column readable — a note on every row would say nothing. Those carry the existing
     * "as of …" label.
     */
    private static String sessionNote(MarketSession session, Instant ts, boolean lagging) {
        String base = switch (session) {
            case PRE -> "pre-market";
            case POST -> "after-hours";
            case REGULAR -> null;
        };
        if (base == null) return lagging ? asOfLabel(ts) : null;
        return base + " " + TIME_FMT.format(ts.atZone(ZoneId.systemDefault()));
    }

    /**
     * Hand the lagging tickers to the warmer, at most once per {@link #WARM_COOLDOWN} each. The
     * panel is re-fetched on every tab click, and a symbol Yahoo simply has no intraday data for
     * would otherwise queue a fetch on each one.
     */
    private void warmLaggingQuotes(Set<String> tickers) {
        if (tickers.isEmpty()) return;
        Instant now = Instant.now();
        List<String> due = new ArrayList<>();
        for (String tk : tickers) {
            Instant last = lastWarmed.get(tk);
            if (last == null || last.isBefore(now.minus(WARM_COOLDOWN))) {
                lastWarmed.put(tk, now);
                due.add(tk);
            }
        }
        if (due.isEmpty()) return;
        log.info("Open orders: warming lagging quotes for {}", due);
        try {
            quoteWarmer.accept(due);
        } catch (Exception e) {
            log.warn("Open orders: quote warm failed", e);
        }
    }

    /** Enrichment by-products the view needs: warnings, per-row age notes, the newest quote, and what to warm. */
    private record Quotes(List<String> warnings, Map<String, String> notes, String asOf, Set<String> warm) {
        static Quotes none() {
            return new Quotes(List.of(), Map.of(), null, Set.of());
        }
    }

    /**
     * Publish the order symbols so the price jobs keep their feeds warm. A resting order is often
     * on something neither held nor previously traded, so no other source ({@code held_symbols},
     * the ledger, the watchlist) names it — a WMT buy limit sat two weeks stale for exactly that
     * reason. Skipped when nothing parsed: an unreadable export must not read as "there are no
     * orders any more" and silently drop every symbol from the fetch set.
     */
    private void publishOrderSymbols(List<OpenOrder> orders, List<String> sources) {
        if (sources.isEmpty()) return;
        Set<String> symbols = new LinkedHashSet<>();
        for (OpenOrder o : orders) {
            // An unmapped AJ Bell row falls through carrying its description as the symbol; those
            // resolve to no ticker and would only pollute the fetch set.
            if (o.symbol() != null && tickerFor(o.symbol()) != null) symbols.add(o.symbol().toUpperCase());
        }
        kv.putStringSet(ORDER_SYMBOLS_KEY, symbols);
    }

    /**
     * Realized daily σ (not annualised) over the RV10 / RV30 windows for each ticker, from one daily
     * history read per symbol. Returns {@code [RV10, RV30]}; either entry is null when the series is
     * too short. Currency-free — volatility is a return ratio, so a {@code .L} pence series needs no
     * conversion.
     */
    private Map<String, BigDecimal[]> loadDailyVols(Set<String> tickers) {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(VOL_LOOKBACK_DAYS);
        Map<String, BigDecimal[]> out = new HashMap<>();
        for (String tk : tickers) {
            List<PriceBar> bars = history.getPriceHistory(tk, from, to);
            out.put(tk, new BigDecimal[]{
                    PriceStats.dailyVol(bars, RV_SHORT),
                    PriceStats.dailyVol(bars, RV_LONG)});
        }
        return out;
    }

    /** Yahoo ticker for an order symbol; null when the order carries no symbol. Bonds map to themselves. */
    private String tickerFor(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        return Instruments.isBond(symbol) ? symbol.toUpperCase() : tickerMap.tickerFor(symbol);
    }

    /** Most recent stored daily close for {@code ticker}, or null when there is none inside the age bound. */
    private PriceBar latestDailyClose(String ticker) {
        // getPriceOn is on-or-before, so one call already returns the newest bar there is — walking
        // back a day at a time only re-asked the same question, which is why the age bound has to be
        // applied to the answer. As a loop guard it never rejected anything, however old.
        LocalDate today = LocalDate.now();
        PriceBar bar = history.getPriceOn(ticker, today);
        if (bar == null || bar.date().isBefore(today.minusDays(MAX_PRICE_AGE_DAYS))) return null;
        return bar;
    }

    /**
     * Express a raw quote (in {@code rawCcy}) in {@code target} currency's major units. Handles the
     * {@code GBp} pence → GBP pounds shift, returns as-is when currencies already match, and otherwise
     * cross-converts via GBP using the FX snapshot (rates are units of foreign ccy per 1 GBP, so
     * native→GBP divides and GBP→target multiplies). Null when a needed rate is missing.
     */
    static BigDecimal priceInCurrency(BigDecimal raw, String rawCcy, String target,
                                      Map<String, BigDecimal> rates) {
        if (raw == null || rawCcy == null || target == null) return null;
        BigDecimal value = raw;
        String from = rawCcy;
        if ("GBp".equals(from)) {
            value = value.movePointLeft(2);
            from = "GBP";
        }
        String to = "GBp".equals(target) ? "GBP" : target;
        if (from.equals(to)) return value;

        BigDecimal gbp;
        if ("GBP".equals(from)) {
            gbp = value;
        } else {
            BigDecimal r = rates.get(from);
            if (r == null || r.signum() == 0) return null;
            gbp = value.divide(r, 10, RoundingMode.HALF_UP);
        }
        if ("GBP".equals(to)) return gbp;
        BigDecimal tr = rates.get(to);
        if (tr == null || tr.signum() == 0) return null;
        return gbp.multiply(tr);
    }

    /**
     * Symbols whose row opens the shared price popup on click — those that resolve to a ticker (or
     * are gilts, which map to themselves and are priced by the HL scrape). See
     * {@link OrdersReport#chartable(OpenOrder)} for why the unresolvable ones stay inert.
     */
    private Set<String> chartableSymbols(List<OpenOrder> orders) {
        Set<String> out = new LinkedHashSet<>();
        for (OpenOrder o : orders) {
            if (o.symbol() != null && tickerFor(o.symbol()) != null) out.add(o.symbol());
        }
        return out;
    }

    /**
     * The instruments this view could not price for want of a mapping.
     *
     * <p>Runs after enrichment, so "could not price" is the same fact the user is looking at: the
     * Current column is blank. That alone is not enough — a stale feed also blanks it — so a symbol
     * only qualifies when <em>no daily close has ever been stored</em> under its resolved ticker,
     * the same test {@code SyncPortfolioService.unpricedSymbols} applies. Bonds are excluded: gilts
     * are priced by their own scraper and have no Yahoo listing to map.
     */
    private List<UnmappedInstrument> unmappedInstruments(List<OpenOrder> orders) {
        Map<String, UnmappedInstrument> byKey = new LinkedHashMap<>();
        for (OpenOrder o : orders) {
            String sym = o.symbol();
            if (sym == null || sym.isBlank() || Instruments.isBond(sym)) continue;
            if (o.currentPrice() != null) continue;

            // The AJ Bell orders parser passes an unmapped description through as the symbol, so
            // symbol == name is exactly "this description matched no keyword". The other parsers
            // export a real ticker alongside a descriptive name, which never collide.
            boolean needsAlias = o.name() != null && o.name().equals(sym);

            String ticker = tickerFor(sym);
            if (!needsAlias && history.getLatestPriceDate(ticker) != null) continue;

            String key = needsAlias ? "name:" + o.name() : "sym:" + sym.toUpperCase();
            byKey.putIfAbsent(key, new UnmappedInstrument(
                    o.broker(),
                    o.name() != null ? o.name() : sym,
                    needsAlias ? null : sym,
                    needsAlias,
                    needsAlias ? suggestKeyword(o.name()) : null,
                    needsAlias ? null : suggestTicker(sym, o.currency()),
                    needsAlias ? null : ticker,
                    exchangeSuffix(o.currency())));
        }
        return List.copyOf(byKey.values());
    }

    /**
     * A match phrase for an instrument description: the first {@link #KEYWORD_WORDS} words,
     * lowercased and stripped of punctuation. Short on purpose — the same instrument is described
     * slightly differently in the orders export and the cash statement ("… ETF 1C GBP" vs the same
     * with an "ORD" tail), and one keyword should catch both. It is only a suggestion; the field is
     * editable.
     */
    static String suggestKeyword(String name) {
        if (name == null) return "";
        String[] words = name.toLowerCase().replaceAll("[^a-z0-9 ]", " ").trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(KEYWORD_WORDS, words.length); i++) {
            if (words[i].isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(words[i]);
        }
        return sb.toString();
    }

    /** A starting guess at the Yahoo ticker: London listings need the {@code .L} suffix. */
    static String suggestTicker(String symbol, String currency) {
        if (symbol == null) return "";
        String sym = symbol.trim().toUpperCase();
        if (sym.contains(".")) return sym;
        return sym + exchangeSuffix(currency);
    }

    /**
     * The exchange suffix the order's currency implies, so the dashboard can complete the ticker box
     * as the symbol is typed — the symbol isn't known server-side for an unmapped description.
     * Sterling means London here; a EUR order could be any of three exchanges, so it gets nothing
     * rather than a guess that would be wrong two thirds of the time.
     */
    static String exchangeSuffix(String currency) {
        return ("GBP".equals(currency) || "GBp".equals(currency)) ? ".L" : "";
    }

    private static String displayKey(OpenOrder o) {
        if (o.symbol() != null && !o.symbol().isBlank()) return o.symbol();
        return o.name();
    }

    static boolean isOpen(String status) {
        if (status == null || status.isBlank()) return true;   // unknown → show it
        return !TERMINAL.contains(status.trim().toLowerCase());
    }

    /**
     * Convert a native-currency amount to GBP. Rates are units of foreign currency per 1 GBP, so
     * native → GBP divides (the app-wide convention). Returns null when the amount, currency or a
     * usable rate is missing — the view then shows "—" rather than a wrong number.
     */
    static BigDecimal toGbp(BigDecimal amount, String currency, Map<String, BigDecimal> rates) {
        if (amount == null || currency == null) return null;
        if ("GBP".equals(currency)) return amount.setScale(2, RoundingMode.HALF_UP);
        if ("GBp".equals(currency)) return amount.movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
        BigDecimal rate = rates.get(currency);
        if (rate == null || rate.signum() == 0) return null;
        return amount.divide(rate, 2, RoundingMode.HALF_UP);
    }

    /**
     * Instruments on the order list that carry no usable mapping, so the dashboard can offer to add
     * one inline instead of asking for a source edit.
     *
     * <p>Two distinct gaps, which need different inputs from the user and so are distinguished here
     * rather than lumped into one "unmapped" flag:
     * <ul>
     *   <li><b>{@code needsAlias}</b> — the broker's export carries no ticker and the description
     *       matched no keyword, so the parser passed the description straight through as the symbol.
     *       Both a name mapping and a ticker mapping are missing.</li>
     *   <li>otherwise — the symbol is known but nothing has ever priced under its resolved ticker.
     *       Only the ticker mapping is missing (or the existing one is wrong).</li>
     * </ul>
     *
     * @param triedTicker the ticker actually resolved and looked up — for an unmapped symbol that is
     *                    the identity fallback, which is what has to change.
     */
    public record UnmappedInstrument(String broker, String name, String symbol, boolean needsAlias,
                                     String suggestedKeyword, String suggestedTicker,
                                     String triedTicker, String tickerSuffix) {
    }

    /**
     * @param empty true when no order file was found for any broker (distinct from "files found,
     *              but nothing open") so the view can show the right message.
     */
    /**
     * @param priceNotes symbol → "as of …" label for rows whose price isn't a current quote (a
     *                   daily close, or a quote well behind its market peers). Absent = current.
     * @param quotesAsOf newest quote across the table, as a local time; null when none was used.
     */
    public record OrdersReport(List<OpenOrder> orders, List<String> sources,
                               List<String> warnings, boolean empty,
                               List<UnmappedInstrument> unmapped,
                               Map<String, String> priceNotes, String quotesAsOf,
                               Set<String> chartableSymbols) {

        /**
         * Whether clicking this row can open the price popup. Only a symbol that resolves to a
         * ticker qualifies: an AJ Bell row whose description matched no keyword carries the whole
         * description as its symbol, and opening a chart for "XTRACKERS MSCI WORLD HEALTH CARE"
         * would fetch nothing and look like a broken feature rather than a missing mapping — which
         * the panel already offers to fix a few inches above.
         */
        public boolean chartable(OpenOrder o) {
            return o != null && o.symbol() != null && chartableSymbols.contains(o.symbol());
        }
    }
}
