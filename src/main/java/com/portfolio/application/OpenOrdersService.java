package com.portfolio.application;

import com.portfolio.ExcelReportWriter;
import com.portfolio.adapter.HoldingFileLocator;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.PriceStats;
import com.portfolio.domain.model.IntradayPrice;
import com.portfolio.domain.model.OpenOrder;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.parser.OrderParser;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.persistence.PriceHistoryRepository;
import com.portfolio.port.FxRateProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /** How many calendar days back to walk for a daily-close fallback when no intraday quote exists. */
    private static final int DAILY_FALLBACK_DAYS = 6;

    /** Realized-daily-vol windows (in sessions): RV10 (short) + RV30 (long). */
    private static final int RV_SHORT = 10;
    private static final int RV_LONG = 30;
    /** Horizons (sessions) for the touch-probability columns; the σ-distance column uses the short one. */
    private static final int TOUCH_HORIZON_SESSIONS = 5;
    private static final int TOUCH_HORIZON_SESSIONS_LONG = 20;
    /** Calendar-day lookback loaded per symbol to compute the daily-vol windows (≈ 60 sessions of margin). */
    private static final int VOL_LOOKBACK_DAYS = 90;

    private final HoldingFileLocator fileLocator;
    private final List<OrderParser> parsers;
    private final FxRateProvider fxRateProvider;
    private final ExcelReportWriter excelReportWriter;
    private final Path outputDir;
    private final IntradayPriceRepository intraday;
    private final PriceHistoryRepository history;
    private final YahooTickerMap tickerMap;

    public OpenOrdersService(HoldingFileLocator fileLocator, List<OrderParser> parsers,
                             FxRateProvider fxRateProvider, ExcelReportWriter excelReportWriter,
                             Path outputDir, IntradayPriceRepository intraday,
                             PriceHistoryRepository history, YahooTickerMap tickerMap) {
        this.fileLocator = fileLocator;
        this.parsers = parsers;
        this.fxRateProvider = fxRateProvider;
        this.excelReportWriter = excelReportWriter;
        this.outputDir = outputDir;
        this.intraday = intraday;
        this.history = history;
        this.tickerMap = tickerMap;
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

        enrichWithCurrentPrice(orders, rates);
        return new OrdersReport(orders, sources, warnings, orders.isEmpty() && sources.isEmpty());
    }

    /**
     * Fill each order's live-price and volatility columns. Live price: {@link OpenOrder#currentPrice()}
     * (in the order's own currency) and the signed {@link OpenOrder#distancePct()} from the limit,
     * from the bulk-loaded latest intraday quote (one query for all symbols), falling back to the most
     * recent stored daily close within {@link #DAILY_FALLBACK_DAYS}; a listing quoted in a different
     * currency (or {@code GBp} pence vs GBP pounds) is cross-converted through the FX snapshot.
     * Volatility: realized daily σ over the {@link #RV_SHORT}/{@link #RV_LONG}-session windows (not
     * annualised) from the symbol's daily series, plus — when a limit and a live price both exist — the
     * signed {@link #TOUCH_HORIZON_SESSIONS}-session σ distance to the limit and the driftless
     * first-passage touch probability. σ figures are currency-free return ratios, so they need no FX.
     * Degrades silently — anything we can't compute stays null ("—").
     */
    private void enrichWithCurrentPrice(List<OpenOrder> orders, Map<String, BigDecimal> rates) {
        Set<String> tickers = new LinkedHashSet<>();
        for (OpenOrder o : orders) {
            String tk = tickerFor(o.symbol());
            if (tk != null) tickers.add(tk);
        }
        if (tickers.isEmpty()) return;

        Map<String, IntradayPrice> latest = intraday.loadLatestIntradayPrices(tickers);
        Map<String, BigDecimal[]> dailyVols = loadDailyVols(tickers);   // ticker → [RV10, RV30]

        for (int i = 0; i < orders.size(); i++) {
            OpenOrder o = orders.get(i);
            String tk = tickerFor(o.symbol());
            if (tk == null) continue;

            BigDecimal[] vols = dailyVols.getOrDefault(tk, new BigDecimal[]{null, null});
            BigDecimal dv10 = vols[0], dv30 = vols[1];

            BigDecimal rawClose = null;
            String rawCcy = null;
            IntradayPrice ip = latest.get(tk);
            if (ip != null) {
                rawClose = BigDecimal.valueOf(ip.close());
                rawCcy = ip.currency();
            } else {
                PriceBar bar = latestDailyClose(tk);
                if (bar != null) {
                    rawClose = BigDecimal.valueOf(bar.close());
                    rawCcy = bar.currency();
                }
            }

            BigDecimal current = rawClose == null ? null : priceInCurrency(rawClose, rawCcy, o.currency(), rates);
            if (current != null) {
                BigDecimal distance = (o.price() != null && o.price().signum() != 0)
                        ? current.subtract(o.price()).divide(o.price(), 6, RoundingMode.HALF_UP)
                        : null;
                o = o.withCurrentPrice(current.setScale(4, RoundingMode.HALF_UP), distance);
            }

            // σ distance / touch prob need a limit and a live price; the short window is the near-term
            // vol estimate, falling back to the 30-session one when the 10-session window is too thin.
            // Both touch probabilities share one base vol — they differ only by the √horizon scaling.
            BigDecimal baseVol = dv10 != null ? dv10 : dv30;
            BigDecimal sigmaToLimit = PriceStats.sigmaDistance(current, o.price(), baseVol, TOUCH_HORIZON_SESSIONS);
            BigDecimal touchProb = PriceStats.touchProbability(sigmaToLimit);
            BigDecimal touchProb20 = PriceStats.touchProbability(
                    PriceStats.sigmaDistance(current, o.price(), baseVol, TOUCH_HORIZON_SESSIONS_LONG));
            orders.set(i, o.withVolatility(dv10, dv30, sigmaToLimit, touchProb, touchProb20));
        }
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

    /** Most recent stored daily close for {@code ticker} within the fallback window, or null. */
    private PriceBar latestDailyClose(String ticker) {
        LocalDate day = LocalDate.now();
        for (int i = 0; i < DAILY_FALLBACK_DAYS; i++) {
            PriceBar bar = history.getPriceOn(ticker, day);
            if (bar != null) return bar;
            day = day.minusDays(1);
        }
        return null;
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
     * @param empty true when no order file was found for any broker (distinct from "files found,
     *              but nothing open") so the view can show the right message.
     */
    public record OrdersReport(List<OpenOrder> orders, List<String> sources,
                               List<String> warnings, boolean empty) {
    }
}
