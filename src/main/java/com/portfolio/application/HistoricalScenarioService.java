package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.HistoricalScenarioSimulator;
import com.portfolio.domain.HistoricalScenarioSimulator.CashBucket;
import com.portfolio.domain.HistoricalScenarioSimulator.Position;
import com.portfolio.domain.HistoricalScenarioSimulator.Result;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.model.AggHolding;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import com.portfolio.port.HistoricalFxRateProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the "what if next year played out like {@code [from..to]}" scenario over today's
 * actual portfolio. Composes {@link SyncFromCashService} (for today's GBP-valued positions and
 * cash legs) with {@link PriceHistoryRepository} (for the historical return curves) and
 * delegates the math to {@link HistoricalScenarioSimulator}.
 *
 * <p>Currency handling: every price series is pre-converted to GBP at the historical FX rate
 * for each date so the simulator's {@code price(d) / price(from)} ratio captures both the
 * native return and the FX move during the period. Equities use {@code adj_close} (total
 * return); bonds use clean {@code close} and accrue their coupon on top via the simulator's
 * coupon term. Gilt coupon rate is parsed out of the symbol string (e.g.
 * {@code "GILT 4.25% 2032"} → {@code 0.0425}).
 *
 * <p>Substitutes: a user-supplied map ({@code original → replacement}, persisted in
 * {@link KeyValueStore} under {@link #SUBSTITUTES_KEY}) overrides the return curve for any
 * symbol where extrapolating its own past doesn't make sense (NVDA → EQQQ is the canonical
 * example). When a position has no price data for the window and no user substitute, the
 * service falls back to {@link #DEFAULT_SUBSTITUTE} so the position still contributes a
 * sensible curve rather than sitting flat. The response flags both "substituted" (any
 * substitute applied) and "defaultSubstitute" (this service chose the default because the
 * original had no data) so the UI can distinguish "your override" from "auto-filled gap".
 */
public class HistoricalScenarioService {

    private static final Logger log = LoggerFactory.getLogger(HistoricalScenarioService.class);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final Pattern COUPON_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%");

    /** KV key under which per-symbol substitute overrides are stored (newline-separated "SYM=REPLACEMENT"). */
    public static final String SUBSTITUTES_KEY = "whatif_substitutes";
    /** Applied automatically for any position whose original symbol has no price rows in the window. */
    public static final String DEFAULT_SUBSTITUTE = "EQQQ";

    private final SyncFromCashService syncFromCashService;
    private final PriceHistoryRepository priceRepo;
    private final HistoricalFxRateProvider fxProvider;
    private final YahooTickerMap tickerMap;
    private final KeyValueStore settings;
    private final HistoricalScenarioSimulator simulator = new HistoricalScenarioSimulator();

    public HistoricalScenarioService(SyncFromCashService syncFromCashService,
                                     PriceHistoryRepository priceRepo,
                                     HistoricalFxRateProvider fxProvider,
                                     YahooTickerMap tickerMap,
                                     KeyValueStore settings) {
        this.syncFromCashService = syncFromCashService;
        this.priceRepo = priceRepo;
        this.fxProvider = fxProvider;
        this.tickerMap = tickerMap;
        this.settings = settings;
    }

    /**
     * @param gbpCashRatePct user-entered annual rate for GBP cash, in percent (e.g. {@code 5.0} for 5%).
     * @param usdCashRatePct same, for USD cash. Other currencies compound at 0% in this stage.
     * @param userSubstitutes ad-hoc overrides for this run; merged with the persisted set in
     *                        {@link #SUBSTITUTES_KEY}. Pass an empty map to use only the persisted
     *                        overrides (or {@code null} for none).
     */
    public ScenarioResponse run(LocalDate from, LocalDate to,
                                BigDecimal gbpCashRatePct, BigDecimal usdCashRatePct,
                                Map<String, String> userSubstitutes) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from/to must be non-null");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must be on or before 'to' (got " + from + " > " + to + ")");
        }
        SyncResult sync = syncFromCashService.sync();
        if (sync.empty()) return ScenarioResponse.empty(from, to);

        List<Position> positions = new ArrayList<>();
        Map<String, BigDecimal> cashByCcy = new LinkedHashMap<>();
        for (AggHolding h : sync.holdings()) {
            if ("CASH".equals(h.securityId())) {
                if (h.marketValueGbp() != null) {
                    cashByCcy.merge(h.currencyCode(), h.marketValueGbp(), BigDecimal::add);
                }
                continue;
            }
            if (h.marketValueGbp() == null || h.marketValueGbp().signum() == 0) continue;
            boolean isBond = Instruments.isBond(h.securityId());
            BigDecimal coupon = isBond ? extractCoupon(h.securityId()) : null;
            positions.add(new Position(h.securityId().toUpperCase(), h.marketValueGbp(), isBond, coupon));
        }

        BigDecimal gbpRate = pctToFraction(gbpCashRatePct);
        BigDecimal usdRate = pctToFraction(usdCashRatePct);
        List<CashBucket> cashes = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : cashByCcy.entrySet()) {
            BigDecimal rate = switch (e.getKey()) {
                case "GBP" -> gbpRate;
                case "USD" -> usdRate;
                default -> BigDecimal.ZERO;
            };
            cashes.add(new CashBucket(e.getKey(), e.getValue(), rate));
        }

        Map<String, String> mergedSubs = mergeSubstitutes(loadSubstitutes(), userSubstitutes);

        Set<String> candidateSymbols = new LinkedHashSet<>();
        for (Position p : positions) candidateSymbols.add(p.symbol());
        for (String v : mergedSubs.values()) candidateSymbols.add(v);
        candidateSymbols.add(DEFAULT_SUBSTITUTE);

        Map<String, NavigableMap<LocalDate, BigDecimal>> priceSeriesGbp =
                loadPriceSeriesGbp(candidateSymbols, from, to);

        Set<String> defaultApplied = new LinkedHashSet<>();
        Map<String, String> effectiveSubs = new LinkedHashMap<>(mergedSubs);
        if (priceSeriesGbp.containsKey(DEFAULT_SUBSTITUTE)) {
            for (Position p : positions) {
                String sym = p.symbol();
                String currentSub = effectiveSubs.get(sym);
                String effective = currentSub != null ? currentSub : sym;
                NavigableMap<LocalDate, BigDecimal> series = priceSeriesGbp.get(effective);
                if (hasNoUsableData(series, to) && !sym.equals(DEFAULT_SUBSTITUTE)) {
                    effectiveSubs.put(sym, DEFAULT_SUBSTITUTE);
                    defaultApplied.add(sym);
                }
            }
        }

        Result result = simulator.simulate(positions, cashes, from, to, effectiveSubs, priceSeriesGbp);
        return ScenarioResponse.from(result, defaultApplied);
    }

    /** Persisted overrides; map keys + values are upper-cased. */
    public Map<String, String> loadSubstitutes() {
        Set<String> lines = settings.getStringSet(SUBSTITUTES_KEY);
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq <= 0 || eq >= line.length() - 1) continue;
            String k = line.substring(0, eq).trim().toUpperCase();
            String v = line.substring(eq + 1).trim().toUpperCase();
            if (!k.isEmpty() && !v.isEmpty()) out.put(k, v);
        }
        return out;
    }

    /** Replace the persisted overrides with {@code subs}. Empty keys/values are dropped. */
    public void saveSubstitutes(Map<String, String> subs) {
        List<String> lines = new ArrayList<>();
        if (subs != null) {
            for (Map.Entry<String, String> e : subs.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                String k = e.getKey().trim().toUpperCase();
                String v = e.getValue().trim().toUpperCase();
                if (!k.isEmpty() && !v.isEmpty() && !k.equals(v)) lines.add(k + "=" + v);
            }
        }
        settings.putStringSet(SUBSTITUTES_KEY, lines);
    }

    // ---- helpers --------------------------------------------------------

    /**
     * Parse the percent coupon embedded in a gilt symbol like {@code "GILT 4.25% 2032"}.
     * Returns the rate as a fraction (4.25% → 0.0425), or {@code null} when no coupon is
     * present or parseable.
     */
    static BigDecimal extractCoupon(String symbol) {
        if (symbol == null) return null;
        Matcher m = COUPON_PATTERN.matcher(symbol);
        if (!m.find()) return null;
        try {
            return new BigDecimal(m.group(1)).divide(HUNDRED, 6, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal pctToFraction(BigDecimal pct) {
        if (pct == null) return BigDecimal.ZERO;
        return pct.divide(HUNDRED, 6, RoundingMode.HALF_UP);
    }

    private static Map<String, String> mergeSubstitutes(Map<String, String> persisted,
                                                        Map<String, String> userSupplied) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : persisted.entrySet()) {
            out.put(e.getKey().toUpperCase(), e.getValue().toUpperCase());
        }
        if (userSupplied != null) {
            for (Map.Entry<String, String> e : userSupplied.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                String k = e.getKey().trim().toUpperCase();
                String v = e.getValue().trim().toUpperCase();
                if (k.isEmpty() || v.isEmpty()) continue;
                out.put(k, v);
            }
        }
        return out;
    }

    private static boolean hasNoUsableData(NavigableMap<LocalDate, BigDecimal> series, LocalDate windowEnd) {
        // "No usable data" = either no rows at all, or every row sits past the window end
        // (a freshly-listed symbol whose first close is after `to` can't anchor the start price).
        if (series == null || series.isEmpty()) return true;
        return series.firstKey().isAfter(windowEnd);
    }

    /**
     * Load price history for each candidate symbol and pre-convert to GBP per date. Equities
     * use {@code adj_close} (so price ratio = total return); bonds use clean {@code close}
     * (coupon is accrued separately by the simulator). {@code GBp} (London pence) is divided
     * by 100; USD/EUR are divided by the historical Frankfurter rate for each date.
     */
    private Map<String, NavigableMap<LocalDate, BigDecimal>> loadPriceSeriesGbp(
            Collection<String> symbols, LocalDate from, LocalDate to) {
        if (symbols.isEmpty()) return Map.of();
        // Pad the lookup window so floor-fill has data to fall back to for early dates and
        // FX has the rate immediately before `from` available for the first sample.
        LocalDate fetchFrom = from.minusDays(30);
        LocalDate fetchTo = to.plusDays(1);

        Map<String, NavigableMap<LocalDate, BigDecimal>> fxByCcy = preloadFx(fetchFrom, fetchTo);

        Map<String, NavigableMap<LocalDate, BigDecimal>> out = new LinkedHashMap<>();
        for (String sym : symbols) {
            String upper = sym.toUpperCase();
            if (out.containsKey(upper)) continue;
            String ticker = Instruments.isBond(upper) ? upper : tickerMap.tickerFor(upper);
            List<PriceBar> bars = priceRepo.getPriceHistory(ticker, fetchFrom, fetchTo);
            if (bars.isEmpty()) continue;
            NavigableMap<LocalDate, BigDecimal> series = new TreeMap<>();
            boolean isBond = Instruments.isBond(upper);
            for (PriceBar b : bars) {
                double raw = isBond ? b.close() : b.adjClose();
                BigDecimal price = BigDecimal.valueOf(raw);
                String ccy = b.currency();
                if ("GBp".equals(ccy)) {
                    price = price.movePointLeft(2);
                    ccy = "GBP";
                }
                BigDecimal gbp;
                if ("GBP".equals(ccy)) {
                    gbp = price;
                } else {
                    NavigableMap<LocalDate, BigDecimal> fxSeries = fxByCcy.get(ccy);
                    if (fxSeries == null) continue;
                    BigDecimal rate = HistoricalFxRateProvider.rateOnOrBefore(fxSeries, b.date());
                    if (rate == null || rate.signum() == 0) continue;
                    gbp = price.divide(rate, 10, RoundingMode.HALF_UP);
                }
                series.put(b.date(), gbp);
            }
            if (!series.isEmpty()) out.put(upper, series);
        }
        return out;
    }

    private Map<String, NavigableMap<LocalDate, BigDecimal>> preloadFx(LocalDate from, LocalDate to) {
        Map<String, NavigableMap<LocalDate, BigDecimal>> out = new LinkedHashMap<>();
        for (String ccy : List.of("USD", "EUR")) {
            try {
                Map<LocalDate, BigDecimal> series = fxProvider.fetchRateSeries(ccy, from, to);
                out.put(ccy, new TreeMap<>(series));
            } catch (Exception e) {
                log.warn("Could not fetch historical FX for {} — positions in that currency will be skipped",
                        ccy, e);
            }
        }
        return out;
    }

    // ---- response DTO ---------------------------------------------------

    public record ScenarioResponse(
            String from, String to,
            List<DataPoint> timeline,
            List<SymbolRow> perSymbol,
            List<CashRow> perCash,
            BigDecimal startTotalGbp, BigDecimal endTotalGbp,
            BigDecimal pnlGbp, BigDecimal periodReturn) {

        public static ScenarioResponse empty(LocalDate from, LocalDate to) {
            return new ScenarioResponse(from.toString(), to.toString(),
                    List.of(), List.of(), List.of(),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null);
        }

        static ScenarioResponse from(Result r, Set<String> defaultApplied) {
            List<DataPoint> timeline = new ArrayList<>(r.timeline().size());
            for (HistoricalScenarioSimulator.DataPoint d : r.timeline()) {
                timeline.add(new DataPoint(d.date().toString(), d.totalValueGbp()));
            }
            List<SymbolRow> rows = new ArrayList<>(r.perSymbol().size());
            for (HistoricalScenarioSimulator.SymbolResult s : r.perSymbol()) {
                rows.add(new SymbolRow(s.symbol(), s.effectiveSymbol(),
                        s.startValueGbp(), s.endValueGbp(), s.pnlGbp(), s.periodReturn(),
                        s.substituted(), defaultApplied.contains(s.symbol().toUpperCase()),
                        s.missing()));
            }
            // Sort by absolute P&L magnitude so the biggest contributors (positive or negative)
            // surface first in the table — same UX expectation as the Attribution tab.
            rows.sort((a, b) -> b.pnlGbp().abs().compareTo(a.pnlGbp().abs()));
            List<CashRow> cashRows = new ArrayList<>(r.perCash().size());
            for (HistoricalScenarioSimulator.CashResult c : r.perCash()) {
                cashRows.add(new CashRow(c.currency(),
                        c.startValueGbp(), c.endValueGbp(), c.pnlGbp(), c.periodReturn()));
            }
            return new ScenarioResponse(r.from().toString(), r.to().toString(),
                    timeline, rows, cashRows,
                    r.startTotalGbp(), r.endTotalGbp(), r.pnlGbp(), r.periodReturn());
        }
    }

    public record DataPoint(String date, BigDecimal totalValueGbp) {
    }

    public record SymbolRow(String symbol, String effectiveSymbol,
                            BigDecimal startValueGbp, BigDecimal endValueGbp,
                            BigDecimal pnlGbp, BigDecimal periodReturn,
                            boolean substituted, boolean defaultSubstitute, boolean missing) {
    }

    public record CashRow(String currency,
                          BigDecimal startValueGbp, BigDecimal endValueGbp,
                          BigDecimal pnlGbp, BigDecimal periodReturn) {
    }
}
