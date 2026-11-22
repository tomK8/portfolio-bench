package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.CashLedgerReconstructor;
import com.portfolio.domain.CashLedgerReconstructor.Position;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.IntradayPrice;
import com.portfolio.domain.model.MarketState;
import com.portfolio.domain.model.PreviousClose;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.CashTransactionRepository.CashBalance;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.persistence.PriceHistoryRepository;
import com.portfolio.port.FxRateProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Snapshot for the Live tab: per-position last intraday quote, day change vs the prior
 * stored daily close, and aggregate totals. Stateless apart from a short FX rate cache
 * — the SSE controller calls {@link #snapshot()} every {@code TICK_SECONDS}, and
 * the FX endpoint shouldn't be poked that often.
 *
 * <p><b>Day change is measured against the feed's own previous close</b>, read from
 * {@code meta.previousClose} on the intraday response and held in {@link PreviousCloseCache}.
 * The feed states the number; nothing here deduces it. That matters because the obvious local
 * answer — the newest stored bar before the quote's trading day — is not a prior close at all
 * when the daily job is behind. On 2026-11-22 it had last run on the 18th, so EQQQ's baseline
 * was Monday's close against Friday's quote and the column read −6.40% for a −5.56% day;
 * every row's Day Δ equalled its 5d % because both landed on the same stale bar, and no
 * calendar distance can tell the two apart — a Monday quote is three days after Friday's close
 * whether the history is whole or four sessions short.
 *
 * <p><b>A baseline we cannot vouch for is no baseline.</b> With no usable figure from the feed
 * the stored close is used only when {@code price_history} already holds a bar for the quote's
 * own trading day: that bar is what proves the series runs right up to the quote, so the bar
 * before it is the prior session by construction rather than by assumption. It is how gilts
 * keep a Day Δ — their daily bar is upserted by the same hourly scrape that quotes them — and
 * how the 2026-08-20 MRNA +12.13% stays fixed, since the baseline is still the close before
 * {@link IntradayPrice#tradingDay()} and never a partial bar for the quote's own day. Otherwise
 * the cell is null and the symbol is named in {@code Snapshot.missingDayBaseline}, excluded
 * from the day totals as well as from its own row.
 *
 * <p>Baseline currency matches the listing currency in the intraday feed, so {@code GBp}
 * pence prices cancel cleanly when divided. The trailing 5d…6M columns are calendar-anchored
 * against the live quote, so they all include the current move and can never disagree with the
 * Day Δ column about how far the name has come.
 *
 * <p>Quotes come from {@link IntradayPriceRepository#loadLatestQuotes} — the newest bar of any
 * session — so a US name shows its pre-market print at 07:00 London rather than freezing on
 * last night's close. Each row reports which session its quote came from and what state the
 * listing's market is in, so the UI can label an extended-hours price instead of passing it off
 * as a regular one.
 */
public class LivePricesService {

    private static final Logger log = LoggerFactory.getLogger(LivePricesService.class);
    private static final Duration FX_TTL = Duration.ofMinutes(5);
    /** How recent a quote must be to imply a live market, when no published calendar is held. */
    private static final Duration QUOTE_LIVE_WINDOW = Duration.ofMinutes(60);

    private final CashTransactionRepository repo;
    private final IntradayPriceRepository intraday;
    private final PriceHistoryRepository history;
    private final FxRateProvider fxRateProvider;
    private final YahooTickerMap tickerMap;
    private final MarketHoursCache marketHours;
    private final PreviousCloseCache prevCloses;
    private final SectorGroupKeyProvider sectorKeys;

    private volatile Map<String, BigDecimal> cachedRates = Map.of();
    private volatile Instant cachedRatesAt = Instant.EPOCH;

    public LivePricesService(CashTransactionRepository repo,
                             IntradayPriceRepository intraday,
                             PriceHistoryRepository history,
                             FxRateProvider fxRateProvider,
                             YahooTickerMap tickerMap,
                             MarketHoursCache marketHours,
                             PreviousCloseCache prevCloses,
                             SectorGroupKeyProvider sectorKeys) {
        this.repo = repo;
        this.intraday = intraday;
        this.history = history;
        this.fxRateProvider = fxRateProvider;
        this.tickerMap = tickerMap;
        this.marketHours = marketHours;
        this.prevCloses = prevCloses;
        this.sectorKeys = sectorKeys;
    }

    public Snapshot snapshot() {
        Map<String, BigDecimal> rates = rates();

        List<CashTransaction> rows = repo.loadDividendTransactions();
        List<Position> positions = new CashLedgerReconstructor().reconstruct(rows);
        List<CashBalance> cashBalances = repo.latestCashBalances();

        Map<String, String> tickerBySymbol = new LinkedHashMap<>();
        for (Position p : positions) {
            String sym = p.securityId();
            if (sym == null || "CASH".equals(sym)) continue;
            String upper = sym.toUpperCase();
            String ticker = Instruments.isBond(sym) ? upper : tickerMap.tickerFor(sym);
            tickerBySymbol.putIfAbsent(upper, ticker);
        }
        Map<String, IntradayPrice> latestByTicker =
                intraday.loadLatestQuotes(tickerBySymbol.values());

        LocalDate today = LocalDate.now();
        // One bulk boundary query per date across every ticker, rather than a getPriceOn pair
        // per ticker per lookback: this runs on the 15s SSE tick, and the per-ticker form cost
        // (1 + 2×lookbacks) connections each time. Only the *base* of each window is loaded —
        // every window's near end is the live intraday quote, so today's move is inside all of
        // them (see PriceMomentum.changeFromLive).
        Set<String> tickers = new HashSet<>(tickerBySymbol.values());
        DayAnchors anchors = dayAnchors(tickers, latestByTicker, today);
        Map<String, PriceBar> base5 = history.loadPricesAsOf(tickers, today.minusDays(5));
        Map<String, PriceBar> base10 = history.loadPricesAsOf(tickers, today.minusDays(10));
        Map<String, PriceBar> base30 = history.loadPricesAsOf(tickers, today.minusDays(30));
        Map<String, PriceBar> base3m = history.loadPricesAsOf(tickers, today.minusMonths(3));
        Map<String, PriceBar> base6m = history.loadPricesAsOf(tickers, today.minusMonths(6));

        BigDecimal totalPosGbp = BigDecimal.ZERO;
        BigDecimal totalDayGbp = BigDecimal.ZERO;
        BigDecimal prevPosTotalGbp = BigDecimal.ZERO;
        List<Row> outRows = new ArrayList<>();
        List<String> noBaseline = new ArrayList<>();
        Instant latestTs = Instant.EPOCH;
        Instant now = Instant.now();

        for (Position p : positions) {
            String upper = p.securityId().toUpperCase();
            String ticker = tickerBySymbol.get(upper);
            if (ticker == null) continue;
            IntradayPrice ip = latestByTicker.get(ticker);
            if (ip == null) continue;
            DayBase base = anchors.baselineFor(ticker, prevCloses);
            if (base == null) noBaseline.add(p.securityId());

            Currency ccy = resolveCurrency(p, ip);
            BigDecimal lastGbp = realtimeGbp(p.securityId(), ccy, p.quantity(),
                    BigDecimal.valueOf(ip.close()), ip.currency(), rates);
            BigDecimal prevGbp = (base != null) ? realtimeGbp(p.securityId(), ccy, p.quantity(),
                    base.price(), base.currency(), rates) : null;
            BigDecimal lastPriceDisplay = priceForDisplay(
                    BigDecimal.valueOf(ip.close()), ip.currency(), ccy);

            BigDecimal dayChangeGbp = null;
            BigDecimal dayChangePct = null;
            if (prevGbp != null && prevGbp.signum() != 0 && lastGbp != null) {
                dayChangeGbp = lastGbp.subtract(prevGbp);
                dayChangePct = dayChangeGbp.divide(prevGbp, 6, RoundingMode.HALF_UP);
            }

            outRows.add(new Row(
                    p.securityId(),
                    ccy.getCurrencyCode(),
                    p.quantity().setScale(4, RoundingMode.HALF_UP),
                    scaleOrNull(lastPriceDisplay, 4),
                    scaleOrNull(dayChangePct, 6),
                    scaleOrNull(dayChangeGbp, 2),
                    scaleOrNull(lastGbp, 2),
                    formatAccounts(p.accounts()),
                    ip.ts(),
                    ip.session().name(),
                    marketStateOf(ticker, ip, now).name(),
                    PriceMomentum.changeFromLive(ip.close(), ip.currency(), base5.get(ticker)),
                    PriceMomentum.changeFromLive(ip.close(), ip.currency(), base10.get(ticker)),
                    PriceMomentum.changeFromLive(ip.close(), ip.currency(), base30.get(ticker)),
                    PriceMomentum.changeFromLive(ip.close(), ip.currency(), base3m.get(ticker)),
                    PriceMomentum.changeFromLive(ip.close(), ip.currency(), base6m.get(ticker))));
            if (lastGbp != null) totalPosGbp = totalPosGbp.add(lastGbp);
            if (prevGbp != null) prevPosTotalGbp = prevPosTotalGbp.add(prevGbp);
            if (dayChangeGbp != null) totalDayGbp = totalDayGbp.add(dayChangeGbp);
            if (ip.ts().isAfter(latestTs)) latestTs = ip.ts();
        }

        BigDecimal cashGbp = BigDecimal.ZERO;
        for (CashBalance cb : cashBalances) {
            cashGbp = cashGbp.add(cashToGbp(cb, rates));
        }

        outRows.sort(Comparator.comparing(
                (Row r) -> r.positionGbp() == null ? BigDecimal.ZERO : r.positionGbp()).reversed());

        BigDecimal totalGbp = totalPosGbp.add(cashGbp);
        BigDecimal dayChangePct = (prevPosTotalGbp.signum() != 0)
                ? totalDayGbp.divide(prevPosTotalGbp, 6, RoundingMode.HALF_UP) : null;
        Totals totals = new Totals(
                totalPosGbp.setScale(2, RoundingMode.HALF_UP),
                cashGbp.setScale(2, RoundingMode.HALF_UP),
                totalGbp.setScale(2, RoundingMode.HALF_UP),
                totalDayGbp.setScale(2, RoundingMode.HALF_UP),
                scaleOrNull(dayChangePct, 6));

        return new Snapshot(outRows, totals, Instant.now(),
                latestTs == Instant.EPOCH ? null : latestTs,
                sectorGroups(outRows), false, List.copyOf(noBaseline));
    }

    /**
     * Sector groups for the current rows, shipped on <b>every</b> snapshot whether anyone has the
     * Live table grouped or not.
     *
     * <p>That is forced by the transport, not laziness: {@code /live/stream} broadcasts one
     * snapshot to every connected emitter, so there is no per-client selection a query parameter
     * could carry without giving each emitter its own state. Computing it once for everybody is
     * both simpler and cheaper than that, and it keeps the weighted arithmetic on the server —
     * the browser only chooses whether to draw the header rows. The cost is a classification pass
     * over ~60 rows against an in-memory map; the fundamentals cache behind it is TTL-held by
     * {@link SectorGroupKeyProvider} precisely so this can run on a 15-second tick.
     *
     * <p>Degrades to no groups: a failure here must not cost the user their price table. The
     * dropdown then simply has nothing to show, which is the same state as an empty portfolio.
     */
    private List<LiveGroup> sectorGroups(List<Row> rows) {
        if (rows.isEmpty() || sectorKeys == null) return List.of();
        try {
            return LiveGrouper.group(rows, LiveGrouper.sectorKey(sectorKeys.get()));
        } catch (RuntimeException e) {
            log.warn("Live: sector grouping failed, serving ungrouped rows", e);
            return List.of();
        }
    }

    /** One Day Δ baseline: a price in the listing currency it was quoted in. */
    private record DayBase(BigDecimal price, String currency) {
    }

    /**
     * What {@code price_history} can offer as a Day Δ baseline, and for which tickers it may be
     * believed.
     *
     * @param prior      the last stored close strictly before each quote's own trading day
     * @param reachesDay the tickers whose stored history holds a bar <em>for</em> that trading
     *                   day — the only ones whose {@code prior} is demonstrably the session
     *                   before the quote rather than the far side of a gap in the data
     */
    private record DayAnchors(Map<String, PriceBar> prior, Set<String> reachesDay) {

        /**
         * The baseline for one ticker: the feed's own previous close, else the stored close when
         * the history vouches for it, else none at all.
         *
         * <p>The order is not a preference between two equal sources. {@code meta.previousClose}
         * is the exchange's figure for the session before the one being quoted and arrives with
         * every intraday pass, so it is right even mid-session, when no daily bar for today
         * exists yet and the stored path has nothing to say.
         */
        DayBase baselineFor(String ticker, PreviousCloseCache cache) {
            PreviousClose fed = (cache == null) ? null : cache.get(ticker);
            if (fed != null) return new DayBase(BigDecimal.valueOf(fed.close()), fed.currency());
            PriceBar stored = prior.get(ticker);
            if (stored == null || !reachesDay.contains(ticker)) return null;
            return new DayBase(BigDecimal.valueOf(stored.close()), stored.currency());
        }
    }

    /**
     * The stored side of the Day Δ baseline: the last daily close strictly before each quote's
     * own trading day, plus which of those closes the history is complete enough to vouch for.
     *
     * <p>Grouped by trading day rather than queried per ticker: the whole book spans two or three
     * distinct sessions at most (a London morning has US names on yesterday's session and LSE
     * names on today's), so this is a couple of bulk queries on the 15s SSE tick, not one per
     * position. Tickers with no quote fall back to the calendar boundary so a position still
     * gets a baseline.
     *
     * <p>The second query is what separates "the prior session" from "the last bar we happen to
     * hold". {@code loadPricesAsOf} answers "newest bar at or before this date" and so always
     * answers <em>something</em>; asking it for the trading day itself and checking the bar
     * really is dated that day proves the series runs up to the quote, which makes the bar
     * before it the prior session with nothing inferred.
     */
    private DayAnchors dayAnchors(Set<String> tickers,
                                  Map<String, IntradayPrice> quotes,
                                  LocalDate today) {
        Map<LocalDate, Set<String>> byTradingDay = new LinkedHashMap<>();
        for (String t : tickers) {
            IntradayPrice ip = quotes.get(t);
            LocalDate day = (ip != null) ? ip.tradingDay() : today;
            byTradingDay.computeIfAbsent(day, d -> new HashSet<>()).add(t);
        }
        Map<String, PriceBar> prior = new java.util.HashMap<>();
        Set<String> reachesDay = new HashSet<>();
        for (Map.Entry<LocalDate, Set<String>> e : byTradingDay.entrySet()) {
            LocalDate day = e.getKey();
            prior.putAll(history.loadPricesAsOf(e.getValue(), day.minusDays(1)));
            history.loadPricesAsOf(e.getValue(), day).forEach((ticker, bar) -> {
                if (day.equals(bar.date())) reachesDay.add(ticker);
            });
        }
        return new DayAnchors(prior, reachesDay);
    }

    /**
     * What the listing's market is doing right now. The published calendar is authoritative when
     * we hold one; without it (cold start, or a gilt, which has no Yahoo calendar) fall back to
     * quote age. The fallback is deliberately generous — Yahoo writes a 1-minute bar only when
     * something trades, and a thin name can go the better part of an hour between prints
     * mid-session, so a tight threshold would keep declaring open markets shut (the same trap
     * the Open-orders staleness rule hit with XWHS.L).
     */
    private MarketState marketStateOf(String ticker, IntradayPrice ip, Instant now) {
        // Gilts are scraped hourly from HL, not quoted by Yahoo, so they have neither a published
        // calendar nor a quote cadence the age fallback can read: a bar 57 minutes old would say
        // OPEN and the same bar three minutes later would say CLOSED, flickering the row tint on
        // a price that never moved. Better to declare the state unknown and shade nothing.
        if (Instruments.isBond(ticker)) return MarketState.UNKNOWN;
        MarketState published = marketHours.stateAt(ticker, now);
        if (published != MarketState.UNKNOWN) return published;
        boolean recent = Duration.between(ip.ts(), now).compareTo(QUOTE_LIVE_WINDOW) < 0;
        if (!recent) return MarketState.CLOSED;
        return switch (ip.session()) {
            case PRE -> MarketState.PRE;
            case POST -> MarketState.POST;
            case REGULAR -> MarketState.OPEN;
        };
    }

    private synchronized Map<String, BigDecimal> rates() {
        if (!cachedRates.isEmpty()
                && Duration.between(cachedRatesAt, Instant.now()).compareTo(FX_TTL) < 0) {
            return cachedRates;
        }
        try {
            cachedRates = fxRateProvider.fetchRates();
            cachedRatesAt = Instant.now();
        } catch (Exception e) {
            log.warn("Live: FX fetch failed, using cached rates", e);
        }
        return cachedRates;
    }

    private static BigDecimal scaleOrNull(BigDecimal v, int scale) {
        return v == null ? null : v.setScale(scale, RoundingMode.HALF_UP);
    }

    private static Currency resolveCurrency(Position p, IntradayPrice ip) {
        String c = ip.currency();
        if ("GBp".equals(c)) return Currency.getInstance("GBP");
        try {
            return Currency.getInstance(c);
        } catch (Exception ignored) {
            return Currency.getInstance(p.tradeCurrency());
        }
    }

    private static BigDecimal priceForDisplay(BigDecimal raw, String rawCcy, Currency displayCcy) {
        if ("GBp".equals(rawCcy) && "GBP".equals(displayCcy.getCurrencyCode())) {
            return raw.movePointLeft(2);
        }
        return raw;
    }

    private static BigDecimal realtimeGbp(String securityId, Currency ccy, BigDecimal qty,
                                          BigDecimal rawPrice, String rawCcy,
                                          Map<String, BigDecimal> rates) {
        BigDecimal price = priceForDisplay(rawPrice, rawCcy, ccy);
        BigDecimal nativeAmt = Instruments.isBond(securityId)
                ? price.multiply(qty).movePointLeft(2)
                : price.multiply(qty);
        if ("GBP".equals(ccy.getCurrencyCode())) return nativeAmt;
        BigDecimal rate = rates.get(ccy.getCurrencyCode());
        return (rate == null || rate.signum() == 0)
                ? null : nativeAmt.divide(rate, 10, RoundingMode.HALF_UP);
    }

    private static BigDecimal cashToGbp(CashBalance cb, Map<String, BigDecimal> rates) {
        if ("GBP".equals(cb.currency()) || cb.cashNative() == null) {
            return BigDecimal.valueOf(cb.cashGbp());
        }
        BigDecimal rate = rates.get(cb.currency());
        return (rate != null && rate.signum() != 0)
                ? BigDecimal.valueOf(cb.cashNative()).divide(rate, 10, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(cb.cashGbp());
    }

    private static String formatAccounts(Collection<String> accounts) {
        StringBuilder sb = new StringBuilder();
        for (String a : accounts) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(Account.displayNameOf(a));
        }
        return sb.toString();
    }

    public record Snapshot(
            List<Row> rows,
            Totals totals,
            Instant generatedAt,
            Instant latestPriceAt,
            /**
             * Sector groups over {@code rows}, always populated (see {@code sectorGroups}). The
             * client renders them only when its own Group-by dropdown asks for them.
             */
            List<LiveGroup> groups,
            /**
             * True while {@code CatchUpService} is refetching after a dormant spell, so the tab can
             * say the figures are still arriving. Set by the controller on the way out rather than
             * computed here: it is a fact about the process, not about the prices in this snapshot.
             */
            boolean catchingUp,
            /**
             * Symbols whose day move could not be established, named rather than absorbed. Their
             * rows still carry a price and every trailing column — only the two Day Δ cells are
             * blank, and the day totals are struck without them, so the tile stays the sum of
             * what is on screen.
             */
            List<String> missingDayBaseline) {

        /**
         * Back-compatible form for callers with no grouping to supply — the Excel export builds
         * its sheet from the rows alone, and a grouped spreadsheet would be worse than a flat one
         * a reader can pivot.
         */
        public Snapshot(List<Row> rows, Totals totals, Instant generatedAt, Instant latestPriceAt) {
            this(rows, totals, generatedAt, latestPriceAt, List.of());
        }

        /** The snapshot as the service builds it; the catch-up flag is stamped on afterwards. */
        public Snapshot(List<Row> rows, Totals totals, Instant generatedAt, Instant latestPriceAt,
                        List<LiveGroup> groups) {
            this(rows, totals, generatedAt, latestPriceAt, groups, false, List.of());
        }

        /** This snapshot, saying whether a catch-up is in flight behind it. */
        public Snapshot withCatchUp(boolean inFlight) {
            return new Snapshot(rows, totals, generatedAt, latestPriceAt, groups, inFlight,
                    missingDayBaseline);
        }
    }

    public record Row(
            String symbol,
            String currency,
            BigDecimal quantity,
            BigDecimal lastPrice,
            BigDecimal dayChangePct,
            BigDecimal dayChangeGbp,
            BigDecimal positionGbp,
            String accounts,
            Instant priceTs,
            String session,        // MarketSession of the quote: REGULAR / PRE / POST
            String marketState,    // MarketState of the listing right now: OPEN / PRE / POST / CLOSED / UNKNOWN
            BigDecimal change5dPct,        // live quote vs the close 5 calendar days back (fraction)
            BigDecimal change10dPct,       // live quote vs the close 10 calendar days back (fraction)
            BigDecimal monthChangePct,     // live vs the close ~30 days back; tints the Last cell and fills the 30d column
            BigDecimal change3mPct,        // live quote vs the close 3 calendar months back (fraction)
            BigDecimal change6mPct) {      // live quote vs the close 6 calendar months back (fraction)
    }

    public record Totals(
            BigDecimal positionsGbp,
            BigDecimal cashGbp,
            BigDecimal totalGbp,
            BigDecimal dayChangeGbp,
            BigDecimal dayChangePct) {
    }
}
