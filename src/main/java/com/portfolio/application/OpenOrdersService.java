package com.portfolio.application;

import com.portfolio.ExcelReportWriter;
import com.portfolio.adapter.HoldingFileLocator;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.AssetClass;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.PriceStats;
import com.portfolio.domain.VolForecast;
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
     * Calibration record — one line per priced order per snapshot, on its own logger so it can be
     * routed to its own file (see {@code logback-spring.xml}) and left there without drowning the
     * application log.
     *
     * <p>The point is to be able to answer, months from now, whether orders shown at P=60% actually
     * filled about 60% of the time. That needs the inputs as well as the output, because a
     * probability is only judgeable against the model that produced it: {@code sigmaEff}, the
     * horizon it was priced over, the decay weight the blend used, and which source the σ came
     * from. Written as {@code key=value} pairs so a later analysis is a grep and a split, not a
     * parser.
     */
    private static final Logger calibration =
            LoggerFactory.getLogger("com.portfolio.calibration.touch");

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

    /**
     * Realized-daily-vol windows, in sessions. RV10/RV30 are displayed and bracket the headline
     * probability as a plausibility range; RV20 is <em>current state</em> and RV250 the
     * <em>long-run anchor</em> that {@link VolForecast} blends between (see that class for why the
     * scaling vol is a forecast rather than a trailing window of the same length as the horizon).
     */
    private static final int RV_SHORT = 10;
    private static final int RV_NOW = 20;
    private static final int RV_LONG = 30;
    private static final int RV_ANCHOR = 250;
    /** Horizon (sessions) for the fixed near-term touch column; the σ-distance column shares it. */
    private static final int TOUCH_HORIZON_SESSIONS = 5;
    /**
     * Upper bound on the user-supplied horizon for the {@code P(N)} column: about five years of
     * sessions, well past any resting order's life. The cap exists so a stray keystroke can't ask
     * for an absurd horizon, not because 1,250 sessions is a defensible modelling window — it
     * isn't (see {@link OpenOrder#LONG_HORIZON_SESSIONS}).
     */
    static final int MAX_HORIZON_SESSIONS = 1250;
    /**
     * The horizon {@code P(N)} is priced over, per row — <b>each order's own trading sessions to
     * expiry</b>, or {@link VolForecast.Config#gtcHorizonSessions()} when it has no dated one.
     *
     * <p>Sessions rather than calendar days because that is what the whole app counts, and what the
     * σ scaling consumes: {@code σ_h = σ·√sessions}, off bars that exist only on trading days. The
     * conversion from an order's calendar expiry happens once, in {@link #sessionsToExpiry}.
     *
     * <p>Per-order rather than one standing horizon for the table. The question a resting order
     * actually poses is whether it fills <em>before it lapses</em>, and that deadline is a property
     * of the order, not of the reader — a common horizon made the column comparable across rows at
     * the cost of answering nobody's question about any particular one. The comparison is not lost:
     * P(5d) is still the same window for every row, and the box in each cell still re-prices that
     * row over anything you type. A GTC order has no deadline of its own, so one has to be assumed
     * to price it at all, and that assumption is a parameter rather than a constant.
     */
    static final int DEFAULT_HORIZON_SESSIONS = VolForecast.Config.DEFAULT_GTC_HORIZON_SESSIONS;
    /**
     * Calendar-day lookback loaded per symbol for the daily-vol windows. Sized for the RV250
     * anchor: 250 sessions is about 350 calendar days, and the margin absorbs holidays and feed
     * gaps. RV250 is what makes the forecast a forecast, so an under-sized window here would
     * quietly reduce the whole model to a trailing RV20.
     */
    private static final int VOL_LOOKBACK_DAYS = 420;
    /**
     * Minimum daily returns behind the RV250 anchor before it is treated as a long-run estimate.
     * {@link PriceStats#dailyVol} takes whatever the series offers, so a name listed three months
     * ago would otherwise hand back a 60-session "anchor" that is really a second current-state
     * window — the blend would then revert toward today, which is the one thing it must not do.
     */
    private static final int MIN_ANCHOR_RETURNS = 120;

    /**
     * KV key for the stored drift setting — one app-wide switch, not a per-row or per-view one.
     *
     * <p>It lives on the service rather than in the view because every surface has to agree: the
     * table, the pre-trade probe, the {@code P(N)} re-price box, the Excel export and the
     * calibration log all resolve their config through {@link #effectiveConfig}, so a spreadsheet
     * can never be priced on a different model from the screen it was downloaded from.
     */
    static final String USE_DRIFT_KEY = "orders_use_drift";

    /** KV key for the stored event-variance setting — same one-switch-for-everything contract. */
    static final String USE_EVENT_VAR_KEY = "orders_use_event_var";

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
    private final Consumer<String> historyWarmer;
    /**
     * Scheduled events per symbol, for the event-variance term. Optional: an absent source means no
     * events are ever found, which is the same state as an unfetched calendar and is reported the
     * same way — unadjusted, never guessed.
     */
    private final OrderEventVariance eventVariance;
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
        this(fileLocator, parsers, fxRateProvider, excelReportWriter, outputDir, intraday, history,
                tickerMap, kv, quoteWarmer, symbol -> { });
    }

    /**
     * @param historyWarmer handed a symbol whose <em>daily</em> series is missing or stale, to
     *                      backfill off the nightly cycle. Separate from {@code quoteWarmer}
     *                      because it answers a different gap: a quote refresh is a one-minute bar,
     *                      while the volatility every probability is scaled by needs weeks of daily
     *                      closes. Only {@link #probe} uses it — a resting order's symbol is
     *                      published to the fetch universe and so is already covered, but an order
     *                      the user is only <em>considering</em> has told nothing about itself yet.
     *                      Expected to return immediately (the wiring hands it to a background
     *                      executor); the caller is told to reload rather than made to wait.
     */
    public OpenOrdersService(HoldingFileLocator fileLocator, List<OrderParser> parsers,
                             FxRateProvider fxRateProvider, ExcelReportWriter excelReportWriter,
                             Path outputDir, IntradayPriceRepository intraday,
                             PriceHistoryRepository history, YahooTickerMap tickerMap,
                             KeyValueStore kv, Consumer<Collection<String>> quoteWarmer,
                             Consumer<String> historyWarmer) {
        this(fileLocator, parsers, fxRateProvider, excelReportWriter, outputDir, intraday, history,
                tickerMap, kv, quoteWarmer, historyWarmer, null);
    }

    /**
     * @param eventVariance source of scheduled earnings dates for the {@code useEventVar} term.
     *                      Null means none are available, which resolves to an unadjusted
     *                      probability rather than an inferred date — see {@link OrderEventVariance}.
     */
    public OpenOrdersService(HoldingFileLocator fileLocator, List<OrderParser> parsers,
                             FxRateProvider fxRateProvider, ExcelReportWriter excelReportWriter,
                             Path outputDir, IntradayPriceRepository intraday,
                             PriceHistoryRepository history, YahooTickerMap tickerMap,
                             KeyValueStore kv, Consumer<Collection<String>> quoteWarmer,
                             Consumer<String> historyWarmer, OrderEventVariance eventVariance) {
        this.eventVariance = eventVariance;
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
        this.historyWarmer = historyWarmer;
    }

    /**
     * Writes the current open orders to a date-stamped {@code Open Orders-YYYYMMDD.xlsx} in the
     * output directory (defaults to ~/Documents — same place the portfolio exports land, not the
     * browser's download folder). Returns the written path, or {@link ExportResult#nothing()} when
     * no order files were found at all.
     *
     * <p>Re-parses through {@link #load()}, so the file carries the stored forecast setting the
     * screen is showing rather than the shipped default — a spreadsheet priced on a different model
     * from the panel it came from would be indistinguishable from one priced on the same.
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

    /** Whether the drifted first passage is switched on, from the stored setting. */
    public boolean useDrift() {
        return kv != null && kv.getBoolean(USE_DRIFT_KEY, false);
    }

    /** Store the drift setting. Takes effect on the next read of any surface — see {@link #USE_DRIFT_KEY}. */
    public void setUseDrift(boolean enabled) {
        if (kv != null) kv.putBoolean(USE_DRIFT_KEY, enabled);
    }

    /** Whether scheduled-event variance is switched on, from the stored setting. */
    public boolean useEventVar() {
        return kv != null && kv.getBoolean(USE_EVENT_VAR_KEY, false);
    }

    public void setUseEventVar(boolean enabled) {
        if (kv != null) kv.putBoolean(USE_EVENT_VAR_KEY, enabled);
    }

    /**
     * The forecast config actually in force, resolving <b>explicit request → stored setting →
     * shipped default</b>.
     *
     * <p>A null {@code useDrift} means "not stated", which is not the same as false — that is why
     * the resolution can't live in {@link VolForecast.Config#of}, where the domain has no store to
     * read. A request may still override the setting in either direction
     * ({@code ?useDrift=false} against a stored true), which is what keeps a one-off comparison
     * possible without touching the switch.
     *
     * <p>Null-safe on the store: several callers construct this service without one, and a missing
     * store means the shipped default rather than a failure.
     */
    public VolForecast.Config effectiveConfig(Double halfLifeDays, Integer gtcSessions,
                                             Boolean useDrift) {
        return effectiveConfig(halfLifeDays, gtcSessions, useDrift, null);
    }

    public VolForecast.Config effectiveConfig(Double halfLifeDays, Integer gtcSessions,
                                             Boolean useDrift, Boolean useEventVar) {
        boolean drift = useDrift != null ? useDrift : useDrift();
        boolean events = useEventVar != null ? useEventVar : useEventVar();
        return VolForecast.Config.of(halfLifeDays, gtcSessions, drift, events);
    }

    /** The panel's default view: the stored forecast setting, calibration records written. */
    public OrdersReport load() {
        return load(effectiveConfig(null, null, null));
    }

    /**
     * @param cfg volatility-forecast knobs — the mean-reversion half-life, the horizon assumed for
     *            a GTC order, and whether to apply drift. Exposed as query params so each can be
     *            swept without a rebuild; absent ones come from the stored setting or the shipped
     *            default (see {@link #effectiveConfig}).
     */
    public OrdersReport load(VolForecast.Config cfg) {
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
        Quotes quotes = enrichWithCurrentPrice(orders, rates, cfg, true);
        warnings.addAll(quotes.warnings());
        warmLaggingQuotes(quotes.warm());
        return new OrdersReport(orders, sources, warnings, orders.isEmpty() && sources.isEmpty(),
                unmappedInstruments(orders), quotes.notes(), quotes.asOf(), chartableSymbols(orders),
                cfg);
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
    private Quotes enrichWithCurrentPrice(List<OpenOrder> orders, Map<String, BigDecimal> rates,
                                          VolForecast.Config cfg, boolean recordCalibration) {
        Set<String> tickers = new LinkedHashSet<>();
        for (OpenOrder o : orders) {
            String tk = tickerFor(o.symbol());
            if (tk != null) tickers.add(tk);
        }
        if (tickers.isEmpty()) return Quotes.none();

        Map<String, IntradayPrice> latest = intraday.loadLatestQuotes(tickers);
        Map<String, VolForecast.Inputs> dailyVols = loadDailyVols(tickers);   // ticker → RV10/20/30/250
        Instant quoteCutoff = Instant.now().minus(Duration.ofDays(MAX_PRICE_AGE_DAYS));
        Map<String, String> stale = new LinkedHashMap<>();               // symbol → date last seen
        Map<String, String> notes = new LinkedHashMap<>();               // symbol → "as of …" label
        // Symbols whose probability could not be event-adjusted, and why. Surfaced rather than
        // silently left diffusive: an unadjusted figure the reader knows about is usable, one they
        // don't is a number that quietly understates a name with a print coming.
        Map<String, OrderEventVariance.Coverage> eventGaps = new LinkedHashMap<>();
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

            VolForecast.Inputs vols = dailyVols.getOrDefault(tk, VolForecast.Inputs.NONE);

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

            // Both touch columns go through VolForecast.touch, so each is scaled by the σ forecast
            // for *its own* horizon rather than by one trailing window shared across horizons. The
            // near-term column keeps its fixed 5 sessions — it is the one figure that stays
            // comparable across every row — and the σ→lim column is that same call's σ distance, so
            // P(5d) = 2·Φ(−|σ→lim|) remains checkable by eye on the row.
            // Drift, when it is switched on at all, is a property of what the instrument *is* —
            // resolved from the symbol, never from the ticker's own history.
            AssetClass assetClass = Instruments.assetClassOf(o.symbol());

            // Scheduled events are looked up once per row over the longest horizon this row will be
            // priced at, and VolForecast keeps whichever of them fall inside each individual
            // horizon — so the 5-session column and the P(N) column see the same event set and
            // disagree only about which of it is inside their own window, which is the truth.
            Integer toExpiry = sessionsToExpiry(o.expiry());
            int horizon = toExpiry != null ? toExpiry : cfg.gtcHorizonSessions();
            OrderEventVariance.Events events = cfg.useEventVar() && eventVariance != null
                    ? eventVariance.eventsFor(o.symbol(), horizon)
                    : OrderEventVariance.Events.NONE;
            VolForecast.Inputs withEvents = new VolForecast.Inputs(
                    vols.rv10(), vols.rv20(), vols.rv30(), vols.rv250(), events.events());

            VolForecast.Touch near = VolForecast.touch(withEvents, current, o.price(),
                    TOUCH_HORIZON_SESSIONS, cfg, assetClass);
            o = o.withVolatility(vols.rv10(), vols.rv20(), vols.rv30(), vols.rv250(),
                    near.sigmaToLimit(), near.probability());

            // P(N): the order's own deadline in trading sessions, or the GTC assumption when it has
            // none. The reader retunes any row from the box in the cell (GET /orders/touch), which
            // re-blends at the new horizon rather than rescaling a fixed σ.
            VolForecast.Touch far = VolForecast.touch(withEvents, current, o.price(), horizon,
                    cfg, assetClass);
            o = o.withHorizonTouch(far, toExpiry);
            orders.set(i, o);
            if (cfg.useEventVar() && !events.adjusted()
                    && events.coverage() != OrderEventVariance.Coverage.COMPLETE) {
                eventGaps.put(o.symbol(), events.coverage());
            }
            if (recordCalibration) recordTouch(o, far, assetClass, events);
        }
        String asOf = newestOverall == null ? null : TIME_FMT.format(
                newestOverall.atZone(ZoneId.systemDefault()));
        List<String> warnings = new ArrayList<>(eventGapWarnings(eventGaps));
        if (!stale.isEmpty()) {
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, String> e : stale.entrySet()) {
                lines.add(e.getKey() + " (last priced " + e.getValue() + ")");
            }
            log.warn("Open orders: price feed has gone quiet for {}", lines);
            warnings.add("No current price for " + String.join(", ", lines)
                    + " — nothing had been refreshing those feeds. They are in the fetch universe now; "
                    + "the price columns fill on the next tick.");
        }
        return new Quotes(warnings, notes, asOf, warm);
    }

    /**
     * Write one calibration record for a priced order. Silent for a row that could not be priced
     * (nothing to calibrate) and for the pre-trade probe, which prices orders that do not exist —
     * a hypothetical in the record would look exactly like a real order that never filled.
     *
     * <p>Broker, reference and the order's own dates are included so a record can be joined against
     * a later fill: the reference is the only field that identifies the order across snapshots.
     */
    private void recordTouch(OpenOrder o, VolForecast.Touch touch, AssetClass assetClass,
                             OrderEventVariance.Events events) {
        if (touch == null || touch.probability() == null) return;
        calibration.info(
                "broker={} symbol={} ref={} side={} limit={} current={} entered={} expiry={} "
                        + "sessions={} fromExpiry={} sigmaEffDiffusive={} sigmaEff={} decay={} "
                        + "volSource={} assetClass={} driftUsed={} muUsed={} nuUsed={} "
                        + "eventCount={} eventVarAdded={} eventCoverage={} "
                        + "rv10={} rv20={} rv30={} rv250={} p={} pLow={} pHigh={}",
                o.broker(), o.symbol(), o.reference(), o.side(), o.price(), o.currentPrice(),
                o.dateEntered(), o.expiry() != null ? o.expiry() : o.duration(),
                touch.sessions(), o.horizonFromExpiry(), touch.sigmaEffDiffusive(), touch.sigmaEff(),
                touch.decay(), touch.source(), assetClass, touch.drifted(), touch.drift(),
                touch.logDrift(), touch.eventCount(), touch.eventVariance(), events.coverage(),
                o.dailyVol10(), o.dailyVol20(), o.dailyVol30(), o.dailyVol250(),
                touch.probability(), touch.probabilityLow(), touch.probabilityHigh());
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
     * rather than a disk scan.
     *
     * <p>It takes the row's four realized-vol windows rather than one σ, because the horizon is
     * exactly what the forecast depends on: changing N re-blends current state toward the anchor
     * (see {@link VolForecast}) instead of rescaling a fixed σ by {@code √N}. Rescaling a fixed σ
     * is what a same-length trailing window would do, and it is the thing this change replaced.
     * The maths stays server-side so this column and the table's other one can never disagree;
     * {@code sessions=5} still reproduces the row's own P(5d) exactly.
     *
     * @param sessions trading sessions; clamped to {@code [1, MAX_HORIZON_SESSIONS]}.
     */
    public TouchEstimate touchEstimate(BigDecimal current, BigDecimal limit,
                                       VolForecast.Inputs vols, int sessions,
                                       VolForecast.Config cfg, String symbol) {
        int clamped = Math.max(1, Math.min(sessions, MAX_HORIZON_SESSIONS));
        // Resolving the asset class is a pure string predicate — no re-parse, no FX, no DB read —
        // so the endpoint stays the loopback calculation it was even with drift wired in. A null
        // config means the caller stated nothing, so the stored setting applies: that is what lets
        // the P(N) box follow the table's model without the browser having to know which it is.
        VolForecast.Touch t = VolForecast.touch(vols, current, limit, clamped,
                cfg == null ? effectiveConfig(null, null, null) : cfg,
                Instruments.assetClassOf(symbol));
        return new TouchEstimate(clamped, t.sigmaToLimit(), t.probability(),
                t.probabilityLow(), t.probabilityHigh(), t.sigmaEff(), t.decay(),
                t.source() == null ? null : t.source().name(),
                clamped > OpenOrder.LONG_HORIZON_SESSIONS);
    }

    /**
     * One re-priced horizon for the P(N) column: the headline probability, the RV10/RV30 range
     * around it, and the forecast that produced it.
     *
     * @param longHorizon true when the horizon is long enough that the driftless assumption is
     *                    doing real work — surfaced on screen rather than silently tolerated.
     */
    public record TouchEstimate(int sessions, BigDecimal sigma, BigDecimal probability,
                                BigDecimal probabilityLow, BigDecimal probabilityHigh,
                                BigDecimal sigmaEff, BigDecimal decay, String volSource,
                                boolean longHorizon) {
    }

    /** The four realized-vol windows carried on an enriched row, as the forecast's input record. */
    static VolForecast.Inputs volsOf(OpenOrder o) {
        return new VolForecast.Inputs(o.dailyVol10(), o.dailyVol20(), o.dailyVol30(), o.dailyVol250());
    }

    /**
     * Price a limit order that has not been placed yet: given a symbol and a limit price, return the
     * same live-price and volatility statistics the table reports for a resting order — current
     * price, distance to the limit, RV10/RV30, σ→limit, P(5d), P(20d) and the P(N) horizon column.
     *
     * <p>The touch probability is the number that says whether a limit is ambitious or lazy, and it
     * was only readable <em>after</em> the order existed at a broker — which is the wrong way round
     * for calibrating one. This answers the same question before the order is placed.
     *
     * <p>A hypothetical order is the same object as a real one, so it is built as an
     * {@link OpenOrder} and run through the <em>same</em> {@link #enrichWithCurrentPrice} pass the
     * parsed rows take, rather than through a parallel calculation. That is what makes "the same
     * seven statistics" true by construction instead of by inspection: the quote-age bound, the
     * pre/post-market labelling, the RV10→RV30 vol fallback and the first-passage maths are one
     * implementation, and {@code sessions=5} still reproduces P(5d) here exactly as it does there.
     *
     * <p>The limit is read in the instrument's <b>own quote currency</b>, with London pence folded
     * into pounds ({@code GBp} → GBP) — the same currency an AJ Bell row already prints, since that
     * parser normalises "53800p" to £538.00. Detecting it from the stored quote rather than asking
     * for it has a second effect worth having: the probe values a quote in its own currency, so no
     * FX rate enters the calculation at all and the whole thing is a pure disk read that can be
     * re-run on every keystroke of the limit box.
     *
     * <p>A symbol nobody holds, has ordered or watches has a cold feed — which is exactly the state
     * a pre-trade probe finds it in, and the reason it needs its own warming: {@link #load} publishes
     * a resting order's symbol to the fetch universe, but an order merely being considered has told
     * the app nothing. Both feeds are warmed in the background (quotes via the intraday job, daily
     * closes via {@link #historyWarmer}) and the caller is told to reload — the render never waits.
     *
     * @param side     "Buy" or "Sell"; anything else reads as Buy. It changes no statistic — the
     *                 touch probability is symmetric — only whether the limit is on the fill side of
     *                 the market right now (see {@link LimitProbe#immediateFill()}).
     * @param sessions horizon for the P(N) column in trading sessions; null takes the table's
     *                 standing default, and it is clamped to {@code [1, MAX_HORIZON_SESSIONS]}.
     */
    public LimitProbe probe(String symbol, BigDecimal limit, String side, Integer sessions) {
        return probe(symbol, limit, side, sessions, effectiveConfig(null, null, null));
    }

    public LimitProbe probe(String symbol, BigDecimal limit, String side, Integer sessions,
                            VolForecast.Config cfg) {
        String sym = symbol == null ? "" : symbol.trim().toUpperCase();
        // An order being considered has no expiry yet, so the GTC assumption is the natural default
        // here — the same one a placed GTC order would be priced over.
        int horizon = Math.max(1, Math.min(
                sessions == null ? cfg.gtcHorizonSessions() : sessions, MAX_HORIZON_SESSIONS));
        if (sym.isEmpty()) {
            return LimitProbe.unpriceable(sym, null, List.of("Enter a symbol to test a limit against."));
        }

        String ticker = tickerFor(sym);
        String ccy = quoteCurrency(ticker);
        if (ccy == null) {
            // Nothing has ever priced this ticker, so there is not even a currency to read the limit
            // in. A typo and a genuinely new instrument are indistinguishable from here, and both are
            // answered the same way: warm the feeds, name the ticker actually tried, and let the
            // reader either wait a few seconds or correct the mapping the panel already offers below.
            warmHistory(sym);
            warmQuote(sym, ticker);
            return LimitProbe.unpriceable(sym, ticker, List.of(
                    "Nothing has ever priced under " + ticker + ". If that is the right Yahoo ticker, "
                            + "a price fetch is queued now — reload in a few seconds. If it is not, "
                            + "map the symbol in the list below."));
        }

        List<OpenOrder> hypothetical = new ArrayList<>();
        hypothetical.add(OpenOrder.parsed("What-if", sym, null,
                "sell".equalsIgnoreCase(side) ? "Sell" : "Buy", "Limit",
                null, limit, false, null, ccy, null, null, null, null, null));

        // Map.of() rather than a live FX snapshot: the order's currency *is* the quote's currency,
        // so priceInCurrency never needs a rate. Keeping the network out of this path is what makes
        // re-running the probe as the limit is typed cost nothing.
        Quotes quotes = enrichWithCurrentPrice(hypothetical, Map.of(), cfg, false);
        warmLaggingQuotes(quotes.warm());

        OpenOrder o = hypothetical.get(0);
        // Re-price at the requested horizon. The enrichment pass has already priced this row over
        // the GTC default; anything else the reader asked for is the same call at another N.
        if (horizon != o.horizonSessions() && o.horizonSessions() != null) {
            o = o.withHorizonTouch(VolForecast.touch(volsOf(o), o.currentPrice(), o.price(), horizon,
                    cfg, Instruments.assetClassOf(sym)), null);
        }

        List<String> warnings = new ArrayList<>(quotes.warnings());
        if (o.currentPrice() == null && quotes.warnings().isEmpty()) {
            warmQuote(sym, ticker);
            warnings.add("No current price for " + ticker + " — the last stored quote is older than "
                    + MAX_PRICE_AGE_DAYS + " days. A fetch is queued; reload in a few seconds.");
        }
        if (o.sigmaEff() == null) {
            warmHistory(sym);
            warnings.add("No usable daily history for " + ticker + " over the last " + VOL_LOOKBACK_DAYS
                    + " days, so there is no volatility to scale a probability by. A backfill is "
                    + "queued; reload in a few seconds.");
        }
        if (limit == null || limit.signum() <= 0) {
            warnings.add("Enter a limit price above zero for the distance and touch probabilities.");
        }
        return new LimitProbe(sym, ticker, o, quotes.notes().get(sym), quotes.asOf(), warnings);
    }

    /**
     * The currency a limit on this instrument is naturally quoted in — the stored quote's own
     * currency, with London pence folded into pounds so the probe reads the way an AJ Bell order row
     * already does. Null when nothing has ever priced the ticker, which is the one state the probe
     * cannot compute anything from.
     *
     * <p>The daily fallback is deliberately <em>not</em> bounded by {@link #MAX_PRICE_AGE_DAYS}: a
     * bar too old to print as a price still says perfectly well what currency the listing trades in,
     * and using it here only means the probe knows how to read the user's limit. Whether there is a
     * <em>usable</em> price is decided downstream, by the shared enrichment, under the normal bound.
     */
    private String quoteCurrency(String ticker) {
        if (ticker == null) return null;
        String ccy = null;
        IntradayPrice ip = intraday.loadLatestQuotes(Set.of(ticker)).get(ticker);
        if (ip != null) ccy = ip.currency();
        if (ccy == null) {
            PriceBar bar = history.getPriceOn(ticker, LocalDate.now());
            if (bar != null) ccy = bar.currency();
        }
        if (ccy == null) return null;
        return "GBp".equals(ccy) ? "GBP" : ccy;
    }

    /** Queue an intraday quote fetch for a probed symbol, unless it is a gilt (not on Yahoo). */
    private void warmQuote(String symbol, String ticker) {
        if (ticker == null || Instruments.isBond(symbol)) return;
        warmLaggingQuotes(Set.of(ticker));
    }

    /**
     * Queue a daily-history backfill for a symbol whose volatility could not be computed. Shares
     * {@link #lastWarmed} with the quote warmer under a distinct key prefix, so a burst of probes on
     * one symbol — the limit box re-runs the calculation as it is typed into — costs one fetch
     * rather than one per keystroke.
     */
    private void warmHistory(String symbol) {
        if (symbol == null || symbol.isBlank() || Instruments.isBond(symbol)) return;
        String key = "history:" + symbol.toUpperCase();
        Instant now = Instant.now();
        Instant last = lastWarmed.get(key);
        if (last != null && last.isAfter(now.minus(WARM_COOLDOWN))) return;
        lastWarmed.put(key, now);
        log.info("Limit probe: backfilling daily history for {}", symbol);
        try {
            historyWarmer.accept(symbol);
        } catch (Exception e) {
            log.warn("Limit probe: daily-history warm failed for {}", symbol, e);
        }
    }

    /**
     * A limit order priced before it exists.
     *
     * @param hypothetical the synthetic order after the standard enrichment — null only when the
     *                     ticker has never been priced, so there is no currency to read the limit
     *                     in. Its broker reads "What-if" and it carries no quantity, date or status:
     *                     no statistic depends on those, and reusing the record rather than
     *                     inventing a parallel one is what keeps this panel and the orders table
     *                     from ever disagreeing about the same instrument.
     * @param priceNote    the "as of …" / "close …" / "pre-market …" label the table would print
     *                     beside the price, or null when it is a current regular-hours quote.
     */
    public record LimitProbe(String symbol, String ticker, OpenOrder hypothetical,
                             String priceNote, String quotesAsOf, List<String> warnings) {

        static LimitProbe unpriceable(String symbol, String ticker, List<String> warnings) {
            return new LimitProbe(symbol, ticker, null, null, null, warnings);
        }

        /** Whether a live price was found — without one, every column right of it is blank. */
        public boolean priced() {
            return hypothetical != null && hypothetical.currentPrice() != null;
        }

        /**
         * True when the limit already sits on the fill side of the market — a buy at or above it, a
         * sell at or below. That order is a market order in all but name: it executes on placement
         * at the market price or better, so its probability of <em>execution</em> is ~100%.
         *
         * <p>Worth surfacing loudly because the touch columns do not say that, and quietly read as
         * if they did. First passage is symmetric in |σ|, so a buy limit 1.26σ <em>above</em> the
         * market prints the same modest 20.6% a buy limit 1.26σ below it does — but the figure is
         * measuring the market travelling <em>up</em> to that price, which is not what has to happen
         * for this order to fill. Left unlabelled, a limit that fills instantly reads as a one-in-
         * five chance, which is the exact inverse of the truth.
         *
         * <p>Deliberately not {@link OpenOrder#throughLimit()}. That flag answers a different
         * question — "why is this resting order still open" — so it stands down outside regular
         * hours and allows a margin for II's fee-inflated estimated limit. Neither qualification
         * applies to a price the reader has just typed against the newest quote there is.
         */
        public boolean immediateFill() {
            if (!priced() || hypothetical.price() == null || hypothetical.price().signum() <= 0) {
                return false;
            }
            return "Buy".equalsIgnoreCase(hypothetical.side())
                    ? hypothetical.currentPrice().compareTo(hypothetical.price()) <= 0
                    : hypothetical.currentPrice().compareTo(hypothetical.price()) >= 0;
        }
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
    /**
     * Symbols whose probability could not be event-adjusted, phrased by *why* — the three states a
     * blank would conflate. Only raised when the event term is switched on: with it off, every row
     * is unadjusted by design and saying so on each would be noise.
     */
    private static List<String> eventGapWarnings(Map<String, OrderEventVariance.Coverage> gaps) {
        if (gaps.isEmpty()) return List.of();
        List<String> partial = new ArrayList<>();
        List<String> uncovered = new ArrayList<>();
        for (Map.Entry<String, OrderEventVariance.Coverage> e : gaps.entrySet()) {
            switch (e.getValue()) {
                case NOT_COVERED -> uncovered.add(e.getKey());
                case PARTIAL, NONE -> partial.add(e.getKey());
                default -> { }
            }
        }
        List<String> out = new ArrayList<>();
        if (!partial.isEmpty()) {
            log.info("Event variance: calendar incomplete for {} — probabilities left unadjusted", partial);
            out.add("No earnings date within the horizon for " + String.join(", ", partial)
                    + " — the calendar hasn't been fetched that far ahead yet, so those probabilities "
                    + "carry no event variance. A fetch is queued; reload in a few seconds.");
        }
        if (!uncovered.isEmpty()) {
            out.add("Earnings dates aren't available for " + String.join(", ", uncovered)
                    + " — the calendar feed is US-only, so these listings are never in it. Their "
                    + "probabilities are diffusive-only and will stay that way.");
        }
        return out;
    }

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
     * Realized daily σ (not annualised) over the four windows the forecast needs, from one daily
     * history read per symbol: RV10 and RV30 for display and the plausibility range, RV20 as
     * current state and RV250 as the long-run anchor. Any entry is null when the series can't
     * support it. Currency-free — volatility is a return ratio, so a {@code .L} pence series needs
     * no conversion.
     *
     * <p>The anchor is the one window with a quality bar on it. {@link PriceStats#dailyVol} shortens
     * its window to whatever the series offers rather than returning null, which is right for a
     * display column and wrong for an anchor: a name with four months of history would supply a
     * 60-session "RV250" that is really current state under another name, and the blend would then
     * revert toward the very thing it is supposed to be correcting. Below
     * {@link #MIN_ANCHOR_RETURNS} returns there is no anchor and {@code VolForecast} says so in the
     * source it reports.
     */
    private Map<String, VolForecast.Inputs> loadDailyVols(Set<String> tickers) {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(VOL_LOOKBACK_DAYS);
        Map<String, VolForecast.Inputs> out = new HashMap<>();
        for (String tk : tickers) {
            List<PriceBar> bars = history.getPriceHistory(tk, from, to);
            BigDecimal anchor = (bars != null && bars.size() > MIN_ANCHOR_RETURNS)
                    ? PriceStats.dailyVol(bars, RV_ANCHOR) : null;
            out.put(tk, new VolForecast.Inputs(
                    PriceStats.dailyVol(bars, RV_SHORT),
                    PriceStats.dailyVol(bars, RV_NOW),
                    PriceStats.dailyVol(bars, RV_LONG),
                    anchor));
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
        // Instruments.matchKey is the form the keyword is stored in and the form the parsers reduce
        // a description to, so a suggestion built any other way could save and never match.
        String[] words = Instruments.matchKey(name).split(" ");
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
    /**
     * @param config the forecast config this report was actually priced with — echoed so the view
     *               renders its own controls from server state and can never show a switch position
     *               the numbers beside it don't reflect. Same convention as {@code GET /swing}
     *               echoing its effective {@code SwingConfig}.
     */
    public record OrdersReport(List<OpenOrder> orders, List<String> sources,
                               List<String> warnings, boolean empty,
                               List<UnmappedInstrument> unmapped,
                               Map<String, String> priceNotes, String quotesAsOf,
                               Set<String> chartableSymbols,
                               VolForecast.Config config) {

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
