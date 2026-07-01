package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.PriceStats;
import com.portfolio.domain.WatchlistAlerts;
import com.portfolio.domain.WatchlistAlerts.Flags;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.IntradayBar;
import com.portfolio.domain.model.IntradayPrice;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.FundamentalsRepository;
import com.portfolio.persistence.FundamentalsRepository.Cached;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.persistence.PriceHistoryRepository;
import com.portfolio.persistence.WatchlistRepository;
import com.portfolio.persistence.WatchlistRepository.Entry;
import com.portfolio.port.FxRateProvider;
import com.portfolio.adapter.YahooQuoteSummaryFetcher.QuoteSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles the volatility-trading watchlist screen and serves its price-popup series.
 *
 * <p>Each row joins four data sources for one user-entered symbol: daily prices
 * ({@code price_history}) for realized volatility and trailing-window momentum; the latest
 * 1-minute quote ({@code price_intraday}) for the live price and today's move; the
 * fundamentals cache for the 52-week high/low, P/E and beta; and the cash ledger for the
 * position and unrealized gain when the symbol is actually held. Symbols with no price history
 * yet (freshly added, still backfilling) come back flagged {@code missingData} so the row can
 * render as a placeholder.
 *
 * <p>To keep a whole-watchlist refresh cheap, the per-row inputs are taken as <em>one</em>
 * snapshot: a single FX fetch, one bulk latest-intraday lookup, and one ledger read grouped by
 * symbol. Position reconstruction reuses {@link PositionDetailService}'s FIFO engine rather
 * than duplicating it. The alert {@link Flags} are computed here too so the screen and the
 * {@link WatchlistAlertJob} evaluate identical rules from identical numbers.
 */
public class WatchlistService {

    private static final Logger log = LoggerFactory.getLogger(WatchlistService.class);

    private static final int VOL_WINDOW = 30;          // sessions of returns for realized vol
    private static final int PRICE_SCALE = 4;
    private static final int PCT_SCALE = 6;
    private static final int GBP_SCALE = 2;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int INTRADAY_SERIES_STRIDE_ABOVE_DAYS = 5;   // >5-day windows → ~5-min buckets

    private final WatchlistRepository watchlistRepo;
    private final PriceHistoryRepository priceRepo;
    private final IntradayPriceRepository intradayRepo;
    private final FundamentalsRepository fundamentalsRepo;
    private final CashTransactionRepository cashRepo;
    private final FxRateProvider fxRateProvider;
    private final YahooTickerMap tickerMap;

    public WatchlistService(WatchlistRepository watchlistRepo,
                            PriceHistoryRepository priceRepo,
                            IntradayPriceRepository intradayRepo,
                            FundamentalsRepository fundamentalsRepo,
                            CashTransactionRepository cashRepo,
                            FxRateProvider fxRateProvider,
                            YahooTickerMap tickerMap) {
        this.watchlistRepo = watchlistRepo;
        this.priceRepo = priceRepo;
        this.intradayRepo = intradayRepo;
        this.fundamentalsRepo = fundamentalsRepo;
        this.cashRepo = cashRepo;
        this.fxRateProvider = fxRateProvider;
        this.tickerMap = tickerMap;
    }

    public WatchlistView view() {
        List<Entry> entries = watchlistRepo.loadAll();
        if (entries.isEmpty()) return new WatchlistView(List.of());

        // One-shot shared inputs.
        Map<String, Cached> fundamentals = fundamentalsRepo.loadAll();
        Map<String, List<CashTransaction>> ledgerBySymbol = ledgerBySymbol();
        Map<String, BigDecimal> rates = fetchRatesQuietly();

        List<String> tickers = new ArrayList<>();
        for (Entry e : entries) tickers.add(tickerFor(e.symbol()));
        Map<String, IntradayPrice> latest = intradayRepo.loadLatestIntradayPrices(tickers);

        LocalDate today = LocalDate.now();
        List<Row> rows = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            rows.add(buildRow(e, today, fundamentals, ledgerBySymbol, rates, latest));
        }
        return new WatchlistView(rows);
    }

    private Row buildRow(Entry e, LocalDate today,
                         Map<String, Cached> fundamentals,
                         Map<String, List<CashTransaction>> ledgerBySymbol,
                         Map<String, BigDecimal> rates,
                         Map<String, IntradayPrice> latest) {
        String symbol = e.symbol();
        String ticker = tickerFor(symbol);

        List<PriceBar> bars = priceRepo.getPriceHistory(ticker, today.minusDays(400), today);
        IntradayPrice ip = latest.get(ticker);

        QuoteSummary q = fundamentals.containsKey(symbol) ? fundamentals.get(symbol).quote() : null;
        String currency = ip != null ? ip.currency() : (q != null ? q.currency() : null);

        // Live price: prefer the intraday quote, fall back to the most recent daily close.
        // (Assigned in two steps, not a ternary: mixing primitive ip.close() with a boxed
        // Double would unbox the fallback and NPE when a freshly-added symbol has no prices yet.)
        Double lastClose = bars.isEmpty() ? null : bars.get(bars.size() - 1).close();
        Double current = lastClose;
        if (ip != null) current = ip.close();

        if (bars.isEmpty() && current == null) {
            return Row.missing(symbol, e.highThresholdPct(), e.moveThresholdPct());
        }

        // Trailing-window momentum, calendar-anchored. "Today" = live vs the last close before today.
        BigDecimal todayPct = PriceStats.pctChange(current, PriceStats.closeOnOrBefore(bars, today.minusDays(1)));
        BigDecimal pct3d  = PriceStats.pctChange(current, PriceStats.closeOnOrBefore(bars, today.minusDays(3)));
        BigDecimal pct5d  = PriceStats.pctChange(current, PriceStats.closeOnOrBefore(bars, today.minusDays(5)));
        BigDecimal pct10d = PriceStats.pctChange(current, PriceStats.closeOnOrBefore(bars, today.minusDays(10)));
        BigDecimal pct30d = PriceStats.pctChange(current, PriceStats.closeOnOrBefore(bars, today.minusDays(30)));

        BigDecimal vol = PriceStats.annualizedVol(bars, VOL_WINDOW);

        BigDecimal currentPrice = current == null ? null
                : BigDecimal.valueOf(current).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        BigDecimal week52High = q == null ? null : q.week52High();
        BigDecimal week52Low = q == null ? null : q.week52Low();
        BigDecimal pctFromHigh = (currentPrice != null && week52High != null && week52High.signum() > 0)
                ? currentPrice.subtract(week52High).divide(week52High, PCT_SCALE, RoundingMode.HALF_UP) : null;
        BigDecimal pctFromLow = (currentPrice != null && week52Low != null && week52Low.signum() > 0)
                ? currentPrice.subtract(week52Low).divide(week52Low, PCT_SCALE, RoundingMode.HALF_UP) : null;

        BigDecimal trailingPe = q == null ? null : q.trailingPe();
        BigDecimal beta = q == null ? null : q.beta();

        Position pos = position(symbol, ticker, ledgerBySymbol.get(symbol), ip, rates);

        Flags flags = WatchlistAlerts.evaluate(currentPrice, week52High, todayPct,
                e.highThresholdPct(), e.moveThresholdPct());

        return new Row(symbol, currency, currentPrice,
                scalePct(todayPct), scalePct(pct3d), scalePct(pct5d), scalePct(pct10d), scalePct(pct30d),
                week52High, week52Low, scalePct(pctFromHigh), scalePct(pctFromLow),
                scalePct(vol), trailingPe, beta,
                pos.held(), pos.valueGbp(), pos.gainGbp(), scalePct(pos.gainPct()),
                e.highThresholdPct(), e.moveThresholdPct(),
                flags.nearHigh(), flags.bigMove(), flags.moveDirection(),
                false);
    }

    // ---- Position / gain (reuses PositionDetailService's FIFO engine) -------

    private Position position(String symbol, String ticker, List<CashTransaction> rows,
                              IntradayPrice ip, Map<String, BigDecimal> rates) {
        if (rows == null || rows.isEmpty()) return Position.none();
        List<CashTransaction> pruned = PositionDetailService.pruneCorrections(rows);
        PositionDetailService.Lots lots = PositionDetailService.reconstruct(pruned);
        BigDecimal shares = lots.totalOpenShares();
        if (shares.signum() == 0) return Position.none();
        BigDecimal cost = lots.totalOpenCostGbp();
        BigDecimal value = positionValueGbp(symbol, shares, ip, rates);
        if (value == null) {
            return new Position(true, null, null, null);
        }
        BigDecimal gain = value.subtract(cost);
        BigDecimal gainPct = cost.signum() == 0 ? null
                : gain.divide(cost, PCT_SCALE, RoundingMode.HALF_UP);
        return new Position(true, value.setScale(GBP_SCALE, RoundingMode.HALF_UP),
                gain.setScale(GBP_SCALE, RoundingMode.HALF_UP), gainPct);
    }

    /** Same valuation model as {@link PositionDetailService}: bonds per-£100, {@code GBp} pence, FX by division. */
    private BigDecimal positionValueGbp(String symbol, BigDecimal shares, IntradayPrice ip,
                                        Map<String, BigDecimal> rates) {
        if (ip == null || shares.signum() == 0) return null;
        BigDecimal price = BigDecimal.valueOf(ip.close());
        String ccy = ip.currency();
        if ("GBp".equals(ccy)) {
            price = price.movePointLeft(2);
            ccy = "GBP";
        }
        BigDecimal nativeVal = Instruments.isBond(symbol)
                ? price.multiply(shares).divide(HUNDRED, 10, RoundingMode.HALF_UP)
                : price.multiply(shares);
        if ("GBP".equals(ccy)) return nativeVal;
        BigDecimal rate = rates.get(ccy);
        if (rate == null || rate.signum() == 0) return null;
        return nativeVal.divide(rate, 10, RoundingMode.HALF_UP);
    }

    // ---- Popup price series -------------------------------------------------

    /**
     * Price series for the popup chart. Windows {@code 1D}/{@code 3D}/{@code 5D}/{@code 10D}/
     * {@code 15D} come from 1-minute intraday bars (downsampled to ~5-minute buckets beyond
     * 5 days to stay light); {@code 30D} falls back to daily closes, since intraday retention
     * only reaches 15 days. Daily points use the raw close the user saw that day
     * ({@code close × splitFactor}).
     */
    public Series series(String symbolRaw, String windowRaw) {
        String symbol = symbolRaw == null ? "" : symbolRaw.trim().toUpperCase();
        String window = windowRaw == null ? "5D" : windowRaw.trim().toUpperCase();
        if (symbol.isEmpty()) return new Series(symbol, window, "daily", List.of());
        String ticker = tickerFor(symbol);

        if ("30D".equals(window)) {
            List<PriceBar> bars = priceRepo.getPriceHistory(ticker,
                    LocalDate.now().minusDays(31), LocalDate.now());
            List<Point> pts = new ArrayList<>(bars.size());
            for (PriceBar b : bars) {
                pts.add(new Point(b.date().toString(),
                        BigDecimal.valueOf(b.close() * b.splitFactor()).setScale(PRICE_SCALE, RoundingMode.HALF_UP)));
            }
            return new Series(symbol, window, "daily", pts);
        }

        int days = switch (window) {
            case "1D" -> 1;
            case "3D" -> 3;
            case "10D" -> 10;
            case "15D" -> 15;
            default -> 5;                 // 5D and any unknown value
        };
        Instant from = Instant.now().minus(Duration.ofDays(days));
        List<IntradayBar> bars = intradayRepo.loadIntradaySeries(ticker, from);
        int stride = days > INTRADAY_SERIES_STRIDE_ABOVE_DAYS ? 5 : 1;
        List<Point> pts = new ArrayList<>();
        for (int i = 0; i < bars.size(); i++) {
            if (i % stride != 0 && i != bars.size() - 1) continue;   // keep the last point
            IntradayBar b = bars.get(i);
            pts.add(new Point(b.ts().toString(),
                    BigDecimal.valueOf(b.close()).setScale(PRICE_SCALE, RoundingMode.HALF_UP)));
        }
        return new Series(symbol, window, "intraday", pts);
    }

    // ---- helpers ------------------------------------------------------------

    private String tickerFor(String symbol) {
        return Instruments.isBond(symbol) ? symbol : tickerMap.tickerFor(symbol);
    }

    private Map<String, List<CashTransaction>> ledgerBySymbol() {
        Map<String, List<CashTransaction>> out = new LinkedHashMap<>();
        for (CashTransaction t : cashRepo.loadDividendTransactions()) {
            if (t.symbol() == null) continue;
            out.computeIfAbsent(t.symbol().toUpperCase(), k -> new ArrayList<>()).add(t);
        }
        return out;
    }

    private Map<String, BigDecimal> fetchRatesQuietly() {
        try {
            return fxRateProvider.fetchRates();
        } catch (Exception ex) {
            log.warn("Could not fetch FX rates for watchlist; held-position gains will be blank", ex);
            return Map.of();
        }
    }

    private static BigDecimal scalePct(BigDecimal v) {
        return v == null ? null : v.setScale(PCT_SCALE, RoundingMode.HALF_UP);
    }

    private record Position(boolean held, BigDecimal valueGbp, BigDecimal gainGbp, BigDecimal gainPct) {
        static Position none() {
            return new Position(false, null, null, null);
        }
    }

    // ---- DTOs ---------------------------------------------------------------

    /**
     * One watchlist row. Percentages are fractions ({@code 0.1} = 10%). {@code currentPrice},
     * {@code week52High}/{@code week52Low} are native-currency (see {@code currency}).
     * {@code held} gates the position columns. The {@code nearHigh}/{@code bigMove}/
     * {@code moveDirection} triple drives the row colour. {@code missingData} marks a
     * freshly-added symbol still backfilling.
     */
    public record Row(String symbol, String currency, BigDecimal currentPrice,
                      BigDecimal todayPct, BigDecimal pct3d, BigDecimal pct5d,
                      BigDecimal pct10d, BigDecimal pct30d,
                      BigDecimal week52High, BigDecimal week52Low,
                      BigDecimal pctFromHigh, BigDecimal pctFromLow,
                      BigDecimal realizedVol, BigDecimal trailingPe, BigDecimal beta,
                      boolean held, BigDecimal positionValueGbp, BigDecimal gainGbp, BigDecimal gainPct,
                      BigDecimal highThresholdPct, BigDecimal moveThresholdPct,
                      boolean nearHigh, boolean bigMove, int moveDirection,
                      boolean missingData) {

        static Row missing(String symbol, BigDecimal highPct, BigDecimal movePct) {
            return new Row(symbol, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null,
                    false, null, null, null, highPct, movePct,
                    false, false, 0, true);
        }
    }

    public record WatchlistView(List<Row> rows) {
    }

    public record Point(String t, BigDecimal p) {
    }

    public record Series(String symbol, String window, String granularity, List<Point> points) {
    }
}
